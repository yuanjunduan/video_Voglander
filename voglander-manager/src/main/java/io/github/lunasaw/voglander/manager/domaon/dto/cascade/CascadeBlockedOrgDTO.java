package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import lombok.Data;

/**
 * 某上级平台目录里「勾了要推、却立不起来」的组织，供页面回答「为什么这个组织没上去」。
 *
 * <p><b>为什么不能只给目录内的节点</b>：{@code configurePublish} 在写入前会拦一次祖先未发布，
 * 但拦住之后存量配置仍可能处于半截状态（组织自己勾了、祖先后来被停用或被撤回）。
 * 这类组织在 {@code platformOrgView} 里被静默剔除——它们不出现在目录里，
 * 页面上也就没有任何入口提示「你还勾着它，但上级看不到」。
 *
 * <p>阻断节点可能是它自己（本地停用），也可能是某个祖先（本地停用或未发布）：
 * 两者对上级的结果一样，但用户要修的地方完全不同，因此必须把阻断节点报出来。
 */
@Data
public class CascadeBlockedOrgDTO {

    /** 自己本地停用：打开本地启停即可，与发布配置无关 */
    public static final String REASON_LOCAL_DISABLED = "LOCAL_DISABLED";

    /** 祖先本地停用：上级目录里它整棵子树早就被 DEL 了，得先启用那一级 */
    public static final String REASON_ANCESTOR_LOCAL_DISABLED = "ANCESTOR_LOCAL_DISABLED";

    /** 祖先未发布到该平台：祖先没有 enabled=1 的发布行，叶子再怎么勾也挂不上去 */
    public static final String REASON_ANCESTOR_NOT_PUBLISHED = "ANCESTOR_NOT_PUBLISHED";

    /** 本地组织主键（{@code tb_cascade_org.id}） */
    private Long id;

    /** 本地组织编码 */
    private String localCode;

    /** 本地组织名称 */
    private String name;

    /** 阻断节点编码（含自身）：链路自上而下第一个让它在上级立不起来的组织 */
    private String blockedByCode;

    /** 阻断节点名称，报错文案直接用，不必让前端再查一次 */
    private String blockedByName;

    /** 阻断原因，取值见本类的 {@code REASON_*} 常量 */
    private String reason;

    /**
     * 阻断节点的处置策略是否为 {@code REROUTE}：为真表示该组织的通道没跟着它消失，
     * 而是改挂域节点继续上报。页面据此区分「组织没上去」与「组织没上去但业务还在」。
     */
    private Boolean rerouted;
}
