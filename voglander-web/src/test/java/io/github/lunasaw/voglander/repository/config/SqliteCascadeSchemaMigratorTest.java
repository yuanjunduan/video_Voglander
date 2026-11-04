package io.github.lunasaw.voglander.repository.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 1.10.1 级联域 schema 迁移的回归测试。
 *
 * <p>重点钉住 SQLite 那条最容易坏的路：组织表不能 {@code DROP COLUMN} 也不能改列名，
 * 只能建中间表搬数据。搬迁要同时满足「旧列消失、新列有值、唯一键变单列、编码列补齐、
 * 索引指向新列、重复执行不重复搬」六件事，任一件漏了都会在启动时静默留下半升级库。
 *
 * <p>另外钉住发布表的「空即继承」：{@code publish_code} 允许 NULL 且多行并存，
 * 但一旦填了值，同平台的跨对象类型重码必须被库拦住。
 */
@DisplayName("SQLite 1.10.1 级联域增量迁移")
class SqliteCascadeSchemaMigratorTest {

    private static final String LEGACY_ORG_CODE = "34020000002160000001";

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-migration-", ".db");
        Files.deleteIfExists(dbFile);
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.sqlite.JDBC");
        source.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        dataSource = source;
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
        Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
    }

    @Test
    @DisplayName("1.10.0 老库重建：platform_id/cascade_org_id/parent_org_id 全部退场，数据保留")
    void legacyOrgTable_shouldBeRebuilt() throws Exception {
        prepareLegacyOrgSchema();

        migrator().migrate();

        assertFalse(columnExists("tb_cascade_org", "platform_id"), "去平台化后不应再有 platform_id");
        assertFalse(columnExists("tb_cascade_org", "cascade_org_id"), "旧列名应被 org_code 取代");
        assertFalse(columnExists("tb_cascade_org", "parent_org_id"), "旧父列名应被 parent_id 取代");
        assertTrue(columnExists("tb_cascade_org", "parent_id"));
        for (String column : new String[] {"code_province", "code_city", "code_county", "code_industry",
            "code_type", "code_network", "code_start_seq", "code_rule_enabled"}) {
            assertTrue(columnExists("tb_cascade_org", column), "缺少编码段列 " + column);
        }
        assertEquals(LEGACY_ORG_CODE, text("SELECT org_code FROM tb_cascade_org WHERE id=1"));
        assertEquals("根组织", text("SELECT org_name FROM tb_cascade_org WHERE id=1"));
        assertEquals("/" + LEGACY_ORG_CODE + "/", text("SELECT org_path FROM tb_cascade_org WHERE id=1"));
        assertEquals(0, scalar("SELECT code_rule_enabled FROM tb_cascade_org WHERE id=1"));
        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_org WHERE parent_id=1"));
        /* 组织去平台化与「从通道反推发布关系」各登记一条：两张表都需要一次性的改写 */
        assertEquals(2, countMigrationHistory());
    }

    @Test
    @DisplayName("中间态库（已去平台化但仍用 parent_org_id）也能补齐到 parent_id")
    void halfMigratedOrgTable_shouldReachTargetShape() throws Exception {
        execute("CREATE TABLE tb_cascade_org ("
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
            + " code_rule_enabled INTEGER NOT NULL DEFAULT 0)");
        execute("CREATE TABLE tb_cascade_channel (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL, cascade_org_id VARCHAR(64) DEFAULT NULL)");
        execute("CREATE TABLE tb_device (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id VARCHAR(64) NOT NULL)");
        execute("INSERT INTO tb_cascade_org(org_code, org_name) VALUES('" + LEGACY_ORG_CODE + "', '根组织')");

        migrator().migrate();

        assertTrue(columnExists("tb_cascade_org", "parent_id"));
        assertFalse(columnExists("tb_cascade_org", "parent_org_id"));
        assertEquals(LEGACY_ORG_CODE, text("SELECT org_code FROM tb_cascade_org WHERE id=1"));
    }

    @Test
    @DisplayName("组织唯一键改为单列 org_code，路径索引不再带 platform_id")
    void rebuiltOrgTable_shouldCarrySingleColumnIndexes() throws Exception {
        prepareLegacyOrgSchema();

        migrator().migrate();

        assertTrue(indexExists("uk_cascade_org_code"));
        assertTrue(indexExists("idx_cascade_org_parent"));
        assertTrue(indexExists("idx_cascade_org_path"));
        assertEquals("org_code", indexColumns("uk_cascade_org_code"));
        assertEquals("parent_id", indexColumns("idx_cascade_org_parent"));
        assertFalse(indexColumns("idx_cascade_org_path").contains("platform_id"),
            "idx_cascade_org_path 仍带 platform_id → 旧索引没被替换");
    }

    @Test
    @DisplayName("重复启动不重复搬表，migration 只登记一次")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacyOrgSchema();
        SqliteCascadeSchemaMigrator migrator = migrator();

        migrator.migrate();
        migrator.migrate();

        assertEquals(2, scalar("SELECT count(*) FROM tb_cascade_org"));
        assertEquals(2, countMigrationHistory(), "重复启动不能追加新的 migration 行");
    }

    @Test
    @DisplayName("跨平台同编码搬迁后不撞单列唯一键，第二个拼平台后缀保命")
    void duplicateLegacyCodeAcrossPlatforms_shouldSurviveRebuild() throws Exception {
        prepareLegacyOrgSchema();
        execute("INSERT INTO tb_cascade_org(platform_id, cascade_org_id, org_name) "
            + "VALUES('P2', '" + LEGACY_ORG_CODE + "', '另一平台的同码组织')");

        migrator().migrate();

        assertEquals(3, scalar("SELECT count(*) FROM tb_cascade_org"), "三行旧组织应全部搬过去，一个都不能丢");
        assertEquals(LEGACY_ORG_CODE, text("SELECT org_code FROM tb_cascade_org WHERE id=1"));
        assertEquals(LEGACY_ORG_CODE + "001", text("SELECT org_code FROM tb_cascade_org WHERE id=2"));
        assertEquals(LEGACY_ORG_CODE + "-P2", text("SELECT org_code FROM tb_cascade_org WHERE id=3"));
    }

    @Test
    @DisplayName("发布表建成：编码为空表示继承本地，多行 NULL 并存，填了值才受重码约束")
    void publishTable_shouldKeepNullAsInheritAndGuardFilledCodes() throws Exception {
        prepareLegacyOrgSchema();

        migrator().migrate();

        assertTrue(tableExists("tb_cascade_publish"));
        assertTrue(indexExists("uk_publish_object"));
        assertTrue(indexExists("uk_publish_code"));
        assertTrue(indexExists("idx_publish_type"));
        assertTrue(columnExists("tb_device", "org_id"));
        assertTrue(indexExists("idx_device_org"));

        /* 旧 platform_id 必须停成发布行：否则组织一旦去平台化，「推给谁」这条信息就永久丢了 */
        assertEquals(2, scalar("SELECT count(*) FROM tb_cascade_publish WHERE platform_id='P1'"),
            "旧 platform_id 要逐行翻译成发布关系");
        assertEquals(1, scalar("SELECT enabled FROM tb_cascade_publish"
            + " WHERE platform_id='P1' AND object_type='ORG' AND object_key='" + LEGACY_ORG_CODE + "'"));
        assertNull(text("SELECT publish_code FROM tb_cascade_publish"
            + " WHERE platform_id='P1' AND object_key='" + LEGACY_ORG_CODE + "'"), "编码没被改写时留空即继承本地编码");

        /* 换一个平台手工插：P1 已有回填行，同键再插会撞 uk_publish_object */
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key)"
            + " VALUES('P9', 'ORG', '" + LEGACY_ORG_CODE + "')");
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key)"
            + " VALUES('P9', 'DEVICE', '34020000001320000001')");
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key)"
            + " VALUES('P9', 'CHANNEL', '34020000001320000001:34020000001310000001')");
        assertEquals(5, scalar("SELECT count(*) FROM tb_cascade_publish"), "2 行回填 + 3 行手工");
        assertNull(text("SELECT publish_code FROM tb_cascade_publish WHERE platform_id='P9' AND object_type='ORG'"));

        /* 填了覆盖值就要受平台内跨类型防重码约束 */
        execute("UPDATE tb_cascade_publish SET publish_code='34020000002169999999'"
            + " WHERE platform_id='P9' AND object_type='ORG'");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> execute(
            "UPDATE tb_cascade_publish SET publish_code='34020000002169999999'"
                + " WHERE platform_id='P9' AND object_type='CHANNEL'"));
        assertTrue(error.getCause() != null || error.getMessage() != null);
        execute("UPDATE tb_cascade_publish SET publish_code='34020000001310000001'"
            + " WHERE platform_id='P9' AND object_type='CHANNEL'");
    }

    @Test
    @DisplayName("已按目标形状建好的库，迁移器不改动结构与数据")
    void freshShapeDatabase_shouldBeUntouched() throws Exception {
        execute("CREATE TABLE tb_cascade_channel (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL, cascade_org_id VARCHAR(64) DEFAULT NULL)");
        execute("CREATE TABLE tb_device (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id VARCHAR(64) NOT NULL)");

        migrator().migrate();
        execute("INSERT INTO tb_device(device_id) VALUES('device-1')");
        migrator().migrate();

        assertTrue(columnExists("tb_cascade_org", "parent_id"));
        assertTrue(columnExists("tb_device", "org_id"));
        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_org"));
        assertEquals(1, scalar("SELECT count(*) FROM tb_device"));
    }

    // ------------------------------------------------------------------ 夹具

    /** 1.10.0 形状：platform_id + parent_org_id + cascade_org_id，无编码段列 */
    private void prepareLegacyOrgSchema() throws Exception {
        execute("CREATE TABLE tb_cascade_org ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " parent_org_id INTEGER DEFAULT NULL,"
            + " org_path VARCHAR(512) DEFAULT '' NOT NULL,"
            + " cascade_org_id VARCHAR(64) NOT NULL,"
            + " org_name VARCHAR(255) NOT NULL,"
            + " sort_order INTEGER DEFAULT 0 NOT NULL,"
            + " enabled INTEGER DEFAULT 1 NOT NULL,"
            + " revoke_policy VARCHAR(16) DEFAULT 'CASCADE' NOT NULL)");
        execute("CREATE UNIQUE INDEX uk_cascade_org_code ON tb_cascade_org (platform_id, cascade_org_id)");
        execute("CREATE INDEX idx_cascade_org_parent ON tb_cascade_org (parent_org_id)");
        execute("CREATE INDEX idx_cascade_org_path ON tb_cascade_org (platform_id, org_path)");
        execute("CREATE TABLE tb_cascade_channel (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL, cascade_org_id VARCHAR(64) DEFAULT NULL)");
        execute("CREATE TABLE tb_device (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id VARCHAR(64) NOT NULL)");
        execute("INSERT INTO tb_cascade_org(platform_id, cascade_org_id, org_name) "
            + "VALUES('P1', '" + LEGACY_ORG_CODE + "', '根组织')");
        execute("INSERT INTO tb_cascade_org(platform_id, parent_org_id, cascade_org_id, org_name) "
            + "VALUES('P1', 1, '" + LEGACY_ORG_CODE + "001', '子组织')");
    }

    private SqliteCascadeSchemaMigrator migrator() {
        SqliteCascadeSchemaMigrator migrator = new SqliteCascadeSchemaMigrator();
        migrator.setDataSource(dataSource);
        return migrator;
    }

    // ------------------------------------------------------------------ 查询工具

    /** 执行 DDL/DML；JDBC 异常统一包成 IllegalStateException，便于断言唯一键冲突 */
    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("执行失败: " + sql, e);
        }
    }

    private int scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private String text(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private boolean tableExists(String table) throws Exception {
        return scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='" + table + "'") > 0;
    }

    private boolean indexExists(String index) throws Exception {
        return scalar("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='" + index + "'") > 0;
    }

    private boolean columnExists(String table, String column) throws Exception {
        return scalar("SELECT count(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'") > 0;
    }

    /** 索引列名串（多列以逗号分隔），用于判断索引指向的列是否已被替换 */
    private String indexColumns(String index) throws Exception {
        StringBuilder columns = new StringBuilder();
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT name FROM pragma_index_info('" + index + "')")) {
            while (resultSet.next()) {
                if (columns.length() > 0) {
                    columns.append(',');
                }
                columns.append(resultSet.getString(1));
            }
        }
        return columns.toString();
    }

    private int countMigrationHistory() throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history");
    }
}
