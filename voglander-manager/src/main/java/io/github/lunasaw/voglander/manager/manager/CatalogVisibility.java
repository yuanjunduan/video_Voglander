package io.github.lunasaw.voglander.manager.manager;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;

/**
 * 组织树的<b>本地</b>可见性快照：整棵树里每个节点是否真的可达。
 *
 * <p><b>为什么需要它</b>：{@code tb_cascade_org.enabled} 只是「自己的开关」，
 * 祖先停用后子孙的开关依旧是 1，但它们已经随祖先从所有平台的目录里消失了。
 * 页面只看 {@code enabled} 就会出现「显示启用、上级其实看不到」，因此凡是
 * 「这个节点还有没有意义」的判断都必须走这里，而不是看单条记录的开关。
 *
 * <p><b>与平台无关</b>：本快照只回答「本地结构是否可达」。某个上级能不能看到，
 * 还要叠加 {@code tb_cascade_publish} 的发布范围——那是发布层的事，
 * 把两者揉在一起会让「上级看不到」无法归因到「结构断了」还是「没发布」。
 *
 * <p><b>一次算好</b>：构造时全库只查一次，逐节点向上找第一个停用节点，
 * 列表出参、通道归属校验都用同一份结果，避免 N+1 与口径漂移。
 */
public final class CatalogVisibility {

    private final Map<String, CascadeOrgDTO> orgByCode;
    /** 组织编码 → 卡住它的停用节点名称（自身停用即自身名称）；有效组织不在其中 */
    private final Map<String, String>       blockedByOrgName;
    /** 自身与整条祖先链都启用的组织编码 */
    private final Set<String>               effectiveCodes;

    CatalogVisibility(Map<String, CascadeOrgDTO> orgByCode, Map<String, String> blockedByOrgName,
        Set<String> effectiveCodes) {
        this.orgByCode = orgByCode == null ? Collections.emptyMap() : orgByCode;
        this.blockedByOrgName = blockedByOrgName == null ? Collections.emptyMap() : blockedByOrgName;
        this.effectiveCodes = effectiveCodes == null ? Collections.emptySet() : effectiveCodes;
    }

    /**
     * 组织在本地是否可达。
     *
     * @param orgCode 组织编码；空表示不挂组织（挂域节点），按可见处理
     */
    public boolean isOrgEffective(String orgCode) {
        return StringUtils.isBlank(orgCode) || effectiveCodes.contains(orgCode);
    }

    /**
     * 组织被哪一级组织连带停用。
     *
     * @return 停用节点名称；组织有效或编码为空时返回 null
     */
    public String blockedOrgName(String orgCode) {
        return StringUtils.isBlank(orgCode) ? null : blockedByOrgName.get(orgCode);
    }

    /** 按编码取组织，供出参补充路径等信息；不存在返回 null。 */
    public CascadeOrgDTO org(String orgCode) {
        return StringUtils.isBlank(orgCode) ? null : orgByCode.get(orgCode);
    }

    /** 有效组织编码集合（只读）。 */
    public Set<String> effectiveCodes() {
        return Collections.unmodifiableSet(effectiveCodes);
    }
}
