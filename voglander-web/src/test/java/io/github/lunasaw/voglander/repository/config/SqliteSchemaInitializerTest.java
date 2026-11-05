package io.github.lunasaw.voglander.repository.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * D3 红线测试：把 SQLite 全量脚本和运行时 initializer 的 29 表契约钉成 CI 失败。
 * <p>
 * 纯单元测试（不起 Spring 上下文）：对一个全新临时 SQLite 文件运行<b>真实</b>的
 * {@link SqliteSchemaInitializer#initSchemaIfEmpty()}（加载 classpath {@code db/voglander-sqlite.sql}），
 * 断言：
 * <ol>
 * <li>建出全 29 张 {@code tb_} 业务表；</li>
 *   <li>admin 种子（id=1）存在；</li>
 *   <li>库非空但表残缺时 fail-fast，且<b>绝不</b>跑建表脚本——那会把现场数据清掉；</li>
 *   <li>1.10.2 契约：建表脚本不再建 {@code tb_cascade_channel}，老表由迁移器退场。</li>
 * </ol>
 *
 * @author luna
 */
@DisplayName("D3 — SQLite clone-and-run 建表完整性")
class SqliteSchemaInitializerTest {

    /** 脚本权威 tb_ 业务表数（sequence 表不在 tb_% 过滤内；1.10.2 通道老表退场后为 28，1.10.5 新增 tb_cascade_client 后为 29） */
    private static final int EXPECTED_TABLE_COUNT = 29;

    private Path             dbFile;
    private DataSource       dataSource;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = Files.createTempFile("voglander-schema-init-test-", ".db");
        // 删掉空占位文件，确保是「全新空库」语义（sqlite 会按需重建）
        Files.deleteIfExists(dbFile);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath() + "?busy_timeout=10000");
        this.dataSource = ds;
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(dbFile);
        // 清理 WAL/SHM 旁路文件
        Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
        Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
    }

    private SqliteSchemaInitializer newInitializer(DataSource ds) {
        SqliteSchemaInitializer initializer = new SqliteSchemaInitializer();
        ReflectionTestUtils.setField(initializer, "dataSource", ds);
        return initializer;
    }

    private int countUserTables(DataSource ds) throws Exception {
        String sql = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'tb_%'";
        try (Connection conn = ds.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean tableExists(DataSource ds, String table) throws Exception {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'";
        try (Connection conn = ds.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next();
        }
    }

    private boolean columnExists(DataSource ds, String table, String column) throws Exception {
        String sql = "SELECT count(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'";
        try (Connection conn = ds.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private String menuCode(DataSource ds, long id) throws Exception {
        try (Connection conn = ds.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT menu_code FROM tb_menu WHERE id=" + id)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int countMenuChildren(DataSource ds, long parentId) throws Exception {
        String sql = "SELECT count(*) FROM tb_menu WHERE parent_id=" + parentId;
        try (Connection conn = ds.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("全新空库 → 真实建表流程 → 建出全 29 张 tb_ 表")
    void freshDb_shouldBuildAllTables() throws Exception {
        newInitializer(dataSource).initSchemaIfEmpty();

        int actual = countUserTables(dataSource);
        assertEquals(EXPECTED_TABLE_COUNT, actual,
            "期望建出 " + EXPECTED_TABLE_COUNT + " 张 tb_ 表，实际 " + actual
                + "（Spring ResourceDatabasePopulator 语句切分中断的回归红线）");
    }

    @Test
    @DisplayName("全新空库 → 建表序列最后一张 tb_image_collection_config 必须存在，且老通道表已不再建")
    void freshDb_shouldBuildLastTable() throws Exception {
        newInitializer(dataSource).initSchemaIfEmpty();

        assertTrue(tableExists(dataSource, "tb_image_collection_config"),
            "建表序列真正最后一张表 tb_image_collection_config 缺失 → 脚本中断");
        assertTrue(!tableExists(dataSource, "tb_cascade_channel"),
            "1.10.2 契约：建表脚本不应再建 tb_cascade_channel，通道目录唯一来源是 tb_cascade_publish");
    }

    @Test
    @DisplayName("全新空库 → 1.10.5 契约：建 tb_cascade_client，平台表只留 client_ref_id")
    void freshDb_shouldExposeCascadeClientContract() throws Exception {
        newInitializer(dataSource).initSchemaIfEmpty();

        assertTrue(tableExists(dataSource, "tb_cascade_client"),
            "1.10.5 契约：必须建 tb_cascade_client，本端身份只存这张表");
        assertTrue(columnExists(dataSource, "tb_cascade_platform", "client_ref_id"),
            "1.10.5 契约：平台表必须带 client_ref_id 外键列，否则平台配置无法引用本端身份");
        for (String gone : new String[]{"local_client_id", "local_ip", "local_port"}) {
            assertTrue(!columnExists(dataSource, "tb_cascade_platform", gone),
                "1.10.5 契约：平台表不应再有 " + gone + "，本端身份已移到 tb_cascade_client");
        }
    }

    @Test
    @DisplayName("全新空库 → 菜单主键不撞车：604 归级联运行状态，本端客户端页取 605 + 按钮 60501-60504")
    void freshDb_shouldSeedCascadeClientMenuAt605() throws Exception {
        newInitializer(dataSource).initSchemaIfEmpty();

        /* 1.0.13 的「级联运行状态」页占了 604；菜单脚本用的是 INSERT OR IGNORE，
           id 撞车时整行被静默丢弃，表现为「页面进不去、按钮却挂在别的页面下」。
           这里把两个页面的主键归属钉死，下次再撞 id 会直接红。 */
        assertEquals("CascadeMonitor", menuCode(dataSource, 604), "604 应属于级联运行状态页");
        assertEquals("CascadeClient", menuCode(dataSource, 605), "本端客户端页应为 605，不应与 604 抢主键");
        assertEquals(4, countMenuChildren(dataSource, 605), "605 下应有新增/编辑/删除/设为默认 4 个按钮");
        assertEquals("CascadeClientCreate", menuCode(dataSource, 60501), "60501 应为新增按钮");
    }

    @Test
    @DisplayName("全新空库 → admin 种子(id=1)与角色映射建成")
    void freshDb_shouldSeedAdmin() throws Exception {
        newInitializer(dataSource).initSchemaIfEmpty();

        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT id, username FROM tb_user WHERE username='admin'")) {
            assertTrue(rs.next(), "admin 种子用户应存在");
            assertEquals(1, rs.getInt("id"), "admin 用户 id 应为 1");
        }
    }

    @Test
    @DisplayName("全新空库 → tb_cascade_platform 带上 1.10.3 的 8 个编码规则列")
    void freshDb_shouldExposePlatformCodingColumns() throws Exception {
        newInitializer(dataSource).initSchemaIfEmpty();

        /* 建表脚本与 SqliteCascadeCodingSchemaMigrator 必须同形：脚本缺列空库能起来但读写平台 500，
           迁移器缺列则老库升级后只是表面上能启动 */
        String[] columns = {"code_province", "code_city", "code_county", "code_industry",
            "code_network", "code_start_seq", "code_rule_enabled", "publish_ancestors"};
        for (String column : columns) {
            String sql = "SELECT count(*) FROM pragma_table_info('tb_cascade_platform') WHERE name='" + column + "'";
            try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
                assertTrue(rs.next() && rs.getInt(1) == 1, "建表脚本缺少 tb_cascade_platform." + column);
            }
        }
    }

    @Test
    @DisplayName("已初始化库（表齐全）→ 二次调用跳过，幂等")
    void alreadyInitialized_shouldSkip() throws Exception {
        SqliteSchemaInitializer initializer = newInitializer(dataSource);
        initializer.initSchemaIfEmpty();
        int afterFirst = countUserTables(dataSource);

        // 二次调用不应抛异常、不应改变表数
        initializer.initSchemaIfEmpty();
        assertEquals(afterFirst, countUserTables(dataSource), "已初始化库二次调用应幂等跳过");
    }

    /**
     * 库非空但表残缺 → fail-fast，且<b>一行数据都不准丢</b>。
     *
     * <p>这是 1.10.2 把哨兵从「某张特定表」改成「库里有没有 tb_ 表」之后最重要的红线：
     * 不再有单表哨兵后，就不存在「合法老库缺了哨兵表被误判为空库」的情形，而一旦误判，
     * 空库分支会执行含 {@code DROP TABLE} 的全量脚本，把现场数据清掉。
     */
    @Test
    @DisplayName("库非空但表残缺 → fail-fast，且不跑建表脚本（数据一行不丢）")
    void nonEmptyIncompleteDb_shouldFailFastWithoutWiping() throws Exception {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE tb_user (id INTEGER PRIMARY KEY, username VARCHAR(64))");
            stmt.execute("INSERT INTO tb_user(id, username) VALUES (7, 'keep-me')");
        }

        SqliteSchemaInitializer initializer = newInitializer(dataSource);
        assertThrows(IllegalStateException.class, initializer::initSchemaIfEmpty,
            "库非空但 tb_ 表数<21 的残缺库应 fail-fast，而不是当空库重建");

        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT username FROM tb_user WHERE id=7")) {
            assertTrue(rs.next(), "建表脚本带 DROP TABLE，绝不能在半途库上执行——原有数据必须还在");
            assertEquals("keep-me", rs.getString(1));
        }
    }
}
