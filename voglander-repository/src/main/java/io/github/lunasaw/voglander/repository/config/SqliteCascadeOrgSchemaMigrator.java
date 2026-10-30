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
 * SQLite 级联组织树 schema 迁移（幂等，可重复执行）。
 *
 * <p><b>为什么需要它</b>：1.0.11 的级联组织表与 {@code cascade_org_id} 列只提供了
 * 手工执行的迁移脚本，运行时没有任何程序化升级入口。老库升级后第一次查
 * {@code tb_cascade_channel} 就会抛 {@code no such column}，而 {@code SELECT *}
 * 才报错的时机很晚，表现为"提交了组织、上级也收了，一查通道就 500"。
 * 本类把三次增量（建组织表、补通道归属列、补物化路径列）合并成一次幂等迁移：
 *
 * <ul>
 *   <li>建表用 {@code CREATE TABLE IF NOT EXISTS}</li>
 *   <li>加列前查 {@code pragma_table_info}，SQLite 的 {@code ADD COLUMN} 不支持
 *       {@code IF NOT EXISTS}，重复执行会报 duplicate column</li>
 *   <li>加索引用 {@code CREATE INDEX IF NOT EXISTS}</li>
 *   <li>回填 {@code org_path} 用递归 CTE，只覆盖仍为空的记录，重复执行不会破坏已有值</li>
 * </ul>
 *
 * <p>建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；本类只负责把老库补齐到该脚本的形状。
 * 非 SQLite 数据源直接跳过，MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*} 下的脚本。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeOrgSchemaMigrator {

    /** 迁移键：三次增量合并登记，只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "add-cascade-org-tree-and-path";

    private static final String ORG_TABLE          = "tb_cascade_org";
    private static final String CHANNEL_TABLE      = "tb_cascade_channel";
    private static final String ORG_COLUMN         = "org_path";
    private static final String CHANNEL_ORG_COLUMN = "cascade_org_id";

    private static final String CREATE_ORG_TABLE =
        "CREATE TABLE IF NOT EXISTS tb_cascade_org ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " parent_org_id INTEGER DEFAULT NULL,"
            + " org_path VARCHAR(512) DEFAULT '' NOT NULL,"
            + " cascade_org_id VARCHAR(64) NOT NULL,"
            + " org_name VARCHAR(255) NOT NULL,"
            + " sort_order INTEGER DEFAULT 0 NOT NULL,"
            + " enabled INTEGER DEFAULT 1 NOT NULL)";

    /** 从每个根组织出发拼编码路径；只补 org_path 仍为空的记录 */
    private static final String BACKFILL_ORG_PATH =
        "WITH RECURSIVE tree(id, path) AS ("
            + " SELECT id, '/' || cascade_org_id || '/' FROM tb_cascade_org WHERE parent_org_id IS NULL"
            + " UNION ALL"
            + " SELECT c.id, tree.path || c.cascade_org_id || '/'"
            + " FROM tb_cascade_org c JOIN tree ON c.parent_org_id = tree.id)"
            + " UPDATE tb_cascade_org"
            + " SET org_path = (SELECT path FROM tree t WHERE t.id = tb_cascade_org.id)"
            + " WHERE org_path = '' AND id IN (SELECT id FROM tree)";

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
                execute(connection, CREATE_ORG_TABLE);
                addColumnIfMissing(connection, CHANNEL_TABLE, CHANNEL_ORG_COLUMN, "VARCHAR(64) DEFAULT NULL");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_org_parent"
                    + " ON tb_cascade_org (parent_org_id)");
                execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_cascade_org_code"
                    + " ON tb_cascade_org (platform_id, cascade_org_id)");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_channel_org"
                    + " ON tb_cascade_channel (platform_id, cascade_org_id)");
                addColumnIfMissing(connection, ORG_TABLE, ORG_COLUMN, "VARCHAR(512) NOT NULL DEFAULT ''");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_org_path"
                    + " ON tb_cascade_org (platform_id, org_path)");
                /* 回填放在加列之后、且只补空值：新库与已回填的库都是一条空操作的 UPDATE */
                execute(connection, BACKFILL_ORG_PATH);
                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联组织 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联组织 schema 迁移失败", e);
        }
    }

    /** 校验结果：组织表、通道归属列、路径列都必须存在，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        requireTable(connection, ORG_TABLE);
        requireColumn(connection, CHANNEL_TABLE, CHANNEL_ORG_COLUMN);
        requireColumn(connection, ORG_TABLE, ORG_COLUMN);
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
