package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 通道批量推送结果（Manager 出参）。
 *
 * <p><b>为什么要逐类计数而不是只回「成功数」</b>：一次批量推送里，同一个动作对不同的行结果并不相同——
 * 有的新建、有的早就推过、有的推送行被人工停报、还有的因为组织不在该平台目录里被拒。
 * 只回一个数字，用户面对「推了 50 条只多了 30 条」时无从判断是重复还是失败。
 *
 * @author luna
 */
@Data
public class CascadeChannelBatchPublishResultDTO {

    /** 本次实际处理的本地通道数（去重后） */
    private int          channelCount;

    /** 目标平台数 */
    private int          platformCount;

    /** 新建的发布行数（通道 × 平台） */
    private int          created;

    /** 该平台早已配置且正在上报，跳过 */
    private int          skippedEnabled;

    /**
     * 该平台早已配置但处于停报状态，跳过。
     *
     * <p><b>只跳过，不自动改回上报</b>：停报往往是上级要求下线，批量动作静默推翻它是越权。
     * 要恢复请在通道详情里单独开启（那里会一并校验组织可见性）。
     */
    private int          skippedDisabled;

    /** 逐条失败明细（推送行没建出来，原因照原样带回） */
    private List<Failure> failures = new ArrayList<>();

    /**
     * 单条失败：定位到「哪条通道 × 哪个平台」，以及为什么没推上去。
     */
    @Data
    public static class Failure {

        private String localDeviceId;

        private String localChannelId;

        /** 目标平台编码；为空表示这条通道在解析阶段就没找到（与平台无关） */
        private String platformId;

        private String reason;
    }
}
