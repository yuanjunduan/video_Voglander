package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import lombok.Data;

/**
 * 级联「取消同步」请求。
 *
 * <p>只有通道处置策略一个参数，但它是<b>必须由用户显式选择</b>的：组织从上级目录消失后，
 * 其下通道只有两种归宿——跟着一起消失，或者改挂到域节点继续上报。两种归宿在协议上
 * 走的是完全不同的报文（DEL 通道 vs 改 ParentID），事后无法从 {@code enabled} 反推，
 * 因此随操作一起落库到 {@code tb_cascade_org.revoke_policy}。
 *
 * @author luna
 */
@Data
public class CascadeRevokeReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 通道处置策略：{@code CASCADE} 通道一并移除（默认）/ {@code REROUTE} 通道改挂域节点。
     *
     * <p>传空表示沿用组织上已保存的策略，两者都为空时按 {@code CASCADE} 处理。
     */
    private String policy;
}