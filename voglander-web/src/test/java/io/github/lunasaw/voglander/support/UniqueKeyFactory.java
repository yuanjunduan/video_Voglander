package io.github.lunasaw.voglander.support;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试唯一键生成工厂
 * 用于并发测试场景生成全局唯一的业务键，避免测试数据冲突
 *
 * 格式: {prefix}-{timestamp}-{threadId}-{uuid4}
 *
 * @author luna
 */
public class UniqueKeyFactory {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    /** 20 位国标编码的序号：从当前毫秒低 9 位起跳，避开与库内历史编码撞车的常见区间 */
    private static final AtomicLong    GB_SEQ  = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L);

    private static String generate(String prefix) {
        long timestamp = System.currentTimeMillis();
        long threadId = Thread.currentThread().getId();
        String uuid = UUID.randomUUID().toString().substring(0, 4);
        int count = COUNTER.incrementAndGet();
        return String.format("%s-%d-%d-%s-%d", prefix, timestamp, threadId, uuid, count);
    }

    public static String deviceId() {
        return generate("dev");
    }

    public static String serverId() {
        return generate("srv");
    }

    public static String callId() {
        return generate("call");
    }

    public static String app() {
        return generate("app");
    }

    public static String stream() {
        return generate("stream");
    }

    public static String channelId() {
        return generate("ch");
    }

    /**
     * 20 位纯数字国标编码（如本端平台编码）。
     *
     * <p>不能用 {@link #deviceId()}：它带前缀与短横线，只能当普通业务键，
     * 而平台/客户端编码有「20 位 ASCII 数字」的硬约束。
     *
     * @return 34 开头、共 20 位的唯一编码
     */
    public static String gbId() {
        return "34" + String.format("%018d", GB_SEQ.incrementAndGet());
    }
}
