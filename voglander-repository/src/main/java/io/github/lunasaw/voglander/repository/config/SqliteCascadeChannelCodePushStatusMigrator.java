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
 * SQLite 级联「通道自己的码 + 发布行拆键/推送状态」schema 迁移（幂等，可重复执行）。
 *
 * <p><b>为什么需要它</b>：① 通道原先只有 {@code channel_id}，上级看到的是发布行的共享编码，
 * 本地这一层没有「自己的码」的落库位，编码规则一旦接管就无处固化（§6.1 ①）；
 * ③ 发布行用 {@code deviceId:channelId} 拼接的 {@code object_key} 当查询条件等于用字符串前缀
 * 推断标识（§6.3），推送结论也只在日志里（§11.7）。
 *
 * <p><b>为什么单独一支迁移器</b>：迁移键是「跑过就跳过」，1.10.3 的同批改动已分别登记
 * {@link SqliteCascadeCodingSchemaMigrator}（平台编码规则）与
 * {@link SqliteCascadePublishCodeSourceMigrator}（编码来源）。合进任何一支都会让
 * 「哪张表缺哪几列」在排查时看不出来。
 *
 * <p><b>幂等策略</b>与 {@link SqliteCascadePublishCodeSourceMigrator} 一致：加列前查
 * {@code pragma_table_info}、建索引前查 {@code sqlite_master}（SQLite 的 {@code ADD COLUMN}
 * 与 {@code CREATE INDEX} 都不支持 {@code IF NOT EXISTS}），成功后登记
 * {@code schema_migration_history}。建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；
 * 非 SQLite 数据源直接跳过，MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*} 下的脚本。
 *
 * <p>{@code device_key} 回填只在列为空时执行一次，口径与 {@code PublishKeys.channelDeviceId}
 * 完全一致（取第一个 {@code :} 之前）；{@code object_key} 缺少分隔符的脏数据保持为空，不猜测。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeChannelCodePushStatusMigrator {

    /** 迁移键：只用于人工排查「这个库升级到哪一步了」 */
    public static final String MIGRATION_KEY = "cascade-channel-code-push-status";

    private static final String CHANNEL_TABLE = "tb_device_channel";

    private static final String PUBLISH_TABLE = "tb_cascade_publish";

    /** 通道「自己的码」：空表示尚未生成，读取侧回落到 {@code channel_id} */
    private static final String[][] CHANNEL_COLUMNS = {
        {"code", "VARCHAR(64) DEFAULT NULL"}
    };

    /**
     * 发布行拆键与推送结论。
     *
     * <p>四列都允许空：{@code device_key} 为空即回落到「按 object_key 归组」的旧行为；
     * {@code last_push_*} 为空表示从未推送过。
     */
    private static final String[][] PUBLISH_COLUMNS = {
        {"device_key", "VARCHAR(64) DEFAULT NULL"},
        {"last_push_time", "DATETIME DEFAULT NULL"},
        {"last_push_status", "VARCHAR(16) DEFAULT NULL"},
        {"last_push_message", "VARCHAR(255) DEFAULT NULL"}
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
                boolean channelTable = tableExists(connection, CHANNEL_TABLE);
                boolean publishTable = tableExists(connection, PUBLISH_TABLE);
                /* 两张表都不存在说明尚未建库：由全量脚本负责，这里不做建表 */
                if (!channelTable && !publishTable) {
                    log.debug("SQLite 尚无 {} / {}，跳过通道编码与推送状态迁移", CHANNEL_TABLE, PUBLISH_TABLE);
                    return;
                }
                if (channelTable) {
                    for (String[] column : CHANNEL_COLUMNS) {
                        addColumnIfMissing(connection, CHANNEL_TABLE, column[0], column[1]);
                    }
                    ensureIndex(connection, "idx_channel_code",
                        "CREATE INDEX idx_channel_code ON " + CHANNEL_TABLE + " (code)");
                }
                if (publishTable) {
                    for (String[] column : PUBLISH_COLUMNS) {
                        addColumnIfMissing(connection, PUBLISH_TABLE, column[0], column[1]);
                    }
                    ensureIndex(connection, "idx_publish_device",
                        "CREATE INDEX idx_publish_device ON " + PUBLISH_TABLE
                            + " (platform_id, object_type, device_key)");
                    backfillDeviceKey(connection);
                }
                verify(connection, channelTable, publishTable);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联通道编码与推送状态 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联通道编码与推送状态 schema 迁移失败", e);
        }
    }

    /** 校验结果：涉及的表必须列与索引齐全，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection, boolean channelTable, boolean publishTable) throws SQLException {
        if (channelTable) {
            for (String[] column : CHANNEL_COLUMNS) {
                requireColumn(connection, CHANNEL_TABLE, column[0]);
            }
            requireIndex(connection, "idx_channel_code");
        }
        if (publishTable) {
            for (String[] column : PUBLISH_COLUMNS) {
                requireColumn(connection, PUBLISH_TABLE, column[0]);
            }
            requireIndex(connection, "idx_publish_device");
        }
    }

    /**
     * 存量通道发布行回填设备段：只认 CHANNEL、只在列为空时写，重复执行无副作用。
     *
     * <p>用 {@code substr/instr} 而不是 {@code LIKE} 截取，口径与 {@code PublishKeys.channelDeviceId}
     * 保持逐字一致；缺少分隔符的脏键跳过（回落到旧的分组方式，不猜设备号）。
     */
    private void backfillDeviceKey(Connection connection) throws SQLException {
        int affected;
        try (Statement statement = connection.createStatement()) {
            affected = statement.executeUpdate("UPDATE " + PUBLISH_TABLE
                + " SET device_key = substr(object_key, 1, instr(object_key, ':') - 1)"
                + " WHERE object_type = 'CHANNEL'"
                + " AND device_key IS NULL"
                + " AND instr(object_key, ':') > 1");
        }
        if (affected > 0) {
            log.info("SQLite 迁移回填 {}.device_key: {} 行", PUBLISH_TABLE, affected);
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

    private void requireIndex(Connection connection, String index) throws SQLException {
        if (!indexExists(connection, index)) {
            throw new IllegalStateException("SQLite 缺少索引 " + index);
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

    private void ensureIndex(Connection connection, String index, String ddl) throws SQLException {
        if (indexExists(connection, index)) {
            return;
        }
        execute(connection, ddl);
        log.info("SQLite 迁移补索引: {}", index);
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

    private boolean indexExists(Connection connection, String index) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?")) {
            ps.setString(1, index);
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
