package io.github.lunasaw.voglander.manager.manager;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.RevokePolicy;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;

/**
 * 上级目录可见性快照：某个平台的整张组织树在上级眼里到底长什么样。
 *
 * <p><b>为什么需要它</b>：{@code tb_cascade_org.enabled} 只是「自己的开关」，
 * 祖先停报后子孙的开关依旧是 1，但它们早已随祖先被 DEL 从上级目录删掉。
 * 页面只看 {@code enabled} 就会出现「显示上报中、上级其实看不到」，因此凡是
 * 「上级能不能看到」的判断都必须走这里，而不是看单条记录的开关。
 *
 * <p><b>一次算好</b>：构造时整平台只查一次库，逐节点向上找第一个停报节点，
 * 列表出参、通道可见性都用同一份结果，避免 N+1 与口径漂移。
 *
 * <p>通道可见性的判定口径必须与 {@code CascadeCatalogAssembler#isChannelVisible} 保持一致，
 * 否则「页面显示的」和「实际推给上级的」会长期不一致。
 */
public final class CatalogVisibility {

    private final Map<String, CascadeOrgDTO> orgByCode;
    /** 组织编码 → 卡住它的停报节点名称（自身停报即自身名称）；生效组织不在其中 */
    private final Map<String, String>       blockedByOrgName;
    /** 自身与整条祖先链都在报的组织编码 */
    private final Set<String>               effectiveCodes;

    CatalogVisibility(Map<String, CascadeOrgDTO> orgByCode, Map<String, String> blockedByOrgName,
        Set<String> effectiveCodes) {
        this.orgByCode = orgByCode == null ? Collections.emptyMap() : orgByCode;
        this.blockedByOrgName = blockedByOrgName == null ? Collections.emptyMap() : blockedByOrgName;
        this.effectiveCodes = effectiveCodes == null ? Collections.emptySet() : effectiveCodes;
    }

    /**
     * 组织在上级目录里是否可见。
     *
     * @param cascadeOrgId 组织编码；空表示不挂组织（挂域节点），按可见处理
     */
    public boolean isOrgEffective(String cascadeOrgId) {
        return StringUtils.isBlank(cascadeOrgId) || effectiveCodes.contains(cascadeOrgId);
    }

    /**
     * 组织被哪一级组织连带停报。
     *
     * @return 停报节点名称；组织生效或编码为空时返回 null
     */
    public String blockedOrgName(String cascadeOrgId) {
        return StringUtils.isBlank(cascadeOrgId) ? null : blockedByOrgName.get(cascadeOrgId);
    }

    /** 按编码取组织，供出参补充路径等信息；不存在返回 null。 */
    public CascadeOrgDTO org(String cascadeOrgId) {
        return StringUtils.isBlank(cascadeOrgId) ? null : orgByCode.get(cascadeOrgId);
    }

    /**
     * 通道在上级目录里是否仍会出现。
     *
     * <p>所属组织生效即可见；组织停报时按该组织的 {@code revokePolicy} 决定：
     * {@code REROUTE} 上浮到域节点继续上报（可见），{@code CASCADE} 随组织一起消失。
     * 组织已被物理删除时按可见处理——快照组装会兜底挂到域节点，不默默吞掉通道。
     */
    public boolean isChannelVisible(String cascadeOrgId) {
        if (isOrgEffective(cascadeOrgId)) {
            return true;
        }
        CascadeOrgDTO org = orgByCode.get(cascadeOrgId);
        return org == null || RevokePolicy.REROUTE.equals(RevokePolicy.normalize(org.getRevokePolicy()));
    }

    /** 生效组织编码集合（只读）。 */
    public Set<String> effectiveCodes() {
        return Collections.unmodifiableSet(effectiveCodes);
    }
}
