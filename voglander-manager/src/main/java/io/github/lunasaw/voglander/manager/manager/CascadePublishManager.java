package io.github.lunasaw.voglander.manager.manager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.CodeSource;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.RevokePolicy;
import io.github.lunasaw.voglander.manager.assembler.CascadeAssembler;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.service.CascadePublishService;
import io.github.lunasaw.voglander.repository.entity.CascadePublishDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联发布配置管理器：本地资产 → 上级平台的可见性视图。
 *
 * <p><b>它在三层里的位置</b>：本地资产（组织/设备/通道）不认平台，协议层只认发布集。
 * 本类是两者之间唯一的翻译层——「这个对象推给哪些平台」「在那个平台用什么编码」
 * 全部从这里读，不允许各处自己拼 SQL。
 *
 * <p><b>对象用业务键定位，不用自增主键</b>：{@code object_key} 取
 * {@code org_code} / {@code device_id} / {@code device_id:channel_id}（见
 * {@code CascadeConstant.PublishKeys}）。原因是主键属于存储细节：设备与通道会被物理删后重建，
 * id 一变发布关系就成了孤儿；而上级点播反查只需要一次查询就能从 {@code object_key} 拿到本地通道标识。
 *
 * <p><b>三条铁律</b>
 * <ul>
 *   <li><b>取消勾选只置 0 不删行</b>：删了编码覆盖就一起丢了，重新开启时得重填一遍；
 *       真正不需要时才显式 {@link #unpublish}。</li>
 *   <li><b>编码覆盖按平台唯一</b>：{@code UNIQUE (platform_id, publish_code)} 是跨对象类型的，
 *       组织码与通道码撞了上级同样丢节点，所以写入前先查一次，给出可读的冲突提示，
 *       而不是把数据库的约束异常抛到页面上。</li>
 *   <li><b>{@code parent_key} 只存差异</b>：留空表示按本地结构推导父节点，填了才覆盖。
 *       同一对象推给集团平台与区级平台时，本地组织树只有一份，差异全在这一列。</li>
 * </ul>
 */
@Slf4j
@Component
public class CascadePublishManager {

    /** 批量反查时单条 SQL 的 IN 上限，避免超长参数列表 */
    private static final int IN_BATCH_SIZE = 500;

    /**
     * 固化结果。
     *
     * <p><b>为什么不复用 {@code boolean}</b>：{@code false} 有两种完全不同的含义——
     * 撞唯一键要换下一个号重试，而「行上已经有码」必须立刻放弃这一行。
     * 混成一种结果，后者会被一直当成撞键，换号换到号段耗尽才报错。
     */
    public enum FreezeOutcome {

        /** 写入成功 */
        WRITTEN,

        /** 撞 {@code uk_publish_code}：换下一个号重试 */
        DUPLICATE,

        /** 条件未命中：读与写之间该行已有码（通常是人工填写），必须放弃这一行 */
        OCCUPIED
    }

    @Autowired
    private CascadePublishService cascadePublishService;

    /**
     * 写一条发布配置（按 {@code (platform, type, objectKey)} upsert）。
     *
     * <p>{@code publishCode} 留空表示沿用本地编码；一旦填了值，同平台内不允许与其它对象重复。
     *
     * <p><b>传进来的码一律按人工值处理</b>：本方法的三个调用方（组织发布覆盖、通道新增、通道编辑）
     * 传的都是用户在页面上确认过的值，落库时标 {@code code_source=MANUAL}，此后规则重算不再改它。
     * 规则生成的码不走这里，走 {@link #freezeCode} 并标 {@code AUTO}。
     *
     * <p><b>不支持清空编码</b>：{@code publishCode} 传 {@code null} 或空串都等于「这次不动编码」
     * （空白先被归成 null，{@code updateById} 跳过该列）。摘掉覆盖要另走显式入口，
     * 不能靠传空——那会让「没填」与「要清空」不可区分。
     *
     * <p><b>为什么带事务</b>：改码与翻来源是两条 UPDATE，必须同生共死。中间失败会留下
     * 「人工码 + AUTO 来源」的行，下一次规则重算就会照 AUTO 把它改写掉——正是 2.5 要防的事。
     *
     * @return 记录主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long publish(CascadePublishDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.hasText(dto.getPlatformId(), "platformId 不能为空");
        PublishObjectType objectType = requireObjectType(dto.getObjectType());
        String objectKey = requireObjectKey(dto.getObjectKey());
        String parentKey = StringUtils.trimToNull(dto.getParentKey());

        String platformId = dto.getPlatformId();
        String publishCode = StringUtils.trimToNull(dto.getPublishCode());
        if (publishCode != null) {
            assertCodeAvailable(platformId, objectType, objectKey, publishCode);
        }
        CascadePublishDO existed = findDO(platformId, objectType, objectKey);
        if (existed == null) {
            CascadePublishDO insert = CascadeAssembler.toDO(dto);
            insert.setObjectType(objectType.name());
            insert.setObjectKey(objectKey);
            insert.setParentKey(parentKey);
            insert.setPublishCode(publishCode);
            insert.setDeviceKey(deviceKeyOf(objectType, objectKey));
            insert.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
            insert.setRevokePolicy(normalizePolicy(dto.getRevokePolicy()));
            /* 新建行上的码只可能来自人工填写：无条件标 MANUAL 并把生成时间留空，否则后续
               「重算/补齐」会把它当成规则生成值改写掉。不信任 dto 里的来源——规则路径走
               freezeCode，一旦允许调用方自报 AUTO，人工码就能被伪装成规则值。
               不传码时两列一并置空，避开「空码 + 自报 AUTO」的脏状态 */
            insert.setCodeSource(publishCode == null ? null : CodeSource.MANUAL);
            insert.setCodeGeneratedTime(null);
            cascadePublishService.save(insert);
            log.info("新增发布配置: platformId={}, objectType={}, objectKey={}, parentKey={}, publishCode={},"
                + " codeSource={}, enabled={}", platformId, objectType, objectKey, parentKey, publishCode,
                insert.getCodeSource(), insert.getEnabled());
            return insert.getId();
        }
        /* 值真的变了才算「人工改写」：前端把回显值原样再提交一次，不能把 AUTO 变成人工值。
           那一次保存什么都没改，翻转来源反而让这个码从此不再跟随规则——用户并没有这个意图 */
        boolean manualCode = publishCode != null
            && !StringUtils.equals(publishCode, StringUtils.trimToNull(existed.getPublishCode()));
        CascadePublishDO patch = new CascadePublishDO();
        patch.setId(existed.getId());
        /* parent_key 属于「按平台的结构差异」：传空表示这次不改，摘掉覆盖要走 setParentKey(..., "") */
        patch.setParentKey(parentKey);
        patch.setPublishCode(publishCode);
        /* 存量行可能还是拆键前的空值：非通道类返回 null，updateById 会跳过这一列 */
        patch.setDeviceKey(deviceKeyOf(objectType, objectKey));
        patch.setPublishName(dto.getPublishName());
        patch.setEnabled(dto.getEnabled() == null ? existed.getEnabled() : dto.getEnabled());
        patch.setSortOrder(dto.getSortOrder());
        patch.setManufacturer(dto.getManufacturer());
        patch.setModel(dto.getModel());
        patch.setAddress(dto.getAddress());
        patch.setIpAddress(dto.getIpAddress());
        patch.setPtzType(dto.getPtzType());
        patch.setCivilCode(dto.getCivilCode());
        patch.setRevokePolicy(normalizePolicy(dto.getRevokePolicy()));
        patch.setExtend(dto.getExtend());
        boolean updated = cascadePublishService.updateById(patch);
        if (updated && manualCode) {
            /* 来源要翻成 MANUAL，生成时间要清空；而 updateById 表达不了 NULL，单独一条条件更新 */
            cascadePublishService.lambdaUpdate()
                .eq(CascadePublishDO::getId, existed.getId())
                .set(CascadePublishDO::getCodeSource, CodeSource.MANUAL)
                .set(CascadePublishDO::getCodeGeneratedTime, null)
                .update();
        }
        log.info("更新发布配置: id={}, platformId={}, objectType={}, objectKey={}, publishCode={}, manualCode={}",
            existed.getId(), platformId, objectType, objectKey, publishCode, manualCode);
        return existed.getId();
    }

    /**
     * 配置一个对象要发布到哪些平台：给定的平台 upsert 为启用，未给定的已存在行置为停用。
     *
     * <p>不删行：删掉等于把编码覆盖一起丢掉，重新勾选时用户得重填一遍。
     *
     * @param platformIds 目标平台编码集合（空集合表示全部取消勾选）
     * @return 实际生效的平台数
     */
    public int configurePlatforms(String objectType, String objectKey, Collection<String> platformIds) {
        PublishObjectType type = requireObjectType(objectType);
        String key = requireObjectKey(objectKey);
        Set<String> targets = new LinkedHashSet<>();
        if (platformIds != null) {
            for (String platformId : platformIds) {
                if (StringUtils.isNotBlank(platformId)) {
                    targets.add(platformId.trim());
                }
            }
        }
        /* 已存在行优先保留编码覆盖，只翻转 enabled；没有行的才新建 */
        Map<String, CascadePublishDO> existedByPlatform = new LinkedHashMap<>();
        for (CascadePublishDO each : listDOByObject(type, key)) {
            existedByPlatform.put(each.getPlatformId(), each);
        }
        for (String platformId : targets) {
            CascadePublishDO existed = existedByPlatform.get(platformId);
            if (existed == null) {
                CascadePublishDO insert = new CascadePublishDO();
                insert.setPlatformId(platformId);
                insert.setObjectType(type.name());
                insert.setObjectKey(key);
                insert.setDeviceKey(deviceKeyOf(type, key));
                insert.setEnabled(1);
                cascadePublishService.save(insert);
                continue;
            }
            if (!Integer.valueOf(1).equals(existed.getEnabled())) {
                CascadePublishDO patch = new CascadePublishDO();
                patch.setId(existed.getId());
                patch.setEnabled(1);
                cascadePublishService.updateById(patch);
            }
        }
        for (Map.Entry<String, CascadePublishDO> entry : existedByPlatform.entrySet()) {
            if (targets.contains(entry.getKey()) || !Integer.valueOf(1).equals(entry.getValue().getEnabled())) {
                continue;
            }
            CascadePublishDO patch = new CascadePublishDO();
            patch.setId(entry.getValue().getId());
            patch.setEnabled(0);
            cascadePublishService.updateById(patch);
        }
        log.info("配置发布范围: objectType={}, objectKey={}, 目标平台={}", type, key, targets);
        return targets.size();
    }

    /** 取消发布（物理删除该行，编码覆盖一并丢弃）；不需要删时就别调，用停用即可。 */
    public boolean unpublish(String platformId, String objectType, String objectKey) {
        PublishObjectType type = requireObjectType(objectType);
        String key = StringUtils.trimToNull(objectKey);
        if (StringUtils.isBlank(platformId) || key == null) {
            return false;
        }
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId)
            .eq(CascadePublishDO::getObjectType, type.name())
            .eq(CascadePublishDO::getObjectKey, key);
        return cascadePublishService.remove(qw);
    }

    /** 单独翻转某条发布的启停；记录不存在时返回 false。 */
    public boolean setEnabled(String platformId, String objectType, String objectKey, int enabled) {
        PublishObjectType type = requireObjectType(objectType);
        String key = StringUtils.trimToNull(objectKey);
        if (StringUtils.isBlank(platformId) || key == null) {
            return false;
        }
        CascadePublishDO existed = findDO(platformId, type, key);
        if (existed == null) {
            return false;
        }
        CascadePublishDO patch = new CascadePublishDO();
        patch.setId(existed.getId());
        patch.setEnabled(enabled);
        return cascadePublishService.updateById(patch);
    }

    /**
     * 只改通道处置策略，不动编码覆盖与启停。
     *
     * <p>为什么不复用 {@link #publish}：那个是<b>整体覆盖</b>语义，传进来的空字段虽然被
     * {@code updateById} 跳过，但调用方很容易以为“只改策略”而顺手把编码覆盖一起传空，
     * 事后无法区分「没填」与「要清空」。策略是停用预览与报文编排的直接依据，单独一条链路更安全。
     *
     * @param policy CASCADE / REROUTE；空白按 CASCADE
     * @return 是否更新成功；发布行不存在返回 false（策略只能属于已存在的发布关系）
     */
    public boolean setRevokePolicy(String platformId, String objectType, String objectKey, String policy) {
        PublishObjectType type = requireObjectType(objectType);
        String key = StringUtils.trimToNull(objectKey);
        if (StringUtils.isBlank(platformId) || key == null) {
            return false;
        }
        CascadePublishDO existed = findDO(platformId, type, key);
        if (existed == null) {
            return false;
        }
        CascadePublishDO patch = new CascadePublishDO();
        patch.setId(existed.getId());
        patch.setRevokePolicy(RevokePolicy.normalize(policy));
        boolean ok = cascadePublishService.updateById(patch);
        if (ok) {
            log.info("更新发布策略: platformId={}, objectType={}, objectKey={}, policy={}",
                platformId, type, key, patch.getRevokePolicy());
        }
        return ok;
    }

    /**
     * 设置该平台下的父节点覆盖（{@code parent_key}）。
     *
     * <p>与 {@link #publish} 分开同样是为了区分「没填」与「要清空」：
     * {@code parentKey} 传空串表示<b>摘掉覆盖</b>、回落到本地结构推导（落库 NULL），
     * 传 null 表示这次不动。
     *
     * @return 是否更新成功；发布行不存在返回 false
     */
    public boolean setParentKey(String platformId, String objectType, String objectKey, String parentKey) {
        PublishObjectType type = requireObjectType(objectType);
        String key = StringUtils.trimToNull(objectKey);
        if (StringUtils.isBlank(platformId) || key == null || parentKey == null) {
            return false;
        }
        CascadePublishDO existed = findDO(platformId, type, key);
        if (existed == null) {
            return false;
        }
        String normalized = StringUtils.trimToNull(parentKey);
        CascadePublishDO patch = new CascadePublishDO();
        patch.setId(existed.getId());
        /* updateById 跳过 null 列，摘掉覆盖必须显式 SET；否则「改回按本地结构」这个动作永远落不了库 */
        if (normalized == null) {
            boolean ok = cascadePublishService.lambdaUpdate()
                .eq(CascadePublishDO::getId, existed.getId())
                .set(CascadePublishDO::getParentKey, null)
                .update();
            if (ok) {
                log.info("清除发布父节点覆盖: platformId={}, objectType={}, objectKey={}", platformId, type, key);
            }
            return ok;
        }
        patch.setParentKey(normalized);
        return cascadePublishService.updateById(patch);
    }

    /** 取某对象在某平台的发布配置；没有配置返回 null。 */
    public CascadePublishDTO get(String platformId, String objectType, String objectKey) {
        PublishObjectType type = requireObjectType(objectType);
        String key = StringUtils.trimToNull(objectKey);
        if (StringUtils.isBlank(platformId) || key == null) {
            return null;
        }
        return CascadeAssembler.toDTO(findDO(platformId, type, key));
    }

    /** 按主键取发布行；不存在返回 null（页面详情/反查用）。 */
    public CascadePublishDTO getById(Long id) {
        return id == null ? null : CascadeAssembler.toDTO(cascadePublishService.getById(id));
    }

    /**
     * 按上报编码反查某平台的一条发布行。
     *
     * <p>上级报文只带 {@code DeviceID}（= 本端发出的编码），点播、录像查询、PTZ 控制都靠它反查本地通道。
     *
     * @param publishCode 上报编码（该平台内跨对象类型唯一）
     * @return 发布行；不存在返回 null
     */
    public CascadePublishDTO getByCode(String platformId, String objectType, String publishCode) {
        PublishObjectType type = requireObjectType(objectType);
        String code = StringUtils.trimToNull(publishCode);
        if (StringUtils.isBlank(platformId) || code == null) {
            return null;
        }
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId)
            .eq(CascadePublishDO::getObjectType, type.name())
            .eq(CascadePublishDO::getPublishCode, code);
        return CascadeAssembler.toDTO(cascadePublishService.getOne(qw));
    }

    /**
     * 某类型下全部平台的发布行（页面不指定平台时用）。
     *
     * <p>行数 = 平台数 × 对象数，量级在千以内；不要拿它做逐请求的热路径。
     *
     * @param onlyEnabled 是否只要启用行
     */
    public List<CascadePublishDTO> listByType(String objectType, boolean onlyEnabled) {
        PublishObjectType type = requireObjectType(objectType);
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getObjectType, type.name())
            .eq(onlyEnabled, CascadePublishDO::getEnabled, 1)
            .orderByAsc(CascadePublishDO::getPlatformId)
            .orderByAsc(CascadePublishDO::getId);
        return toDTOList(cascadePublishService.list(qw));
    }

    /**
     * 某平台的<b>全部</b>发布行（不分对象类型、含停用行）。
     *
     * <p>号池与平台内查重都要它：号池算的是「这个平台已经占掉的生效码」，停用行同样算——
     * 上级侧可能还留着那些节点，新号占上去就变成两个同 {@code DeviceID} 的记录；
     * 查重也必须跳类型，因为唯一键 {@code (platform_id, publish_code)} 不含 {@code object_type}。
     *
     * <p>按类型分三次调的 {@link #listByPlatform} 不能拿来做这两件事：既多两次往返，
     * 也容易漏掉新增的对象类型。
     */
    public List<CascadePublishDTO> listByPlatformAll(String platformId) {
        if (StringUtils.isBlank(platformId)) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId)
            .orderByAsc(CascadePublishDO::getObjectType)
            .orderByAsc(CascadePublishDO::getId);
        return toDTOList(cascadePublishService.list(qw));
    }

    /**
     * 固化上报编码：写回 {@code publish_code} 并标记来源，<b>不走 {@link #publish} 的整体覆盖语义</b>。
     *
     * <p>为什么单独一条链路：{@code publish()} 会把名称/排序/元数据一起当成本次覆盖，
     * 而生成编码时调用方只关心「码」这一个字段，走那边会把其它列意外冲成空。
     *
     * <p><b>只在列仍为空时写入</b>：生成方是「先读空列、后写回」，中间这段时间用户可能在页面上
     * 填了码。无条件写会把刚落地的人工值顶成规则码并翻成 AUTO，等于替用户改码（2.5）。
     * 命中不了条件时返回 {@link FreezeOutcome#OCCUPIED}，调用方必须放弃这一行而不是换号重试——
     * 换号只会一直撞同一个条件，直到号段耗尽。
     *
     * <p>不再在这里查一遍重：号池已排除同平台现存的生效码，剩下的并发窗口交给
     * {@code uk_publish_code}——让唯一键报上来，比多一次全表扫描划算。
     *
     * @param id            发布行主键
     * @param code          生成结果
     * @param source        {@code AUTO} / {@code MANUAL}
     * @param generatedTime 生成时间（人工填写传 null）
     * @return 写入成功 / 撞唯一键 / 行上已有码
     */
    public FreezeOutcome freezeCode(Long id, String code, String source, LocalDateTime generatedTime) {
        String value = StringUtils.trimToNull(code);
        if (id == null || value == null) {
            return FreezeOutcome.OCCUPIED;
        }
        boolean updated = cascadePublishService.lambdaUpdate()
            .eq(CascadePublishDO::getId, id)
            .and(w -> w.isNull(CascadePublishDO::getPublishCode).or().eq(CascadePublishDO::getPublishCode, ""))
            .set(CascadePublishDO::getPublishCode, value)
            .set(CascadePublishDO::getCodeSource, source)
            .set(CascadePublishDO::getCodeGeneratedTime, generatedTime)
            .update();
        return updated ? FreezeOutcome.WRITTEN : FreezeOutcome.OCCUPIED;
    }

    /**
     * 仅当 {@code publish_code} 为空时补一次码（{@code source} 为空则不标记来源）。
     *
     * <p><b>为什么需要它</b>：上级点播/PTZ/录像反查只走 {@link #getByCode(String, String, String)}，
     * 即只认 {@code publish_code} 列。通道新增在编码规则未启用时不会生成任何码，
     * 若不把本地码落到这一列，原本能反查的通道会集体失联。
     *
     * <p><b>为什么是「为空才写」</b>：规则启用时 {@link #freezeCode} 已先写过 AUTO 码，
     * 无条件覆盖会把规则结果换成设备通道号。两条链路因此可以固定顺序调用而不互相踩踏，
     * 调用方也不必先判断规则开关。
     *
     * <p><b>人工行不补</b>：{@code code_source=MANUAL} 的行不受「回落本地码」这条链路改写（2.5）。
     *
     * <p><b>与 {@code resolve}/{@code batchFill} 的边界</b>：那两条只看 {@code publish_code} 是否为空、
     * 不看来源，所以 MANUAL 行的码若被清空，它们仍会补一个规则码并把来源翻成 AUTO。
     * 这是 2.4 存量补码的刻意取舍：升级上来的行来源全是 NULL，按来源拦会让它们永远补不上。
     * 现状不存在清空编码的入口，分歧触发不到；将来补清空入口时必须同时给出来源复位出口。
     *
     * @param id 发布行主键
     * @param code 要落的码（通常是本地通道码）；空值直接返回 false
     * @param source {@code AUTO} / {@code MANUAL}，null=不标记来源（历史/回落值）
     * @param generatedTime 生成时间，人工与回落值传 null
     * @return 是否发生了写入（列上已有值或人工行返回 false）
     */
    public boolean fillCodeIfBlank(Long id, String code, String source, LocalDateTime generatedTime) {
        String value = StringUtils.trimToNull(code);
        if (id == null || value == null) {
            return false;
        }
        return cascadePublishService.lambdaUpdate()
            .eq(CascadePublishDO::getId, id)
            .and(w -> w.isNull(CascadePublishDO::getPublishCode).or().eq(CascadePublishDO::getPublishCode, ""))
            .and(w -> w.isNull(CascadePublishDO::getCodeSource)
                .or().ne(CascadePublishDO::getCodeSource, CodeSource.MANUAL))
            .set(CascadePublishDO::getPublishCode, value)
            .set(source != null, CascadePublishDO::getCodeSource, source)
            .set(generatedTime != null, CascadePublishDO::getCodeGeneratedTime, generatedTime)
            .update();
    }

    /** 某平台的发布集，{@code onlyEnabled=true} 时只要启用行（组装器用）。 */
    public List<CascadePublishDTO> listByPlatform(String platformId, String objectType, boolean onlyEnabled) {
        PublishObjectType type = requireObjectType(objectType);
        if (StringUtils.isBlank(platformId)) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId)
            .eq(CascadePublishDO::getObjectType, type.name())
            .eq(onlyEnabled, CascadePublishDO::getEnabled, 1)
            .orderByAsc(CascadePublishDO::getSortOrder)
            .orderByAsc(CascadePublishDO::getId);
        return toDTOList(cascadePublishService.list(qw));
    }

    /**
     * 某平台某类型的发布集，按 {@code object_key} 建索引。
     *
     * <p>组装目录时先取一次、按 key 查，避免每节点回表；{@code onlyEnabled=true} 时只含启用行。
     */
    public Map<String, CascadePublishDTO> indexByObjectKey(String platformId, String objectType,
        boolean onlyEnabled) {
        Map<String, CascadePublishDTO> index = new LinkedHashMap<>();
        for (CascadePublishDTO publish : listByPlatform(platformId, objectType, onlyEnabled)) {
            if (publish.getObjectKey() != null) {
                index.put(publish.getObjectKey(), publish);
            }
        }
        return index;
    }

    /** 某对象的全部发布行（含停用），按平台编码升序。 */
    public List<CascadePublishDTO> listByObject(String objectType, String objectKey) {
        return toDTOList(listDOByObject(requireObjectType(objectType), requireObjectKey(objectKey)));
    }

    /**
     * 批量按业务键取发布行（含停用）。
     *
     * <p>本地资产被批量删除时要一次问清「这批对象推给了哪些平台」：逐条调 {@link #listByObject}
     * 在清空一台设备的通道时就是 N 次查库。
     */
    public List<CascadePublishDTO> listByObjectKeys(String objectType, Collection<String> objectKeys) {
        PublishObjectType type = requireObjectType(objectType);
        List<String> keys = distinctKeys(objectKeys);
        if (keys.isEmpty()) {
            return new ArrayList<>();
        }
        List<CascadePublishDO> rows = new ArrayList<>();
        for (List<String> batch : partition(keys)) {
            LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
            qw.eq(CascadePublishDO::getObjectType, type.name())
                .in(CascadePublishDO::getObjectKey, batch)
                .orderByAsc(CascadePublishDO::getPlatformId)
                .orderByAsc(CascadePublishDO::getId);
            rows.addAll(cascadePublishService.list(qw));
        }
        return toDTOList(rows);
    }

    /**
     * 按业务键前缀取发布行（含停用）。
     *
     * <p>{@code object_key} 是拼出来的（通道为 {@code deviceId:channelId}），没有可单独筛选的设备列，
     * 删除设备时只能按前缀捞它名下全部通道的发布行。
     *
     * @param keyPrefix 含分隔符的前缀，例如 {@code 34020000001320000001:}
     */
    public List<CascadePublishDTO> listByObjectKeyPrefix(String objectType, String keyPrefix) {
        PublishObjectType type = requireObjectType(objectType);
        String prefix = StringUtils.trimToNull(keyPrefix);
        if (prefix == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getObjectType, type.name())
            .likeRight(CascadePublishDO::getObjectKey, prefix)
            .orderByAsc(CascadePublishDO::getPlatformId)
            .orderByAsc(CascadePublishDO::getId);
        return toDTOList(cascadePublishService.list(qw));
    }

    /** 某对象已发布到的平台编码（含停用行）；发布事件要按它分发。 */
    public List<String> platformIdsOf(String objectType, String objectKey) {
        String key = StringUtils.trimToNull(objectKey);
        if (key == null) {
            return new ArrayList<>();
        }
        return listDOByObject(requireObjectType(objectType), key).stream()
            .map(CascadePublishDO::getPlatformId)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .collect(java.util.stream.Collectors.toList());
    }

    /** 某对象「启用发布」的平台编码——只有这些平台才需要收目录变更报文。 */
    public List<String> enabledPlatformIdsOf(String objectType, String objectKey) {
        PublishObjectType type = requireObjectType(objectType);
        String key = StringUtils.trimToNull(objectKey);
        if (key == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getObjectType, type.name())
            .eq(CascadePublishDO::getObjectKey, key)
            .eq(CascadePublishDO::getEnabled, 1);
        return cascadePublishService.list(qw).stream()
            .map(CascadePublishDO::getPlatformId)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 反查：某平台某类型下已发布的全部对象业务键。
     *
     * <p>「这个平台能看到哪些对象」与「这个对象推给了哪些平台」是同一张表的两个方向，
     * 两个方向都要有入口，否则调用方只能自己写 SQL 拼，久了就漂移。
     *
     * @param onlyEnabled 是否只统计启用的发布行
     */
    public List<String> objectKeysOf(String platformId, String objectType, boolean onlyEnabled) {
        if (StringUtils.isBlank(platformId)) {
            return new ArrayList<>();
        }
        return listByPlatform(platformId, objectType, onlyEnabled).stream()
            .map(CascadePublishDTO::getObjectKey)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 批量取「对象 → 已发布平台」，列表页一次查询填充，避免逐行回表。
     *
     * @param onlyEnabled 是否只统计启用的发布行
     */
    public Map<String, List<String>> platformIdsByObjectKey(String objectType, Collection<String> objectKeys,
        boolean onlyEnabled) {
        Map<String, List<String>> result = new HashMap<>();
        List<String> keys = distinctKeys(objectKeys);
        if (keys.isEmpty()) {
            return result;
        }
        PublishObjectType type = requireObjectType(objectType);
        for (List<String> batch : partition(keys)) {
            LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
            qw.eq(CascadePublishDO::getObjectType, type.name())
                .in(CascadePublishDO::getObjectKey, batch)
                .eq(onlyEnabled, CascadePublishDO::getEnabled, 1);
            for (CascadePublishDO each : cascadePublishService.list(qw)) {
                result.computeIfAbsent(each.getObjectKey(), key -> new ArrayList<>()).add(each.getPlatformId());
            }
        }
        return result;
    }

    /**
     * 把某对象的发布行整体换到新业务键上（组织编码重算时用）。
     *
     * <p>不删旧行再建新行：删了会丢 {@code publish_code} / {@code enabled} / {@code revoke_policy}
     * 这些平台侧配置，重建时无从恢复。
     *
     * @return 是否更新成功
     */
    public boolean renameObjectKey(String objectType, String oldObjectKey, String newObjectKey) {
        PublishObjectType type = requireObjectType(objectType);
        String from = StringUtils.trimToNull(oldObjectKey);
        String to = StringUtils.trimToNull(newObjectKey);
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        LambdaUpdateWrapper<CascadePublishDO> uw = new LambdaUpdateWrapper<>();
        uw.eq(CascadePublishDO::getObjectType, type.name())
            .eq(CascadePublishDO::getObjectKey, from)
            .set(CascadePublishDO::getObjectKey, to);
        return cascadePublishService.update(uw);
    }

    /**
     * 把引用旧业务键的父节点覆盖整体改到新键（组织编码重算时用）。
     *
     * <p>{@code parent_key} 是「上级看到的父节点」。组织编码一变，所有指向它的通道/设备
     * 都会挂到不存在的父节点下，快照里直接变成孤儿——这一步漏掉，上级目录里那批通道
     * 会被整体剪掉，比编码错更难查。
     *
     * @return 是否更新成功
     */
    public boolean renameParentKey(String oldObjectKey, String newObjectKey) {
        String from = StringUtils.trimToNull(oldObjectKey);
        String to = StringUtils.trimToNull(newObjectKey);
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        LambdaUpdateWrapper<CascadePublishDO> uw = new LambdaUpdateWrapper<>();
        uw.eq(CascadePublishDO::getParentKey, from)
            .set(CascadePublishDO::getParentKey, to);
        return cascadePublishService.update(uw);
    }

    /** 删除对象时清理它的全部发布行；返回删除条数。 */
    public int deleteByObject(String objectType, String objectKey) {
        PublishObjectType type = requireObjectType(objectType);
        String key = StringUtils.trimToNull(objectKey);
        if (key == null) {
            return 0;
        }
        List<CascadePublishDO> rows = listDOByObject(type, key);
        if (rows.isEmpty()) {
            return 0;
        }
        cascadePublishService.removeByIds(rows.stream().map(CascadePublishDO::getId).toList());
        return rows.size();
    }

    /**
     * 按主键批量物理删除发布行；返回删除条数。
     *
     * <p>删除本地资产时用：对象已经不存在，发布行留着只会变成孤儿——组装器会跳过并告警，
     * 上级那边却还留着上次推上去的节点。
     */
    public int deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> targets = new ArrayList<>(new LinkedHashSet<>(ids));
        targets.removeIf(each -> each == null);
        int deleted = 0;
        for (int from = 0; from < targets.size(); from += IN_BATCH_SIZE) {
            List<Long> batch = targets.subList(from, Math.min(from + IN_BATCH_SIZE, targets.size()));
            if (cascadePublishService.removeByIds(batch)) {
                deleted += batch.size();
            }
        }
        return deleted;
    }

    /**
     * 编码可用性检查：同一平台内不允许两个对象的<b>生效码</b>重复（跨对象类型）。
     *
     * <p><b>为什么按生效码而不是按 {@code publish_code} 列</b>：该列为空表示沿用本地编码
     * （ORG/DEVICE 取 {@code object_key}，CHANNEL 取 {@code object_key} 的通道段），
     * 上级看到的就是那个值。只按列比对时，「给 A 填上 B 的本地码」这种真冲突查不出来——
     * 对方列上是一片 NULL，{@code uk_publish_code} 同样不判重（NULL 不参与唯一约束），
     * 最后在上级目录里变成两个同 {@code DeviceID} 的节点，点播随机命中其中一个。
     *
     * <p>库上的 {@code uk_publish_code} 只能兜住「两边都填了覆盖」的情况，这里负责把它兜不住的那半边
     * 补上，并把约束异常换成能定位到「哪个对象占了它」的提示。
     */
    private void assertCodeAvailable(String platformId, PublishObjectType objectType, String objectKey,
        String publishCode) {
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId);
        for (CascadePublishDO each : cascadePublishService.list(qw)) {
            boolean sameObject = objectType.name().equals(each.getObjectType())
                && objectKey.equals(each.getObjectKey());
            if (sameObject) {
                continue;
            }
            String occupied = effectiveCodeOf(each);
            Assert.isTrue(!publishCode.equals(occupied),
                () -> "上报编码【" + publishCode + "】在平台 " + platformId + " 已被占用（"
                    + each.getObjectType() + " " + each.getObjectKey() + "）");
        }
    }

    /**
     * 上级眼里的节点编码：发布行编码优先，未覆盖时回落本地编码。
     *
     * <p>口径统一在 {@link CascadeCodeBuilder#effectiveCode}，与组装器、{@code DEL} 报文同一处；
     * 这里算错，查重就会和实际上报的编码对不上，等于没查。
     */
    private String effectiveCodeOf(CascadePublishDO row) {
        return CascadeCodeBuilder.effectiveCode(row.getPublishCode(),
            CascadeCodeBuilder.localCodeOf(row.getObjectType(), row.getObjectKey()));
    }

    /**
     * 发布行需要冗余的设备段：只有 CHANNEL 有，取业务键第一个分隔符之前。
     *
     * <p>{@code object_key} 是拼接键，不能当查询条件用（§6.3），所以写入时就把设备段拆出来；
     * 脏键（缺少分隔符）返回 null，保持空值即回落到「按 {@code object_key} 归组」的旧行为。
     */
    private String deviceKeyOf(PublishObjectType objectType, String objectKey) {
        return objectType == PublishObjectType.CHANNEL ? PublishKeys.channelDeviceId(objectKey) : null;
    }

    private CascadePublishDO findDO(String platformId, PublishObjectType objectType, String objectKey) {
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId)
            .eq(CascadePublishDO::getObjectType, objectType.name())
            .eq(CascadePublishDO::getObjectKey, objectKey);
        return cascadePublishService.getOne(qw);
    }

    private List<CascadePublishDO> listDOByObject(PublishObjectType objectType, String objectKey) {
        if (StringUtils.isBlank(objectKey)) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getObjectType, objectType.name())
            .eq(CascadePublishDO::getObjectKey, objectKey)
            .orderByAsc(CascadePublishDO::getPlatformId);
        return cascadePublishService.list(qw);
    }

    private List<CascadePublishDTO> toDTOList(List<CascadePublishDO> rows) {
        List<CascadePublishDTO> list = new ArrayList<>(rows.size());
        for (CascadePublishDO each : rows) {
            list.add(CascadeAssembler.toDTO(each));
        }
        return list;
    }

    /** 空值按 CASCADE 落库：策略事后无法从 enabled 反推，必须留一个确定值。 */
    private String normalizePolicy(String policy) {
        return RevokePolicy.normalize(policy);
    }

    /** 业务键必填：空键会让不同对象的发布行互相覆盖（唯一键里的 NULL 不判重）。 */
    private String requireObjectKey(String objectKey) {
        String key = StringUtils.trimToNull(objectKey);
        Assert.notNull(key, "objectKey 不能为空");
        return key;
    }

    private PublishObjectType requireObjectType(String objectType) {
        PublishObjectType type = PublishObjectType.of(objectType);
        Assert.notNull(type, "非法的发布对象类型: " + objectType);
        return type;
    }

    private List<String> distinctKeys(Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>(objectKeys.size());
        for (String each : new LinkedHashSet<>(objectKeys)) {
            if (StringUtils.isNotBlank(each)) {
                keys.add(each.trim());
            }
        }
        return keys;
    }

    private List<List<String>> partition(List<String> keys) {
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < keys.size(); from += IN_BATCH_SIZE) {
            batches.add(keys.subList(from, Math.min(from + IN_BATCH_SIZE, keys.size())));
        }
        return batches;
    }
}
