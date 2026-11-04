package io.github.lunasaw.voglander.manager.code;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.CodeSource;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder.Segments;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager.OrgCodeRule;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 上报编码解析器：<b>生成 + 固化</b>的唯一入口。
 *
 * <p><b>它解决什么</b>：同一个本地组织/通道推给不同上级，前 14 位可以完全不同（A 局 340200、
 * B 局 440300）。段位按「平台规则 → 组织规则 → 全局配置 → 硬兜底」逐段回落，
 * 序号在<b>该平台的生效码集合</b>里取下一个空位，算出来立刻写回发布行并标记
 * {@code code_source=AUTO}——<b>生成即固化</b>，下次进来自会命中已固化的值。
 *
 * <p><b>为什么必须落库</b>：现算的码会漂。号池里多进一行（新增一路通道、人工填了一个码），
 * 同一个对象的序号就可能变——上一秒上级收到 {@code ...000012}，下一秒变 {@code ...000013}，
 * 而报文没有「改码」语义，只能删旧建新，历史录像与订阅全部错位。
 *
 * <p><b>与纯读口径的分工</b>：「上级看到的码 = {@code publish_code ?: 自己的码}」这条纯计算
 * 口径在 {@link CascadeCodeBuilder#effectiveCode}，目录组装、{@code DEL} 报文、平台内查重都用它。
 * 本类只负责「还没有码时怎么给一个，并把它钉住」，<b>绝不</b>被 DEL/撤回路径调用——
 * 那些路径若就地生成新码，删的就是上级从没见过的节点。
 *
 * <p><b>开关语义</b>：总闸 {@code gateway.cascade.code-rule.enabled} 关闭时一律回本地码，
 * 升级后逐字节不变；总闸打开后，平台规则与组织规则<b>任一开启</b>即按规则生成
 * （都没开仍回本地码）。平台规则留空的段位逐段回落到组织规则与全局兜底。
 *
 * @author luna
 */
@Slf4j
@Component
public class CascadeCodeResolver {

    @Autowired
    private CascadePublishManager  cascadePublishManager;

    @Autowired
    private CascadeOrgManager      cascadeOrgManager;

    @Autowired
    private CascadePlatformManager cascadePlatformManager;

    @Autowired
    private DeviceManager          deviceManager;

    @Autowired
    private DeviceChannelManager   deviceChannelManager;

    /**
     * 编码规则总闸，默认关闭：关闭时本类完全退化成「回本地码」，不查号池、不写库。
     *
     * <p>与 {@code LocalCascadeCodeRuleService} 读的是同一个配置键：一个是「码怎么算」，
     * 一个是「算完写到哪」，不应该有两个总闸。
     */
    @Value("${gateway.cascade.code-rule.enabled:false}")
    private boolean codeRuleGlobalEnabled;

    /**
     * 取该对象在该平台的上报编码，<b>没有就生成并固化</b>。
     *
     * <p>调用前提：发布行已经存在（本方法把结果写回那一行）。发布行不存在时不做生成、
     * 直接回本地码——没有落库位就算出码，下次调用必然换号，比回本地码更危险。
     *
     * <p>取到已有值时原样返回，不论是人工填的还是上次自动生成的：<b>已上报的码不自动变更</b>。
     * 要重算只能走别人主动写的入口（发布配置里改码），本方法不提供「覆盖已有编码」的能力（2.5 的人工值保护依赖这一点）。
     *
     * @param platformId 上级平台编码
     * @param objectType ORG / DEVICE / CHANNEL
     * @param objectKey  对象业务键
     * @return 上报编码；规则未启用或发布行缺失时为本地码
     * @throws IllegalStateException 号段耗尽（同一前 14 位下 6 位序号已用尽）
     */
    public String resolve(String platformId, String objectType, String objectKey) {
        PublishObjectType type = PublishObjectType.of(objectType);
        Assert.notNull(type, "非法的发布对象类型: " + objectType);
        Assert.hasText(platformId, "platformId 不能为空");
        String key = StringUtils.trimToNull(objectKey);
        Assert.hasText(key, "objectKey 不能为空");

        String localCode = CascadeCodeBuilder.localCodeOf(type.name(), key);
        CascadePublishDTO row = cascadePublishManager.get(platformId, type.name(), key);
        if (row == null) {
            log.warn("发布行不存在，编码无处固化，本次回退本地码: platformId={}, objectType={}, objectKey={}",
                platformId, type, key);
            return localCode;
        }
        String frozen = StringUtils.trimToNull(row.getPublishCode());
        if (frozen != null) {
            return frozen;
        }
        if (!codeRuleGlobalEnabled) {
            return localCode;
        }

        Generation generation = generationOf(platformId, type, key);
        if (generation == null) {
            /* 两道规则都没开：与升级前一致，回本地码且不写库 */
            return localCode;
        }
        Set<String> used = new LinkedHashSet<>(effectiveCodePool(platformId));
        Allocated allocated = allocateAndFreeze(row, generation, used);
        if (allocated.occupied()) {
            /* 生成期间该行被写了码（通常是人工填写）：以库里现值回话，不能覆盖 */
            CascadePublishDTO latest = cascadePublishManager.get(platformId, type.name(), key);
            String current = latest == null ? null : StringUtils.trimToNull(latest.getPublishCode());
            return current != null ? current : localCode;
        }
        if (allocated.code() == null) {
            throw new IllegalStateException("号段耗尽：前缀 " + generation.segments().head()
                + " 下 6 位序号已用尽，请调整前缀或起始序号");
        }
        return allocated.code();
    }

    /**
     * 对一组平台逐个 {@link #resolve}：组织建行/发布保存后一次补齐。
     *
     * <p>只处理传入的平台，不扫全库：调用方刚改的就是这几家。
     * 单个平台失败（号段耗尽等）不影响其它平台——发布关系已经建好，码下次进来自会补上，
     * 不能因为一家平台把前缀配错就让整次保存回滚。
     *
     * @return platformId → 上报编码；取不到码的平台不出现在结果里
     */
    public Map<String, String> resolveAll(String objectType, String objectKey, Collection<String> platformIds) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (platformIds == null || platformIds.isEmpty()) {
            return resolved;
        }
        for (String each : platformIds) {
            String platformId = StringUtils.trimToNull(each);
            if (platformId == null) {
                continue;
            }
            try {
                resolved.put(platformId, resolve(platformId, objectType, objectKey));
            } catch (IllegalStateException e) {
                log.warn("级联编码生成失败，跳过该平台: platformId={}, objectType={}, objectKey={}, cause={}",
                    platformId, objectType, objectKey, e.getMessage());
            }
        }
        return resolved;
    }

    /**
     * 批量补齐某平台某类型下「还没有上报编码」的发布行。
     *
     * <p><b>为什么需要单独一支</b>：入口改造只覆盖「新建」，存量行（1.10.2 迁上来的通道、
     * 先建关系后开规则的平台）永远等不到生成时机，规则开了它们仍然是逐个现算的本地码。
     *
     * <p><b>{@code dryRun} 预览也必须占号</b>：同一批里多行共享一个号池，预览不算号就会给多行
     * 返回同一个编码，用户照着确认反而制造冲突。
     *
     * <p>只补 {@code publish_code} 为空的行：已固化的码与人工码都不动。
     * 人工行（{@code code_source} 非 AUTO）另外计数到 {@code manualProtected}，
     * 让用户看得出「规则没有漏掉它们」；平台与归属组织都没开规则的行直接跳过——
     * 它们本来就该用本地码，补了反而是新码。
     *
     * @param platformId 上级平台编码
     * @param objectType ORG / DEVICE / CHANNEL
     * @param dryRun     true=只预览不落库
     */
    public CascadeCodeBatchFillResult batchFill(String platformId, String objectType, boolean dryRun) {
        PublishObjectType type = PublishObjectType.of(objectType);
        Assert.notNull(type, "非法的发布对象类型: " + objectType);
        Assert.hasText(platformId, "platformId 不能为空");

        CascadeCodeBatchFillResult result = new CascadeCodeBatchFillResult();
        result.setPlatformId(platformId);
        result.setObjectType(type.name());
        result.setDryRun(dryRun);
        if (!codeRuleGlobalEnabled) {
            result.setNote("编码规则总闸未开启（gateway.cascade.code-rule.enabled），本次未补码");
            return result;
        }
        result.setRuleEnabled(true);

        Set<String> used = new LinkedHashSet<>(effectiveCodePool(platformId));
        for (CascadePublishDTO row : cascadePublishManager.listByPlatform(platformId, type.name(), false)) {
            if (StringUtils.isNotBlank(row.getPublishCode())) {
                /* 有值就不补；其中非 AUTO 的是人工值（含历史未标记行），单独计数：
                   不然用户会以为批量补齐漏了一批行 */
                if (CodeSource.isManual(row.getCodeSource())) {
                    result.setManualProtected(result.getManualProtected() + 1);
                }
                continue;
            }
            Generation generation = generationOf(platformId, type, row.getObjectKey());
            if (generation == null) {
                continue;
            }
            if (dryRun) {
                String preview = nextFreeCode(generation, used);
                if (preview == null) {
                    result.getConflicts().add("号段耗尽: objectKey=" + row.getObjectKey()
                        + ", 前缀=" + generation.segments().head());
                    continue;
                }
                used.add(preview);
                result.getChanges().add(change(type, row.getObjectKey(), preview));
                continue;
            }
            try {
                Allocated allocated = allocateAndFreeze(row, generation, used);
                if (allocated.occupied()) {
                    /* 预览与落库之间该行被写了码：回读来源再计数——并发生成方刚写的 AUTO
                       不是人工码，计进 manualProtected 会让页面误报「有人手工改过」 */
                    CascadePublishDTO current = cascadePublishManager.get(platformId, type.name(),
                        row.getObjectKey());
                    if (current != null && CodeSource.isManual(current.getCodeSource())) {
                        result.setManualProtected(result.getManualProtected() + 1);
                    }
                    continue;
                }
                if (allocated.code() == null) {
                    result.getConflicts().add("号段耗尽: objectKey=" + row.getObjectKey()
                        + ", 前缀=" + generation.segments().head());
                    continue;
                }
                result.getChanges().add(change(type, row.getObjectKey(), allocated.code()));
            } catch (RuntimeException e) {
                /* 单行失败不断整批：其余行照样补码，失败原因回给调用方精确重试 */
                result.getConflicts().add("生成失败: objectKey=" + row.getObjectKey() + ", " + e.getMessage());
            }
        }
        result.setApplied(!dryRun && !result.getChanges().isEmpty());
        result.setNote(noteOf(result));
        return result;
    }

    /**
     * 该平台已占用的<b>生效码</b>集合（生成号池）。
     *
     * <p>含停用行：上级侧可能还留着那些节点，新号占上去就变成两个同 {@code DeviceID} 的记录。
     * 也含「未配码、按本地码上报」的对象——它们占的正是本地码，只数 {@code publish_code} 列会漏。
     *
     * <p>只取该平台的行，不做全库扫描：不同平台是独立的编码空间，跨平台的码相同不构成冲突；
     * 真要跨平台复用同一个对象时，该对象的码已经固化，不会再生成。
     *
     * @param platformId 上级平台编码
     * @return 生效码列表（可能含重复与空值，调用方自行去重）
     */
    public List<String> effectiveCodePool(String platformId) {
        List<String> pool = new ArrayList<>();
        for (CascadePublishDTO row : cascadePublishManager.listByPlatformAll(platformId)) {
            String code = CascadeCodeBuilder.effectiveCode(row.getPublishCode(),
                CascadeCodeBuilder.localCodeOf(row.getObjectType(), row.getObjectKey()));
            if (StringUtils.isNotBlank(code)) {
                pool.add(code);
            }
        }
        return pool;
    }

    /** 该平台是否已启用平台级编码规则（平台配置缺失视为未启用）。 */
    public boolean platformRuleEnabled(String platformId) {
        CascadePlatformDTO platform = platformOf(platformId);
        return platform != null && platform.usePlatformCodeRule();
    }

    // ------------------------------------------------------------------ 内部

    /** 一次生成所需的上下文：段位与起始序号（号池每次现取，不缓存，避免拿旧快照发号）。 */
    private record Generation(Segments segments, Integer startSeq) {
    }

    /** 解析本次生成的段位与起始序号；平台规则与归属组织规则都没开时返回 {@code null}。 */
    private Generation generationOf(String platformId, PublishObjectType type, String key) {
        CascadePlatformDTO platform = platformOf(platformId);
        boolean platformRule = platform != null && platform.usePlatformCodeRule();
        OrgCodeRule orgRule = orgRuleOf(type, key);
        if (!platformRule && orgRule == null) {
            return null;
        }
        return new Generation(segmentsOf(platform, platformRule, orgRule, type),
            startSeqOf(platform, platformRule, orgRule));
    }

    private CascadePlatformDTO platformOf(String platformId) {
        return StringUtils.isBlank(platformId) ? null : cascadePlatformManager.getByPlatformId(platformId);
    }

    /**
     * 段位：平台规则（类型位永远是 null）→ 组织规则 → 全局兜底，逐段回落。
     *
     * <p>类型位在这里按对象类型落定：组织沿用配置的 215/216，设备 132，通道 131。
     * 平台规则里不给类型位，就是为了避免「通道码以 216 开头」这种一眼看不出错的串。
     */
    private Segments segmentsOf(CascadePlatformDTO platform, boolean platformRule, OrgCodeRule orgRule,
        PublishObjectType type) {
        Segments platformSeg = platformRule ? platformSegments(platform) : null;
        Segments orgSeg = orgRule == null ? null : orgRule.segments();
        Segments resolved = CascadeCodeBuilder.resolve(platformSeg, orgSeg, cascadeOrgManager.globalCodeSegments());
        if (PublishObjectType.DEVICE == type) {
            return resolved.withType(CascadeCodeBuilder.DEVICE_TYPE);
        }
        if (PublishObjectType.CHANNEL == type) {
            return resolved.withType(CascadeCodeBuilder.CHANNEL_TYPE);
        }
        return resolved;
    }

    /** 平台规则段：只取前 14 位里可配的段，类型位留空交给对象类型决定。 */
    private Segments platformSegments(CascadePlatformDTO platform) {
        return platform == null ? null
            : new Segments(platform.getCodeProvince(), platform.getCodeCity(), platform.getCodeCounty(),
                platform.getCodeIndustry(), null, platform.getCodeNetwork());
    }

    /** 号段起点：平台给了就用平台的，否则跟组织的（一个园区把起点定在 1000，它的摄像机同样成立）。 */
    private Integer startSeqOf(CascadePlatformDTO platform, boolean platformRule, OrgCodeRule orgRule) {
        Integer platformStart = platformRule && platform != null ? platform.getCodeStartSeq() : null;
        return platformStart != null ? platformStart : orgRule == null ? null : orgRule.startSeq();
    }

    /**
     * 该对象所属组织的生效规则（总闸关闭或组织未启用时返回 {@code null}）。
     *
     * <p>直接复用 {@link CascadeOrgManager#effectiveCodeRule}：它已经把「组织 → 父组织 → 全局」
     * 回落过一遍，这里再拼一次只会让两份实现慢慢分叉。
     */
    private OrgCodeRule orgRuleOf(PublishObjectType type, String objectKey) {
        Long orgId = orgIdOf(type, objectKey);
        return orgId == null ? null : cascadeOrgManager.effectiveCodeRule(orgId);
    }

    /** 对象的归属组织：组织取自身，设备取归属组织，通道优先自身归属、其次跟随设备。 */
    private Long orgIdOf(PublishObjectType type, String objectKey) {
        if (PublishObjectType.ORG == type) {
            CascadeOrgDTO org = cascadeOrgManager.getByCode(objectKey);
            return org == null ? null : org.getId();
        }
        if (PublishObjectType.DEVICE == type) {
            DeviceDO device = deviceManager.getByDeviceId(objectKey);
            return device == null ? null : device.getOrgId();
        }
        DeviceChannelDO channel = deviceChannelManager.getByChannelId(PublishKeys.channelChannelId(objectKey));
        if (channel == null) {
            return null;
        }
        if (channel.getOrgId() != null) {
            return channel.getOrgId();
        }
        DeviceDO device = deviceManager.getByDeviceId(channel.getDeviceId());
        return device == null ? null : device.getOrgId();
    }

    /**
     * 写回发布行并标记 AUTO。
     *
     * <p>撞 {@code uk_publish_code} 才换号重试；行上已有码说明读与写之间被人工写了，
     * 由 {@link #allocateAndFreeze} 放弃这一行。
     */
    private CascadePublishManager.FreezeOutcome freeze(CascadePublishDTO row, String code) {
        try {
            return cascadePublishManager.freezeCode(row.getId(), code, CodeSource.AUTO, LocalDateTime.now());
        } catch (DuplicateKeyException e) {
            log.warn("上报编码落库撞唯一键，换下一个号重试: publishId={}, platformId={}, code={}",
                row.getId(), row.getPlatformId(), code);
            return CascadePublishManager.FreezeOutcome.DUPLICATE;
        }
    }

    /**
     * 在号池里找下一个空号，<b>不落库也不占号</b>。
     *
     * <p>{@link CascadeCodeBuilder#nextSeq} 的语义是「同 head 下最大值 + 1」，所以起点只算一次，
     * 后续靠自增跳过已用号——不必每轮重扫号池。号段按 head 分段，进不进 {@code used} 由调用方决定：
     * 预览要占号（同一批里不能给两行发同一个），落库失败时也要占号（否则下一轮还是它）。
     *
     * @return 可用编码；该 head 下序号用尽返回 {@code null}
     */
    private String nextFreeCode(Generation generation, Set<String> used) {
        Segments segments = generation.segments();
        int seq = CascadeCodeBuilder.nextSeq(new ArrayList<>(used), segments.prefix() + segments.type(),
            generation.startSeq());
        for (; seq <= CascadeCodeBuilder.MAX_SEQ; seq++) {
            String code = CascadeCodeBuilder.join(segments.head(), seq);
            if (!used.contains(code)) {
                return code;
            }
        }
        return null;
    }

    /**
     * 取号并写回发布行（{@code code_source=AUTO}）；撞唯一键就占住该号继续找。
     *
     * <p>成功的号同样进 {@code used}：批量补齐时同一批里的下一行不能再算到它头上。
     *
     * <p>「行上已有码」不换号：那不是撞键，是这行在读与写之间被人工接管了，
     * 换号只会一直撞同一个条件直到号段耗尽。
     *
     * @return 已落库的编码 / 号段耗尽 / 该行已被写入而放弃
     */
    private Allocated allocateAndFreeze(CascadePublishDTO row, Generation generation, Set<String> used) {
        while (true) {
            String code = nextFreeCode(generation, used);
            if (code == null) {
                return Allocated.exhausted();
            }
            used.add(code);
            CascadePublishManager.FreezeOutcome outcome = freeze(row, code);
            /* switch 表达式强制穷尽：将来新增枚举值会编译失败，而不是静默退化成继续换号 */
            Allocated result = switch (outcome) {
                case WRITTEN -> {
                    log.info("级联编码按规则生成并固化: platformId={}, objectType={}, objectKey={}, code={}",
                        row.getPlatformId(), row.getObjectType(), row.getObjectKey(), code);
                    yield Allocated.written(code);
                }
                case OCCUPIED -> {
                    log.info("发布行在生成期间已有编码，放弃该行: publishId={}, objectType={}, objectKey={}",
                        row.getId(), row.getObjectType(), row.getObjectKey());
                    yield Allocated.taken();
                }
                /* 该号被别的行占住：返回 null 表示换下一个号 */
                case DUPLICATE -> null;
            };
            if (result != null) {
                return result;
            }
        }
    }

    /** 取号结果：{@code code} 非空表示已落库，{@code occupied} 表示该行已被别人写入。 */
    private record Allocated(String code, boolean occupied) {
        static Allocated written(String code) {
            return new Allocated(code, false);
        }

        static Allocated exhausted() {
            return new Allocated(null, false);
        }

        static Allocated taken() {
            return new Allocated(null, true);
        }
    }

    /** 变更明细：{@code before} 留空——补码的对象本来就没有旧值。 */
    private CascadeCodeReapplyResult.Change change(PublishObjectType type, String objectKey, String after) {
        CascadeCodeReapplyResult.Change change = new CascadeCodeReapplyResult.Change();
        change.setObjectType(type.name());
        change.setObjectKey(objectKey);
        change.setAfter(after);
        return change;
    }

    private String noteOf(CascadeCodeBatchFillResult result) {
        int count = result.getChanges().size();
        List<String> parts = new ArrayList<>();
        if (count == 0) {
            parts.add(result.getConflicts().isEmpty() ? "没有需要补码的发布行" : "补码全部失败，详见 conflicts");
        } else {
            parts.add(result.isDryRun() ? "预览 " + count + " 行待补码，未落库" : "已补齐 " + count + " 行上报编码");
        }
        if (result.getManualProtected() > 0) {
            parts.add(result.getManualProtected() + " 行人工码保持不动");
        }
        if (count > 0 && !result.getConflicts().isEmpty()) {
            parts.add(result.getConflicts().size() + " 行失败");
        }
        return String.join("，", parts);
    }
}
