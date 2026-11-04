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
 * SQLite 级联发布表的「对象键」迁移：{@code object_id}（自增主键）→ {@code object_key}（业务键），
 * 同时补 {@code parent_key}（幂等）。
 *
 * <p><b>为什么不用自增主键定位发布对象</b>：id 是存储细节，不是身份。
 * <ul>
 *   <li>设备与通道行会被目录同步物理删后重建，id 一变，发布关系就成了指不到任何对象的孤儿；</li>
 *   <li>上级点播反查本地通道时，只有业务键能让「一次查询」拿到 {@code device_id} / {@code channel_id}，
 *       用 id 还得再回表拼一次；</li>
 *   <li>「这个对象推给了哪些平台」与「这个平台推了哪些对象」是同一张表的两个方向，
 *       后者的自然维度正是业务键。</li>
 * </ul>
 * 因此 ORG 存 {@code org_code}、DEVICE 存 {@code device_id}、CHANNEL 存 {@code device_id:channel_id}
 * （见 {@code CascadeConstant.PublishKeys}）。
 *
 * <p><b>为什么加 {@code parent_key}</b>：「不同上级挂不同父节点」是「对象独立于平台」唯一绕不开的差异。
 * 留空按本地结构推导，填了才覆盖——只存差异，本地组织树仍然只有一份。
 *
 * <p><b>为什么是重建而不是 {@code RENAME COLUMN}</b>：原列是 INTEGER 亲和性。虽然 20 位国标编码
 * 超出 int64 不会被强转，但短编码（测试数据、自定义编码）会被 SQLite 悄悄转成整数，
 * 语义上必须落成 TEXT。重建与 {@link SqliteCascadeSchemaMigrator} 处理组织表的手法一致：
 * 弃旧索引 → {@code RENAME TO _cascade_publish_legacy} → 按目标形状建表 →
 * 分对象类型 {@code INSERT ... SELECT} 回填业务键 → 删中间表 → 重建索引。
 *
 * <p><b>为什么单独一支迁移器</b>：迁移键是「跑过就跳过」，1.10.1 的键早已登记在大量库上，
 * 再往那支里加逻辑，那些库永远补不上本列。一次演进一个 key、一个事务。
 *
 * <p>建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；非 SQLite 数据源直接跳过，
 * MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*}{@code /1.10.2-cascade-publish-object-key.sql}。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadePublishKeyMigrator {

    /** 迁移键：只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "cascade-publish-object-key";

    private static final String PUBLISH_TABLE = "tb_cascade_publish";
    private static final String ORG_TABLE     = "tb_cascade_org";
    private static final String DEVICE_TABLE  = "tb_device";
    private static final String CHANNEL_TABLE = "tb_device_channel";

    private static final String OBJECT_KEY_COLUMN      = "object_key";
    private static final String PARENT_KEY_COLUMN      = "parent_key";
    private static final String LEGACY_OBJECT_ID_COLUMN = "object_id";

    /** 重建期间的中间表名：刻意不用 {@code tb_} 前缀，避免被 initializer 的表数校验算进去 */
    private static final String LEGACY_TABLE = "_cascade_publish_legacy";

    /** 目标形状；与 {@code sql/voglander-sqlite.sql} 中 tb_cascade_publish 逐列对齐 */
    private static final String CREATE_PUBLISH_TABLE =
        "CREATE TABLE " + PUBLISH_TABLE + " ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " object_type VARCHAR(16) NOT NULL,"
            + " object_key VARCHAR(128) NOT NULL,"
            + " parent_key VARCHAR(128) DEFAULT NULL,"
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

    /** 搬迁时原样搬运的列：对象键与 parent_key 之外的字段一个都不能丢，否则等于重配一遍 */
    private static final String COPY_COLUMNS =
        " id, create_time, update_time, platform_id, object_type,"
            + " publish_code, publish_name, enabled, sort_order,"
            + " manufacturer, model, address, ip_address, ptz_type, civil_code, revoke_policy, extend";

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行迁移。非 SQLite、数据源缺失或发布表尚未建出时静默跳过。
     *
     * @throws IllegalStateException 迁移或校验失败（上抛阻断启动，不留"旧列还在但查询已按新列拼"的隐性坏库）
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
                /* 表不存在说明尚未建库（或 1.10.1 迁移器还没跑到）：全量脚本 / 那支迁移器负责建表 */
                if (!tableExists(connection, PUBLISH_TABLE)) {
                    log.debug("SQLite 尚无 {}，跳过发布对象键迁移", PUBLISH_TABLE);
                    return;
                }
                rebuildIfLegacyShape(connection);
                /* 半升级库（列已改但 parent_key 没补）：这里兜底，代价是一次 pragma 查询 */
                addColumnIfMissing(connection, PARENT_KEY_COLUMN, "VARCHAR(128) DEFAULT NULL");
                createIndexes(connection);
                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联发布对象键迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联发布对象键迁移失败", e);
        }
    }

    /**
     * 老形状（有 {@code object_id} 或没有 {@code object_key}）时重建整表并按对象类型回填业务键。
     *
     * <p>判据在重建后不再成立，因此重复启动是幂等的。
     */
    private void rebuildIfLegacyShape(Connection connection) throws SQLException {
        if (!legacyShape(connection)) {
            return;
        }
        /* 索引会跟着 RENAME 走且保留原名，不先删就会卡住后面的"同名建新索引" */
        execute(connection, "DROP INDEX IF EXISTS uk_publish_object");
        execute(connection, "DROP INDEX IF EXISTS uk_publish_code");
        execute(connection, "DROP INDEX IF EXISTS idx_publish_type");
        execute(connection, "ALTER TABLE " + PUBLISH_TABLE + " RENAME TO " + LEGACY_TABLE);
        execute(connection, CREATE_PUBLISH_TABLE);

        int total = countLegacyRows(connection);
        int orgRows = copyRows(connection, "ORG", ORG_TABLE, "o", "o.org_code");
        int deviceRows = copyRows(connection, "DEVICE", DEVICE_TABLE, "d", "d.device_id");
        int channelRows = copyRows(connection, "CHANNEL", CHANNEL_TABLE, "c",
            "c.device_id || ':' || c.channel_id");

        /* 指不到任何本地对象的历史行（对象已删）无法翻译成业务键，只能丢弃：
           留着会让 object_key 落成空串，把唯一键卡成一片冲突 */
        int dropped = total - orgRows - deviceRows - channelRows;
        execute(connection, "DROP TABLE " + LEGACY_TABLE);
        log.info("SQLite 迁移：{} 重建为 object_key（ORG {} / DEVICE {} / CHANNEL {} 行，丢弃 {} 行）",
            PUBLISH_TABLE, orgRows, deviceRows, channelRows, Math.max(dropped, 0));
    }

    /**
     * 把某一类对象的历史发布行翻成业务键。
     *
     * @param objectType 发布对象类型
     * @param localTable 本地资产表
     * @param alias      本地表在连接中的别名（旧表固定用 {@code l}）
     * @param keyExpr    业务键取值表达式（如 {@code c.device_id || ':' || c.channel_id}）
     * @return 搬迁行数
     */
    private int copyRows(Connection connection, String objectType, String localTable, String alias, String keyExpr)
        throws SQLException {
        if (!tableExists(connection, localTable)) {
            log.warn("SQLite 迁移：{} 不存在，{} 类发布行无法回填业务键，跳过", localTable, objectType);
            return 0;
        }
        return executeUpdate(connection, "INSERT INTO " + PUBLISH_TABLE
            + " (" + COPY_COLUMNS + ", " + OBJECT_KEY_COLUMN + ", " + PARENT_KEY_COLUMN + ")"
            + " SELECT " + prefixed(COPY_COLUMNS, "l") + ", " + keyExpr + ", NULL"
            + " FROM " + LEGACY_TABLE + " l JOIN " + localTable + " " + alias
            + " ON " + alias + ".id = l." + LEGACY_OBJECT_ID_COLUMN
            + " WHERE l.object_type = '" + objectType + "'");
    }

    /** 给逗号分隔的列清单逐项加表别名（{@code id, name} → {@code l.id, l.name}） */
    private String prefixed(String columns, String alias) {
        StringBuilder sb = new StringBuilder();
        for (String column : columns.split(",")) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(alias).append('.').append(column.trim());
        }
        return sb.toString();
    }

    private int countLegacyRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + LEGACY_TABLE)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 目标形状判据：表在，且 {@code object_key} 缺位或旧列仍在 */
    private boolean legacyShape(Connection connection) throws SQLException {
        return !columnExists(connection, OBJECT_KEY_COLUMN)
            || columnExists(connection, LEGACY_OBJECT_ID_COLUMN);
    }

    /** 唯一键与查询索引；{@code uk_publish_code} 是跨对象类型的平台内防重码，必须保留 */
    private void createIndexes(Connection connection) throws SQLException {
        execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_object"
            + " ON " + PUBLISH_TABLE + " (platform_id, object_type, " + OBJECT_KEY_COLUMN + ")");
        execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_code"
            + " ON " + PUBLISH_TABLE + " (platform_id, publish_code)");
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_publish_type"
            + " ON " + PUBLISH_TABLE + " (platform_id, object_type, enabled)");
    }

    /** 校验：新列与新索引必须就位、旧列必须已消失，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        requireColumn(connection, OBJECT_KEY_COLUMN);
        requireColumn(connection, PARENT_KEY_COLUMN);
        if (columnExists(connection, LEGACY_OBJECT_ID_COLUMN)) {
            throw new IllegalStateException(
                "SQLite 表 " + PUBLISH_TABLE + " 仍存在旧列 " + LEGACY_OBJECT_ID_COLUMN + "，发布对象键迁移未完成");
        }
        requireIndex(connection, "uk_publish_object");
        requireIndex(connection, "uk_publish_code");
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

    private void requireColumn(Connection connection, String column) throws SQLException {
        if (!columnExists(connection, column)) {
            throw new IllegalStateException("SQLite 表 " + PUBLISH_TABLE + " 缺少列 " + column);
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

    private void addColumnIfMissing(Connection connection, String column, String definition) throws SQLException {
        if (columnExists(connection, column)) {
            return;
        }
        execute(connection, "ALTER TABLE " + PUBLISH_TABLE + " ADD COLUMN " + column + " " + definition);
        log.info("SQLite 迁移补列: {}.{}", PUBLISH_TABLE, column);
    }

    private boolean columnExists(Connection connection, String column) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM pragma_table_info(?) WHERE name=?")) {
            ps.setString(1, PUBLISH_TABLE);
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

    private int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
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
