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
 * 1.10.4 级联平台名称列 schema 迁移的回归测试。
 *
 * <p>钉住三件事：老库补上 {@code platform_name}、存量平台补成空串（不替用户编名字）、
 * 重复启动幂等且已有名称不被重置。缺任一件，升级现场会变成「平台一读写就 no such column」
 * 或「用户取的名字被迁移洗掉」。
 */
@DisplayName("SQLite 1.10.4 级联平台名称增量迁移")
class SqliteCascadePlatformNameMigratorTest {

    private static final String COLUMN = "platform_name";

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-name-migration-", ".db");
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
    @DisplayName("1.10.3 老库启动后补上 platform_name 列")
    void legacyDatabase_shouldGainNameColumn() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertTrue(columnExists(COLUMN), "缺少列 platform_name，平台配置一读写就 no such column");
        assertEquals(1, countMigrationHistory(), "一次演进只登记一个迁移键");
    }

    @Test
    @DisplayName("存量平台补成空串（未命名），不替用户编名字")
    void existingRows_shouldBecomeUnnamed() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_platform WHERE " + COLUMN + " = ''"),
            "存量行必须是空串：升级不能自作主张生成名称");
        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_platform WHERE " + COLUMN + " IS NULL"),
            "列是 NOT NULL DEFAULT ''，补出来的值不应是 NULL");
    }

    @Test
    @DisplayName("重复启动不重复加列，迁移键只登记一次")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacySchema();
        SqliteCascadePlatformNameMigrator migrator = migrator();

        migrator.migrate();
        migrator.migrate();

        assertTrue(columnExists(COLUMN));
        assertEquals(1, countMigrationHistory(), "重复启动不能追加新的 migration 行");
    }

    @Test
    @DisplayName("列已存在（半升级库）时不重置已填的名称")
    void alreadyMigratedDatabase_shouldKeepName() throws Exception {
        prepareLegacySchema();
        execute("ALTER TABLE tb_cascade_platform ADD COLUMN " + COLUMN + " VARCHAR(128) NOT NULL DEFAULT ''");
        execute("UPDATE tb_cascade_platform SET " + COLUMN + " = '市公安局上级平台' WHERE id = 1");

        migrator().migrate();

        assertTrue(columnExists(COLUMN));
        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_platform WHERE " + COLUMN + " = '市公安局上级平台'"),
            "补列不能把用户已填的名称洗掉");
    }

    @Test
    @DisplayName("表还没建（全新库）时跳过迁移，由全量脚本负责建表")
    void emptyDatabase_shouldBeSkipped() throws Exception {
        migrator().migrate();

        assertFalse(tableExists("tb_cascade_platform"), "迁移器不负责建表");
        assertEquals(0, countMigrationHistory(), "跳过的迁移不应登记为已执行");
    }

    // ------------------------------------------------------------------ 夹具

    /** 1.10.3 形状：有平台画像与编码规则，但没有名称列 */
    private void prepareLegacySchema() throws Exception {
        execute("CREATE TABLE tb_cascade_platform ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " platform_ip VARCHAR(64) NOT NULL,"
            + " platform_domain VARCHAR(64) NOT NULL,"
            + " local_client_id VARCHAR(64) NOT NULL,"
            + " catalog_with_device_layer INTEGER DEFAULT 0 NOT NULL,"
            + " extend TEXT)");
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id)"
            + " VALUES('34020000002000000001', '10.0.0.1', '3402000000', '34020000002000000002')");
    }

    private SqliteCascadePlatformNameMigrator migrator() {
        SqliteCascadePlatformNameMigrator migrator = new SqliteCascadePlatformNameMigrator();
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

    private boolean columnExists(String column) throws Exception {
        return scalar("SELECT count(*) FROM pragma_table_info('tb_cascade_platform') WHERE name='"
            + column + "'") > 0;
    }

    private int countMigrationHistory() throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history");
    }
}
