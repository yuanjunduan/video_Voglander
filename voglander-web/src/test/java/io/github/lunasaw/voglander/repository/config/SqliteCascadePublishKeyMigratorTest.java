package io.github.lunasaw.voglander.repository.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 1.10.2 级联发布表「对象键」迁移的回归测试。
 *
 * <p>钉住四件事：三类对象（组织/设备/通道）的历史行都能翻成业务键、覆盖列（编码/名称/策略）逐字段保留、
 * {@code parent_key} 落空（按本地结构推导）、指不到本地对象的历史行被丢弃而不是留下空键。
 *
 * <p>业务键是「删表退场」的前置条件：迁移若丢了编码覆盖，上级目录里的 DeviceID 会整体改名，
 * 表现为一批旧节点消失、一批新节点出现，事后无法从库里恢复。
 */
@DisplayName("SQLite 1.10.2 级联发布对象键迁移")
class SqliteCascadePublishKeyMigratorTest {

    private static final String ORG_CODE   = "34020000002150000001";
    private static final String DEVICE_ID  = "34020000001320000001";
    private static final String CHANNEL_ID = "34020000001310000001";
    private static final String PLATFORM   = "34020000002000000001";

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-publish-key-", ".db");
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
    @DisplayName("三类对象都按本地业务键回填，旧列被移除、唯一键建在新列上")
    void legacyRows_shouldBeKeyedByBusinessKey() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertEquals(ORG_CODE, text("SELECT object_key FROM tb_cascade_publish WHERE object_type='ORG'"));
        assertEquals(DEVICE_ID, text("SELECT object_key FROM tb_cascade_publish WHERE object_type='DEVICE'"));
        assertEquals(DEVICE_ID + ":" + CHANNEL_ID,
            text("SELECT object_key FROM tb_cascade_publish WHERE object_type='CHANNEL'"));
        assertFalse(columnExists("object_id"), "旧自增主键列必须退场，否则两套键会长期并存");
        assertTrue(indexExists("uk_publish_object"));
        assertEquals(1, countMigrationHistory(), "一次演进只登记一个迁移键");
    }

    @Test
    @DisplayName("编码覆盖、名称与通道处置策略逐字段保留")
    void overrides_shouldSurviveMigration() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertEquals("OVERRIDE-CODE", text("SELECT publish_code FROM tb_cascade_publish WHERE object_type='ORG'"),
            "编码覆盖丢了，上级看到的 DeviceID 会整体改名");
        assertEquals("集团总部", text("SELECT publish_name FROM tb_cascade_publish WHERE object_type='ORG'"));
        assertEquals("REROUTE", text("SELECT revoke_policy FROM tb_cascade_publish WHERE object_type='ORG'"));
        assertEquals(0, scalar("SELECT enabled FROM tb_cascade_publish WHERE object_type='DEVICE'"),
            "发布启停是本地配置，不能被迁移重算成 1");
    }

    @Test
    @DisplayName("parent_key 一律留空：父节点按本地结构推导，只存差异")
    void parentKey_shouldBeNullForLocalStructure() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_publish WHERE parent_key IS NOT NULL"));
    }

    @Test
    @DisplayName("指不到本地对象的历史行被丢弃，不留下空业务键")
    void orphanRows_shouldBeDropped() throws Exception {
        prepareLegacySchema();
        execute("INSERT INTO tb_cascade_publish (platform_id, object_type, object_id, enabled)"
            + " VALUES('" + PLATFORM + "', 'DEVICE', 99999, 1)");

        migrator().migrate();

        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_publish WHERE object_key IS NULL OR object_key=''"),
            "空键会把唯一索引卡成一片冲突");
        assertEquals(3, scalar("SELECT count(*) FROM tb_cascade_publish"), "三类对象各一行");
    }

    @Test
    @DisplayName("重复启动不重建、不重复登记迁移键")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacySchema();
        SqliteCascadePublishKeyMigrator migrator = migrator();

        migrator.migrate();
        migrator.migrate();

        assertEquals(1, countMigrationHistory());
        assertEquals(3, scalar("SELECT count(*) FROM tb_cascade_publish"), "重复迁移不能把行数翻倍");
    }

    @Test
    @DisplayName("半升级库（键已改、parent_key 缺失）只补列")
    void halfMigratedDatabase_shouldGainParentKey() throws Exception {
        prepareLegacySchema();
        migrator().migrate();
        execute("ALTER TABLE tb_cascade_publish DROP COLUMN parent_key");

        migrator().migrate();

        assertTrue(columnExists("parent_key"));
        assertEquals(ORG_CODE, text("SELECT object_key FROM tb_cascade_publish WHERE object_type='ORG'"),
            "补列不能顺手重算业务键");
    }

    @Test
    @DisplayName("发布表还没建（全新库）时跳过，由全量脚本负责建表")
    void missingPublishTable_shouldBeSkipped() throws Exception {
        migrator().migrate();

        assertFalse(tableExists("tb_cascade_publish"), "迁移器不负责建表");
        assertEquals(0, countMigrationHistory(), "跳过的迁移不应登记为已执行");
    }

    // ------------------------------------------------------------------ 夹具

    /** 1.10.1 形状：发布表按 object_id 定位，既没有 object_key 也没有 parent_key */
    private void prepareLegacySchema() throws Exception {
        execute("CREATE TABLE tb_cascade_org ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " org_code VARCHAR(64) NOT NULL,"
            + " parent_id INTEGER DEFAULT NULL)");
        execute("CREATE TABLE tb_device ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " device_id VARCHAR(64) NOT NULL)");
        execute("CREATE TABLE tb_device_channel ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " device_id VARCHAR(64) NOT NULL,"
            + " channel_id VARCHAR(64) NOT NULL)");
        execute("CREATE TABLE tb_cascade_publish ("
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
            + " extend TEXT)");
        execute("CREATE UNIQUE INDEX uk_publish_object"
            + " ON tb_cascade_publish (platform_id, object_type, object_id)");
        execute("CREATE UNIQUE INDEX uk_publish_code ON tb_cascade_publish (platform_id, publish_code)");
        execute("CREATE INDEX idx_publish_type ON tb_cascade_publish (platform_id, object_type, enabled)");

        execute("INSERT INTO tb_cascade_org(org_code) VALUES('" + ORG_CODE + "')");
        execute("INSERT INTO tb_device(device_id) VALUES('" + DEVICE_ID + "')");
        execute("INSERT INTO tb_device_channel(device_id, channel_id) VALUES('" + DEVICE_ID + "', '"
            + CHANNEL_ID + "')");
        execute("INSERT INTO tb_cascade_publish"
            + " (platform_id, object_type, object_id, publish_code, publish_name, enabled, revoke_policy)"
            + " VALUES('" + PLATFORM + "', 'ORG', 1, 'OVERRIDE-CODE', '集团总部', 1, 'REROUTE')");
        execute("INSERT INTO tb_cascade_publish (platform_id, object_type, object_id, enabled)"
            + " VALUES('" + PLATFORM + "', 'DEVICE', 1, 0)");
        execute("INSERT INTO tb_cascade_publish (platform_id, object_type, object_id, enabled)"
            + " VALUES('" + PLATFORM + "', 'CHANNEL', 1, 1)");
    }

    private SqliteCascadePublishKeyMigrator migrator() {
        SqliteCascadePublishKeyMigrator migrator = new SqliteCascadePublishKeyMigrator();
        migrator.setDataSource(dataSource);
        return migrator;
    }

    // ------------------------------------------------------------------ 查询工具

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

    private boolean columnExists(String column) throws Exception {
        return scalar("SELECT count(*) FROM pragma_table_info('tb_cascade_publish') WHERE name='" + column + "'")
            > 0;
    }

    private int countMigrationHistory() throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history");
    }
}
