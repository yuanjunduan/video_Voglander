package io.github.lunasaw.voglander.repository.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.annotation.DbType;

import lombok.extern.slf4j.Slf4j;

/**
 * SQLite 级联「发布行编码来源」schema 迁移（幂等，可重复执行）。
 *
 * <p><b>为什么需要它</b>：1.10.3 起上报编码可以由规则生成，生成结果必须落库（现算会漂：
 * 号池一变，同一路通道下次就换号），而归一行还要说清这个码是「规则算的」还是「人填的」——
 * 2.5 的人工值保护只认 {@code code_source=MANUAL}。缺这两列，固化与保护都无处落笔。
 *
 * <p><b>为什么只补两列</b>：§6.1 ③ 里另有 {@code device_key} / {@code last_push_*}，
 * 它们服务于「通道按设备拆键」与「推送状态回写」，与 2.4 的入口改造同批，
 * 放在这里会让 2.2 的迁移键提前占用后续列的位置。
 *
 * <p><b>为什么单独一支迁移器</b>：迁移键是「跑过就跳过」，1.10.3 的平台编码规则
 * （{@link SqliteCascadeCodingSchemaMigrator}）登记的是另一张表的另一个键；
 * 两支合在一起会让「哪张表缺列」在排查时看不出来。
 *
 * <p>幂等策略与 {@link SqliteCascadeChannelMetaSchemaMigrator} 一致：加列前查
 * {@code pragma_table_info}（SQLite 的 {@code ADD COLUMN} 不支持 {@code IF NOT EXISTS}），
 * 成功后登记 {@code schema_migration_history}。建表脚本的权威源仍是
 * {@code sql/voglander-sqlite.sql}；非 SQLite 数据源直接跳过，MySQL/PostgreSQL 由 DBA
 * 执行 {@code sql/migration/*} 下的脚本。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadePublishCodeSourceMigrator {

    /** 迁移键：只用于人工排查「这个库升级到哪一步了」 */
    public static final String MIGRATION_KEY = "cascade-publish-code-source";

    private static final String PUBLISH_TABLE = "tb_cascade_publish";

    /**
     * 列名 → 列定义。
     *
     * <p>两列都允许空：空表示「历史数据未标记」，读取侧仍按 {@code publish_code ?: 本地码}
     * 解析，升级不会改变任何一条上报编码。
     */
    private static final String[][] COLUMNS = {
        {"code_source", "VARCHAR(8) DEFAULT NULL"},
        {"code_generated_time", "DATETIME DEFAULT NULL"}
    };

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
                /* 表不存在说明尚未建库：由 SqliteSchemaInitializer 的全量脚本负责，这里不做建表 */
                if (!tableExists(connection, PUBLISH_TABLE)) {
                    log.debug("SQLite 尚无 {}，跳过发布行编码来源迁移", PUBLISH_TABLE);
                    return;
                }
                for (String[] column : COLUMNS) {
                    addColumnIfMissing(connection, PUBLISH_TABLE, column[0], column[1]);
                }
                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联发布行编码来源 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联发布行编码来源 schema 迁移失败", e);
        }
    }

    /** 校验结果：两列必须都存在，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        for (String[] column : COLUMNS) {
            requireColumn(connection, PUBLISH_TABLE, column[0]);
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

    private void requireColumn(Connection connection, String table, String column) throws SQLException {
        if (!columnExists(connection, table, column)) {
            throw new IllegalStateException("SQLite 表 " + table + " 缺少列 " + column);
        }
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition)
        throws SQLException {
        if (columnExists(connection, table, column)) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("SQLite 迁移补列: {}.{}", table, column);
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

    private void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }
}
