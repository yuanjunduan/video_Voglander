package io.github.lunasaw.voglander.manager.code;

import java.util.Collection;

import org.apache.commons.lang3.StringUtils;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;

/**
 * 国标 20 位编码的纯计算器：<b>不碰数据库、不碰 Spring</b>。
 *
 * <p><b>为什么单独抽一个类</b>：编码对不对，与「从哪读配置」「写完发什么报文」是两个问题。
 * 把段回落、拼位、发号收在一个无状态工具里，才能用单测把「同一输入永远同一输出」钉死；
 * 一旦混进 {@code reapply} 的事务里，任何改规则的动作都得先起一套库。
 *
 * <p><b>位宽（GB/T 28181）</b>
 * <pre>
 * 20 位 = 省(2) + 市(2) + 县(4) + 行业(2) + 类型(3) + 网络(1) + 序号(6)
 * </pre>
 * 「前 14 位」= 省市区 + 行业 + 类型 + 网络，是「同一类对象的编码前缀」；
 * 序号在<b>同一前缀下全库递增</b>，不按组织各自计数——上级只认最终 20 位唯一，
 * 号段是内部规划习惯，按组织独立发号反而会撞全局唯一键。
 */
public final class CascadeCodeBuilder {

    /** 国标编码总长度 */
    public static final int    CODE_LENGTH       = 20;
    /** 中心编码段长度：省(2) + 市(2) + 县(4) */
    public static final int    CENTER_LENGTH     = 8;
    /** 中心编码 + 行业：本地编码的「前 10 位」，跨组织固定 */
    public static final int    PREFIX_LENGTH     = 10;
    /** 类型段长度：215 / 216 / 132 / 131 */
    public static final int    TYPE_LENGTH       = 3;
    /** 序号段长度 */
    public static final int    SEQ_LENGTH        = 6;
    /** 序号段上限：6 位十进制 */
    public static final int    MAX_SEQ           = 999_999;
    /** 网络标识默认值 */
    public static final String DEFAULT_NETWORK   = "0";
    /** 组织类型默认值：业务分组 */
    public static final String DEFAULT_ORG_TYPE  = "215";
    /** 设备类型位 */
    public static final String DEVICE_TYPE       = "132";
    /** 通道类型位 */
    public static final String CHANNEL_TYPE      = "131";

    private CascadeCodeBuilder() {
    }

    /**
     * 编码段：省 / 市 / 县 / 行业 / 类型 / 网络。
     *
     * @param province 省 2 位
     * @param city     市 2 位
     * @param county   县区 4 位
     * @param industry 行业 2 位
     * @param type     类型 3 位
     * @param network  网络标识 1 位
     */
    public record Segments(String province, String city, String county, String industry, String type,
        String network) {

        /** 中心编码 8 位（省市区），落 prefix 前 8 位 */
        public String center() {
            return province + city + county;
        }

        /** 前 10 位：中心编码 + 行业。设备与通道沿用这一段，只换类型与序号 */
        public String prefix() {
            return center() + industry;
        }

        /** 前 14 位：前 10 位 + 类型 + 网络，序号在它之下递增 */
        public String head() {
            return prefix() + type + network;
        }

        /** 换类型位，其余不变（设备 132 / 通道 131 都从组织的段派生） */
        public Segments withType(String newType) {
            return new Segments(province, city, county, industry, newType, network);
        }

        public boolean isComplete() {
            return province != null && city != null && county != null && industry != null && type != null
                && network != null;
        }
    }

    /**
     * 段回落：<b>自身 → 父组织 → 全局默认</b>，逐段独立回落。
     *
     * <p>逐段而不是整体：现场普遍只改「市/县」这一段，逐级重填四段没人受得了；
     * 而组织树整体换行政区时，改一次根节点就能让整棵子树跟着走。
     *
     * @param self   自身配置（可空）
     * @param parent 父组织配置（可空）
     * @param global 全局兜底（来自 {@code gateway.cascade.code-prefix} 等配置，可空）
     */
    public static Segments resolve(Segments self, Segments parent, Segments global) {
        return new Segments(
            pick(self == null ? null : self.province(), parent == null ? null : parent.province(),
                global == null ? null : global.province()),
            pick(self == null ? null : self.city(), parent == null ? null : parent.city(),
                global == null ? null : global.city()),
            pick(self == null ? null : self.county(), parent == null ? null : parent.county(),
                global == null ? null : global.county()),
            pick(self == null ? null : self.industry(), parent == null ? null : parent.industry(),
                global == null ? null : global.industry()),
            pick(self == null ? null : self.type(), parent == null ? null : parent.type(),
                global == null ? null : global.type()),
            pick(self == null ? null : self.network(), parent == null ? null : parent.network(),
                global == null ? null : global.network()));
    }

    private static String pick(String self, String parent, String fallback) {
        return StringUtils.firstNonBlank(normalize(self), normalize(parent), normalize(fallback));
    }

    /** 去空白；空值返回 null，交给回落链 */
    private static String normalize(String raw) {
        return StringUtils.trimToNull(raw);
    }

    /**
     * 全局兜底段：{@code gateway.cascade.code-prefix} 是 10 位「中心编码 + 行业」，
     * 拆成 省2 / 市2 / 县4 / 行业2，类型与网络取默认值。
     *
     * @param prefix  10 位前缀；长度不足或非数字时返回 null（由调用方决定报错）
     * @param orgType 组织类型位，空则 215
     */
    public static Segments fromPrefix(String prefix, String orgType) {
        String value = StringUtils.trimToNull(prefix);
        if (value == null || value.length() < PREFIX_LENGTH || !isDigits(value)) {
            return null;
        }
        return new Segments(value.substring(0, 2), value.substring(2, 4), value.substring(4, 8),
            value.substring(8, 10), StringUtils.defaultIfBlank(orgType, DEFAULT_ORG_TYPE), DEFAULT_NETWORK);
    }

