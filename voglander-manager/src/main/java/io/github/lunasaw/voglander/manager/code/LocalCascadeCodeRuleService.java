package io.github.lunasaw.voglander.manager.code;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder.Segments;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager.OrgCodeRule;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 编码规则的默认实现：<b>开关关闭时一律回本地编码</b>，打开后按组织的七段配置派生。
 *
 * <p>本地资产表里本来就有一份编码（组织 {@code org_code} / 设备 {@code device_id} /
 * 通道 {@code channel_id}），规则关闭时它就是唯一答案。
 *
 * <p><b>通道编码为什么要读已有发布行</b>：同一路通道可以推给多个上级，如果每推一个平台就
 * 重新发一次号，同一个对象在不同上级那里会有不同编码。上级侧对账、故障回溯、跨平台核查
 * 全都对不上。所以先看这个业务键下是否已经有国标编码，有就复用；没有才发新号。
 *
 * <p>刻意不做缓存：编码会被目录同步（设备/通道重建）与人工覆盖改写，缓存一份只会让
 * 「上级看到的编码」与库里不一致，而这类问题排查成本极高。
 */
@Slf4j
@Component
public class LocalCascadeCodeRuleService implements CascadeCodeRuleService {

    /** 规则未启用时的统一说明，调用方可直接回显给用户。 */
    public static final String DISABLED_NOTE = "编码规则未启用：本次未重算，仍使用本地编码";

    /**
     * 该组织没开「按规则生成编码」时的说明。
     *
     * <p>与 {@link #DISABLED_NOTE} 分开：两者的 {@code changes} 都是空，混成一句话用户会去查
     * 一个没问题的总闸，而真正要打开的是组织上的那个开关。
     */
    public static final String ORG_DISABLED_NOTE = "该组织未启用「按规则生成编码」：本次未重算";

    @Autowired
    private CascadeOrgManager     cascadeOrgManager;

    @Autowired
    private CascadePublishManager cascadePublishManager;

    @Autowired
    private DeviceManager         deviceManager;

    @Autowired
    private DeviceChannelManager  deviceChannelManager;

    /**
     * 编码规则总闸，默认关闭。
     *
     * <p>与组织级 {@code code_rule_enabled} 是「与」的关系：总闸决定这套规则在本实例上
     * 是否成立，组织开关决定具体哪个组织放行。升级后默认关闭，上级目录里的编码一个字节都不变。
     */
    @Value("${gateway.cascade.code-rule.enabled:false}")
    private boolean codeRuleEnabled;

    @Override
    public boolean enabled() {
        return codeRuleEnabled;
    }

    /**
     * 组织编码：规则启用后，组织编码在<b>创建时</b>就按七段生成并落库，所以这里仍返回
     * {@code org_code}——重算属于覆盖动作，不在查询路径上做。
     */
    @Override
    public String orgCode(Long orgId) {
        if (orgId == null) {
            return null;
        }
        CascadeOrgDTO org = cascadeOrgManager.getById(orgId);
        return org == null ? null : org.getOrgCode();
    }

    /**
     * 设备编码。
     *
     * <p><b>规则启用后仍回本地 {@code device_id}</b>：设备层目前没有稳定的落库点
     * （仓库里还没有创建 {@code DEVICE} 发布行的入口），现算出来的序号下次调用就可能变，
     * 拿它去上报等于给上级一个漂移的编码。等设备层发布入口落地后再接进来。
     */
    @Override
    public String deviceCode(Long deviceId) {
        if (deviceId == null) {
            return null;
        }
        DeviceDTO device = deviceManager.getDeviceDTOById(deviceId);
        return device == null ? null : device.getDeviceId();
    }

    /**
     * 通道编码：规则启用且所属组织放行时，按「组织段 + 类型 131 + 全库号池」派生。
     *
     * <p>序号只在<b>首次</b>分配：已经有编码的通道（任一平台的 {@code publish_code}）直接复用，
     * 不再走进号池逻辑，否则同一条通道重算两次就会拿到两个号。
     */
    @Override
    public String channelCode(Long channelId) {
        if (channelId == null) {
            return null;
        }
        DeviceChannelDTO channel = deviceChannelManager.getDeviceChannelDTOById(channelId);
        if (channel == null) {
            return null;
        }
        if (!codeRuleEnabled) {
            return channel.getChannelId();
        }
        OrgCodeRule rule = cascadeOrgManager.effectiveCodeRule(resolveOrgId(channel));
        if (rule == null) {
            /* 组织没开规则：通道跟着组织走本地编码，避免同一组织下一半国标一半本地 */
            return channel.getChannelId();
        }

        String objectKey = PublishKeys.channel(channel.getDeviceId(), channel.getChannelId());
        String reusable = reusableCode(objectKey);
        if (reusable != null) {
            return reusable;
        }

        Segments segments = rule.segments().withType(CascadeCodeBuilder.CHANNEL_TYPE);
        int seq = CascadeCodeBuilder.nextSeq(channelCodePool(), segments.prefix() + segments.type(),
            rule.startSeq());
        String code = CascadeCodeBuilder.join(segments.head(), seq);
        log.info("通道编码按规则分配: channelId={}, objectKey={}, code={}", channel.getChannelId(), objectKey, code);
        return code;
    }

