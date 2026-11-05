package io.github.lunasaw.voglander.repository.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.annotation.DbType;

import lombok.extern.slf4j.Slf4j;

/**
 * SQLite 级联「本端客户端」抽取迁移（幂等）。
 *
 * <p><b>为什么要把本端身份从平台行里拆出去</b>：{@code tb_cascade_platform} 原本每行都冗余一份
 * {@code local_client_id / local_ip / local_port}，于是新增第二个上级平台要再抄一遍同样的值——
 * 抄错的本端 IP 会让上级把 INVITE 打到一个不可达地址，而页面仍显示「在线」（注册是主动外发的，
 * 只要对端收得到 UDP 就算成功），这类故障从界面上看不出来。拆成一张本端客户端表后，新增平台
 * 只需选择一个已有身份。
 *
 * <p><b>为什么本端端口不进新表</b>：一个客户端协议栈只监听一个 socket（{@code sip.client.port}），
 * 端口是部署事实而非可选项。留在表里等于允许它和实际监听口不一致，只有坏处。因此本迁移只搬
 * 编码与通告 IP，端口一律由运行时配置提供。
 *
 * <p><b>为什么三个老列必须真的删掉</b>：{@code local_client_id} 是 {@code NOT NULL} 且无默认值，
 * 新代码不再写它，留着会让「新增上级平台」直接插入失败。删列用 SQLite 3.35+ 的
 * {@code ALTER TABLE ... DROP COLUMN}（本项目 sqlite-jdbc 3.49 已支持），列上没有索引或视图引用，
 * 不需要重建表。
 *
 * <p><b>收敛规则</b>：按 {@code (local_client_id, local_ip)} 分组，一组一条客户端记录；同一身份被
 * 多个平台引用时共用一条，否则改一次 IP 要改很多处。首个（id 最小的）客户端置为默认行，新增平台
 * 时靠它预选。编码为空的行不生成客户端——空编码不是可用身份，留给应用层报「请先创建本端客户端」。
 *
 * <p>幂等策略与 {@link SqliteCascadePlatformNameMigrator} 一致：每一步先查 {@code pragma_table_info}
 * 或 {@code sqlite_master}，成功后登记 {@code schema_migration_history}。建表脚本的权威源仍是
 * {@code sql/voglander-sqlite.sql}；非 SQLite 数据源直接跳过（MySQL/PostgreSQL 用
 * {@code sql/migration/**} 下的人工脚本）。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeClientSchemaMigrator {

    /** 迁移键：只用于人工排查「这个库升级到哪一步了」 */
    public static final String  MIGRATION_KEY = "cascade-client-extraction";

    private static final String PLATFORM_TABLE = "tb_cascade_platform";
    private static final String CLIENT_TABLE   = "tb_cascade_client";
    private static final String CLIENT_REF     = "client_ref_id";

    /** 待搬走并删除的三个本端列，顺序即删列顺序 */
    private static final String[] LEGACY_COLUMNS = { "local_client_id", "local_ip", "local_port" };

    private static final String CLIENT_TABLE_DDL = "CREATE TABLE IF NOT EXISTS " + CLIENT_TABLE + " ("
        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
        + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
        + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
        + " client_id VARCHAR(64) NOT NULL,"
        + " client_name VARCHAR(128) DEFAULT '' NOT NULL,"
        + " client_ip VARCHAR(64) DEFAULT '' NOT NULL,"
        + " is_default INTEGER DEFAULT 0 NOT NULL,"
        + " enabled INTEGER DEFAULT 1 NOT NULL,"
        + " remark VARCHAR(255) DEFAULT NULL,"
        + " extend TEXT)";

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行迁移。非 SQLite 或数据源缺失时静默跳过。
     *
     * @throws IllegalStateException 迁移或校验失败（上抛阻断启动，不留「列缺失」的隐性坏库）
     */
    public void migrate() {
        if (dataSource == null || DatabaseTypeDetector.detectDbType(dataSource) != DbType.SQLITE) {
            return;
        }
        ensureHistoryTable();
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                /* 表不存在说明尚未建库：全量脚本负责，这里不建表 */
                if (!tableExists(connection, PLATFORM_TABLE)) {
                    log.debug("SQLite 尚无 {}，跳过本端客户端抽取迁移", PLATFORM_TABLE);
                    return;
                }
                createClientTableIfMissing(connection);
                addClientRefColumnIfMissing(connection);
                if (columnExists(connection, PLATFORM_TABLE, "local_client_id")) {
                    backfillClients(connection);
                    dropLegacyColumns(connection);
                }
                ensureDefaultClient(connection);
                requireColumn(connection, PLATFORM_TABLE, CLIENT_REF);
                requireTable(connection, CLIENT_TABLE);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联本端客户端 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联本端客户端 schema 迁移失败", e);
        }
    }

    // ------------------------------------------------------------------ 结构

    private void createClientTableIfMissing(Connection connection) throws SQLException {
        if (tableExists(connection, CLIENT_TABLE)) {
            return;
        }
        execute(connection, CLIENT_TABLE_DDL);
        execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_cascade_client_id ON " + CLIENT_TABLE + " (client_id)");
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_client_default ON " + CLIENT_TABLE + " (is_default)");
        log.info("SQLite 迁移建表: {}", CLIENT_TABLE);
    }

    private void addClientRefColumnIfMissing(Connection connection) throws SQLException {
        if (columnExists(connection, PLATFORM_TABLE, CLIENT_REF)) {
            return;
        }
        /* 可空加列：存量回填在同一个事务里完成，失败整体回滚；「必填」由应用层与全量脚本的 DDL 保证 */
        execute(connection, "ALTER TABLE " + PLATFORM_TABLE + " ADD COLUMN " + CLIENT_REF + " INTEGER");
        log.info("SQLite 迁移补列: {}.{}", PLATFORM_TABLE, CLIENT_REF);
    }

    private void dropLegacyColumns(Connection connection) throws SQLException {
        for (String column : LEGACY_COLUMNS) {
            /* 先落引用再删列：顺序反了会在删完列后无法按旧值回填 */
            if (columnExists(connection, PLATFORM_TABLE, column)) {
                execute(connection, "ALTER TABLE " + PLATFORM_TABLE + " DROP COLUMN " + column);
                log.info("SQLite 迁移删列: {}.{}", PLATFORM_TABLE, column);
            }
        }
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 按 {@code (local_client_id, local_ip)} 收敛客户端行并回填 {@code client_ref_id}。
     *
     * <p>单调按 id 升序处理，保证「首条身份」可预期（它会被 {@link #ensureDefaultClient} 选为默认行），
     * 也保证重复运行的结果与首次一致。
     */
    private void backfillClients(Connection connection) throws SQLException {
        Map<String, Long> known = new LinkedHashMap<>();
        String sql = "SELECT id, local_client_id, COALESCE(local_ip, '') AS local_ip FROM " + PLATFORM_TABLE + " ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql);
            ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long platformId = rs.getLong("id");
                String clientId = trimToEmpty(rs.getString("local_client_id"));
                if (clientId.isEmpty()) {
                    log.warn("SQLite 迁移跳过无本端编码的平台行: id={}，需在页面上重新选择本端客户端", platformId);
                    continue;
                }
                Long ref = known.get(clientId);
                if (ref == null) {
                    ref = findClientId(connection, clientId);
                    if (ref == null) {
                        ref = insertClient(connection, clientId, trimToEmpty(rs.getString("local_ip")));
                    }
                    known.put(clientId, ref);
                }
                updateClientRef(connection, platformId, ref);
            }
        }
        log.info("SQLite 迁移本端客户端: 收敛 {} 条身份", known.size());
    }

    private Long findClientId(Connection connection, String clientId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM " + CLIENT_TABLE + " WHERE client_id = ?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private long insertClient(Connection connection, String clientId, String clientIp) throws SQLException {
        String sql = "INSERT INTO " + CLIENT_TABLE
            + "(create_time, update_time, client_id, client_name, client_ip, is_default, enabled)"
            + " VALUES(CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, '', ?, 0, 1)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, clientId);
            ps.setString(2, clientIp);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("插入本端客户端后未取到主键: clientId=" + clientId);
                }
                return keys.getLong(1);
            }
        }
    }

    private void updateClientRef(Connection connection, long platformId, long clientRef) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE " + PLATFORM_TABLE + " SET " + CLIENT_REF + " = ? WHERE id = ?")) {
            ps.setLong(1, clientRef);
            ps.setLong(2, platformId);
            ps.executeUpdate();
        }
    }

    /**
     * 保证默认行全局唯一：没有默认行时选 id 最小的一条。
     *
     * <p>只在「零默认」时补，已有默认行绝不改写——用户手选的默认次序不该被一次重启洗掉。
     */
    private void ensureDefaultClient(Connection connection) throws SQLException {
        if (scalar(connection, "SELECT count(*) FROM " + CLIENT_TABLE) == 0) {
            return;
        }
        if (scalar(connection, "SELECT count(*) FROM " + CLIENT_TABLE + " WHERE is_default = 1") > 0) {
            return;
        }
        execute(connection, "UPDATE " + CLIENT_TABLE + " SET is_default = 1"
            + " WHERE id = (SELECT MIN(id) FROM " + CLIENT_TABLE + ")");
    }

    // ------------------------------------------------------------------ 校验与工具

    private void requireColumn(Connection connection, String table, String column) throws SQLException {
        if (!columnExists(connection, table, column)) {
            throw new IllegalStateException("SQLite 表 " + table + " 缺少列 " + column);
        }
    }

    private void requireTable(Connection connection, String table) throws SQLException {
        if (!tableExists(connection, table)) {
            throw new IllegalStateException("SQLite 表 " + table + " 未建出");
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM pragma_table_info(?) WHERE name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void ensureHistoryTable() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migration_history ("
                + "migration_key VARCHAR(128) PRIMARY KEY, applied_at DATETIME NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("无法初始化 SQLite 迁移历史表", e);
        }
    }

    private void recordApplied(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT OR IGNORE INTO schema_migration_history(migration_key, applied_at) VALUES(?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, MIGRATION_KEY);
            ps.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }
}
