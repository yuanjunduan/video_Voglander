package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CascadeSsrcGenerator} 单元测试。
 *
 * <p>规则对齐国标与参考实现 CSS：{@code 首位(0实时/1回放) + 本域编码[4..9] + 4 位序号}，恒为 10 位。
 * 上级 INVITE 不带 ssrc 时用它兜底，避免回填 "0" 导致上级按 ssrc 匹配 RTP 取不到流。
 */
@DisplayName("CascadeSsrcGenerator：级联点播 SSRC 生成")
class CascadeSsrcGeneratorTest {

    /** 域编码里能取到非零中段的样例，便于断言 {@code sipId[4:9]} */
    private static final String DOMAIN     = "37000020002000000100";
    private static final String MID        = "00200";

    @Test
    @DisplayName("实时点播：首位 0、恒 10 位、中段取编码[4..9]")
    void generate_realtime() {
        String ssrc = CascadeSsrcGenerator.generate(DOMAIN, "7", false);

        assertThat(ssrc).hasSize(10);
        assertThat(ssrc.charAt(0)).isEqualTo('0');
        assertThat(ssrc.substring(1, 6)).isEqualTo(MID);
        assertThat(ssrc).matches("\\d{10}");
    }

    @Test
    @DisplayName("回放点播：首位 1")
    void generate_playback() {
        String ssrc = CascadeSsrcGenerator.generate(DOMAIN, "7", true);

        assertThat(ssrc).hasSize(10);
        assertThat(ssrc.charAt(0)).isEqualTo('1');
        assertThat(ssrc.substring(1, 6)).isEqualTo(MID);
    }

    @Test
    @DisplayName("域编码过短/含非数字：中段补 00000，输出仍为 10 位")
    void midDigits_fallback() {
        assertThat(CascadeSsrcGenerator.midDigits(null)).isEqualTo("00000");
        assertThat(CascadeSsrcGenerator.midDigits("3402")).isEqualTo("00000");
        assertThat(CascadeSsrcGenerator.midDigits("34020A00002000000001")).isEqualTo("00000");

        String ssrc = CascadeSsrcGenerator.generate("3402", "3", false);
        assertThat(ssrc).hasSize(10).startsWith("000000");
    }

    @Test
    @DisplayName("纯数字通道号：取数字部分作为序号基值")
    void channelSeq_digits() {
        assertThat(CascadeSsrcGenerator.channelSeq(null)).isZero();
        assertThat(CascadeSsrcGenerator.channelSeq("  ")).isZero();
        assertThat(CascadeSsrcGenerator.channelSeq("7")).isEqualTo(7);
        assertThat(CascadeSsrcGenerator.channelSeq("通道7")).isEqualTo(7);
        assertThat(CascadeSsrcGenerator.channelSeq("10007")).isEqualTo(7);
    }

    @Test
    @DisplayName("非数字通道号：CRC32 稳定映射，同一通道每次基值一致")
    void channelSeq_nonNumericStable() {
        int first  = CascadeSsrcGenerator.channelSeq("lab_ch");
        int second = CascadeSsrcGenerator.channelSeq("lab_ch");

        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(0, 9999);
        assertThat(CascadeSsrcGenerator.channelSeq("lab_ch2")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("同通道连续分配：序号段递增 1，避免 startSendRtp 互相覆盖")
    void generate_incrementsWithinChannel() {
        String a = CascadeSsrcGenerator.generate(DOMAIN, "7", false);
        String b = CascadeSsrcGenerator.generate(DOMAIN, "7", false);

        int seqA = Integer.parseInt(a.substring(6));
        int seqB = Integer.parseInt(b.substring(6));
        assertThat(seqB).isEqualTo((seqA + 1) % 10000);
    }

    @Test
    @DisplayName("并发分配：结果互不相同")
    void generate_concurrentUnique() throws Exception {
        int             threads = 8;
        int             perTask = 50;
        Set<String>     ssrcs   = ConcurrentHashMap.newKeySet();
        CountDownLatch  ready   = new CountDownLatch(threads);
        CountDownLatch  go      = new CountDownLatch(1);
        ExecutorService pool    = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    for (int j = 0; j < perTask; j++) {
                        ssrcs.add(CascadeSsrcGenerator.generate(DOMAIN, "7", false));
                    }
                    return null;
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(ssrcs).hasSize(threads * perTask);
    }
}
