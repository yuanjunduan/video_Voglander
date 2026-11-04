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
 * 1.10.2「通道老表退场」的回归测试。
 *
 * <p>钉住三件事：搬迁登记过才敢删表；老表还有数据而搬迁没登记时宁可阻断启动也不猜；
 * 表本来不在或已删过时只需登记迁移键，重复启动幂等。
 *
 * <p>第二条是整支迁移的安全底线——删表不可逆，一旦在数据没搬完时执行，现场的上报配置就
 * 再也没有第二份副本。
 */
@DisplayName("SQLite 1.10.2 级联通道老表退场迁移")
class SqliteCascadeChannelDropMigratorTest {

    private static final String PLATFORM_A = "34020000002000000001";

    private Path       dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-cascade-channel-drop-", ".db");
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
    @DisplayName("搬迁已登记：删表并登记退场迁移键")
    void publishMigrated_shouldDropTable() throws Exception {
        prepareLegacyTable();
        legacyChannel();
        markApplied(SqliteCascadeChannelPublishMigrator.MIGRATION_KEY);

        migrator().migrate();

        assertFalse(tableExists("tb_cascade_channel"), "通道目录的唯一来源已是发布表，老表应退场");
        assertTrue(applied(SqliteCascadeChannelDropMigrator.MIGRATION_KEY), "删完必须登记，否则每次启动都查一遍");
    }

    @Test
    @DisplayName("老表还有数据但搬迁没登记：拒绝删表并阻断启动")
    void legacyRowsWithoutPublishMigration_shouldRefuseToDrop() throws Exception {
        prepareLegacyTable();
        legacyChannel();

        SqliteCascadeChannelDropMigrator migrator = migrator();
        IllegalStateException error = assertThrows(IllegalStateException.class, migrator::migrate);

        assertTrue(error.getMessage().contains("拒绝删表"), "错误信息要说清为什么不动手，便于现场归因");
        assertTrue(tableExists("tb_cascade_channel"), "数据没搬完就删表 = 静默丢配置，绝不能发生");
        assertFalse(applied(SqliteCascadeChannelDropMigrator.MIGRATION_KEY), "失败的迁移不能登记为已执行");
    }

    @Test
    @DisplayName("老表是空表且搬迁没登记：放行删除，没有配置可丢")
    void emptyLegacyTable_shouldBeDropped() throws Exception {
        prepareLegacyTable();

        migrator().migrate();

        assertFalse(tableExists("tb_cascade_channel"), "空表没有任何配置可丢，不必卡住升级");
        assertTrue(applied(SqliteCascadeChannelDropMigrator.MIGRATION_KEY));
    }

    @Test
    @DisplayName("老表本来不存在（新库）：只登记迁移键，不报错")
    void missingTable_shouldJustRecordApplied() throws Exception {
        migrator().migrate();

        assertTrue(applied(SqliteCascadeChannelDropMigrator.MIGRATION_KEY),
            "新库的建表脚本已不再建这张表，仍需登记以免每次启动重复判定");
    }

    @Test
    @DisplayName("重复启动幂等：已登记后不再触碰表")
    void repeatedStartup_shouldBeIdempotent() throws Exception {
        prepareLegacyTable();
        markApplied(SqliteCascadeChannelPublishMigrator.MIGRATION_KEY);

        SqliteCascadeChannelDropMigrator migrator = migrator();
        migrator.migrate();
        /* 第二次跑时老表已不在，若实现漏了 applied 判断就会走到"表不存在→登记"分支，结果相同；
           这里断的是迁移历史只有一条，反过来说明没有重复写 */
        migrator.migrate();

        assertEquals(2, countHistory(), "两个迁移键各一条，不该出现重复登记");
    }

    // ------------------------------------------------------------------ 夹具

    /** 老表的最小形状：删表只需它的存在与行数，列只需插入用到的那几列。 */
    private void prepareLegacyTable() throws Exception {
        execute("CREATE TABLE tb_cascade_channel ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " platform_id VARCHAR(64) NOT NULL,"
            + " local_device_id VARCHAR(64) NOT NULL,"
            + " local_channel_id VARCHAR(64) NOT NULL,"
            + " cascade_channel_id VARCHAR(64) NOT NULL)");
    }

    private void legacyChannel() {
        execute("INSERT INTO tb_cascade_channel(platform_id, local_device_id, local_channel_id, cascade_channel_id)"
            + " VALUES('" + PLATFORM_A + "', '34020000001320000001', '34020000001310000001',"
            + " '34020000001310000001')");
    }

    private void markApplied(String key) throws Exception {
        ensureHistoryTable();
        execute("INSERT OR IGNORE INTO schema_migration_history(migration_key, applied_at)"
            + " VALUES('" + key + "', CURRENT_TIMESTAMP)");
    }

    private SqliteCascadeChannelDropMigrator migrator() {
        SqliteCascadeChannelDropMigrator migrator = new SqliteCascadeChannelDropMigrator();
        migrator.setDataSource(dataSource);
        return migrator;
    }

    // ------------------------------------------------------------------ 查询工具

    private void ensureHistoryTable() throws Exception {
        execute("CREATE TABLE IF NOT EXISTS schema_migration_history ("
            + "migration_key VARCHAR(128) PRIMARY KEY, applied_at DATETIME NOT NULL)");
    }

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

    private boolean applied(String key) throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history WHERE migration_key='" + key + "'") > 0;
    }

    private int countHistory() throws Exception {
        return scalar("SELECT count(*) FROM schema_migration_history");
    }

    private boolean tableExists(String table) throws Exception {
        return scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='" + table + "'") > 0;
    }
}
