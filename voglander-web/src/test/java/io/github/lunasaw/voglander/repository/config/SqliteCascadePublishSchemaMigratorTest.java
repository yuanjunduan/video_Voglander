package io.github.lunasaw.voglander.repository.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 1.10.1 级联去平台化 + 发布层迁移的回归测试。
 *
 * <p>重点钉住 SQLite 那条最容易坏的路：组织表不能 {@code DROP COLUMN} 也不能改列名，
 * 只能建中间表搬数据。搬迁要同时满足「旧列消失、新列有值、唯一键变单列、编码列补齐、
 * 索引指向新列、重复执行不重复搬」六件事，任一件漏了都会在启动时静默留下半升级库。
 */
@DisplayName("SQLite 1.10.1 级联去平台化与发布层增量迁移")
class SqliteCascadePublishSchemaMigratorTest {

    private static final String LEGACY_ORG_CODE = "34020000002160000001";

    private Path         dbFile;
    private DataSource   dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-publish-migration-", ".db");
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
    @DisplayName("老库组织表重建：platform_id/cascade_org_id 消失，编码与数据都保留")
    void legacyOrgTable_shouldBeRebuiltAroundOrgCode() throws Exception {
        prepareLegacyOrgSchema();

        orgMigrator().migrate();

        assertFalse(columnExists("tb_cascade_org", "platform_id"), "去平台化后不应再有 platform_id");
        assertFalse(columnExists("tb_cascade_org", "cascade_org_id"), "旧列名应被 org_code 取代");
        for (String column : new String[] {"code_province", "code_city", "code_county", "code_industry",
            "code_type", "code_network", "code_start_seq", "code_rule_enabled"}) {
            assertTrue(columnExists("tb_cascade_org", column), "缺少编码段列 " + column);
        }
        assertEquals(LEGACY_ORG_CODE, text("SELECT org_code FROM tb_cascade_org WHERE id=1"));
        assertEquals("根组织", text("SELECT org_name FROM tb_cascade_org WHERE id=1"));
        assertEquals("/" + LEGACY_ORG_CODE + "/", text("SELECT org_path FROM tb_cascade_org WHERE id=1"));
        assertEquals(0, scalar("SELECT code_rule_enabled FROM tb_cascade_org WHERE id=1"));
        assertEquals(1, countMigrationHistory());
    }

    @Test
    @DisplayName("组织唯一键改为单列 org_code，路径索引不再带 platform_id")
    void rebuiltOrgTable_shouldCarrySingleColumnIndexes() throws Exception {
        prepareLegacyOrgSchema();

        orgMigrator().migrate();

        assertTrue(indexExists("uk_cascade_org_code"));
        assertTrue(indexExists("idx_cascade_org_parent"));
        assertTrue(indexExists("idx_cascade_org_path"));
        assertTrue(indexColumns("uk_cascade_org_code").contains("org_code"));
        assertFalse(indexColumns("uk_cascade_org_path").contains("platform_id"),
            "idx_cascade_org_path 仍带 platform_id → 旧索引没被替换");
    }

    @Test
    @DisplayName("重复启动不重复搬表，migration 只登记一次")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacyOrgSchema();
        SqliteCascadeOrgSchemaMigrator migrator = orgMigrator();

        migrator.migrate();
        migrator.migrate();

