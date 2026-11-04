package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder.Segments;

/**
 * 国标 20 位编码的拼装与发号规则（纯计算，不起 Spring）。
 *
 * <p>这一层必须先用单测钉死：{@code reapply} 一旦落库，编码就进了上级目录，
 * 事后只能靠人工对账，测试成本远高于把公式写错在这里被断言拦住。
 */
@DisplayName("CascadeCodeBuilder 编码拼装")
class CascadeCodeBuilderTest {

    /** 37 / 01 / 0200 / 04 / 216 / 1：山东济南历下区、行业 04、虚拟组织、网络 1 */
    private static final Segments SAMPLE = new Segments("37", "01", "0200", "04", "216", "1");

    @Test
    @DisplayName("前 10 位 = 省市区 + 行业，前 14 位再加类型与网络")
    void should_compose_prefix_and_head() {
        assertEquals("37010200", SAMPLE.center());
        assertEquals("3701020004", SAMPLE.prefix());
        assertEquals("37010200042161", SAMPLE.head());
        assertEquals(20, CascadeCodeBuilder.join(SAMPLE.head(), 123).length());
    }

    @Test
    @DisplayName("拼出的编码恰好 20 位，序号补零到 6 位")
    void should_join_twenty_digit_code() {
        assertEquals("37010200042161000123", CascadeCodeBuilder.join(SAMPLE.head(), 123));
        assertEquals("37010200042161999999", CascadeCodeBuilder.join(SAMPLE.head(), 999_999));
    }

    @Test
    @DisplayName("全局前缀 3402000000 应拆成 34 / 02 / 0000 / 00，类型与网络取默认")
    void should_split_global_prefix() {
        Segments segments = CascadeCodeBuilder.fromPrefix("3402000000", null);

        assertNotNull(segments);
        assertEquals("34", segments.province());
        assertEquals("02", segments.city());
        assertEquals("0000", segments.county());
        assertEquals("00", segments.industry());
        assertEquals("215", segments.type());
        assertEquals("0", segments.network());
        /* 拆完拼回去必须与配置的前 10 位一致，否则「默认前缀」与「七段」会分叉 */
        assertEquals("3402000000", segments.prefix());
    }

    @Test
    @DisplayName("全局前缀长度不足或非数字时返回 null")
    void should_reject_bad_prefix() {
        assertNull(CascadeCodeBuilder.fromPrefix("3402", "215"));
        assertNull(CascadeCodeBuilder.fromPrefix("34020000AB", "215"));
        assertNull(CascadeCodeBuilder.fromPrefix(null, "215"));
    }

    @Test
    @DisplayName("段回落：自身 > 父组织 > 全局，逐段独立")
    void should_fall_back_segment_by_segment() {
        Segments self = new Segments(null, null, "0200", null, null, null);
        Segments parent = new Segments("37", "01", "0199", "04", "215", null);
        Segments global = new Segments("34", "02", "0000", "00", "215", "0");

        Segments resolved = CascadeCodeBuilder.resolve(self, parent, global);

        assertEquals("37", resolved.province(), "自身缺、父组织有 → 用父组织");
        assertEquals("01", resolved.city());
        assertEquals("0200", resolved.county(), "自身有 → 压过父组织");
        assertEquals("04", resolved.industry());
        assertEquals("215", resolved.type());
        assertEquals("0", resolved.network(), "父组织缺 → 落全局");
        assertTrue(resolved.isComplete());
    }

    @Test
    @DisplayName("段回落：父组织整体为空时全部落全局")
    void should_fall_back_to_global_when_parent_missing() {
        Segments global = CascadeCodeBuilder.fromPrefix("3402000000", "216");

        Segments resolved = CascadeCodeBuilder.resolve(null, null, global);

        assertEquals(global, resolved);
    }

    @Test
    @DisplayName("发号：取同前缀全库最大值 +1")
    void next_seq_should_take_global_max_plus_one() {
        String head = SAMPLE.head();
        List<String> existing = List.of(head + "000003", head + "000010", "37010200042162000001");

        assertEquals(11, CascadeCodeBuilder.nextSeq(existing, head, null));
    }

    @Test
    @DisplayName("发号：号段起点作为下界，但不会把已用号码降回去")
    void next_seq_should_respect_start_seq_floor() {
        String head = SAMPLE.head();
        List<String> existing = List.of(head + "000003");

        assertEquals(100, CascadeCodeBuilder.nextSeq(existing, head, 100));
        assertEquals(4, CascadeCodeBuilder.nextSeq(existing, head, 2), "下界低于现有最大值时按 max+1");
        assertEquals(4, CascadeCodeBuilder.nextSeq(existing, head, null));
        assertEquals(4, CascadeCodeBuilder.nextSeq(existing, head, -5));
    }

    @Test
    @DisplayName("发号：前缀不符、长度不对、序号非数字的脏数据一律跳过")
    void next_seq_should_ignore_dirty_codes() {
        String head = SAMPLE.head();
        /* Arrays.asList 允许 null 元素：脏数据里确实会有空编码，List.of 会直接 NPE */
        List<String> existing = Arrays.asList(
            "37010200042162000099", /* 类型位不同 */
            head + "ABCDEF",        /* 序号非数字 */
            head + "00001",         /* 长度不足 */
            null);

        assertEquals(1, CascadeCodeBuilder.nextSeq(existing, head, null));
    }

    @Test
    @DisplayName("发号：空集合从 1 开始")
    void next_seq_should_start_from_one() {
        assertEquals(1, CascadeCodeBuilder.nextSeq(List.of(), SAMPLE.head(), null));
        assertEquals(1, CascadeCodeBuilder.nextSeq(null, SAMPLE.head(), null));
    }

    @Test
    @DisplayName("拼装参数非法时直接报错，不产生半截编码")
    void join_should_reject_bad_input() {
        assertThrows(IllegalArgumentException.class, () -> CascadeCodeBuilder.join("3701020004216", 1));
        assertThrows(IllegalArgumentException.class, () -> CascadeCodeBuilder.join("3701020004216X", 1));
        assertThrows(IllegalArgumentException.class, () -> CascadeCodeBuilder.join(null, 1));
        assertThrows(IllegalArgumentException.class, () -> CascadeCodeBuilder.join(SAMPLE.head(), -1));
        assertThrows(IllegalArgumentException.class, () -> CascadeCodeBuilder.join(SAMPLE.head(), 1_000_000));
    }

    @Test
    @DisplayName("解析序号：前缀不符返回 -1")
    void seq_of_should_check_head() {
        assertEquals(123, CascadeCodeBuilder.seqOf(SAMPLE.head() + "000123", SAMPLE.head()));
        assertEquals(-1, CascadeCodeBuilder.seqOf("37010200042162" + "000123", SAMPLE.head()));
        assertEquals(-1, CascadeCodeBuilder.seqOf(null, SAMPLE.head()));
    }

    @Test
    @DisplayName("国标编码判定：必须 20 位纯数字")
    void should_check_gb_code() {
        assertTrue(CascadeCodeBuilder.isGbCode("34020000001320000001"));
        assertFalse(CascadeCodeBuilder.isGbCode("hk-nvr165-ch3"));
        assertFalse(CascadeCodeBuilder.isGbCode("3402000000132000000"));
    }

    @Test
    @DisplayName("换类型位只改类型段，中心与行业不变")
    void should_switch_type_only() {
        Segments channel = SAMPLE.withType(CascadeCodeBuilder.CHANNEL_TYPE);

        assertEquals(SAMPLE.prefix(), channel.prefix());
        assertEquals("37010200041311", channel.head());
    }
}
