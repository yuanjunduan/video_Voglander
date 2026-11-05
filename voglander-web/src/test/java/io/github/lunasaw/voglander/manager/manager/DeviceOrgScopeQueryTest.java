package io.github.lunasaw.voglander.manager.manager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceQueryDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.manager.service.DeviceChannelService;
import io.github.lunasaw.voglander.manager.service.DeviceService;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 按组织浏览设备与通道：精确 / 含子树两种口径。
 *
 * <p>守三条语义：
 * <ul>
 *   <li>设备挂在叶子组织上，{@code includeChildren=false} 时选上级组织只应命中直属设备；</li>
 *   <li>{@code includeChildren=true} 时沿 {@code org_path} 展开，下级组织的设备也要带出来；</li>
 *   <li><b>通道的组织归属来自所属设备</b>——通道行上的 {@code org_id} 已停写（恒 NULL），
 *       若按通道列过滤会一条都查不到，这是改造前的真实缺陷。</li>
 * </ul>
 *
 * <p>组织树必须真实落库：子树展开走的是 {@code org_path} 前缀匹配，裸 id 造不出路径。
 *
 * @author luna
 */
@DisplayName("按组织浏览设备与通道（精确 / 含子树）")
class DeviceOrgScopeQueryTest extends BaseTest {

    @Autowired
    private DeviceManager        deviceManager;

    @Autowired
    private DeviceChannelManager deviceChannelManager;

    @Autowired
    private CascadeOrgManager    cascadeOrgManager;

    @Autowired
    private DeviceService        deviceService;

    @Autowired
    private DeviceChannelService deviceChannelService;

    @Autowired
    private CascadeOrgService    cascadeOrgService;

    private final List<String> createdDeviceIds = new ArrayList<>();
    private final List<Long>   createdOrgIds    = new ArrayList<>();

    @AfterEach
    void cleanup() {
        /* 先删通道再删设备：通道没有外键，但留着会污染其他用例的通道数统计 */
        for (String deviceId : createdDeviceIds) {
            deviceChannelService.remove(
                new LambdaQueryWrapper<DeviceChannelDO>().eq(DeviceChannelDO::getDeviceId, deviceId));
            deviceService.remove(new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getDeviceId, deviceId));
        }
        /* 子组织先于父组织删，倒序正好是「后建先删」 */
        for (int i = createdOrgIds.size() - 1; i >= 0; i--) {
            cascadeOrgService.removeById(createdOrgIds.get(i));
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("设备：精确组织只命中直属设备")
    void devicePage_withoutChildren_matchesDirectOrgOnly() {
        Fixture f = arrangeTree();

        Set<String> deviceIds = deviceIds(query(f.parentOrgId, false));

        assertTrue(deviceIds.contains(f.parentDeviceId), "直属设备必须命中");
        assertFalse(deviceIds.contains(f.childDeviceId), "下级组织的设备不该在「仅本级」里出现");
    }

    @Test
    @DisplayName("设备：含下级组织时把子树设备一起带出")
    void devicePage_withChildren_includesDescendantDevices() {
        Fixture f = arrangeTree();

        Set<String> deviceIds = deviceIds(query(f.parentOrgId, true));

        assertTrue(deviceIds.contains(f.parentDeviceId), "直属设备必须命中");
        assertTrue(deviceIds.contains(f.childDeviceId), "下级组织的设备必须一起带出");
    }

    @Test
    @DisplayName("通道：按组织过滤走所属设备，不看通道行上的 org_id")
    void channelPage_orgFilter_followsDeviceOrg() {
        Fixture f = arrangeTree();

        Set<String> channelIds = channelIds(f.parentOrgId, false);

        assertTrue(channelIds.contains(f.parentChannelId), "直属设备的通道必须命中");
        assertFalse(channelIds.contains(f.childChannelId), "下级设备组织的通道不该在「仅本级」里出现");
    }

    @Test
    @DisplayName("通道：含下级组织时把子树设备的通道一起带出")
    void channelPage_orgFilter_includesDescendantChannels() {
        Fixture f = arrangeTree();

        Set<String> channelIds = channelIds(f.parentOrgId, true);

        assertTrue(channelIds.contains(f.parentChannelId), "直属设备的通道必须命中");
        assertTrue(channelIds.contains(f.childChannelId), "下级设备组织的通道必须一起带出");
    }

    @Test
    @DisplayName("未归组织（orgId=0）：设备与通道都按「设备没有组织」匹配")
    void orgScope_zero_matchesUnassigned() {
        String deviceId = createDeviceWithLabel("unassigned", null);
        String channelId = createChannel(deviceId);

        Set<String> deviceIds = deviceIds(query(0L, false));
        assertTrue(deviceIds.contains(deviceId), "orgId=0 应命中未归组织的设备");

        Set<String> channelIds = channelIds(0L, false);
        assertTrue(channelIds.contains(channelId), "orgId=0 应命中未归组织设备的通道");
    }

    // ------------------------------------------------------------------ 夹具

    /** 一次用例造出的四条资产标识，避免逐字段重复取值 */
    private record Fixture(Long parentOrgId, Long childOrgId, String parentDeviceId, String childDeviceId,
        String parentChannelId, String childChannelId) {
    }

    /**
     * 父组织 P → 子组织 C；设备 A 挂 P、设备 B 挂 C，各带一条通道。
     *
     * <p>通道行上的 {@code org_id} 保持为空（与生产口径一致）：如果能按它查到，说明过滤走错了列。
     */
    private Fixture arrangeTree() {
        Long parentOrgId = createOrg("父组织", null);
        Long childOrgId = createOrg("子组织", parentOrgId);

        String parentDeviceId = createDevice(parentOrgId);
        String childDeviceId = createDevice(childOrgId);

        return new Fixture(parentOrgId, childOrgId, parentDeviceId, childDeviceId,
            createChannel(parentDeviceId), createChannel(childDeviceId));
    }

    private Long createOrg(String label, Long parentId) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setOrgName(label + "-" + UniqueKeyFactory.deviceId());
        dto.setParentId(parentId);
        Long id = cascadeOrgManager.add(dto);
        createdOrgIds.add(id);
        return id;
    }