        assertEquals(2, scalar("SELECT count(*) FROM tb_cascade_org"));
        assertEquals(1, countMigrationHistory());
    }

    @Test
    @DisplayName("跨平台同编码搬迁后不撞单列唯一键，第二个拼平台后缀保命")
    void duplicateLegacyCodeAcrossPlatforms_shouldSurviveRebuild() throws Exception {
        prepareLegacyOrgSchema();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO tb_cascade_org(platform_id, cascade_org_id, org_name) "
                + "VALUES('P2', '" + LEGACY_ORG_CODE + "', '另一平台的同码组织')");
        }

        orgMigrator().migrate();

        assertEquals(3, scalar("SELECT count(*) FROM tb_cascade_org"), "三行旧组织应全部搬过去，一个都不能丢");
        assertEquals(LEGACY_ORG_CODE, text("SELECT org_code FROM tb_cascade_org WHERE id=1"));
        assertEquals(LEGACY_ORG_CODE + "001", text("SELECT org_code FROM tb_cascade_org WHERE id=2"));
        assertEquals(LEGACY_ORG_CODE + "-P2", text("SELECT org_code FROM tb_cascade_org WHERE id=3"));
    }

    @Test
    @DisplayName("发布表与设备归属列补齐，跨对象类型的重码被平台内唯一键拦住")
    void publishSchema_shouldBeCreatedWithCrossTypeCodeGuard() throws Exception {
        prepareLegacyOrgSchema();

        orgMigrator().migrate();
        publishMigrator().migrate();

        assertTrue(tableExists("tb_cascade_publish"));
        assertTrue(indexExists("uk_publish_object"));
        assertTrue(indexExists("uk_publish_code"));
        assertTrue(indexExists("idx_publish_type"));
        assertTrue(indexExists("idx_device_org"));
        assertTrue(columnExists("tb_device", "org_id"));
        assertEquals(2, countMigrationHistory());

        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_id, publish_code) "
            + "VALUES('P1', 'ORG', 1, '34020000002160000001')");
        assertThrows(Exception.class, () -> execute(
            "INSERT INTO tb_cascade_publish(platform_id, object_type, object_id, publish_code) "
                + "VALUES('P1', 'CHANNEL', 9, '34020000002160000001')"),
            "同一平台内组织码与通道码相撞必须被 uk_publish_code 拒绝");
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_id, publish_code) "
            + "VALUES('P2', 'CHANNEL', 9, '34020000001310000001')");
    }

    @Test
    @DisplayName("已按新形状建好的库，两个迁移器都不改动结构与数据")
    void freshShapeDatabase_shouldBeUntouched() throws Exception {
        execute("CREATE TABLE tb_cascade_channel (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL, cascade_org_id VARCHAR(64) DEFAULT NULL)");
        execute("CREATE TABLE tb_device (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " device_id VARCHAR(64) NOT NULL)");

        orgMigrator().migrate();
        publishMigrator().migrate();
        prepareDeviceRow();
        orgMigrator().migrate();

        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_org"));
        assertTrue(columnExists("tb_device", "org_id"));
        assertEquals(1, scalar("SELECT count(*) FROM tb_device"));
    }

    // ------------------------------------------------------------------ 夹具

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
        execute("CREATE TABLE tb_device (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " device_id VARCHAR(64) NOT NULL)");
        execute("INSERT INTO tb_cascade_org(platform_id, cascade_org_id, org_name) "
            + "VALUES('P1', '" + LEGACY_ORG_CODE + "', '根组织')");
        execute("INSERT INTO tb_cascade_org(platform_id, parent_org_id, cascade_org_id, org_name) "
            + "VALUES('P1', 1, '" + LEGACY_ORG_CODE + "001', '子组织')");
    }

    private void prepareDeviceRow() throws Exception {
        execute("INSERT INTO tb_device(device_id) VALUES('device-1')");
    }

    private SqliteCascadeOrgSchemaMigrator orgMigrator() {
        SqliteCascadeOrgSchemaMigrator migrator = new SqliteCascadeOrgSchemaMigrator();
        migrator.setDataSource(dataSource);
        return migrator;
    }

    private SqliteCascadePublishSchemaMigrator publishMigrator() {
        SqliteCascadePublishSchemaMigrator migrator = new SqliteCascadePublishSchemaMigrator();
        migrator.setDataSource(dataSource);
        return migrator;
    }

    // ------------------------------------------------------------------ 查询工具

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
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

    /** 索引列名串（形如 org_code，多列以逗号分隔），用于判断索引指向的列是否已被替换 */
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
