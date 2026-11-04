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
 * 1.10.3 级联平台编码规则 schema 迁移的回归测试。
 *
 * <p>钉住四件事：8 列补齐、存量平台两个开关都是 0（升级不改上报行为）、重复启动幂等、
 * 尚未建库时只跳过不建表。前三件缺任一件，升级现场就会变成「编码悄悄换了」或
 * 「勾选组织时突然冒出一批祖先节点」。
 */
@DisplayName("SQLite 1.10.3 级联平台编码规则增量迁移")
class SqliteCascadeCodingSchemaMigratorTest {

    /** 目标形状：七段里可配的 6 段 + 两个开关（类型位由对象类型决定，不在平台维度） */
    private static final String[] CODING_COLUMNS = {
        "code_province", "code_city", "code_county", "code_industry",
        "code_network", "code_start_seq", "code_rule_enabled", "publish_ancestors"
    };

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-coding-migration-", ".db");
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
    @DisplayName("1.10.2 老库启动后补上 8 个编码规则列")
    void legacyDatabase_shouldGainCodingRuleColumns() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        for (String column : CODING_COLUMNS) {
            assertTrue(columnExists(column), "缺少列 " + column + "，平台配置一读写就 no such column");
        }
        assertEquals(1, countMigrationHistory(), "一次演进只登记一个迁移键");
    }

    @Test
    @DisplayName("存量平台补成「规则关闭 + 不补推祖先」，编码与上报行为都不变")
    void existingRows_shouldKeepPreviousBehaviour() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertEquals(0, scalar("SELECT code_rule_enabled FROM tb_cascade_platform WHERE id=1"),
            "默认开启平台规则会让存量对象全部换码");
        assertEquals(0, scalar("SELECT publish_ancestors FROM tb_cascade_platform WHERE id=1"),
            "默认开启补推会一次放出整条祖先链");
        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_platform WHERE code_province IS NULL"),
            "平台规则留空才代表「沿用组织规则与全局兜底」");
    }

    @Test
    @DisplayName("重复启动不重复加列，迁移键只登记一次")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacySchema();
        SqliteCascadeCodingSchemaMigrator migrator = migrator();

        migrator.migrate();
        migrator.migrate();

        assertEquals(1, countMigrationHistory(), "重复启动不能追加新的 migration 行");
        for (String column : CODING_COLUMNS) {
            assertTrue(columnExists(column));
        }
    }

    @Test
    @DisplayName("半升级库（只有 code_rule_enabled）补齐剩余列，已填的值不动")
    void halfMigratedDatabase_shouldReachTargetShape() throws Exception {
        prepareLegacySchema();
        execute("ALTER TABLE tb_cascade_platform ADD COLUMN code_rule_enabled INTEGER NOT NULL DEFAULT 0");
        execute("UPDATE tb_cascade_platform SET code_rule_enabled = 1 WHERE id = 1");

        migrator().migrate();

        for (String column : CODING_COLUMNS) {
            assertTrue(columnExists(column));
        }
        assertEquals(1, scalar("SELECT code_rule_enabled FROM tb_cascade_platform WHERE id=1"),
            "补列不能把用户已打开的开关重置回 0");
    }

    @Test
    @DisplayName("表还没建（全新库）时跳过迁移，由全量脚本负责建表")
    void emptyDatabase_shouldBeSkipped() throws Exception {
        migrator().migrate();

        assertFalse(tableExists("tb_cascade_platform"), "迁移器不负责建表");
        assertEquals(0, countMigrationHistory(), "跳过的迁移不应登记为已执行");
    }

    // ------------------------------------------------------------------ 夹具

    /** 1.10.2 形状：只有连接参数与目录设备层开关，没有任何编码规则列 */
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

    private SqliteCascadeCodingSchemaMigrator migrator() {
        SqliteCascadeCodingSchemaMigrator migrator = new SqliteCascadeCodingSchemaMigrator();
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
