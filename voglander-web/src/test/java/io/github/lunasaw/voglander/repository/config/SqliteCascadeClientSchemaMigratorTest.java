package io.github.lunasaw.voglander.repository.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 1.10.5 级联「本端客户端」抽取迁移的回归测试。
 *
 * <p>钉住四件事：老库按 {@code (local_client_id, local_ip)} 收敛出本端客户端行、平台行回填
 * {@code client_ref_id}、默认行全局只有一条、三个本端列被真正删除。缺任一件，升级现场不是
 * 「新代码写了不存在的列」就是「同一个本端身份散成多条、改 IP 只改到其中一条」。
 *
 * <p>还钉住两条退化路径：重复启动不得重复生成行；半升级库（客户端表已在、列已删）不得把
 * 已有引用清空。
 */
@DisplayName("SQLite 1.10.5 级联本端客户端抽取迁移")
class SqliteCascadeClientSchemaMigratorTest {

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-client-migration-", ".db");
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
    @DisplayName("老库按 (本端编码, 本端IP) 收敛出客户端行并回填引用")
    void legacyDatabase_shouldExtractClientsAndBackfillRefs() throws Exception {
        prepareLegacySchema();
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id, local_ip, local_port)"
            + " VALUES('37000020002000000100', '10.0.0.1', '3700002000', '34020000002000000001', '192.168.118.7', 5061)");
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id, local_ip, local_port)"
            + " VALUES('37000020002000000900', '10.0.0.2', '3700002000', '34020000002000000900', '192.168.118.4', 5061)");

        migrator().migrate();

        assertEquals(2, scalar("SELECT count(*) FROM tb_cascade_client"), "两种本端组合应生成两条客户端记录");
        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_platform WHERE client_ref_id IS NULL"),
            "每条平台行都必须有 client_ref_id，否则读平台时拿不到 From 身份");
        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_client WHERE is_default = 1"),
            "默认行必须全局唯一：新增平台时靠它预选");
        assertEquals("34020000002000000001", string("SELECT c.client_id FROM tb_cascade_platform p"
            + " JOIN tb_cascade_client c ON c.id = p.client_ref_id WHERE p.platform_id = '37000020002000000100'"),
            "回填的引用必须指向该平台原来的本端编码");
    }

    @Test
    @DisplayName("同一本端组合被多个平台使用时只生成一条客户端记录")
    void sharedClientIdentity_shouldProduceSingleRow() throws Exception {
        prepareLegacySchema();
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id, local_ip, local_port)"
            + " VALUES('37000020002000000100', '10.0.0.1', '3700002000', '34020000002000000001', '192.168.118.7', 5061)");
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id, local_ip, local_port)"
            + " VALUES('37000020002000000200', '10.0.0.2', '3700002000', '34020000002000000001', '192.168.118.7', 5061)");

        migrator().migrate();

        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_client"), "同一身份不能按平台行数复制成多条");
        assertEquals(2, scalar("SELECT count(*) FROM tb_cascade_platform WHERE client_ref_id = "
            + "(SELECT id FROM tb_cascade_client)"), "两个平台都应指向同一条客户端记录");
    }

    @Test
    @DisplayName("三个本端列迁移后必须真的删掉")
    void legacyLocalColumns_shouldBeDropped() throws Exception {
        prepareLegacySchema();
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id, local_ip, local_port)"
            + " VALUES('37000020002000000100', '10.0.0.1', '3700002000', '34020000002000000001', '192.168.118.7', 5061)");

        migrator().migrate();

        assertFalse(columnExists("tb_cascade_platform", "local_client_id"),
            "local_client_id 仍是 NOT NULL 且新代码不再写它，留着会让新增平台直接插入失败");
        assertFalse(columnExists("tb_cascade_platform", "local_ip"));
        assertFalse(columnExists("tb_cascade_platform", "local_port"));
        assertTrue(columnExists("tb_cascade_platform", "client_ref_id"));
    }

    @Test
    @DisplayName("重复启动不重复生成客户端行，迁移键只登记一次")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacySchema();
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id, local_ip, local_port)"
            + " VALUES('37000020002000000100', '10.0.0.1', '3700002000', '34020000002000000001', '192.168.118.7', 5061)");
        SqliteCascadeClientSchemaMigrator migrator = migrator();

        migrator.migrate();
        migrator.migrate();

        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_client"));
        assertEquals(1, scalar("SELECT count(*) FROM schema_migration_history"));
        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_platform WHERE client_ref_id IS NULL"));
    }

    @Test
    @DisplayName("半升级库（客户端表已在、本端列已删）不清空已有引用")
    void halfMigratedDatabase_shouldKeepExistingRef() throws Exception {
        prepareLegacySchema();
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id, local_ip, local_port)"
            + " VALUES('37000020002000000100', '10.0.0.1', '3700002000', '34020000002000000001', '192.168.118.7', 5061)");
        execute("CREATE TABLE tb_cascade_client (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " client_id VARCHAR(64) NOT NULL, client_name VARCHAR(128) DEFAULT '' NOT NULL,"
            + " client_ip VARCHAR(64) DEFAULT '' NOT NULL, is_default INTEGER DEFAULT 0 NOT NULL,"
            + " enabled INTEGER DEFAULT 1 NOT NULL, remark VARCHAR(255) DEFAULT NULL, extend TEXT)");
        execute("INSERT INTO tb_cascade_client(client_id, client_ip, is_default) VALUES('34020000002000000001', '192.168.118.7', 1)");
        execute("ALTER TABLE tb_cascade_platform ADD COLUMN client_ref_id INTEGER");
        execute("UPDATE tb_cascade_platform SET client_ref_id = (SELECT id FROM tb_cascade_client)");
        execute("ALTER TABLE tb_cascade_platform DROP COLUMN local_client_id");
        execute("ALTER TABLE tb_cascade_platform DROP COLUMN local_ip");
        execute("ALTER TABLE tb_cascade_platform DROP COLUMN local_port");

        migrator().migrate();

        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_client"), "已存在的客户端不能被再插一条");
        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_platform WHERE client_ref_id IS NOT NULL"),
            "已回填的引用不能被清空");
    }

    @Test
    @DisplayName("本端编码为空的行不生成客户端，也不阻断迁移")
    void blankClientId_shouldNotBlockMigration() throws Exception {
        prepareLegacySchema();
        execute("INSERT INTO tb_cascade_platform(platform_id, platform_ip, platform_domain, local_client_id, local_ip, local_port)"
            + " VALUES('37000020002000000100', '10.0.0.1', '3700002000', '', '', 5061)");

        migrator().migrate();

        assertEquals(0, scalar("SELECT count(*) FROM tb_cascade_client"), "空编码不是可用身份，不应造出客户端行");
        assertEquals(1, scalar("SELECT count(*) FROM tb_cascade_platform WHERE client_ref_id IS NULL"),
            "这类行留给应用层报「请先创建本端客户端」，迁移不能编造引用");
        assertEquals(1, scalar("SELECT count(*) FROM schema_migration_history"), "跳过单行不影响整支迁移登记");
    }

    @Test
    @DisplayName("表还没建（全新库）时跳过，由全量脚本负责建表")
    void emptyDatabase_shouldBeSkipped() throws Exception {
        migrator().migrate();

        assertFalse(tableExists("tb_cascade_platform"), "迁移器不负责建平台表");
        assertFalse(tableExists("tb_cascade_client"), "迁移器不负责建客户端表");
        assertEquals(0, scalar("SELECT count(*) FROM schema_migration_history"), "跳过的迁移不应登记为已执行");
    }

    // ------------------------------------------------------------------ 夹具

    /** 1.10.4 形状：本端身份仍内嵌在平台行里 */
    private void prepareLegacySchema() throws Exception {
        execute("CREATE TABLE tb_cascade_platform ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " platform_ip VARCHAR(64) NOT NULL,"
            + " platform_port INTEGER NOT NULL DEFAULT 5060,"
            + " platform_domain VARCHAR(64) NOT NULL,"
            + " platform_name VARCHAR(128) NOT NULL DEFAULT '',"
            + " username VARCHAR(64) NOT NULL DEFAULT '',"
            + " password VARCHAR(128) NOT NULL DEFAULT '',"
            + " local_client_id VARCHAR(64) NOT NULL,"
            + " local_ip VARCHAR(64) DEFAULT NULL,"
            + " local_port INTEGER NOT NULL DEFAULT 5061,"
            + " enabled INTEGER NOT NULL DEFAULT 1,"
            + " extend TEXT)");
        execute("CREATE UNIQUE INDEX uk_cascade_platform_id ON tb_cascade_platform (platform_id)");
    }

    private SqliteCascadeClientSchemaMigrator migrator() {
        SqliteCascadeClientSchemaMigrator migrator = new SqliteCascadeClientSchemaMigrator();
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

    private String string(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)) {
            assertNotNull(resultSet, "查询无结果: " + sql);
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private boolean tableExists(String table) throws Exception {
        return scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='" + table + "'") > 0;
    }

    private boolean columnExists(String table, String column) throws Exception {
        return scalar("SELECT count(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'") > 0;
    }
}