    private String createDevice(Long orgId) {
        return createDeviceWithLabel("dev-org", orgId);
    }

    private String createDeviceWithLabel(String label, Long orgId) {
        String deviceId = UniqueKeyFactory.deviceId();
        DeviceDTO dto = new DeviceDTO();
        dto.setDeviceId(deviceId);
        dto.setName(label + "-" + deviceId);
        dto.setIp("127.0.0.1");
        dto.setPort(5060);
        dto.setType(1);
        dto.setServerIp("127.0.0.1");
        if (orgId != null) {
            dto.setOrgId(orgId);
        }
        deviceManager.add(dto);
        createdDeviceIds.add(deviceId);
        return deviceId;
    }

    private String createChannel(String deviceId) {
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setDeviceId(deviceId);
        dto.setChannelId(UniqueKeyFactory.channelId());
        dto.setName("ch-" + dto.getChannelId());
        deviceChannelManager.add(dto);
        return dto.getChannelId();
    }

    private DeviceQueryDTO query(Long orgId, boolean includeChildren) {
        DeviceQueryDTO query = new DeviceQueryDTO();
        query.setOrgId(orgId);
        query.setIncludeChildren(includeChildren);
        return query;
    }

    private Set<String> deviceIds(DeviceQueryDTO query) {
        Page<DeviceDTO> page = deviceManager.getPage(query, 1, 500);
        return page.getRecords().stream().map(DeviceDTO::getDeviceId).collect(Collectors.toSet());
    }

    private Set<String> channelIds(Long orgId, boolean includeChildren) {
        DeviceChannelDTO query = new DeviceChannelDTO();
        query.setOrgId(orgId);
        query.setIncludeChildren(includeChildren);
        Page<DeviceChannelDTO> page = deviceChannelManager.getPage(query, 1, 500);
        return page.getRecords().stream().map(DeviceChannelDTO::getChannelId).collect(Collectors.toSet());
    }
}
