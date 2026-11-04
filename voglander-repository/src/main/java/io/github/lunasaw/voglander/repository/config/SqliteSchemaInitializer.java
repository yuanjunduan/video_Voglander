package io.github.lunasaw.voglander.repository.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.baomidou.mybatisplus.annotation.DbType;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * 生产/开发环境 SQLite 建表与初始化数据导入（clone-and-run 支持）。
 * <p>
 * 背景：项目无 Flyway/Liquibase，亦无程序化建表，历史上 clone-and-run 依赖被提交进 git 的
 * {@code app.db}/{@code test-app.db} 携带 schema。该 db 二进制移出版本控制后，新 clone 将无表可用。
 * 本类在启动时检测空库并执行权威建表脚本 {@code classpath:db/voglander-sqlite.sql}
 * （由 repository 模块构建期从根目录 {@code sql/voglander-sqlite.sql} 拷入，单一源），
 * 一次性建出 28 张表 + 默认 admin/角色/菜单种子，并登记通用任务与图像域迁移。
 * </p>
 * <p>
 * <b>幂等保证</b>：脚本结构段含 {@code DROP TABLE IF EXISTS}（破坏性），故仅在<b>完全空库</b>
 * （一张 {@code tb_} 业务表都没有）时执行；已有任何业务表就直接跳过，绝不触碰现有数据。
 * 因此每个库生命周期内最多执行一次，重启不会清库。
 * </p>
 * <p>
 * <b>为什么不再用 sentinel 表</b>：1.10.2 删掉 {@code tb_cascade_channel} 之后，库里不再有一张
 * 「各历史版本都存在、且建于最后」的表可当哨兵。换成任何较新的表都会让缺少它的合法老库被误判成
 * 空库——空库分支要跑含 {@code DROP TABLE} 的全量脚本，等于把现场数据清掉。代价是半途中断的坏库
 * 不再自动自愈，改由表数校验 fail-fast 暴露给人工处理。
 * </p>
 * <p>
 * 仅 SQLite 生效；MySQL 等其他库由 DBA 用 {@code sql/voglander.sql} 初始化，本类跳过零副作用。
 * 可通过 {@code voglander.sqlite.schema-init.enabled=false} 关闭（默认开启）。
 * </p>
 * <p>
 * 与 {@code SqlitePragmaConfig}（WAL）顺序无关正确性：WAL 是数据库文件级持久设置，
 * 建表在启动期单线程执行，DELETE 模式下亦可成功，之后开 WAL 照常生效——故不引入
 * {@code @DependsOn} 耦合（否则关闭 WAL 开关会因 bean 缺失导致启动失败）。
 * 本类在 {@code @PostConstruct}（容器初始化期）执行，先于 {@code UserInitializer}
 * （{@code CommandLineRunner}，容器就绪后执行）；admin 种子已由脚本以有效 admin123 哈希插入，
 * UserInitializer 检测到 admin 存在自然成 no-op，两者不冲突。
 * </p>
 *
 * @author luna
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "voglander.sqlite.schema-init.enabled", havingValue = "true", matchIfMissing = true)
public class SqliteSchemaInitializer {

    /** classpath 上的权威建表脚本（构建期由 ../sql/voglander-sqlite.sql 拷入） */
    private static final String SCHEMA_SCRIPT       = "db/voglander-sqlite.sql";

    /**
     * 权威脚本应建出的 {@code tb_} 业务表数：旧基线去掉 export 后为 20 张，新增通用任务三表、图像三表、
     * 级联组织表、级联发布表与系统配置表；1.10.2 通道老表退场后再减一张。
     */
    private static final int    EXPECTED_TABLE_COUNT = 28;

    /** 1.0.9 之前可无损升级的合法业务表数。 */
    private static final int    LEGACY_TABLE_COUNT   = 21;

    @Autowired(required = false)
    private DataSource          dataSource;

    @Autowired(required = false)
    private SqliteImageSchemaMigrator imageSchemaMigrator;

    @Autowired(required = false)
    private SqliteBusinessTaskSchemaMigrator businessTaskSchemaMigrator;

    @Autowired(required = false)
    private SqliteCascadeSchemaMigrator cascadeSchemaMigrator;

    @Autowired(required = false)
    private SqliteCascadePublishKeyMigrator cascadePublishKeyMigrator;

    @Autowired(required = false)
    private SqliteCascadeChannelMetaSchemaMigrator cascadeChannelMetaSchemaMigrator;

    @Autowired(required = false)
    private SqliteCascadeAssetSchemaMigrator         cascadeAssetSchemaMigrator;

