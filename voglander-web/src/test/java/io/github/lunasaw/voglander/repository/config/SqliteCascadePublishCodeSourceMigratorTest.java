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
 * 1.10.3 级联发布行「编码来源」schema 迁移的回归测试。
 *
 * <p>钉住四件事：两列补齐、存量行的 {@code publish_code} 与两列都不动（升级不改上报编码）、
 * 重复启动幂等、尚未建库时只跳过不建表。前两件缺任一件，升级现场就会变成
 * 「上级收到的编码集体换号」或者在 2.5 的人工值保护上把历史数据误判成自动生成。
 */
@DisplayName("SQLite 1.10.3 级联发布行编码来源增量迁移")
class SqliteCascadePublishCodeSourceMigratorTest {

    /** 目标形状：来源标记 + 生成时间（device_key / last_push_* 随 2.4 的推送链一起合入） */
    private static final String[] CODE_SOURCE_COLUMNS = {"code_source", "code_generated_time"};

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-code-source-migration-", ".db");
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
    @DisplayName("1.10.2 老库启动后补上 code_source 与 code_generated_time")
    void legacyDatabase_shouldGainCodeSourceColumns() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        for (String column : CODE_SOURCE_COLUMNS) {
            assertTrue(columnExists(column), "缺少列 " + column + "，固化与人工值保护都无处落笔");
        }
        assertEquals(1, countMigrationHistory(), "一次演进只登记一个迁移键");
    }

    @Test
    @DisplayName("存量行两列留空、publish_code 原样保留：升级不改任何一条上报编码")
    void existingRows_shouldKeepPublishCodeUntouched() throws Exception {
        prepareLegacySchema();

        migrator().migrate();

        assertEquals(2, scalar("SELECT count(*) FROM tb_cascade_publish"
            + " WHERE code_source IS NULL AND code_generated_time IS NULL"),
            "留空才代表「历史数据未标记」，随便填 AUTO 会让这些码被当成规则生成值重算");
        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_publish"
            + " WHERE publish_code = '34020000002150000001'"),
            "补列不能借机改写业务列");
    }

    @Test
    @DisplayName("重复启动不重复加列，迁移键只登记一次")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacySchema();
        SqliteCascadePublishCodeSourceMigrator migrator = migrator();

        migrator.migrate();
        migrator.migrate();

        assertEquals(1, countMigrationHistory(), "重复启动不能追加新的 migration 行");
        for (String column : CODE_SOURCE_COLUMNS) {
            assertTrue(columnExists(column));
        }
    }

    @Test
    @DisplayName("半升级库（只有 code_source）补齐剩余列，已填的来源不动")
    void halfMigratedDatabase_shouldReachTargetShape() throws Exception {
        prepareLegacySchema();
        execute("ALTER TABLE tb_cascade_publish ADD COLUMN code_source VARCHAR(8) DEFAULT NULL");
        execute("UPDATE tb_cascade_publish SET code_source = 'MANUAL' WHERE id = 1");

        migrator().migrate();

        for (String column : CODE_SOURCE_COLUMNS) {
            assertTrue(columnExists(column));
        }
        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_publish WHERE code_source = 'MANUAL'"),
            "补列不能把人填的来源标记冲掉");
    }

    @Test
    @DisplayName("表还没建（全新库）时跳过迁移，由全量脚本负责建表")
    void emptyDatabase_shouldBeSkipped() throws Exception {
        migrator().migrate();

        assertFalse(tableExists("tb_cascade_publish"), "迁移器不负责建表");
        assertEquals(0, countMigrationHistory(), "跳过的迁移不应登记为已执行");
    }

    // ------------------------------------------------------------------ 夹具

    /** 1.10.2 形状：发布行只有定位列 + 编码覆盖 + 启停，没有任何「码从哪来」的记录 */
    private void prepareLegacySchema() throws Exception {
        execute("CREATE TABLE tb_cascade_publish ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " object_type VARCHAR(16) NOT NULL,"
            + " object_key VARCHAR(128) NOT NULL,"
            + " publish_code VARCHAR(64) DEFAULT NULL,"
            + " enabled INTEGER DEFAULT 1 NOT NULL)");
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key, publish_code)"
            + " VALUES('34020000002000000001', 'ORG', '34020000002150000001', '34020000002150000001')");
        execute("INSERT INTO tb_cascade_publish(platform_id, object_type, object_key, publish_code)"
            + " VALUES('34020000002000000001', 'CHANNEL', '34020000001320000001:34020000001310000001', NULL)");
    }

    private SqliteCascadePublishCodeSourceMigrator migrator() {
        SqliteCascadePublishCodeSourceMigrator migrator = new SqliteCascadePublishCodeSourceMigrator();
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
        return scalar("SELECT count(*) FROM pragma_table_info('tb_cascade_publish') WHERE name='"
            + column + "'") > 0;
    }

    private int countMigrationHistory() throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history");
    }
}
