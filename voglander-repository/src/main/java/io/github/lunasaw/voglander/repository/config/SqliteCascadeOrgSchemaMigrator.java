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
 * 本类把历次增量（建组织表、补通道归属列、补物化路径列）合并成一次幂等迁移：
 *
 * <ul>
 *   <li>建表用 {@code CREATE TABLE IF NOT EXISTS}</li>
 *   <li>加列前查 {@code pragma_table_info}，SQLite 的 {@code ADD COLUMN} 不支持
 *       {@code IF NOT EXISTS}，重复执行会报 duplicate column</li>
 *   <li>加索引用 {@code CREATE INDEX IF NOT EXISTS}</li>
 *   <li>回填 {@code org_path} 用递归 CTE，只覆盖仍为空的记录，重复执行不会破坏已有值</li>
 * </ul>
 *
 * <p><b>1.10.1 组织去平台化</b>：组织改为全局一套的本地资产，表里不再有 {@code platform_id}，
 * 原 {@code cascade_org_id}（平台内唯一）改名为全局唯一的 {@code org_code}，并新增七段编码规则列。
 * SQLite 不支持 {@code DROP COLUMN} 也不支持改列名，因此老库只能<b>重建表</b>：
 * 先 {@code RENAME TO _cascade_org_legacy}，按新形状建表，{@code INSERT ... SELECT} 把
 * {@code cascade_org_id} 的值写进 {@code org_code}、丢弃 {@code platform_id}，再删旧表并重建索引。
 * 重建判断条件是"有旧列 {@code cascade_org_id} 且无新列 {@code org_code}"，重建完条件不再成立，天然幂等。
 *</p>
 *
 * <p>建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；本类只负责把老库补齐到该脚本的形状。
 * 非 SQLite 数据源直接跳过，MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*} 下的脚本。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeOrgSchemaMigrator {

    /** 迁移键：历次增量合并登记，只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "add-cascade-org-tree-and-path";

    private static final String ORG_TABLE          = "tb_cascade_org";
    private static final String CHANNEL_TABLE      = "tb_cascade_channel";
    private static final String ORG_PATH_COLUMN    = "org_path";
    private static final String ORG_CODE_COLUMN    = "org_code";
    private static final String CHANNEL_ORG_COLUMN = "cascade_org_id";
    private static final String REVOKE_COLUMN      = "revoke_policy";

    /** 平台内唯一的旧列名，重建表时用它判断是否需要升级 */
    private static final String LEGACY_ORG_CODE_COLUMN = "cascade_org_id";

    /** 重建期间的中间表名：刻意不用 {@code tb_} 前缀，避免被 initializer 的表数校验算进去 */
    private static final String LEGACY_ORG_TABLE = "_cascade_org_legacy";

    /** 七段编码规则列 + 启用开关，顺序即 DDL 顺序 */
    private static final String[][] CODE_COLUMNS = {
        {"code_province", "VARCHAR(2) DEFAULT NULL"},
        {"code_city", "VARCHAR(2) DEFAULT NULL"},
        {"code_county", "VARCHAR(4) DEFAULT NULL"},
        {"code_industry", "VARCHAR(2) DEFAULT NULL"},
        {"code_type", "VARCHAR(3) DEFAULT NULL"},
        {"code_network", "VARCHAR(1) DEFAULT NULL"},
        {"code_start_seq", "INTEGER DEFAULT NULL"},
        {"code_rule_enabled", "INTEGER NOT NULL DEFAULT 0"}};

    private static final String CREATE_ORG_TABLE =
        "CREATE TABLE IF NOT EXISTS tb_cascade_org ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " parent_org_id INTEGER DEFAULT NULL,"
            + " org_path VARCHAR(512) DEFAULT '' NOT NULL,"
            + " org_code VARCHAR(64) NOT NULL,"
            + " org_name VARCHAR(255) NOT NULL,"
            + " sort_order INTEGER DEFAULT 0 NOT NULL,"
            + " enabled INTEGER DEFAULT 1 NOT NULL,"
            + " revoke_policy VARCHAR(16) DEFAULT 'CASCADE' NOT NULL,"
            + " code_province VARCHAR(2) DEFAULT NULL,"
            + " code_city VARCHAR(2) DEFAULT NULL,"
            + " code_county VARCHAR(4) DEFAULT NULL,"
            + " code_industry VARCHAR(2) DEFAULT NULL,"
            + " code_type VARCHAR(3) DEFAULT NULL,"
            + " code_network VARCHAR(1) DEFAULT NULL,"
            + " code_start_seq INTEGER DEFAULT NULL,"
            + " code_rule_enabled INTEGER NOT NULL DEFAULT 0)";

    /** 从每个根组织出发拼编码路径；只补 org_path 仍为空的记录 */
    private static final String BACKFILL_ORG_PATH =
        "WITH RECURSIVE tree(id, path) AS ("
            + " SELECT id, '/' || org_code || '/' FROM tb_cascade_org WHERE parent_org_id IS NULL"
            + " UNION ALL"
            + " SELECT c.id, tree.path || c.org_code || '/'"
            + " FROM tb_cascade_org c JOIN tree ON c.parent_org_id = tree.id)"
            + " UPDATE tb_cascade_org"
            + " SET org_path = (SELECT path FROM tree t WHERE t.id = tb_cascade_org.id)"
            + " WHERE org_path = '' AND id IN (SELECT id FROM tree)";

    /** 重建后编码可能因去重被改写，整棵树的物化路径必须按新编码全量重算 */
    private static final String REBUILD_ORG_PATH =
        "WITH RECURSIVE tree(id, path) AS ("
            + " SELECT id, '/' || org_code || '/' FROM tb_cascade_org WHERE parent_org_id IS NULL"
            + " UNION ALL"
            + " SELECT c.id, tree.path || c.org_code || '/'"
            + " FROM tb_cascade_org c JOIN tree ON c.parent_org_id = tree.id)"
            + " UPDATE tb_cascade_org"
            + " SET org_path = (SELECT path FROM tree t WHERE t.id = tb_cascade_org.id)"
            + " WHERE id IN (SELECT id FROM tree)";

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
                /* 去平台化重建必须在建表之前：老库的旧表要整体换成新形状，新库则无旧列直接跳过 */
                rebuildLegacyOrgTable(connection);
                execute(connection, CREATE_ORG_TABLE);
                addColumnIfMissing(connection, CHANNEL_TABLE, CHANNEL_ORG_COLUMN, "VARCHAR(64) DEFAULT NULL");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_org_parent"
                    + " ON tb_cascade_org (parent_org_id)");
                execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_cascade_org_code"
                    + " ON tb_cascade_org (org_code)");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_channel_org"
                    + " ON tb_cascade_channel (platform_id, cascade_org_id)");
                addColumnIfMissing(connection, ORG_TABLE, ORG_PATH_COLUMN, "VARCHAR(512) NOT NULL DEFAULT ''");
                /* 1.0.14：「取消同步」时其下通道的处置策略，历史行一律回落 CASCADE（连带剪掉） */
                addColumnIfMissing(connection, ORG_TABLE, REVOKE_COLUMN, "VARCHAR(16) NOT NULL DEFAULT 'CASCADE'");
                /* 1.10.1：七段编码规则列（预留，暂不生成）——只补列，不写值 */
                for (String[] codeColumn : CODE_COLUMNS) {
                    addColumnIfMissing(connection, ORG_TABLE, codeColumn[0], codeColumn[1]);
                }
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_org_path"
                    + " ON tb_cascade_org (org_path)");
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

    /**
     * 老库去平台化重建：把 {@code tb_cascade_org} 换成无 {@code platform_id}、有 {@code org_code} 的新形状。
     *
     * <p>只在「有旧列 {@code cascade_org_id} 且无新列 {@code org_code}」时动手；重建完该条件不再成立，
     * 所以重复启动不会重复搬迁。整个过程在调用方的事务内，中途失败整体回滚，不会留下半张表。
     *</p>
     *
     * <p>旧唯一键是 {@code (platform_id, cascade_org_id)}，同一个 {@code cascade_org_id} 理论上可在多个
     * 平台各出现一次；直接搬到单列唯一会上报冲突。搬迁时首个（id 最小）保留原值，其余拼上
     * {@code -platform_id} 后缀保命，并因此在最后整棵重算 {@code org_path}。
     *</p>
     */
    private void rebuildLegacyOrgTable(Connection connection) throws SQLException {
        if (!legacyOrgShape(connection)) {
            return;
        }
        String orgPath = columnExists(connection, ORG_TABLE, ORG_PATH_COLUMN) ? ORG_PATH_COLUMN : "''";
        String revoke = columnExists(connection, ORG_TABLE, REVOKE_COLUMN) ? REVOKE_COLUMN : "'CASCADE'";
        String dupSuffix = columnExists(connection, ORG_TABLE, "platform_id")
            ? " || '-' || platform_id"
            : " || '-' || id";

        /* 索引会跟着 RENAME 走且保留原名，不先删就会卡住后面的"同名建新索引" */
        execute(connection, "DROP INDEX IF EXISTS uk_cascade_org_code");
        execute(connection, "DROP INDEX IF EXISTS idx_cascade_org_parent");
        execute(connection, "DROP INDEX IF EXISTS idx_cascade_org_path");
        execute(connection, "ALTER TABLE " + ORG_TABLE + " RENAME TO " + LEGACY_ORG_TABLE);
        execute(connection, CREATE_ORG_TABLE);
        execute(connection, "INSERT OR IGNORE INTO tb_cascade_org"
            + " (id, create_time, update_time, parent_org_id, org_path, org_code, org_name, sort_order,"
            + " enabled, revoke_policy, code_rule_enabled)"
            + " SELECT id, create_time, update_time, parent_org_id, " + orgPath + ","
            + " CASE WHEN id IN (SELECT MIN(id) FROM " + LEGACY_ORG_TABLE
            + " GROUP BY " + LEGACY_ORG_CODE_COLUMN + ")"
            + " THEN " + LEGACY_ORG_CODE_COLUMN
            + " ELSE " + LEGACY_ORG_CODE_COLUMN + dupSuffix + " END,"
            + " org_name, sort_order, enabled, " + revoke + ", 0"
            + " FROM " + LEGACY_ORG_TABLE + " ORDER BY id");
        execute(connection, "DROP TABLE " + LEGACY_ORG_TABLE);
        execute(connection, REBUILD_ORG_PATH);
        log.info("SQLite 迁移：tb_cascade_org 去平台化重建（cascade_org_id → org_code，丢弃 platform_id）");
    }

    /** 老形状判定：表在、旧列在、新列不在 */
    private boolean legacyOrgShape(Connection connection) throws SQLException {
        return tableExists(connection, ORG_TABLE)
            && columnExists(connection, ORG_TABLE, LEGACY_ORG_CODE_COLUMN)
            && !columnExists(connection, ORG_TABLE, ORG_CODE_COLUMN);
    }

    /** 校验结果：组织表、通道归属列、路径列、取消同步策略列、新编码列都必须存在，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        requireTable(connection, ORG_TABLE);
        requireColumn(connection, CHANNEL_TABLE, CHANNEL_ORG_COLUMN);
        requireColumn(connection, ORG_TABLE, ORG_CODE_COLUMN);
        requireColumn(connection, ORG_TABLE, ORG_PATH_COLUMN);
        requireColumn(connection, ORG_TABLE, REVOKE_COLUMN);
        for (String[] codeColumn : CODE_COLUMNS) {
            requireColumn(connection, ORG_TABLE, codeColumn[0]);
        }
        /* 旧列必须已消失：若还在，说明重建没跑成而 SQL 已按 org_code 拼，查不到数据也不会报错 */
        if (columnExists(connection, ORG_TABLE, LEGACY_ORG_CODE_COLUMN)) {
            throw new IllegalStateException(
                "SQLite 表 " + ORG_TABLE + " 仍存在旧列 " + LEGACY_ORG_CODE_COLUMN + "，去平台化未完成");
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