    @Autowired(required = false)
    private SqliteCascadePlatformLayerMigrator       cascadePlatformLayerMigrator;

    @Autowired(required = false)
    private SqliteCascadeCodingSchemaMigrator        cascadeCodingSchemaMigrator;

    @Autowired(required = false)
    private SqliteCascadePublishCodeSourceMigrator   cascadePublishCodeSourceMigrator;

    @Autowired(required = false)
    private SqliteCascadeChannelPublishMigrator        cascadeChannelPublishMigrator;

    @Autowired(required = false)
    private SqliteCascadeChannelCodePushStatusMigrator cascadeChannelCodePushStatusMigrator;

    @Autowired(required = false)
    private SqliteCascadeChannelDropMigrator           cascadeChannelDropMigrator;

    @Autowired(required = false)
    private SqliteSysConfigSchemaMigrator          sysConfigSchemaMigrator;

    /**
     * 启动时按需初始化 SQLite schema。非 SQLite 跳过；已建全库跳过。
     * <p>
     * <b>fail-fast</b>：建表脚本执行失败、或建表后校验发现 {@code tb_} 表数不足，均上抛异常阻断启动——
     * 绝不留下「表残缺但看上去能用」的隐性坏库被后续业务（如 {@code CascadeClientScheduler}）以
     * {@code no such table} 间接暴露。
     */
    @PostConstruct
    public void initSchemaIfEmpty() {
        if (dataSource == null) {
            return;
        }

        // 仅 SQLite 生效；其余库跳过，零副作用
        DbType dbType = DatabaseTypeDetector.detectDbType(dataSource);
        if (dbType != DbType.SQLITE) {
            log.debug("当前数据库类型 {} 非 SQLite，跳过 schema 初始化", dbType);
            return;
        }

        try {
            if (hasAnyUserTable()) {
                // 21 表旧库先通过合法旧版完整性校验，再顺序迁移通用任务和图像领域。
                verifyLegacySchemaComplete();
                migrateBusinessTaskSchema();
                migrateImageSchema();
                migrateCascadeSchema();
                migrateCascadePublishKeySchema();
                migrateCascadeChannelMetaSchema();
                migrateCascadeAssetSchema();
                migrateCascadePlatformLayerSchema();
                migrateCascadeCodingSchema();
                migrateCascadePublishCodeSourceSchema();
                migrateCascadeChannelPublishSchema();
                migrateCascadeChannelCodePushStatusSchema();
                migrateCascadeChannelDropSchema();
                migrateSysConfigSchema();
                verifySchemaComplete();
                log.debug("SQLite 已有业务表且迁移后表数达标，跳过全量建表");
                return;
            }

            log.info("SQLite 无任何 tb_ 业务表（空库），执行建表脚本 {} ...", SCHEMA_SCRIPT);
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(SCHEMA_SCRIPT));
            // 脚本含 DROP TABLE IF EXISTS（幂等），单条失败应整体失败以便排查，不静默继续
            populator.setContinueOnError(false);
            try (Connection conn = dataSource.getConnection()) {
                populator.populate(conn);
            }

            // 空库全量脚本已含图像表；仍运行幂等 migration 以登记 history 并验证关键索引。
            migrateBusinessTaskSchema();
            migrateImageSchema();
            migrateCascadeSchema();
            migrateCascadePublishKeySchema();
            migrateCascadeChannelMetaSchema();
            migrateCascadeAssetSchema();
            migrateCascadePlatformLayerSchema();
            migrateCascadeCodingSchema();
            migrateCascadePublishCodeSourceSchema();
            migrateCascadeChannelPublishSchema();
            migrateCascadeChannelCodePushStatusSchema();
            migrateCascadeChannelDropSchema();
            migrateSysConfigSchema();
            verifySchemaComplete();
            log.info("SQLite schema 初始化完成（建表 {} 张 + 默认 admin/角色/菜单种子）", EXPECTED_TABLE_COUNT);
        } catch (RuntimeException e) {
            // fail-fast：上抛阻断启动，绝不吞异常留隐性坏库
            log.error("SQLite schema 初始化失败，阻断启动：{}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("SQLite schema 初始化失败，阻断启动：{}", e.getMessage(), e);
            throw new IllegalStateException("SQLite schema 初始化失败", e);
        }
    }

