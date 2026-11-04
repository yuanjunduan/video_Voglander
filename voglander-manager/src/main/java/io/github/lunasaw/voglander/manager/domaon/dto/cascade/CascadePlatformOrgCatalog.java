package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.util.List;
import java.util.Set;

/**
 * 某上级平台的「组织视角全貌」：目录内立得起来的组织 + 通道上浮集合 + 勾了却立不起来的组织。
 *
 * <p><b>与 {@link CascadePlatformOrgView} 的分工</b>：后者是协议层组装目录用的最小集，
 * 只包含「上级这一刻会收到什么」；本类多带一份 {@code blockedOrgs}，专供页面回答
 * 「我明明勾了推送，为什么上级没有它」。协议层不要用本类——多算的阻断清单对它毫无用处。
 *
 * <p>⚠️ 两份数据必须出自同一次计算（同一份发布行快照与同一份组织表快照），
 * 否则页面会显示「目录里没有、阻断清单里也没有」的幽灵组织。
 *
 * @param catalogOrgs        目录内组织，已按平台排序值排序、父编码已解析
 * @param reroutedLocalCodes 目录外、通道需上浮挂域节点的本地组织编码
 * @param blockedOrgs        已勾选发布但被本地停用/祖先未发布阻断的组织
 */
public record CascadePlatformOrgCatalog(List<CascadePlatformOrgDTO> catalogOrgs,
    Set<String> reroutedLocalCodes, List<CascadeBlockedOrgDTO> blockedOrgs) {

    public CascadePlatformOrgCatalog {
        catalogOrgs = catalogOrgs == null ? List.of() : List.copyOf(catalogOrgs);
        reroutedLocalCodes = reroutedLocalCodes == null ? Set.of() : Set.copyOf(reroutedLocalCodes);
        blockedOrgs = blockedOrgs == null ? List.of() : List.copyOf(blockedOrgs);
    }

    /** 空视图：平台编码缺失、或该平台一条组织发布配置都没有时使用。 */
    public static CascadePlatformOrgCatalog empty() {
        return new CascadePlatformOrgCatalog(List.of(), Set.of(), List.of());
    }

    /** 只取协议层需要的部分，避免调用方自己拆包时漏掉字段。 */
    public CascadePlatformOrgView toView() {
        return new CascadePlatformOrgView(catalogOrgs, reroutedLocalCodes);
    }
}