    /**
     * 拼出 20 位：{@code head + %06d(seq)}。
     *
     * @param head 前 14 位
     * @param seq  序号，1..999999
     * @throws IllegalArgumentException 段位宽不对或序号越界
     */
    public static String join(String head, int seq) {
        if (StringUtils.isBlank(head) || head.length() != CODE_LENGTH - SEQ_LENGTH || !isDigits(head)) {
            throw new IllegalArgumentException("编码前 14 位非法: " + head);
        }
        if (seq < 0 || seq > MAX_SEQ) {
            throw new IllegalArgumentException("序号超出 6 位范围: " + seq);
        }
        return head + String.format("%06d", seq);
    }

    /**
     * 下一个可用序号：<b>同一 head 下全库最大值 + 1</b>，且不小于 {@code startSeq}。
     *
     * <p>为什么不按组织号段独立计数：唯一键是全局的，两个组织各自从号段起点递增照样会撞，
     * 撞了只能重试，最终还是要退回到「看全局最大」。
     *
     * <p>为什么不复用已删除对象的序号：编号一旦上报过，复用会让上级把两个不同对象的
     * 历史记录混到一起，宁可留空洞。
     *
     * <p><b>{@code head} 允许 13 位</b>（前缀 + 类型，即不含网络位）：历史组织编码是按
     * 「前 13 位匹配 + 跳过 1 位网络位」发号的，库里可能存在网络位非 0 的记录。
     * 匹配口径一旦收紧到 14 位，那些编码就会漏出计数范围，新号立刻与它们撞车。
     *
     * @param existing 同 head 下已有的编码（可包含脏数据，非 20 位或前缀不符的会被跳过）
     * @param head     前 13 位（忽略网络位）或前 14 位
     * @param startSeq 号段起点下界，{@code null} 或 {@code <=0} 表示不设下界
     */
    public static int nextSeq(Collection<String> existing, String head, Integer startSeq) {
        int max = 0;
        if (existing != null) {
            for (String code : existing) {
                int seq = seqOf(code, head);
                if (seq > max) {
                    max = seq;
                }
            }
        }
        int floor = startSeq == null || startSeq < 0 ? 0 : startSeq;
        return Math.max(max + 1, floor);
    }

    /**
     * 从 20 位编码里取序号；前缀不符或非数字返回 -1（脏数据不参与计数）。
     *
     * <p>序号<b>恒取末 6 位</b>，不由 {@code head} 长度推算：{@code head} 传 13 位时
     * 第 14 位是网络标识，它不参与发号，却也不能被当成序号的一部分算进最大值。
     *
     * @param head 前 13 位（忽略网络位）或前 14 位；其他长度视为调用方用错，直接返回 -1
     */
    public static int seqOf(String code, String head) {
        if (!isGbCode(code) || StringUtils.isBlank(head) || !code.startsWith(head)) {
            return -1;
        }
        int headLength = head.length();
        if (headLength != CODE_LENGTH - SEQ_LENGTH && headLength != CODE_LENGTH - SEQ_LENGTH - 1) {
            return -1;
        }
        String tail = code.substring(CODE_LENGTH - SEQ_LENGTH);
        return isDigits(tail) ? Integer.parseInt(tail) : -1;
    }

    /** 是否 20 位纯数字的国标编码 */
    public static boolean isGbCode(String code) {
        return code != null && code.length() == CODE_LENGTH && isDigits(code);
    }

    /**
     * 对象「自己的码」（本地码）：ORG/DEVICE 就是业务键本身，CHANNEL 取业务键的通道段。
     *
     * <p>通道的业务键是 {@code deviceId:channelId} 拼接而成，直接拿去上报会把设备段带上；
     * 用 {@link PublishKeys#channelChannelId} 拆而不是 {@code split(":")}：GB28181 的
     * {@code deviceId} 本身可能含冒号，按第一个分隔符切是唯一不歧义的读法。
     *
     * @param objectType 发布对象类型，大小写不敏感
     * @param objectKey  对象业务键
     */
    public static String localCodeOf(String objectType, String objectKey) {
        return PublishObjectType.of(objectType) == PublishObjectType.CHANNEL
            ? PublishKeys.channelChannelId(objectKey)
            : objectKey;
    }

    /**
     * 上级眼里的生效码：<b>发布行覆盖优先，未覆盖时回落「自己的码」</b>。
     *
     * <p><b>为什么必须收在一个函数里</b>：目录组装、{@code DEL} 报文、平台内查重、列表展示
     * 都要求同一个答案；各处各写一次 {@code defaultIfBlank}，早晚会有一处按列值、一处按解析值，
     * 表现为「页面显示的码」与「报文发出的码」不同，而报文是单向的，没有回滚通道。
     *
     * <p>只算不改写：生成与固化在 {@code CascadeCodeResolver}，DEL/撤回这类路径只能读。
     *
     * @param publishCode 发布行上的编码覆盖，可空
     * @param localCode   对象自己的码，可空
     */
    public static String effectiveCode(String publishCode, String localCode) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(publishCode), localCode);
    }

    private static boolean isDigits(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