    /**
     * 建表后完整性校验：统计 {@code tb_} 开头的业务表数，不足 {@link #EXPECTED_TABLE_COUNT} 即抛异常。
     *
     * @throws IllegalStateException 表数不足（疑似脚本切分失败 / 坏库）
     */
    private void verifySchemaComplete() throws Exception {
        int actual = countUserTables();
        if (actual < EXPECTED_TABLE_COUNT) {
            throw new IllegalStateException(
                "SQLite 建表不完整：期望 " + EXPECTED_TABLE_COUNT + " 张 tb_ 表，实际 " + actual
                    + "，疑似脚本切分失败或坏库");
        }
    }

    private void verifyLegacySchemaComplete() throws Exception {
        int actual = countUserTables();
        if (actual < LEGACY_TABLE_COUNT) {
            throw new IllegalStateException(
                "SQLite 建表不完整：至少期望 " + LEGACY_TABLE_COUNT + " 张旧版 tb_ 表，实际 " + actual);
        }
    }

    private void migrateImageSchema() {
        SqliteImageSchemaMigrator migrator = imageSchemaMigrator;
        if (migrator == null) {
            migrator = new SqliteImageSchemaMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    private void migrateBusinessTaskSchema() {
        SqliteBusinessTaskSchemaMigrator migrator = businessTaskSchemaMigrator;
        if (migrator == null) {
            migrator = new SqliteBusinessTaskSchemaMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联域 schema 的幂等对齐（组织去平台化 + 发布表 + 设备归属列）。
     *
     * <p>整个 1.10.1 演进只走一个迁移器、一个事务：组织表重建与发布表建表必须同进同退，
     * 拆开会让库停在「组织已改列、发布表没建出来」的半升级态。
     * 又因为要跑在表数校验之前，否则老库会被 {@code verifySchemaComplete()} 以「表数不足」拒之门外。
     */
    private void migrateCascadeSchema() {
        SqliteCascadeSchemaMigrator migrator = cascadeSchemaMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadeSchemaMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联发布表「对象键」的幂等补齐（对象键 {@code object_id} → {@code object_key} + {@code parent_key}）。
     *
     * <p>紧跟在 {@link #migrateCascadeSchema()} 之后：那支负责把发布表建出来（老库可能是旧形状），
     * 本支负责把形状换成业务键。拆成两支是因为迁移键是"跑过就跳过"，老库里 1.10.1 的键早已登记。
     */
    private void migrateCascadePublishKeySchema() {
        SqliteCascadePublishKeyMigrator migrator = cascadePublishKeyMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadePublishKeyMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联通道目录元数据列的幂等补齐（1.0.12：厂商/型号/地址/IP/摄像机类型）。
     *
     * <p>不放进迁移历史就跳过的判断：老库升级后缺列会在“新增通道”和目录组装时报
     * {@code no such column}，而建表脚本只对空库生效，故每次启动都验一遍（代价是 5 次
     * {@code pragma_table_info} 查询）。
     */
    private void migrateCascadeChannelMetaSchema() {
        SqliteCascadeChannelMetaSchemaMigrator migrator = cascadeChannelMetaSchemaMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadeChannelMetaSchemaMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联本地资产层（设备/通道的本地启停与通道组织归属）的幂等补齐。
     *
     * <p>与 1.10.1 的 {@link SqliteCascadeSchemaMigrator} 分开两支：迁移键是"跑过就跳过"，
     * 老库已经登记过 1.10.1 的键，再往里加列永远补不上。
     */
    private void migrateCascadeAssetSchema() {
        SqliteCascadeAssetSchemaMigrator migrator = cascadeAssetSchemaMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadeAssetSchemaMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联平台画像（目录是否带设备层）的幂等补齐。
     *
     * <p>与本地资产迁移分开：那一支是“三类资产自己的字段”，这一支是“对端画像”，
     * 两者的演进速度与适用对象都不同，合在一个迁移键里会让排查时看不出卡在哪一步。
     */
    private void migrateCascadePlatformLayerSchema() {
        SqliteCascadePlatformLayerMigrator migrator = cascadePlatformLayerMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadePlatformLayerMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联平台编码规则的幂等补齐（1.10.3：平台级七段前 14 位 + 起始序号 + 启用开关 + 父链策略）。
     *
     * <p>紧跟在 {@link #migrateCascadePlatformLayerSchema()} 之后：同一张表的两次演进，
     * 但迁移键是「跑过就跳过」，1.10.2 的键已在大量库上登记，合进那一支就补不上新列。
     * 不补这 8 列，平台配置一读写就 {@code no such column}。
     */
    private void migrateCascadeCodingSchema() {
        SqliteCascadeCodingSchemaMigrator migrator = cascadeCodingSchemaMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadeCodingSchemaMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联发布行「编码来源」的幂等补齐（1.10.3：{@code code_source} + {@code code_generated_time}）。
     *
     * <p>与平台编码规则同批演进但不同表：那一支解决「按哪套段位发号」，这一支解决「发出来的号
     * 记在哪、怎么区分人工与自动」。缺这两列，规则生成的结果无处固化，每次现算都会漂。
     *
     * <p>必须排在 {@link #migrateCascadeChannelPublishSchema()} 之前：那支会往发布表批量插入通道行，
     * 列先补齐才不至于在插入路径上撞 {@code no such column}。
     */
    private void migrateCascadePublishCodeSourceSchema() {
        SqliteCascadePublishCodeSourceMigrator migrator = cascadePublishCodeSourceMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadePublishCodeSourceMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联通道「进入发布层」的数据迁移（{@code tb_cascade_channel} → {@code tb_cascade_publish}）。
     *
     * <p>必须跟在 {@link #migrateCascadeAssetSchema()} 之后：回填通道归属要写它的 {@code org_id}；
     * 也必须跟在 {@link #migrateCascadePublishKeySchema()} 之后：发布表得先具备业务键形状。
     *
     * <p>不迁这一步，目录组装切到发布集的那一刻，存量部署的上级目录会瞬间清空。
     */
    private void migrateCascadeChannelPublishSchema() {
        SqliteCascadeChannelPublishMigrator migrator = cascadeChannelPublishMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadeChannelPublishMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联「通道自己的码 + 发布行拆键/推送状态」的幂等补齐（1.10.3：{@code tb_device_channel.code}、
     * {@code tb_cascade_publish.device_key / last_push_*}，以及两个新索引）。
     *
     * <p>排在 {@link #migrateCascadeChannelPublishSchema()} 之后、{@link #migrateCascadeChannelDropSchema()}
     * 之前：那支负责把老通道表搬成发布行，本支的回填才有对象可写；而退场老表与这两列无关，
     * 放在它后面只会让「搬迁行未回填设备段」多留一个启动窗口。
     *
     * <p>列必须先于任何写入路径存在：通道新增从 2.4 起会往 {@code code} 与 {@code device_key} 落值，
     * 缺列会让这两条路径在运行期撞 {@code no such column}。
     */
    private void migrateCascadeChannelCodePushStatusSchema() {
        SqliteCascadeChannelCodePushStatusMigrator migrator = cascadeChannelCodePushStatusMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadeChannelCodePushStatusMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 级联通道老表的退场（{@code DROP TABLE tb_cascade_channel}，1.10.2 契约阶段）。
     *
     * <p>必须紧跟 {@link #migrateCascadeChannelPublishSchema()}：那支负责把老表的数据搬成发布行，
     * 本支才敢删。删表前的守护也在本支里——老表还有数据但搬迁没登记，会直接阻断启动。
     */
    private void migrateCascadeChannelDropSchema() {
        SqliteCascadeChannelDropMigrator migrator = cascadeChannelDropMigrator;
        if (migrator == null) {
            migrator = new SqliteCascadeChannelDropMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 系统配置表 {@code tb_sys_config} 的幂等补齐（1.0.13 新增）。
     *
     * <p>不放在表数校验之后：老库若不先补出这张表，{@code verifySchemaComplete()}
     * 会以「表数不足」阻断启动，升级窗口直接被拒之门外。
     */
    private void migrateSysConfigSchema() {
        SqliteSysConfigSchemaMigrator migrator = sysConfigSchemaMigrator;
        if (migrator == null) {
            migrator = new SqliteSysConfigSchemaMigrator();
            migrator.setDataSource(dataSource);
        }
        migrator.migrate();
    }

    /**
     * 统计 {@code tb_} 开头的业务表数量。
     *
     * @return 实际 tb_ 表数
     */
    private int countUserTables() throws Exception {
        String sql = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'tb_%'";
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * 库里是否已有 {@code tb_} 业务表——即「这是一个已初始化的库」而非空库。
     *
     * <p>判据刻意放到最宽：只认「一张都没有」。任何更窄的判据（某张特定表）都会在版本演进中
     * 变成误判源，而误判的代价是执行含 {@code DROP TABLE} 的全量脚本、清掉现场数据。
     *
     * @return true=已有业务表（跳过建表，只走迁移）
     */
    private boolean hasAnyUserTable() throws Exception {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'tb_%' LIMIT 1";
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next();
        }
    }
}
