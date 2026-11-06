package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 级联组织批量推送请求（Web 入参）：一批本地组织 × 一批上级平台。
 *
 * <p>与 {@link CascadeOrgPublishReq} 的区别：那个是「一个组织配哪些平台，逐平台带编码/名称/策略
 * 覆盖」，本请求是「一批组织一起推」，<b>不带</b>覆盖——批量场景下逐平台填编码是一屏填不完的活，
 * 真要覆盖还得回到单条的发布抽屉里改。
 *
 * <p>也不带「通道处置策略」：它属于发布行配置，批量推送只决定「推不推」，
 * 不该顺带把用户上一轮选的策略覆盖成默认值。
 *
 * @author luna
 */
@Data
public class CascadeOrgPublishBatchReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 勾选的组织主键集合（必填）。
     *
     * <p>勾一个组织<b>只推它自己</b>，不连带子孙：那是「推整棵子树」的另一个决定，
     * 由 {@link #includeAncestors} 之外的入口（子树批量勾选）表达。
     */
    private List<Long>   ids;

    /**
     * 目标上级平台编码集合（必填，可多选）。
     *
     * <p>与单条接口同口径：未勾选的平台不会被删行，只把发布行置为停用，编码覆盖保留。
     */
    private List<String> platformIds;

    /**
     * 是否把祖先组织一并推上去；不传按 {@code true} 处理。
     *
     * <p>默认开：只勾叶子时它在上级目录里立不起来（父节点上级没有），
     * 组装器会静默剔除，页面显示「推送中」而上级一条收不到。真要不推祖先就显式传 false，
     * 此时叶子会被祖先链校验挡住并逐条返回原因，用户能明确知道还差哪一级。
     */
    private Boolean      includeAncestors;
}
