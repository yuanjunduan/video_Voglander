package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 级联通道批量推送结果 VO。
 *
 * <p>逐类计数而不是只回「成功数」：用户需要知道少掉的那些是「早就推过」还是「真的失败」——
 * 前者不用管，后者要去补组织发布或缺通道。
 *
 * @author luna
 */
@Data
public class CascadeChannelBatchPublishResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本次处理的本地通道数（去重后） */
    private Integer          channelCount;

    /** 目标平台数 */
    private Integer          platformCount;

    /** 新建的发布行数（通道 × 平台） */
    private Integer          created;

    /** 早已配置且正在上报，跳过 */
    private Integer          skippedEnabled;

    /** 早已配置但处于停报状态，跳过（不自动改回上报） */
    private Integer          skippedDisabled;

    /** 逐条失败明细 */
    private List<FailureVO>  failures;

    /**
     * 单条失败：哪条通道、哪个平台、为什么没推上去。
     */
    @Data
    public static class FailureVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private String localDeviceId;

        private String localChannelId;

        /** 目标平台；为空表示通道在解析阶段就没找到（与平台无关） */
        private String platformId;

        private String reason;
    }
}
