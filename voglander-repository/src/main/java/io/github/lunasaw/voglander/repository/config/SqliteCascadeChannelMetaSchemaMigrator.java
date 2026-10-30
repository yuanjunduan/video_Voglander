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
 * SQLite 级联通道目录元数据 schema 迁移（幂等，可重复执行）。
 *
 * <p><b>为什么需要它</b>：1.0.12 给 {@code tb_cascade_channel} 补了 5 个目录项元数据列
 * （{@code manufacturer/model/address/ip_address/ptz_type}）。老库升级后 {@code SELECT *}
 * 会因缺列直接报 {@code no such column}，而报错点出现在"新增/编辑通道"和目录组装，
 * 表现为上级目录一切照旧、页面一提交就 500。本类在启动时把老库补齐到
 * {@code sql/voglander-sqlite.sql} 的形状。
 *
 * <p>幂等策略与 {@link SqliteCascadeOrgSchemaMigrator} 一致：
 * <ul>
 *   <li>加列前查 {@code pragma_table_info}（SQLite 的 {@code ADD COLUMN} 不支持
 *       {@code IF NOT EXISTS}，重复执行会报 duplicate column）</li>
 *   <li>迁移成功后登记 {@code schema_migration_history}，便于人工判断库升级到哪一步</li>
 * </ul>
 *
 * <p>建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；非 SQLite 数据源直接跳过，
 * MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*} 下的脚本。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeChannelMetaSchemaMigrator {

    /** 迁移键：只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "add-cascade-channel-catalog-meta";

    private static final String CHANNEL_TABLE = "tb_cascade_channel";

    /** 列名 → 列定义；顺序即报表书写顺序，取不到值时上级按缺省处理 */
    private static final String[][] COLUMNS = {
        {"manufacturer", "VARCHAR(64) DEFAULT NULL"},
        {"model", "VARCHAR(128) DEFAULT NULL"},
        {"address", "VARCHAR(255) DEFAULT NULL"},
        {"ip_address", "VARCHAR(64) DEFAULT NULL"},
        {"ptz_type", "INTEGER DEFAULT NULL"}
    };

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行迁移。非 SQLite 或数据源缺失时静默跳过。
     *
     * @throws IllegalStateException 迁移或校验失败（上抛阻断启动，不留"列缺失"的隐性坏库）
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
                if (!tableExists(connection, CHANNEL_TABLE)) {
                    log.debug("SQLite 尚无 {}，跳过目录元数据迁移", CHANNEL_TABLE);
                    return;
                }
                for (String[] column : COLUMNS) {
                    addColumnIfMissing(connection, CHANNEL_TABLE, column[0], column[1]);
                }
                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联通道目录元数据 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联通道目录元数据 schema 迁移失败", e);
        }
    }

    /** 校验结果：5 列必须全部存在，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        for (String[] column : COLUMNS) {
            requireColumn(connection, CHANNEL_TABLE, column[0]);
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
