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
 * 1.10.2「通道进入发布层」数据迁移的回归测试。
 *
 * <p>钉住四件事：老表逐行翻成 CHANNEL 发布行且字段不丢；归属在所有平台一致时回填本地表并清空
 * {@code parent_key}；归属不一致时本地表不动、差异逐行留在 {@code parent_key}；孤儿行丢弃。
 *
 * <p>这四条合起来才是「切换目录来源不丢存量配置」——少任何一条，升级后的上级目录就会少节点
 * 或者挂错父节点，而且现场很难归因。
 */
@DisplayName("SQLite 1.10.2 级联通道进入发布层迁移")
class SqliteCascadeChannelPublishMigratorTest {

    private static final String PLATFORM_A = "34020000002000000001";
    private static final String PLATFORM_B = "34020000002000000002";
    private static final String ORG_TOP    = "34020000002150000001";
    private static final String ORG_SUB    = "34020000002150000002";
    private static final String DEVICE_ID  = "34020000001320000001";
    private static final String CHANNEL_ID = "34020000001310000001";

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-channel-publish-", ".db");
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
    @DisplayName("归属在所有平台一致：回填本地通道组织，发布行 parent_key 留空")
    void singleOrgAcrossPlatforms_shouldBackfillLocalColumn() throws Exception {
        prepareSchema();
        execute("INSERT INTO tb_device_channel(device_id, channel_id) VALUES('" + DEVICE_ID + "', '" + CHANNEL_ID + "')");
        legacyChannel(PLATFORM_A, ORG_SUB, 1);
        legacyChannel(PLATFORM_B, ORG_SUB, 1);

        migrator().migrate();

        assertEquals(2, countPublish(), "每个平台一条发布行");
        assertEquals("'" + DEVICE_ID + ":" + CHANNEL_ID + "'",
            scalarString("SELECT object_key FROM tb_cascade_publish WHERE platform_id='" + PLATFORM_A + "'"));
        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_publish WHERE parent_key IS NOT NULL"),
            "归属已回填本地表，parent_key 不该多余地锁死，否则以后本地换组织上级不跟着动");
        assertEquals(scalar("SELECT id FROM tb_cascade_org WHERE org_code='" + ORG_SUB + "'"),
            scalar("SELECT org_id FROM tb_device_channel"), "回填的是组织主键");
    }

    @Test
    @DisplayName("编码/名称/启停/元数据逐字段搬到发布行")
    void legacyColumns_shouldBeCarriedOverVerbatim() throws Exception {
        prepareSchema();
        execute("INSERT INTO tb_device_channel(device_id, channel_id) VALUES('" + DEVICE_ID + "', '" + CHANNEL_ID + "')");
        legacyChannel(PLATFORM_A, ORG_TOP, 0);

        migrator().migrate();

        assertEquals("'" + CHANNEL_ID + "'",
            scalarString("SELECT publish_code FROM tb_cascade_publish"));
        assertEquals("'前门'", scalarString("SELECT publish_name FROM tb_cascade_publish"));
        assertEquals(0, scalar("SELECT enabled FROM tb_cascade_publish"), "停报状态必须一起搬过来");
        assertEquals("'Hik'", scalarString("SELECT manufacturer FROM tb_cascade_publish"));
        assertEquals("'DS-2CD3T46'", scalarString("SELECT model FROM tb_cascade_publish"));
        assertEquals("'杭州西湖'", scalarString("SELECT address FROM tb_cascade_publish"));
        assertEquals("'192.168.1.64'", scalarString("SELECT ip_address FROM tb_cascade_publish"));
        assertEquals(1, scalar("SELECT ptz_type FROM tb_cascade_publish"));
        assertEquals("'CHANNEL'", scalarString("SELECT object_type FROM tb_cascade_publish"));
    }

    @Test
    @DisplayName("各平台归属不一致：不回填本地表，差异逐行留在 parent_key")
    void conflictingOrgs_shouldKeepPerPlatformParentKey() throws Exception {
        prepareSchema();
        execute("INSERT INTO tb_device_channel(device_id, channel_id) VALUES('" + DEVICE_ID + "', '" + CHANNEL_ID + "')");
        legacyChannel(PLATFORM_A, ORG_TOP, 1);
        legacyChannel(PLATFORM_B, ORG_SUB, 1);

        migrator().migrate();

        assertEquals(0, scalar("SELECT count(*) FROM tb_device_channel WHERE org_id IS NOT NULL"),
            "归属不一致时本地列只存得下一份，不能随手挑一个");
        assertEquals("'" + ORG_TOP + "'",
            scalarString("SELECT parent_key FROM tb_cascade_publish WHERE platform_id='" + PLATFORM_A + "'"));
        assertEquals("'" + ORG_SUB + "'",
            scalarString("SELECT parent_key FROM tb_cascade_publish WHERE platform_id='" + PLATFORM_B + "'"));
    }

    @Test
    @DisplayName("用户已配过本地归属时不覆盖，老平台的归属留在 parent_key")
    void existingLocalOrg_shouldNotBeOverwritten() throws Exception {
        prepareSchema();
        execute("INSERT INTO tb_device_channel(device_id, channel_id, org_id) VALUES('" + DEVICE_ID + "', '"
            + CHANNEL_ID + "', 1)");
        legacyChannel(PLATFORM_A, ORG_SUB, 1);

        migrator().migrate();

        assertEquals(1, scalar("SELECT org_id FROM tb_device_channel"),
            "迁移不能把人工配置改回老平台上的值");
        assertEquals("'" + ORG_SUB + "'", scalarString("SELECT parent_key FROM tb_cascade_publish"),
            "迁移前的行为靠 parent_key 保住");
    }

    @Test
    @DisplayName("本地通道已不存在的老行丢弃，不留下挂不住的孤儿发布行")
    void orphanLegacyRow_shouldBeDropped() throws Exception {
        prepareSchema();
        legacyChannel(PLATFORM_A, ORG_TOP, 1);

        migrator().migrate();

        assertEquals(0, countPublish(), "没有本地通道就没有可上报的节点");
        assertEquals(1, countMigrationHistory(), "丢弃也要登记迁移键，否则每次启动都重跑一遍");
    }

    @Test
    @DisplayName("已存在的发布行不被覆盖，重复启动幂等")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareSchema();
        execute("INSERT INTO tb_device_channel(device_id, channel_id) VALUES('" + DEVICE_ID + "', '" + CHANNEL_ID + "')");
        legacyChannel(PLATFORM_A, ORG_TOP, 1);
        /* 新接口已经配过同一通道：编码覆盖是用户后来的选择，不能被迁移冲掉 */
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key, publish_code, enabled)"
            + " VALUES('" + PLATFORM_A + "', 'CHANNEL', '" + DEVICE_ID + ":" + CHANNEL_ID + "', 'NEW-CODE', 1)");

        SqliteCascadeChannelPublishMigrator migrator = migrator();
        migrator.migrate();
        migrator.migrate();

        assertEquals(1, countPublish(), "重复启动不能插出第二条");
        assertEquals(1, countMigrationHistory(), "一次演进只登记一个迁移键");
        assertEquals("'NEW-CODE'", scalarString("SELECT publish_code FROM tb_cascade_publish"),
            "已存在的发布行保持原样");
    }

    @Test
    @DisplayName("相关表还没建时跳过迁移，不登记迁移键")
    void missingTables_shouldBeSkipped() throws Exception {
        migrator().migrate();

        assertFalse(tableExists("tb_cascade_publish"), "迁移器不负责建表");
        assertEquals(0, countMigrationHistory(), "跳过的迁移不应登记为已执行");
    }

    // ------------------------------------------------------------------ 夹具

    private void prepareSchema() throws Exception {
        execute("CREATE TABLE tb_cascade_org ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " org_code VARCHAR(64) NOT NULL)");
        execute("INSERT INTO tb_cascade_org(org_code) VALUES('" + ORG_TOP + "')");
        execute("INSERT INTO tb_cascade_org(org_code) VALUES('" + ORG_SUB + "')");
        execute("CREATE TABLE tb_device_channel ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " device_id VARCHAR(64) NOT NULL,"
            + " channel_id VARCHAR(50) NOT NULL,"
            + " org_id INTEGER DEFAULT NULL)");
        execute("CREATE TABLE tb_cascade_channel ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " local_device_id VARCHAR(64) NOT NULL,"
            + " local_channel_id VARCHAR(64) NOT NULL,"
            + " cascade_channel_id VARCHAR(64) NOT NULL,"
            + " cascade_name VARCHAR(255) DEFAULT NULL,"
            + " cascade_org_id VARCHAR(64) DEFAULT NULL,"
            + " enabled INTEGER NOT NULL DEFAULT 1,"
            + " manufacturer VARCHAR(64) DEFAULT NULL,"
            + " model VARCHAR(128) DEFAULT NULL,"
            + " address VARCHAR(255) DEFAULT NULL,"
            + " ip_address VARCHAR(64) DEFAULT NULL,"
            + " ptz_type INTEGER DEFAULT NULL)");
        execute("CREATE TABLE tb_cascade_publish ("
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
            + " extend TEXT)");
        execute("CREATE UNIQUE INDEX uk_publish_object ON tb_cascade_publish (platform_id, object_type, object_key)");
        execute("CREATE UNIQUE INDEX uk_publish_code ON tb_cascade_publish (platform_id, publish_code)");
    }

    private void legacyChannel(String platformId, String orgCode, int enabled) {
        execute("INSERT INTO tb_cascade_channel(platform_id, local_device_id, local_channel_id,"
            + " cascade_channel_id, cascade_name, cascade_org_id, enabled,"
            + " manufacturer, model, address, ip_address, ptz_type)"
            + " VALUES('" + platformId + "', '" + DEVICE_ID + "', '" + CHANNEL_ID + "', '" + CHANNEL_ID + "',"
            + " '前门', '" + orgCode + "', " + enabled + ", 'Hik', 'DS-2CD3T46', '杭州西湖', '192.168.1.64', 1)");
    }

    private SqliteCascadeChannelPublishMigrator migrator() {
        SqliteCascadeChannelPublishMigrator migrator = new SqliteCascadeChannelPublishMigrator();
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

    /** 取字符串列并用 SQL 字面量形式返回（{@code 'x'}），便于与期望值直接比较。 */
    private String scalarString(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? "'" + resultSet.getString(1) + "'" : "null";
        }
    }

    private int countPublish() throws Exception {
        return scalar("SELECT count(*) FROM tb_cascade_publish");
    }

    private int countMigrationHistory() throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history");
    }

    private boolean tableExists(String table) throws Exception {
        return scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='" + table + "'") > 0;
    }
}
