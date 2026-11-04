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
 * 1.10.2 级联本地资产层 schema 迁移的回归测试。
 *
 * <p>钉住三件事：设备和通道都补上本地启停、通道补上组织归属、按组织的索引建成。
 * 这三列是「资产层与发布层分离」的schema前提——缺一列，后续组装器就只能退回去按平台建通道行。
 */
@DisplayName("SQLite 1.10.2 级联本地资产增量迁移")
class SqliteCascadeAssetSchemaMigratorTest {

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-asset-migration-", ".db");
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
    @DisplayName("1.10.1 老库启动后补上设备启停、通道归属与通道启停")
    void legacyDatabase_shouldGainLocalAssetColumns() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertTrue(columnExists("tb_device", "enabled"), "设备缺本地启停，只能逐平台关发布行");
        assertTrue(columnExists("tb_device_channel", "org_id"), "通道缺组织归属，无法直挂业务分组");
        assertTrue(columnExists("tb_device_channel", "enabled"), "通道缺本地启停");
        assertTrue(indexExists("idx_channel_org"), "按组织统计通道数会退化成全表扫");
        assertEquals(1, countMigrationHistory(), "一次演进只登记一个迁移键");
    }

    @Test
    @DisplayName("存量行的 enabled 补成 1，org_id 留空（跟随设备归属）")
    void existingRows_shouldDefaultToEnabledAndUnassigned() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertEquals(1, scalar("SELECT enabled FROM tb_device WHERE id=1"));
        assertEquals(1, scalar("SELECT enabled FROM tb_device_channel WHERE id=1"));
        assertEquals(0, scalar("SELECT count(*) FROM tb_device_channel WHERE org_id IS NOT NULL"));
    }

    @Test
    @DisplayName("重复启动不重复加列，迁移键只登记一次")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacySchema();
        SqliteCascadeAssetSchemaMigrator migrator = migrator();

        migrator.migrate();
        migrator.migrate();

        assertEquals(1, countMigrationHistory(), "重复启动不能追加新的 migration 行");
        assertEquals(1, scalar("SELECT enabled FROM tb_device WHERE id=1"));
    }

    @Test
    @DisplayName("半升级库（只有设备启停）补齐剩余两列")
    void halfMigratedDatabase_shouldReachTargetShape() throws Exception {
        prepareLegacySchema();
        execute("ALTER TABLE tb_device ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1");

        migrator().migrate();

        assertTrue(columnExists("tb_device", "enabled"));
        assertTrue(columnExists("tb_device_channel", "org_id"));
        assertTrue(columnExists("tb_device_channel", "enabled"));
    }

    @Test
    @DisplayName("表还没建（全新库）时跳过迁移，由全量脚本负责建表")
    void emptyDatabase_shouldBeSkipped() throws Exception {
        migrator().migrate();

        assertFalse(tableExists("tb_device"), "迁移器不负责建表");
        assertEquals(0, countMigrationHistory(), "跳过的迁移不应登记为已执行");
    }

    // ------------------------------------------------------------------ 夹具

    /** 1.10.1 形状：设备与通道都没有 enabled，通道也没有 org_id */
    private void prepareLegacySchema() throws Exception {
        execute("CREATE TABLE tb_device ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " device_id VARCHAR(64) NOT NULL,"
            + " status INTEGER DEFAULT 0 NOT NULL,"
            + " org_id INTEGER DEFAULT NULL)");
        execute("CREATE TABLE tb_device_channel ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " device_id VARCHAR(64) NOT NULL,"
            + " channel_id VARCHAR(64) NOT NULL,"
            + " status INTEGER DEFAULT 0 NOT NULL)");
        execute("INSERT INTO tb_device(device_id, status) VALUES('34020000001320000001', 1)");
        execute("INSERT INTO tb_device_channel(device_id, channel_id, status)"
            + " VALUES('34020000001320000001', '34020000001310000001', 1)");
    }

    private SqliteCascadeAssetSchemaMigrator migrator() {
        SqliteCascadeAssetSchemaMigrator migrator = new SqliteCascadeAssetSchemaMigrator();
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

    private boolean indexExists(String index) throws Exception {
        return scalar("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='" + index + "'") > 0;
    }

    private boolean columnExists(String table, String column) throws Exception {
        return scalar("SELECT count(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'") > 0;
    }

    private int countMigrationHistory() throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history");
    }
}
