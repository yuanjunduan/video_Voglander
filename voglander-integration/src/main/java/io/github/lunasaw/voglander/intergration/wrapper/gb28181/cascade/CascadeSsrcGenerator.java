package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * 级联点播 SSRC 生成器。
 *
 * <p>按 GB/T 28181，SSRC 由 INVITE 发起方（上级平台）指定、下级原样回显；但海康 iSecure 的
 * 部分版本下发 INVITE 时不带 ssrc（参考实现 CSS 的 {@code generateSsrc} 注释也记录了这一点）。
 * 此时若把 ssrc 填成 {@code "0"}，上级按 ssrc 匹配/分发 RTP 时会取不到流，表现为
 * 「ZLM 侧推流已启动、上级侧无画面」。
 *
 * <p>生成规则对齐国标与 CSS：
 * <pre>
 *   第 1 位     0 = 实时点播，1 = 回放/下载
 *   第 2~6 位   本域 SIP 编码第 5~9 位（取不到数字时补 "00000"）
 *   第 7~10 位  序号：取本地通道号，并对进程内分配做递增偏移，避免同通道并发点播撞车
 * </pre>
 * 结果恒为 10 位十进制，首位只会是 0/1，因此转 int 不会溢出。
 *
 * @author luna
 */
public final class CascadeSsrcGenerator {

    /** 序号段容量 */
    private static final int SEQ_MOD       = 10000;

    /** 序号兜底值 */
    private static final String MID_FALLBACK = "00000";

    /**
     * 进程内分配游标。
     *
     * <p>同一通道被上级并发点播（如两路回放）时，若两次都生成同一个 ssrc，ZLM 的
     * {@code startSendRtp} 会按 ssrc 覆盖前一路，导致先建立的那路黑屏。这里用全局游标做加法偏移，
     * 相邻两次分配必然不同；正常并发量下不会绕满 10000 个号。
     */
    private static final AtomicInteger CURSOR = new AtomicInteger();

    private CascadeSsrcGenerator() {
    }

    /**
     * 生成一个会话内稳定的 10 位 SSRC。
     *
     * @param domainSipId    本域 SIP 编码（20 位国标编码）；非 20 位或非数字时中段补 "00000"
     * @param localChannelId 本地通道号，用于决定序号段；非数字时用 CRC32 稳定映射
     * @param playback       true 表示回放/下载（首位 1），false 表示实时（首位 0）
     */
    public static String generate(String domainSipId, String localChannelId, boolean playback) {
        int seq = Math.floorMod(channelSeq(localChannelId) + CURSOR.getAndIncrement(), SEQ_MOD);
        return (playback ? "1" : "0") + midDigits(domainSipId)
            + String.format(Locale.ROOT, "%04d", seq);
    }

    /**
     * 取 SIP 编码第 5~9 位（下标 4~8），对齐 CSS 的 {@code sipId[4:9]}。
     *
     * <p>不足 9 位或该段含非数字时返回补零串，保证输出恒为 10 位、不污染上游 SDP。
     */
    static String midDigits(String domainSipId) {
        if (domainSipId == null || domainSipId.length() < 9) {
            return MID_FALLBACK;
        }
        String mid = domainSipId.substring(4, 9);
        for (int i = 0; i < mid.length(); i++) {
            if (!Character.isDigit(mid.charAt(i))) {
                return MID_FALLBACK;
            }
        }
        return mid;
    }

    /**
     * 通道号 → 序号段基值。
     *
     * <p>通道号多为纯数字（NVR 通道 1..8），直接取模；Lab 或自定义来源可能是
     * {@code lab_ch} 这类非数字串，用 CRC32 映射成稳定值，保证同一通道每次拿到的基值一致。
     */
    static int channelSeq(String localChannelId) {
        if (localChannelId == null || localChannelId.trim().isEmpty()) {
            return 0;
        }
        String trimmed = localChannelId.trim();
        StringBuilder digits = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        if (digits.length() > 0) {
            try {
                return (int) (Long.parseLong(digits.toString()) % SEQ_MOD);
            } catch (NumberFormatException ignored) {
                // 数字过长（>18 位）时落到 CRC32 分支
            }
        }
        CRC32 crc = new CRC32();
        crc.update(trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (int) (crc.getValue() % SEQ_MOD);
    }
}
