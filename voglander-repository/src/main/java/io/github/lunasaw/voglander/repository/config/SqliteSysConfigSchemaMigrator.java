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
 * SQLite 系统配置表 schema 迁移（幂等，可重复执行）。
 *
 * <p><b>为什么需要它</b>：{@code tb_sys_config} 是 1.0.13 新增表，而建表脚本只在空库执行；
 * 老库升级后 {@code SqliteSchemaInitializer} 会因为 sentinel 表已存在而跳过全量建表，
 * 随后 {@code verifySchemaComplete()} 统计表数不足直接阻断启动。把建表放进迁移链，
 * 老库才能补出这张表并越过表数校验。
 *
 * <p>建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；本类只负责把老库补齐到该脚本的形状。
 * 非 SQLite 数据源直接跳过，MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*} 下的脚本。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteSysConfigSchemaMigrator {

    /** 迁移键：只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "add-sys-config-table";

    private static final String TABLE = "tb_sys_config";

    private static final String CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS tb_sys_config ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " config_key VARCHAR(128) NOT NULL,"
            + " config_value VARCHAR(512) DEFAULT NULL,"
            + " config_group VARCHAR(64) NOT NULL,"
            + " value_type VARCHAR(16) NOT NULL DEFAULT 'STRING',"
            + " description VARCHAR(255) DEFAULT NULL,"
            + " editable INTEGER DEFAULT 1 NOT NULL)";

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行迁移。非 SQLite 或数据源缺失时静默跳过。
     *
     * @throws IllegalStateException 迁移或校验失败（上抛阻断启动，不留"表缺失"的隐性坏库）
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
                execute(connection, CREATE_TABLE);
                execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_config_key"
                    + " ON tb_sys_config (config_key)");
                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 系统配置 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 系统配置 schema 迁移失败", e);
        }
    }

    /** 校验结果：表与唯一索引都必须存在，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("SQLite 缺少表 " + TABLE);
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name='uk_sys_config_key'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("SQLite 缺少唯一索引 uk_sys_config_key");
                }
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
