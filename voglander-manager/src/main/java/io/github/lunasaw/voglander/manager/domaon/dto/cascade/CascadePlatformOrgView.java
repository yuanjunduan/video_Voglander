package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.util.List;
import java.util.Set;

/**
 * 某上级平台的组织视图：目录里立得起来的组织，外加「本地还在、但目录里立不起来、
 * 其通道需要上浮挂域节点」的组织编码。
 *
 * <p><b>两个集合为什么必须一起给</b>：通道的归属组织在该平台目录里不存在时，
 * 它只有两种归宿——跟着消失，或者挂到域节点继续上报。前者是本地什么也不做，
 * 后者必须在报文里有一条改挂事件。只给「目录内的组织」会让调用方无法区分
 * 「组织没了、通道也应该没」与「组织没了、通道上浮」，只能猜。
 *
 * <p>{@code reroutedLocalCodes} 里的编码是<b>本地</b>编码，因为通道表存的是本地编码。
 *
 * @param catalogOrgs        目录内的组织节点，已按平台排序值排好序、父编码已解析
 * @param reroutedLocalCodes 目录外、但通道要上浮的本地组织编码
 */
public record CascadePlatformOrgView(List<CascadePlatformOrgDTO> catalogOrgs, Set<String> reroutedLocalCodes) {

    public CascadePlatformOrgView {
        catalogOrgs = catalogOrgs == null ? List.of() : List.copyOf(catalogOrgs);
        reroutedLocalCodes = reroutedLocalCodes == null ? Set.of() : Set.copyOf(reroutedLocalCodes);
    }

    /** 空视图：平台编码缺失或该平台一条发布配置都没有时使用。 */
    public static CascadePlatformOrgView empty() {
        return new CascadePlatformOrgView(List.of(), Set.of());
    }
}
