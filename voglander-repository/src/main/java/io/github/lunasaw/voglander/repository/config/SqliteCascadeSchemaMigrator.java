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
 * SQLite 级联域 schema 迁移（幂等，可重复执行）。
 *
 * <p><b>为什么需要它</b>：级联的建表/加列历来只提供手工迁移脚本，运行时没有程序化升级入口。
 * 老库升级后第一次查 {@code tb_cascade_channel} 就抛 {@code no such column}，
 * 表现为"提交了组织、上级也收了，一查通道就 500"。而 {@code sql/migration/sqlite/*.sql}
 * 不会被自动执行——{@link SqliteSchemaInitializer} 只认硬编码的迁移器 bean。
 *
 * <p><b>为什么是一个迁移器而不是按表拆</b>：1.10.1 的组织去平台化、发布表、设备归属列属于
 * <b>同一次 schema 演进</b>。拆成多个会让「这个库升到哪一步」要查多行迁移历史，更要紧的是
 * 产生多个事务边界——组织表重建成功、发布表建表失败时，库就停在半升级态，
 * 而这种状态既跑不起来也回不去。一个演进 = 一个 key = 一个事务。
 *
 * <p><b>1.10.1 的三处变化</b>
 * <ul>
 *   <li>{@code tb_cascade_org}：去掉 {@code platform_id}、{@code cascade_org_id} 改名全局唯一的
 *       {@code org_code}、{@code parent_org_id} 改名 {@code parent_id}、补七段编码规则列</li>
 *   <li>{@code tb_cascade_publish}：新建，每个上级平台一套可见性视图</li>
 *   <li>{@code tb_device.org_id}：补可空的组织归属列</li>
 * </ul>
 *
 * <p>SQLite 既不支持 {@code DROP COLUMN} 也不支持改列名，组织表只能<b>重建</b>：
 * 删旧索引 → {@code RENAME TO _cascade_org_legacy} → 按目标形状建表 → {@code INSERT ... SELECT}
 * 搬数据（编码换成 {@code org_code}、父列换成 {@code parent_id}）→ 删中间表 → 重建索引 → 重算物化路径。
 * 重建判据是「目标形状的列还没齐」，重建完判据不再成立，天然幂等。
 *
 * <p>建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；本类只负责把老库补齐到该脚本的形状。
 * 非 SQLite 数据源直接跳过，MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*} 下的脚本。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeSchemaMigrator {

    /** 迁移键：登记本次演进，只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "cascade-local-asset-and-publish";

    private static final String ORG_TABLE      = "tb_cascade_org";
    private static final String CHANNEL_TABLE  = "tb_cascade_channel";
    private static final String DEVICE_TABLE   = "tb_device";
    private static final String PUBLISH_TABLE  = "tb_cascade_publish";

    private static final String ORG_CODE_COLUMN   = "org_code";
    private static final String ORG_PATH_COLUMN   = "org_path";
    private static final String ORG_PARENT_COLUMN = "parent_id";
    private static final String REVOKE_COLUMN     = "revoke_policy";
    private static final String DEVICE_ORG_COLUMN = "org_id";

    /** 1.10.0 及以前的列名，出现即说明需要重建 */
    private static final String LEGACY_ORG_CODE_COLUMN   = "cascade_org_id";
    private static final String LEGACY_ORG_PARENT_COLUMN = "parent_org_id";
    private static final String LEGACY_PLATFORM_COLUMN   = "platform_id";

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
            + " parent_id INTEGER DEFAULT NULL,"
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

    /**
     * 发布表：{@code publish_code} 与 {@code sort_order} 允许为空，空 = 继承本地值。
     *
     * <p>这条"空即继承"是本表能只存差异的关键：{@code UNIQUE (platform_id, publish_code)}
     * 对 NULL 天然放行（SQLite/MySQL/PostgreSQL 一致），所以没被覆盖的对象不占用平台的编码空间，
     * 本地改了编码也不用逐个平台跟着改；一旦填了覆盖值，跨对象类型的重码照样被库拦住。
     */
    private static final String CREATE_PUBLISH_TABLE =
        "CREATE TABLE IF NOT EXISTS tb_cascade_publish ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " object_type VARCHAR(16) NOT NULL,"
            + " object_id INTEGER NOT NULL,"
            + " publish_code VARCHAR(64) DEFAULT NULL,"
            + " publish_name VARCHAR(128) DEFAULT NULL,"
            + " enabled INTEGER DEFAULT 1 NOT NULL,"
            + " sort_order INTEGER DEFAULT NULL,"
            + " manufacturer VARCHAR(64) DEFAULT NULL,"
            + " model VARCHAR(128) DEFAULT NULL,"
            + " address VARCHAR(255) DEFAULT NULL,"
            + " ip_address VARCHAR(64) DEFAULT NULL,"
            + " ptz_type INTEGER DEFAULT NULL,"
            + " civil_code VARCHAR(64) DEFAULT NULL,"
            + " revoke_policy VARCHAR(16) DEFAULT NULL,"
            + " extend TEXT)";

    /** 从每个根组织出发拼编码路径；只补 org_path 仍为空的记录 */
    private static final String BACKFILL_ORG_PATH =
        "WITH RECURSIVE tree(id, path) AS ("
            + " SELECT id, '/' || org_code || '/' FROM tb_cascade_org WHERE parent_id IS NULL"
            + " UNION ALL"
            + " SELECT c.id, tree.path || c.org_code || '/'"
            + " FROM tb_cascade_org c JOIN tree ON c.parent_id = tree.id)"
            + " UPDATE tb_cascade_org"
            + " SET org_path = (SELECT path FROM tree t WHERE t.id = tb_cascade_org.id)"
            + " WHERE org_path = '' AND id IN (SELECT id FROM tree)";

    /** 重建后编码可能因去重被改写，整棵树的物化路径必须按新编码全量重算 */
    private static final String REBUILD_ORG_PATH =
        "WITH RECURSIVE tree(id, path) AS ("
            + " SELECT id, '/' || org_code || '/' FROM tb_cascade_org WHERE parent_id IS NULL"
            + " UNION ALL"
            + " SELECT c.id, tree.path || c.org_code || '/'"
            + " FROM tb_cascade_org c JOIN tree ON c.parent_id = tree.id)"
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
                /* 组织表重建必须先于建表：老表整体换成新形状，新库形已达标则跳过 */
                rebuildLegacyOrgTable(connection);
                execute(connection, CREATE_ORG_TABLE);
                addColumnIfMissing(connection, ORG_TABLE, ORG_PATH_COLUMN, "VARCHAR(512) NOT NULL DEFAULT ''");
                /* 1.0.14：取消同步时其下通道的处置策略，历史行一律回落 CASCADE（连带剪掉） */
                addColumnIfMissing(connection, ORG_TABLE, REVOKE_COLUMN, "VARCHAR(16) NOT NULL DEFAULT 'CASCADE'");
                /* 1.10.1：七段编码规则列（预留，暂不生成）——只补列，不写值 */
                for (String[] codeColumn : CODE_COLUMNS) {
                    addColumnIfMissing(connection, ORG_TABLE, codeColumn[0], codeColumn[1]);
                }
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_org_parent"
                    + " ON tb_cascade_org (parent_id)");
                execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_cascade_org_code"
                    + " ON tb_cascade_org (org_code)");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_org_path"
                    + " ON tb_cascade_org (org_path)");
                execute(connection, BACKFILL_ORG_PATH);

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

                /* 通道归属列在 1.10.1 仍然有效（S4 删除 tb_cascade_channel 时才退场） */
                addColumnIfMissing(connection, CHANNEL_TABLE, LEGACY_ORG_CODE_COLUMN, "VARCHAR(64) DEFAULT NULL");
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_cascade_channel_org"
                    + " ON tb_cascade_channel (platform_id, cascade_org_id)");

                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联域 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联域 schema 迁移失败", e);
        }
    }

    /**
     * 老库重建组织表：把任意历史形状换成「{@code parent_id} + 全局唯一 {@code org_code} + 七段编码列」。
     *
     * <p>只在「旧列还在」时动手，重建完旧列消失，判据不再成立，因此重复启动不会重复搬迁；
     * 整个过程在调用方的事务内，中途失败整体回滚，不会留下半张表。
     *
     * <p>旧唯一键是 {@code (platform_id, cascade_org_id)}，同一个 {@code cascade_org_id} 理论上可在
     * 多个平台各出现一次，直接搬到单列唯一会撞键。搬迁时首个（id 最小）保留原值，其余拼
     * {@code -platform_id} 后缀保命，并因此在最后整棵重算 {@code org_path}。
     */
    private void rebuildLegacyOrgTable(Connection connection) throws SQLException {
        if (!legacyOrgShape(connection)) {
            return;
        }
        /* 所有列存在性判定必须在 RENAME 之前算完：改名后同一张表的列集合已经变了 */
        String parentExpr = pick(connection, ORG_TABLE, ORG_PARENT_COLUMN,
            pick(connection, ORG_TABLE, LEGACY_ORG_PARENT_COLUMN, "NULL"));
        String orgPathExpr = pick(connection, ORG_TABLE, ORG_PATH_COLUMN, "''");
        String revokeExpr = pick(connection, ORG_TABLE, REVOKE_COLUMN, "'CASCADE'");
        String orgCodeExpr = orgCodeExpr(connection);
        StringBuilder codeExprs = new StringBuilder();
        for (String[] codeColumn : CODE_COLUMNS) {
            codeExprs.append(", ").append(pick(connection, ORG_TABLE, codeColumn[0], codeExprDefault(codeColumn[0])));
        }

        /* 索引会跟着 RENAME 走且保留原名，不先删就会卡住后面的"同名建新索引" */
        execute(connection, "DROP INDEX IF EXISTS uk_cascade_org_code");
        execute(connection, "DROP INDEX IF EXISTS idx_cascade_org_parent");
        execute(connection, "DROP INDEX IF EXISTS idx_cascade_org_path");
        execute(connection, "ALTER TABLE " + ORG_TABLE + " RENAME TO " + LEGACY_ORG_TABLE);
        execute(connection, CREATE_ORG_TABLE);
        execute(connection, "INSERT OR IGNORE INTO tb_cascade_org"
            + " (id, create_time, update_time, parent_id, org_path, org_code, org_name, sort_order,"
            + " enabled, revoke_policy, code_province, code_city, code_county, code_industry,"
            + " code_type, code_network, code_start_seq, code_rule_enabled)"
            + " SELECT id, create_time, update_time, " + parentExpr + ", " + orgPathExpr + ", "
            + orgCodeExpr + ", org_name, sort_order, enabled, " + revokeExpr
            + codeExprs
            + " FROM " + LEGACY_ORG_TABLE + " ORDER BY id");
        execute(connection, "DROP TABLE " + LEGACY_ORG_TABLE);
        execute(connection, REBUILD_ORG_PATH);
        log.info("SQLite 迁移：tb_cascade_org 重建（cascade_org_id→org_code, parent_org_id→parent_id, 丢弃 platform_id）");
    }

    /** 老表缺某编码列时的回落值：启用开关默认关，其余段默认空 */
    private String codeExprDefault(String column) {
        return "code_rule_enabled".equals(column) ? "0" : "NULL";
    }

    /** 目标编码表达式：已有 {@code org_code} 直接沿用；否则从 {@code cascade_org_id} 搬家并给重码加平台后缀 */
    private String orgCodeExpr(Connection connection) throws SQLException {
        if (columnExists(connection, ORG_TABLE, ORG_CODE_COLUMN)) {
            return ORG_CODE_COLUMN;
        }
        if (!columnExists(connection, ORG_TABLE, LEGACY_ORG_CODE_COLUMN)) {
            return "''";
        }
        String dupSuffix = columnExists(connection, ORG_TABLE, LEGACY_PLATFORM_COLUMN)
            ? " || '-' || platform_id"
            : " || '-' || id";
        return "CASE WHEN id IN (SELECT MIN(id) FROM " + LEGACY_ORG_TABLE
            + " GROUP BY " + LEGACY_ORG_CODE_COLUMN + ")"
            + " THEN " + LEGACY_ORG_CODE_COLUMN
            + " ELSE " + LEGACY_ORG_CODE_COLUMN + dupSuffix + " END";
    }

    /** 列存在则用同名列，否则回落到给定默认表达式 */
    private String pick(Connection connection, String table, String column, String fallback) throws SQLException {
        return columnExists(connection, table, column) ? column : fallback;
    }

    /** 老形状判定：表在，且目标形状的关键列还没齐 */
    private boolean legacyOrgShape(Connection connection) throws SQLException {
        if (!tableExists(connection, ORG_TABLE)) {
            return false;
        }
        return columnExists(connection, ORG_TABLE, LEGACY_ORG_CODE_COLUMN)
            || columnExists(connection, ORG_TABLE, LEGACY_ORG_PARENT_COLUMN)
            || columnExists(connection, ORG_TABLE, LEGACY_PLATFORM_COLUMN)
            || !columnExists(connection, ORG_TABLE, ORG_CODE_COLUMN);
    }

    /** 校验：组织表、发布表、设备归属列都必须就位；旧列必须已消失，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        requireTable(connection, ORG_TABLE);
        requireTable(connection, PUBLISH_TABLE);
        requireTable(connection, DEVICE_TABLE);
        requireColumn(connection, ORG_TABLE, ORG_CODE_COLUMN);
        requireColumn(connection, ORG_TABLE, ORG_PARENT_COLUMN);
        requireColumn(connection, ORG_TABLE, ORG_PATH_COLUMN);
        requireColumn(connection, ORG_TABLE, REVOKE_COLUMN);
        for (String[] codeColumn : CODE_COLUMNS) {
            requireColumn(connection, ORG_TABLE, codeColumn[0]);
        }
        requireColumn(connection, DEVICE_TABLE, DEVICE_ORG_COLUMN);
        requireIndex(connection, "uk_cascade_org_code");
        requireIndex(connection, "uk_publish_object");
        requireIndex(connection, "uk_publish_code");
        /* 旧列仍在说明重建没跑成，而查询已按新列名拼——那种库查不到数据也不会报错 */
        for (String legacy : new String[] {LEGACY_ORG_CODE_COLUMN, LEGACY_ORG_PARENT_COLUMN, LEGACY_PLATFORM_COLUMN}) {
            if (columnExists(connection, ORG_TABLE, legacy)) {
                throw new IllegalStateException(
                    "SQLite 表 " + ORG_TABLE + " 仍存在旧列 " + legacy + "，去平台化未完成");
            }
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
        if (!tableExists(connection, table)) {
            throw new IllegalStateException("SQLite 缺少表 " + table);
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