    /**
     * 覆盖动作：按当前规则重算子树内的组织编码，{@code dryRun} 只预览。
     *
     * <p><b>只重算组织，不碰设备与通道编码</b>：通道编码一旦上报过，就是上级侧历史录像、
     * 订阅、点播的引用键，跟着组织重编码一起换会让每一路摄像机在上级那里变成「新设备」。
     * 但通道发布行的 {@code parent_key} 会同步到新组织编码，否则它们会挂到不存在的父节点下。
     *
     * <p><b>冲突整批拒绝</b>：唯一的例外是一次写入会同时动 {@code org_path}、发布行
     * {@code object_key} 与通道 {@code parent_key} 三处结构，部分成功会留下一个从外部看
     * 「编码换了、结构没换」的目录，而报文是单向的，没有回滚通道。
     */
    @Override
    public CascadeCodeReapplyResult reapply(Long orgId, boolean dryRun) {
        Assert.notNull(orgId, "orgId 不能为空");
        Assert.notNull(cascadeOrgManager.getById(orgId), "组织不存在: id=" + orgId);
        if (!codeRuleEnabled) {
            log.debug("编码规则未启用，覆盖动作空转: orgId={}, dryRun={}", orgId, dryRun);
            return CascadeCodeReapplyResult.skipped(false, dryRun, DISABLED_NOTE);
        }
        /* 总闸开着但该组织没放行：子树的节点全部会被跳过，不能让用户误以为「已经合规」 */
        if (!cascadeOrgManager.codeRuleEnabled(orgId)) {
            log.debug("组织未启用编码规则，覆盖动作空转: orgId={}, dryRun={}", orgId, dryRun);
            return CascadeCodeReapplyResult.skipped(false, dryRun, ORG_DISABLED_NOTE);
        }

        CascadeOrgManager.CodeReapplyPlan plan = cascadeOrgManager.planCodeReapply(orgId);

        CascadeCodeReapplyResult result = new CascadeCodeReapplyResult();
        result.setRuleEnabled(true);
        result.setDryRun(dryRun);
        result.setConflicts(plan.conflicts());
        for (CascadeOrgManager.OrgCodeChange change : plan.changes()) {
            if (!change.changed()) {
                continue;
            }
            CascadeCodeReapplyResult.Change row = new CascadeCodeReapplyResult.Change();
            row.setObjectType(PublishObjectType.ORG.name());
            row.setObjectKey(change.before());
            row.setBefore(change.before());
            row.setAfter(change.after());
            result.getChanges().add(row);
        }

        if (plan.hasConflict()) {
            result.setApplied(false);
            result.setNote("存在 " + plan.conflicts().size() + " 处编码冲突，已整批拒绝，未写入任何数据");
            return result;
        }
        if (result.getChanges().isEmpty()) {
            result.setApplied(false);
            result.setNote("当前组织编码已符合规则，无需重算");
            return result;
        }
        if (dryRun) {
            result.setApplied(false);
            result.setNote("预览：" + result.getChanges().size() + " 个组织将重编码，未写入任何数据");
            return result;
        }

        cascadeOrgManager.applyCodeReapply(plan);
        result.setApplied(true);
        result.setNote("已重算 " + result.getChanges().size() + " 个组织编码，通道编码保持稳定");
        return result;
    }

    /** 通道的组织归属：自身显式归属优先，其次跟随所属设备 */
    private Long resolveOrgId(DeviceChannelDTO channel) {
        if (channel.getOrgId() != null) {
            return channel.getOrgId();
        }
        DeviceDO device = deviceManager.getByDeviceId(channel.getDeviceId());
        return device == null ? null : device.getOrgId();
    }

    /**
     * 这个业务键下已经存在的国标编码，没有返回 {@code null}。
     *
     * <p>非国标值（本地 {@code channel_id} 原样写进发布行）不算可复用编码：它本来就不是按
     * 七段生成的，拿它当答案等于规则没生效。
     */
    private String reusableCode(String objectKey) {
        for (CascadePublishDTO row : cascadePublishManager.listByObject(PublishObjectType.CHANNEL.name(),
            objectKey)) {
            if (CascadeCodeBuilder.isGbCode(row.getPublishCode())) {
                return row.getPublishCode();
            }
        }
        return null;
    }

    /**
     * 通道号码池：全库已发布编码 + 本地通道标识。
     *
     * <p>本地标识也要进池——历史数据里通道标识本身就是 20 位国标编码，只数发布行会漏掉它们，
     * 新号就会与之撞车（撞的是上级目录里的重号，而不是本地唯一键，更难发现）。
     */
    private List<String> channelCodePool() {
        List<String> pool = new ArrayList<>();
        for (CascadePublishDTO row : cascadePublishManager.listByType(PublishObjectType.CHANNEL.name(), false)) {
            if (StringUtils.isNotBlank(row.getPublishCode())) {
                pool.add(row.getPublishCode());
            }
        }
        for (DeviceChannelDO local : deviceChannelManager.listOrgLinkProjection()) {
            if (StringUtils.isNotBlank(local.getChannelId())) {
                pool.add(local.getChannelId());
            }
        }
        return pool;
    }
}
