package io.github.lunasaw.voglander.client.service.cascade;

import java.util.List;

import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;

/**
 * 向上级平台<b>撤销</b>已上报的目录节点，并给出「上级是否确认」的结论。
 *
 * <p><b>为什么放在 client</b>：调用方是 manager（删除本地资产前要先取消上报），实现方是
 * integration（SIP 报文拼装与发送）。integration 依赖 manager，反向注入就是循环依赖，
 * 所以按仓内既有约定（{@code DeviceCommandService}）把契约放在 client。
 *
 * <p><b>为什么必须是同步返回结论</b>：删除走的是事件链路（{@code LocalCascadeCatalogChangeEvent}
 * → 异步监听 → NOTIFY），异步监听者拿不到结果，也就无法回答「上级确认了吗」。
 * 而「取消成功后才可以删本地数据」这条业务规则要求删除入口能阻塞等待 SIP 2xx，
 * 因此单独开这一条同步出站通道，事件链路只服务于不要求确认的场景。
 *
 * @author luna
 */
public interface CascadeCatalogRevokeService {

    /**
     * 向指定上级发 {@code Event=DEL} 并等待其响应。
     *
     * <p>节点清单必须已按「叶子 → 根」排好：先删父会让子节点变成孤儿，部分上级会忽略
     * 对该孤儿节点的后续删除请求。
     *
     * @param platformId 上级平台编码
     * @param nodes      待撤销的目录节点；为空表示无需撤销，直接返回已确认
     * @return 撤销结论，永不为 {@code null}
     */
    RevokeResult revoke(String platformId, List<LocalCascadeCatalogChangeEvent.Node> nodes);

    /** 撤销结论的成因。 */
    enum Reason {

        /** 上级对全部节点回了 2xx：可以删本地 */
        CONFIRMED,

        /** 发布行指向的平台配置已不存在：无人可确认，放行（否则这条本地资产永远删不掉） */
        PLATFORM_ABSENT,

        /** 上级回了非 2xx：撤销被拒绝 */
        REFUSED,

        /** 等待窗口内上级没有任何响应 */
        TIMEOUT,

        /** 报文没能发出去（构造或传输异常） */
        SEND_FAILED;

        /**
         * 是否允许继续删除本地数据。
         *
         * <p>只有「上级确实拒绝」与「联系不上」才拦下来；平台配置都没了属于历史残留，
         * 拦住只会让本地资产永久无法清理，而上级那里早已没有这个节点。
         */
        public boolean satisfied() {
            return this == CONFIRMED || this == PLATFORM_ABSENT;
        }

        /** 面向用户的失败原因描述。 */
        public String describe() {
            return switch (this) {
                case CONFIRMED -> "已确认";
                case PLATFORM_ABSENT -> "平台配置已不存在";
                case REFUSED -> "上级拒绝了撤销请求";
                case TIMEOUT -> "上级未响应撤销请求";
                case SEND_FAILED -> "撤销报文发送失败";
            };
        }
    }

    /**
     * 单个平台的撤销结果。
     *
     * @param platformId 上级平台编码
     * @param reason     结论成因
     * @param statusCode 上级回应的 SIP 状态码；无回应时为 0
     * @param nodeCount  本次尝试撤销的节点数
     */
    record RevokeResult(String platformId, Reason reason, int statusCode, int nodeCount) {

        public boolean satisfied() {
            return reason.satisfied();
        }

        /** 未确认时的用户可读原因（不含平台编码）。 */
        public String failureReason() {
            String text = reason.describe();
            return reason == Reason.REFUSED && statusCode > 0 ? text + "（响应码 " + statusCode + "）" : text;
        }
    }
}
