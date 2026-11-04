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
 * 1.10.3「通道自己的码 + 发布行拆键/推送状态」schema 迁移的回归测试。
 *
 * <p>钉住四件事：两表列补齐、{@code device_key} 按业务键回填且脏键不猜、已有值不被改写、
 * 重复启动幂等。前两件缺任一件，上级按 {@code publish_code} 反查的通道会集体失联，
 * 而按设备过滤只能退回字符串前缀匹配（§6.3 明令禁止）。
 */
@DisplayName("SQLite 1.10.3 通道编码与发布行拆键增量迁移")
class SqliteCascadeChannelCodePushStatusMigratorTest {

    /** 目标形状：通道自己的码 + 发布行设备段与推送结论 */
    private static final String[] CHANNEL_COLUMNS = {"code"};

    private static final String[] PUBLISH_COLUMNS = {
        "device_key", "last_push_time", "last_push_status", "last_push_message"
    };

    private static final String[] INDEXES = {"idx_channel_code", "idx_publish_device"};

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-code-push-migration-", ".db");
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
    @DisplayName("1.10.2 老库启动后两表各补列并建索引")
    void legacyDatabase_shouldGainColumnsAndIndexes() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        for (String column : CHANNEL_COLUMNS) {
            assertTrue(columnExists("tb_device_channel", column), "通道缺少 code 列，自己的码无处固化");
        }
        for (String column : PUBLISH_COLUMNS) {
            assertTrue(columnExists("tb_cascade_publish", column), "发布行缺少 " + column);
        }
        for (String index : INDEXES) {
            assertTrue(indexExists(index), "缺少索引 " + index + "，按码/按设备查询会退化成全表扫描");
        }
        assertEquals(1, countMigrationHistory(), "一次演进只登记一个迁移键");
    }

    @Test
    @DisplayName("存量 CHANNEL 行按业务键回填 device_key，脏键保持为空不猜测")
    void existingChannelRows_shouldBackfillDeviceKeyOnlyForWellFormedKeys() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_publish"
            + " WHERE object_type = 'CHANNEL' AND device_key = '34020000001320000001'"),
            "回填口径必须与 PublishKeys.channelDeviceId 一致：取第一个分隔符之前");
        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_publish"
            + " WHERE object_type = 'CHANNEL' AND object_key = 'dirty-key' AND device_key IS NOT NULL"),
            "缺少分隔符的脏键不能猜设备号，留空即回落到旧的分组方式");
        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_publish"
            + " WHERE object_type <> 'CHANNEL' AND device_key IS NOT NULL"),
            "只有通道行有设备段，ORG/DEVICE 行不该被写入");
    }

    @Test
    @DisplayName("半升级库：已有的 device_key 不被回填覆盖")
    void halfMigratedDatabase_shouldKeepExistingDeviceKey() throws Exception {
        prepareLegacySchema();
        execute("ALTER TABLE tb_cascade_publish ADD COLUMN device_key VARCHAR(64) DEFAULT NULL");
        execute("UPDATE tb_cascade_publish SET device_key = 'keep-me' WHERE object_key = 'dirty-key'");

        migrator().migrate();

        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_publish WHERE device_key = 'keep-me'"),
            "回填只在列为空时执行，重复执行无副作用");
    }

    @Test
    @DisplayName("重复启动不重复加列/建索引，迁移键只登记一次")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacySchema();
        SqliteCascadeChannelCodePushStatusMigrator migrator = migrator();

        migrator.migrate();
        migrator.migrate();

        assertEquals(1, countMigrationHistory(), "重复启动不能追加新的 migration 行");
        assertEquals(2, scalar("SELECT count(*) FROM sqlite_master WHERE type = 'index'"
            + " AND name IN ('idx_channel_code', 'idx_publish_device')"));
    }

    @Test
    @DisplayName("只有通道表时只补通道部分，不因缺少发布表而失败")
    void channelTableOnly_shouldMigrateIndependently() throws Exception {
        execute("CREATE TABLE tb_device_channel ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " channel_id VARCHAR(50) NOT NULL,"
            + " device_id VARCHAR(64) NOT NULL)");

        migrator().migrate();

        assertTrue(columnExists("tb_device_channel", "code"));
        assertTrue(indexExists("idx_channel_code"));
        assertFalse(tableExists("tb_cascade_publish"), "迁移器不负责建表");
    }

    @Test
    @DisplayName("两张表都没建（全新库）时跳过迁移，由全量脚本负责建表")
    void emptyDatabase_shouldBeSkipped() throws Exception {
        migrator().migrate();

        assertFalse(tableExists("tb_cascade_publish"), "迁移器不负责建表");
        assertEquals(0, countMigrationHistory(), "跳过的迁移不应登记为已执行");
    }

    // ------------------------------------------------------------------ 夹具

    /** 1.10.2 形状：通道没有自己的码，发布行也没有拆键与推送结论 */
    private void prepareLegacySchema() throws Exception {
        execute("CREATE TABLE tb_device_channel ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " channel_id VARCHAR(50) NOT NULL,"
            + " device_id VARCHAR(64) NOT NULL,"
            + " missing_count INTEGER NOT NULL DEFAULT 0)");
        execute("INSERT INTO tb_device_channel(channel_id, device_id) VALUES('34020000001310000001', 'dev-1')");

        execute("CREATE TABLE tb_cascade_publish ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " object_type VARCHAR(16) NOT NULL,"
            + " object_key VARCHAR(128) NOT NULL,"
            + " publish_code VARCHAR(64) DEFAULT NULL,"
            + " enabled INTEGER DEFAULT 1 NOT NULL)");
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key)"
            + " VALUES('34020000002000000001', 'CHANNEL', '34020000001320000001:34020000001310000001')");
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key)"
            + " VALUES('34020000002000000001', 'CHANNEL', 'dirty-key')");
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key)"
            + " VALUES('34020000002000000001', 'ORG', '34020000002150000001')");
    }

    private SqliteCascadeChannelCodePushStatusMigrator migrator() {
        SqliteCascadeChannelCodePushStatusMigrator migrator = new SqliteCascadeChannelCodePushStatusMigrator();
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

    private boolean tableExists(String table) throws Exception {
        return scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='" + table + "'") > 0;
    }

    private boolean columnExists(String table, String column) throws Exception {
        return scalar("SELECT count(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'") > 0;
    }

    private boolean indexExists(String index) throws Exception {
        return scalar("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='" + index + "'") > 0;
    }

    private int countMigrationHistory() throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history");
    }
}
