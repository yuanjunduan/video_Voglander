package io.github.lunasaw.voglander.manager.code;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 批量补齐上报编码的结果：预览与落库共用一份出参。
 *
 * <p><b>为什么不复用 {@link CascadeCodeReapplyResult}</b>：那个结果描述的是「一棵组织子树按新规则重算」，
 * 入口带着 {@code orgId}、出参需要 {@code before/after} 成对（要推 {@code DEL}）。
 * 补码没有旧值（本来就是空的），也没有子树概念，混用一个类会让两类调用方各自忽略一半字段。
 * 复用它的 {@link CascadeCodeReapplyResult.Change}：变更明细的形状完全相同。
 *
 * @author luna
 */
@Data
public class CascadeCodeBatchFillResult {

    /** 上级平台编码 */
    private String                          platformId;

    /** {@code ORG} / {@code DEVICE} / {@code CHANNEL} */
    private String                          objectType;

    /** 编码规则总闸是否开启：{@code false} 时本结果必然 {@code applied=false}。 */
    private boolean                         ruleEnabled;

    /** 是否为预览（未落库）。 */
    private boolean                         dryRun;

    /** 是否真的写了库。预览、总闸关闭或无候选行时恒为 {@code false}。 */
    private boolean                         applied;

    /** 补码明细，{@code before} 恒为空（这些行原本没有码）。 */
    private List<CascadeCodeReapplyResult.Change> changes = new ArrayList<>();

    /** 未能补码的行：号段耗尽、落库异常等，逐条列全而不是中断整批。 */
    private List<String>                    conflicts = new ArrayList<>();

    /**
     * 因人工码受保护而跳过的行数（{@code code_source} 非 AUTO 且已配码）。
     *
     * <p>这些行本来就不需要补码，单独计数是为了让用户看得出「规则没有漏掉它们」；
     * 只报「补了 0 行」会让现场以为规则没生效。
     */
    private int                             manualProtected;

    /** 给用户看的一句话结论，可直接回显。 */
    private String                          note;
}
