package io.github.lunasaw.voglander.manager.code;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 编码覆盖动作的结果：既要能预览给用户看，也要能直接驱动上级侧的 {@code DEL}/{@code ADD} 报文。
 *
 * <p>{@link Change#getBefore()} 与 {@link Change#getAfter()} 成对出现是有意设计：只报「新编码」
 * 的话，调用方推 {@code DEL} 时已经没有旧编码可用了。
 */
@Data
public class CascadeCodeReapplyResult {

    /** 规则是否启用：{@code false} 时本结果必然 {@code applied=false}。 */
    private boolean              ruleEnabled;

    /** 是否为预览（未落库）。 */
    private boolean              dryRun;

    /** 是否真的写了库。预览或规则未启用时恒为 {@code false}。 */
    private boolean              applied;

    /** 编码变更明细，按 组织 → 设备 → 通道 顺序排列。 */
    private List<Change>         changes   = new ArrayList<>();

    /** 平台内查重冲突：同一平台上两个对象重算后编码相同，出现即整批回滚。 */
    private List<String>         conflicts = new ArrayList<>();

    /** 给用户看的一句话结论，可直接回显。 */
    private String               note;

    /** 规则未启用（或本次没做任何事）：空结果，不落库、不发报文。 */
    public static CascadeCodeReapplyResult skipped(boolean ruleEnabled, boolean dryRun, String note) {
        CascadeCodeReapplyResult result = new CascadeCodeReapplyResult();
        result.setRuleEnabled(ruleEnabled);
        result.setDryRun(dryRun);
        result.setApplied(false);
        result.setNote(note);
        return result;
    }

    /** 单个对象的编码变更。 */
    @Data
    public static class Change {

        /** {@code ORG} / {@code DEVICE} / {@code CHANNEL}，与发布行 {@code object_type} 同口径。 */
        private String objectType;

        /** 业务键：组织编码、设备 ID 或 {@code 设备ID:通道ID}。 */
        private String objectKey;

        /** 改之前的编码。 */
        private String before;

        /** 改之后的编码。 */
        private String after;
    }
}
