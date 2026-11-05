package io.github.lunasaw.voglander.common.constant.cascade;

/**
 * GB28181 级联（下级平台）相关常量。
 *
 * <p>本平台作为级联下级平台对接上级时使用：注册保活、上级订阅、录像转查等。
 *
 * @author luna
 */
public interface CascadeConstant {

    /** 级联注册状态（对齐 tb_cascade_platform.register_status） */
    interface RegisterStatus {
        int OFFLINE     = 0;
        int ONLINE      = 1;
        int REGISTERING = 2;
        int FAILED      = 3;
    }

    /**
     * 发布对象类型（对齐 tb_cascade_publish.object_type）。
     *
     * <p>共表而不是每类一张，是为了让 {@code UNIQUE (platform_id, publish_code)} 能在库级拦住
     * <b>跨对象类型</b>的重码——上级目录按 DeviceID 建索引，组织码与通道码撞了同样是丢节点。
     */
    enum PublishObjectType {

        ORG,
        DEVICE,
        CHANNEL;

        /** 归一化：入参大小写不一致时统一成大写，非法值返回 null */
        public static PublishObjectType of(String type) {
            if (type == null || type.isBlank()) {
                return null;
            }
            for (PublishObjectType each : values()) {
                if (each.name().equalsIgnoreCase(type.trim())) {
                    return each;
                }
            }
            return null;
        }
    }

    /** 订阅类型（与 GB28181 Event 头映射） */
    enum SubType {

        CATALOG("Catalog"),
        ALARM("presence.alarm"),
        MOBILE_POSITION("presence.mobile");

        private final String event;

        SubType(String event) {
            this.event = event;
        }

        public String event() {
            return event;
        }
    }

    /** 订阅状态（对齐 tb_cascade_subscribe.status） */
    interface SubStatus {
        int EXPIRED = 0;
        int ACTIVE  = 1;
    }

    /**
     * 发布到某个平台时，取消该组织发布后其下通道的处置策略（对齐 tb_cascade_publish.revoke_policy）。
     *
     * <p>两种策略在协议动作上完全不同，事后无法从 {@code enabled} 反推，因此必须落库：
     * 若只靠“组织不在生效集合就挂根节点”的兜底逻辑，通道会默认走上浮，
     * 与用户「一并取消」的选择冲突。
     *
     * <p>策略属于<b>发布层</b>：同一组织推给两个上级，可以一边连带删通道、一边让通道上浮，
     * 因此它跟着 {@code (platform_id, object_key)} 走，而不是挂在本地组织上。
     */
    interface RevokePolicy {

        /** 通道跟随组织一起从上级目录移除，恢复组织时一起回来（默认） */
        String CASCADE = "CASCADE";

        /** 组织移除，其下通道改挂域节点继续上报 */
        String REROUTE = "REROUTE";

        /** 是否合法取值（空值按默认处理，不算非法） */
        static boolean isValid(String policy) {
            return policy == null || policy.isBlank() || CASCADE.equals(policy) || REROUTE.equals(policy);
        }

        /** 归一化：空值或非法值一律回落 CASCADE */
        static String normalize(String policy) {
            return REROUTE.equals(policy) ? REROUTE : CASCADE;
        }
    }

    /**
     * 上报编码来源（对齐 tb_cascade_publish.code_source）。
     *
     * <p>区分它是为了「码能不能被自动改」：人工填的码一旦被规则重算，上级侧的历史录像、
     * 订阅、点播引用键全部失效，而报文没有「改码」语义，只能删旧建新。
     *
     * <p>空值表示历史数据未标记，按手工码对待（宁可不自动改，也不要默默换掉已上报的码）。
     */
    interface CodeSource {

        /** 按编码规则生成并固化 */
        String AUTO   = "AUTO";

        /** 人工填写 */
        String MANUAL = "MANUAL";

        /**
         * 是否规则生成值：<b>只有明确标成 {@code AUTO} 的才算</b>。
         *
         * <p>判定只在「列上已经有值」时使用——有值才有「能不能被改写」的问题。
         * 空值（历史数据）不算 AUTO，因此永远不会被自动逻辑改写。
         */
        static boolean isAuto(String source) {
            return source != null && AUTO.equalsIgnoreCase(source.trim());
        }

        /**
         * 是否人工值：{@code 非 AUTO 即人工}，含历史未标记的行与任何未知取值。
         *
         * <p>宁可把历史数据当成人工码（不自动改），也不要默默换掉一个已在上级生效的码——
         * 报文没有「改码」语义，改一次等于删旧建新。
         */
        static boolean isManual(String source) {
            return !isAuto(source);
        }
    }

