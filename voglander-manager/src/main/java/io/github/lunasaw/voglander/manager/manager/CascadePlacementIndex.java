package io.github.lunasaw.voglander.manager.manager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import lombok.extern.slf4j.Slf4j;

/**
 * 某平台目录的父节点索引：本地标识 → 上级眼里的编码。
 *
 * <p><b>为什么要单独一个类，而不是留在组装器里</b>：目录组装（决定节点挂在哪个父节点）
 * 与「归属组织变更后删旧位置」（决定 {@code DEL} 报文里的 {@code ParentID}）必须算出
 * <b>同一个</b>父编码。两处各写一套解析，改一次口径就会一边把节点挂到新父、一边去删旧父下
 * 另一个节点，上级目录里留下重复条目——这正是本次要修的缺陷，不能在修的过程中再造一份。
 *
 * <p>把两种父节点来源（组织、设备）汇总成一处查询，是为了让「挂哪个节点」只在一个地方回答。
 * 散布在组装流程里的多段 if 很容易出现「通道挂到组织、事件却报到设备」这种错位。
 *
 * <p><b>只能读，不能改本地数据</b>：本类只做投影，不查库、不落库，索引内容全由调用方喂进来。
 * 这样组装器与变更通知可以各自决定「哪些组织和设备进索引」（例如组织不可见就不进），
 * 而解析规则保持唯一。
 *
 * @author luna
 */
@Slf4j
public final class CascadePlacementIndex {

    private final String              domainId;
    private final Set<String>         reroutedLocalCodes;
    /** 本地组织编码 → 平台组织编码 */
    private final Map<String, String> orgCodeByLocal      = new HashMap<>();
    /** 设备国标 ID → 平台设备编码（只在设备层开启时有内容） */
    private final Map<String, String> deviceCodeByLocal   = new HashMap<>();
    private       int                 orgCount;
    private       int                 deviceCount;

    private CascadePlacementIndex(String domainId, CascadePlatformOrgView orgView) {
        this.domainId = domainId;
        this.reroutedLocalCodes = orgView == null || orgView.reroutedLocalCodes() == null
            ? Set.of() : orgView.reroutedLocalCodes();
    }

    /**
     * 建一个空索引。
     *
     * @param domainId 域节点编码（本端平台编码），组织与设备都挂不住时的最终归宿
     * @param orgView  该平台的组织视图；{@code null} 视作「没有任何组织可见」
     * @return 空索引，调用方随后逐个 {@link #addOrg} / {@link #addDevice}
     */
    public static CascadePlacementIndex of(String domainId, CascadePlatformOrgView orgView) {
        return new CascadePlacementIndex(domainId, orgView);
    }

    /** 组织节点入索引：它将作为其下设备、通道的父节点候选。 */
    public void addOrg(CascadePlatformOrgDTO org) {
        if (org == null || StringUtils.isBlank(org.getLocalCode()) || StringUtils.isBlank(org.getPlatformCode())) {
            return;
        }
        orgCodeByLocal.put(org.getLocalCode(), org.getPlatformCode());
        orgCount++;
    }

    /** 设备节点入索引：它将作为其下通道的父节点候选。 */
    public void addDevice(CascadePlatformDeviceDTO device) {
        if (device == null || StringUtils.isBlank(device.getLocalDeviceId())
            || StringUtils.isBlank(device.getPlatformCode())) {
            return;
        }
        deviceCodeByLocal.put(device.getLocalDeviceId(), device.getPlatformCode());
        deviceCount++;
    }

    /**
     * 设备挂在哪个节点下：只能是它归属的组织；组织不可见则设备也不出现。
     *
     * @return 父节点编码；{@code null} 表示该设备不上报
     */
    public String resolveDeviceParent(CascadePlatformDeviceDTO device) {
        if (device == null) {
            return null;
        }
        String orgCode = StringUtils.trimToNull(device.getLocalOrgCode());
        if (orgCode == null) {
            return domainId;
        }
        String parent = orgCodeByLocal.get(orgCode);
        if (parent == null) {
            log.debug("设备归属组织不在该平台目录内，设备节点不出现: deviceId={}, orgCode={}",
                device.getLocalDeviceId(), orgCode);
        }
        return parent;
    }

    /**
     * 通道挂到哪个节点下（四级解析，前一级不可用才看下一级）。
     *
     * <ol>
     *   <li>通道<b>自己</b>归属的组织可见 → 组织节点</li>
     *   <li>设备已发布且设备层开启 → 设备节点</li>
     *   <li>设备归属的组织可见 → 组织节点（无设备层时的常规归宿）</li>
     *   <li>都不行：归属组织配了 {@code REROUTE} → 域节点；无归属 → 域节点；否则丢弃</li>
     * </ol>
     *
     * <p>第 1 级一旦命中就不再看设备层：通道显式直挂组织是业务分组意图，
     * 开了设备层不能把它悄悄挪到设备下面。组织不可见时也不回落到设备，
     * 而是按该组织的策略处理——否则用户停用一个组织，其下通道会从那个组织“搬家”到设备层。
     *
     * <p><b>没有「发布行指定父节点」这一级</b>：那个覆盖曾允许同一通道在不同上级挂不同父节点，
     * 实际用起来必然出现「改了本地组织、上级目录不动」的错位（快照是增量 upsert，旧位置不会自己消失），
     * 且改挂后没有任何报文能让上级重建父子关系。目录结构现在只有本地归属一个真相。
     *
     * @return 父节点编码；返回 null 表示该通道不上报
     */
    public String resolveChannelParent(CascadeChannelDTO channel) {
        if (channel == null) {
            return null;
        }
        String ownOrg = StringUtils.trimToNull(channel.getCascadeOrgId());
        if (ownOrg != null) {
            return visibleOrPolicy(ownOrg, channel.getCascadeChannelId());
        }
        String deviceCode = deviceCodeByLocal.get(channel.getLocalDeviceId());
        if (deviceCode != null) {
            return deviceCode;
        }
        String deviceOrg = StringUtils.trimToNull(channel.getDeviceOrgCode());
        if (deviceOrg != null) {
            return visibleOrPolicy(deviceOrg, channel.getCascadeChannelId());
        }
        /* 既无自身归属也无设备归属：挂域节点是允许的（早期行为已如此，上级能看到通道） */
        return domainId;
    }

    /**
     * 组织可见就用它，不可见就看该组织的 {@code revoke_policy}。
     *
     * @return 组织编码；{@code REROUTE} 返回域编码；{@code CASCADE} 或未配置返回 null（不上报）
     */
    private String visibleOrPolicy(String orgCode, String cascadeChannelId) {
        String code = orgCodeByLocal.get(orgCode);
        if (code != null) {
            return code;
        }
        if (reroutedLocalCodes.contains(orgCode)) {
            log.debug("组织不在该平台目录内且策略为 REROUTE，通道上浮挂域节点: cascadeChannelId={}, orgCode={}",
                cascadeChannelId, orgCode);
            return domainId;
        }
        log.debug("组织不在该平台目录内且策略为 CASCADE，通道不再上报: cascadeChannelId={}, orgCode={}",
            cascadeChannelId, orgCode);
        return null;
    }

    /** 域节点编码：所有解析的最终归宿，也是 {@code DEL} 找不到父节点时的兜底。 */
    public String domainId() {
        return domainId;
    }

    public int orgCount() {
        return orgCount;
    }

    public int deviceCount() {
        return deviceCount;
    }
}
