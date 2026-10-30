package io.github.lunasaw.voglander.manager.sys;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 可配置项清单：键、默认值、校验区间与「能否热改」的唯一事实源。
 *
 * <p>这张清单只描述<b>能配什么</b>；具体生效值 = {@code tb_sys_config} 里的覆盖值
 * → yml 里显式配的值 → 本清单的默认值，三层依次回落。
 *
 * <p>为什么 {@code scan-interval-ms} 不可热改：它是 {@code @Scheduled(fixedDelayString=...)}
 * 的表达式，Spring 在启动期解析一次就不再重读，库里改了也不会生效。标成只读比"看起来能改、
 * 实际没生效"要诚实——现场遇到对不上的现象时，这条提示能省一轮排查。
 *
 * @author luna
 */
public final class SysConfigCatalog {

    /** 级联目录下发分组 */
    public static final String  GROUP_CASCADE_CATALOG           = "cascade-catalog";

    /** 单条目录 MESSAGE 容纳的通道数上限 */
    public static final String  CASCADE_CATALOG_BATCH_SIZE      = "gateway.cascade.catalog.batch-size";
    /** 分片是否串行（等上一页 200 OK 再发下一页） */
    public static final String  CASCADE_CATALOG_SERIAL          = "gateway.cascade.catalog.serial";
    /** 单页等待 200 OK 的超时（毫秒） */
    public static final String  CASCADE_CATALOG_OK_TIMEOUT_MS   = "gateway.cascade.catalog.ok-timeout-ms";
    /** 分片超时看门狗的扫描间隔（毫秒），仅 yml 生效 */
    public static final String  CASCADE_CATALOG_SCAN_INTERVAL_MS = "gateway.cascade.catalog.scan-interval-ms";
    /** 一批目录分片的最大存活时间（毫秒） */
    public static final String  CASCADE_CATALOG_BATCH_TTL_MS    = "gateway.cascade.catalog.batch-ttl-ms";

    public static final String  TYPE_INT                        = "INT";
    public static final String  TYPE_LONG                       = "LONG";
    public static final String  TYPE_BOOLEAN                    = "BOOLEAN";
    public static final String  TYPE_STRING                     = "STRING";

    private static final List<SysConfigOption> OPTIONS = List.of(
        new SysConfigOption(CASCADE_CATALOG_BATCH_SIZE, GROUP_CASCADE_CATALOG, TYPE_INT, "50",
            "单条目录 MESSAGE 容纳的通道数上限。上级对单包长度有限制，通道多时整包下发会被丢弃或截断，"
                + "表现为上级只看到前几十个通道。",
            true, 1L, 500L),
        new SysConfigOption(CASCADE_CATALOG_SERIAL, GROUP_CASCADE_CATALOG, TYPE_BOOLEAN, "true",
            "分片是否串行：true=等上一页 200 OK 再发下一页，避免瞬时灌包；false=一次性把全部分片发完。",
            true, null, null),
        new SysConfigOption(CASCADE_CATALOG_OK_TIMEOUT_MS, GROUP_CASCADE_CATALOG, TYPE_LONG, "2000",
            "单页等待 200 OK 的超时（毫秒）。到点无论有无应答都推进下一页，避免上级不回 2xx 时目录下发卡死。",
            true, 200L, 60000L),
        new SysConfigOption(CASCADE_CATALOG_SCAN_INTERVAL_MS, GROUP_CASCADE_CATALOG, TYPE_LONG, "1000",
            "分片超时看门狗的扫描间隔（毫秒）。由 @Scheduled 表达式在启动时读取，改动需重启应用。",
            false, 200L, 60000L),
        new SysConfigOption(CASCADE_CATALOG_BATCH_TTL_MS, GROUP_CASCADE_CATALOG, TYPE_LONG, "120000",
            "一批目录分片的最大存活时间（毫秒）。超时丢弃，防止上级中途断开后整棵目录树长期占用内存。",
            true, 10000L, 3600000L));

    private static final Map<String, SysConfigOption> BY_KEY = OPTIONS.stream()
        .collect(Collectors.toUnmodifiableMap(SysConfigOption::key, Function.identity()));

    private SysConfigCatalog() {}

    /**
     * 全部配置项定义。
     *
     * @return 不可变清单，顺序即页面展示顺序
     */
    public static List<SysConfigOption> all() {
        return OPTIONS;
    }

    /**
     * 按配置键查定义。
     *
     * @param key 配置键
     * @return 定义；未登记的键返回 null（写入口据此拒绝未知键）
     */
    public static SysConfigOption find(String key) {
        return key == null ? null : BY_KEY.get(key);
    }
}