    /**
     * 发布对象的业务键（对齐 tb_cascade_publish.object_key）。
     *
     * <p>三类对象各有自己的业务键，互相之间没有统一的主键口径，所以这里按类型分开拼：
     * 组织用 {@code org_code}、设备用 {@code device_id}、通道用 {@code device_id:channel_id}。
     * 用业务键而不是自增主键，是为了让发布关系在「设备/通道被删重建」后仍然指向正确的对象，
     * 也让上级点播能用一次查询拿到本地通道标识。
     */
    interface PublishKeys {

        /** 通道业务键的字段分隔符 */
        String CHANNEL_SEPARATOR = ":";

        /** 通道业务键：{@code deviceId:channelId} */
        static String channel(String deviceId, String channelId) {
            return deviceId + CHANNEL_SEPARATOR + channelId;
        }

        /** 从通道业务键取设备编码；非通道键或格式不对时返回 null */
        static String channelDeviceId(String objectKey) {
            int index = objectKey == null ? -1 : objectKey.indexOf(CHANNEL_SEPARATOR);
            return index <= 0 ? null : objectKey.substring(0, index);
        }

        /** 从通道业务键取通道编码；非通道键或格式不对时返回 null */
        static String channelChannelId(String objectKey) {
            int index = objectKey == null ? -1 : objectKey.indexOf(CHANNEL_SEPARATOR);
            return index <= 0 || index == objectKey.length() - 1 ? null : objectKey.substring(index + 1);
        }
    }

    /**
     * 归属变更的范围标记（{@code LocalCascadePlacementChangeEvent} 的 {@code scope}）。
     *
     * <p>与 {@link PublishObjectType} 同值但语义不同：那边是发布行的对象类型，这边是「变了什么」。
     * 两者今天恰好一一对应，但以后若出现只动通道不动设备的规则，这个值不会跟着被动。
     */
    interface PlacementScope {

        /** 设备（含其下跟随设备的通道） */
        String DEVICE  = "DEVICE";

        /** 单条通道 */
        String CHANNEL = "CHANNEL";
    }

    /** 录像查询请求状态（对齐 tb_cascade_record_request.status） */
    interface RecordReqStatus {
        int PENDING   = 0;
        int RESPONDED = 1;
        int TIMEOUT   = 2;
    }

    /** 默认订阅有效期(秒) */
    int DEFAULT_SUBSCRIBE_EXPIRES = 3600;

    /** Keepalive 心跳间隔(秒) */
    int DEFAULT_KEEPALIVE_INTERVAL = 60;

    /** 级联注册响应超时(秒)：超时仍处于 REGISTERING 则标记失败，避免状态永久卡住 */
    int DEFAULT_REGISTER_TIMEOUT_SEC = 30;

    /** 录像查询请求超时(秒)，超时清理 PENDING */
    int RECORD_REQUEST_TIMEOUT_SEC = 30;

    /** 级联 INVITE 媒体 app */
    String CASCADE_MEDIA_APP = "rtp";

    /* ---------------- 平台 extend 字段键（复用 TEXT 列，避免为此新增表结构） ---------------- */

    /** extend 中最近一次注册失败原因；空串表示当前无错误 */
    String EXTEND_LAST_ERROR    = "lastError";

    /** extend 中最近一次失败/恢复时间（Unix 毫秒） */
    String EXTEND_LAST_ERROR_AT = "lastErrorAt";

    /* ---------------- Web 入参校验正则（编译期常量，供 @Pattern 复用） ---------------- */

    /** 国标 20 位设备/平台 ID */
    String GB_ID_REGEX  = "\\d{20}";

    /** IPv4 点分十进制地址 */
    String IPV4_REGEX   = "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

    /* ---------------- 平台编码规则段位（前 14 位里可配的五段） ---------------- */

    /**
     * 平台编码规则-省/市/行业编码：2 位数字。
     *
     * <p>正则放行空串，因为更新接口用「空串=清空该段」；「启用规则时必须填满」是跨字段约束，
     * 由 {@code CascadePlatformManager} 在写入侧校验，不能靠单字段正则表达。
     *
     * <p><b>没有类型位</b>：组织 215/216、设备 132、通道 131 由对象类型决定。
     * 平台能填类型位的话，通道就会以 216 开头——这是必须避免的误导。
     */
    String CODE_SEG_2_REGEX = "^(\\d{2})?$";

    /** 平台编码规则-县区编码：4 位数字（可为空串） */
    String CODE_SEG_4_REGEX = "^(\\d{4})?$";

    /** 平台编码规则-网络标识：1 位数字（可为空串） */
    String CODE_SEG_1_REGEX = "^(\\d{1})?$";
}
