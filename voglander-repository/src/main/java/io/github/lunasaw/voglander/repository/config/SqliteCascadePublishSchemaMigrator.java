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
 * SQLite 级联发布层 schema 迁移（幂等，可重复执行）。
 *
 * <p><b>为什么需要它</b>：1.10.1 起组织/设备不再绑定上级平台，改为「本地资产 + 发布层」：
 * 本地资产表永远不出现 {@code platform_id}，上报关系统一落在一张 {@code tb_cascade_publish}
 * （唯一键 {@code (platform_id, object_type, object_id)}，编码唯一键 {@code (platform_id, publish_code)}）。
 * 设备通过可空的 {@code org_id} 归属组织。这两处只改了建表脚本，
 * 而建表脚本只在空库时执行，老库启动后会因缺表/缺列直接报 {@code no such table}，
 * 因此必须由本类在启动链路里补齐。
 *
 * <p>职责边界：{@link SqliteCascadeOrgSchemaMigrator} 负责 {@code tb_cascade_org} 的去平台化重建与
 * 编码段列，本类只负责发布表与设备归属列，两个迁移器互不改对方的表。
 *
 * <p>建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；本类只负责把老库补齐到该脚本的形状。
 * 非 SQLite 数据源直接跳过，MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*} 下的脚本。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadePublishSchemaMigrator {

    /** 迁移键：只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "add-cascade-publish-and-device-org";

    private static final String PUBLISH_TABLE      = "tb_cascade_publish";
    private static final String DEVICE_TABLE       = "tb_device";
    private static final String DEVICE_ORG_COLUMN  = "org_id";

    private static final String CREATE_PUBLISH_TABLE =
        "CREATE TABLE IF NOT EXISTS tb_cascade_publish ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " object_type VARCHAR(16) NOT NULL,"
            + " object_id INTEGER NOT NULL,"
            + " publish_code VARCHAR(64) NOT NULL,"
            + " publish_name VARCHAR(128) DEFAULT NULL,"
            + " enabled INTEGER DEFAULT 1 NOT NULL,"
            + " sort_order INTEGER DEFAULT 0 NOT NULL,"
            + " manufacturer VARCHAR(64) DEFAULT NULL,"
            + " model VARCHAR(128) DEFAULT NULL,"
            + " address VARCHAR(255) DEFAULT NULL,"
            + " ip_address VARCHAR(64) DEFAULT NULL,"
            + " ptz_type INTEGER DEFAULT NULL,"
            + " civil_code VARCHAR(64) DEFAULT NULL,"
            + " revoke_policy VARCHAR(16) DEFAULT NULL,"
            + " extend TEXT)";

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行迁移。非 SQLite 或数据源缺失时静默跳过。
     *
     * @throws IllegalStateException 迁移或校验失败（上抛阻断启动，不留"表/列缺失"的隐性坏库）
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
                execute(connection, CREATE_PUBLISH_TABLE);
                /* 同一平台同一对象只允许一条发布配置 */
                execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_object"
                    + " ON tb_cascade_publish (platform_id, object_type, object_id)");
                /* 跨对象类型防重码：上级目录按 DeviceID 建索引，组织码与通道码撞了同样是丢节点 */
                execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_code"
                    + " ON tb_cascade_publish (platform_id, publish_code)");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_publish_type"
                    + " ON tb_cascade_publish (platform_id, object_type, enabled)");
                /* 设备归属组织：可空，允许设备先接入、后归组织（现场调试常见） */
                addColumnIfMissing(connection, DEVICE_TABLE, DEVICE_ORG_COLUMN, "INTEGER DEFAULT NULL");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_device_org ON tb_device (org_id)");
                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联发布层 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联发布层 schema 迁移失败", e);
        }
    }

    /** 校验结果：发布表、三个索引与设备归属列都必须就位，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        requireTable(connection, PUBLISH_TABLE);
        requireTable(connection, DEVICE_TABLE);
        requireIndex(connection, "uk_publish_object");
        requireIndex(connection, "uk_publish_code");
        requireIndex(connection, "idx_publish_type");
        requireColumn(connection, DEVICE_TABLE, DEVICE_ORG_COLUMN);
    }

    private void requireTable(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("SQLite 缺少表 " + table);
                }
            }
        }
    }

    private void requireIndex(Connection connection, String index) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?")) {
            ps.setString(1, index);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("SQLite 缺少索引 " + index);
                }
            }
        }
    }

    private void requireColumn(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM pragma_table_info(?) WHERE name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("SQLite 表 " + table + " 缺少列 " + column);
                }
            }
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
