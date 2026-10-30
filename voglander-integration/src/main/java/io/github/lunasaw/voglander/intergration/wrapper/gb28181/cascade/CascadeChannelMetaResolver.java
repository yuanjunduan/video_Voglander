package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联通道目录项元数据解析：合成上级真正会看到的厂商 / 型号 / 地址 / IP / 摄像机类型。
 *
 * <p><b>为什么不能只用级联表里的值</b>：这三类数据分散在三个地方，级联表只该保存"人工纠正"，
 * 其余应取实时真值，否则设备换了型号、换了 IP，上级目录还停在旧值上。
 *
 * <ol>
 *   <li><b>覆盖值</b>：{@code tb_cascade_channel.manufacturer/model/address/ip_address/ptz_type}，
 *       人工补录优先（RTSP 设备没有安装地址、老国标设备目录不带型号时唯一手段）</li>
 *   <li><b>设备通道 extend</b>：GB28181 通道的 {@code channelInfo} 是目录项 JSON，
 *       {@code manufacturer/model/address/ipAddress/ptzType} 一应俱全；发现类通道则是
 *       {@code vendor/sourceModel/sourceIp}</li>
 *   <li><b>设备表</b>：{@code tb_device.ip}，作为 IP 的最后一级兜底</li>
 * </ol>
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeChannelMetaResolver {

    /** 摄像机类型缺省值：枪机。上级按它区分监控点类型，缺省成空容易被当成非视频通道 */
    public static final int    DEFAULT_PTZ_TYPE     = 3;
    /** 安装地址兜底：地址为空时上级部分实现会判为无效条目 */
    public static final String DEFAULT_ADDRESS      = "China";
    /** 厂商兜底：参考实现（CMS→CSS）对非大华/非中维的厂商一律展示 Hik */
    public static final String DEFAULT_MANUFACTURER = "Hik";

    private final DeviceChannelManager deviceChannelManager;
    private final DeviceManager        deviceManager;

    /**
     * 批量解析：一次取回涉及的通道 extend 与设备 IP，避免逐通道回表。
     *
     * @param channels 级联通道列表
     * @return key 为 {@code deviceId#channelId}（{@link DeviceChannelManager#channelKey}）
     */
    public Map<String, CascadeChannelMeta> resolve(List<CascadeChannelDTO> channels) {
        if (channels == null || channels.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> deviceIds = channels.stream()
            .map(CascadeChannelDTO::getLocalDeviceId)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        Map<String, String> extendMap = safeMap(deviceChannelManager.mapChannelExtend(deviceIds));
        Map<String, String> deviceIpMap = loadDeviceIp(deviceIds);

        Map<String, CascadeChannelMeta> result = new HashMap<>(channels.size());
        for (CascadeChannelDTO channel : channels) {
            String key = DeviceChannelManager.channelKey(channel.getLocalDeviceId(), channel.getLocalChannelId());
            result.put(key, resolveOne(channel, extendMap.get(key), deviceIpMap.get(channel.getLocalDeviceId())));
        }
        return result;
    }

    /**
     * 单条解析。
     *
     * @param channel       级联通道（覆盖值来源）
     * @param extendJson    {@code tb_device_channel.extend} 原文
     * @param deviceIp      {@code tb_device.ip}
     */
    public CascadeChannelMeta resolveOne(CascadeChannelDTO channel, String extendJson, String deviceIp) {
        JSONObject extend = parseObject(extendJson);
        /* channelInfo 是框架 DeviceItem 的 JSON 字符串，嵌在 extend 里 */
        JSONObject item = parseObject(extend.getString("channelInfo"));
        String vendor = extend.getString("vendor");

        String manufacturer = firstText(channel.getManufacturer(),
            normalizeVendor(item.getString("manufacturer")), normalizeVendor(vendor));
        if (manufacturer == null) {
            manufacturer = DEFAULT_MANUFACTURER;
        }
        String model = firstText(channel.getModel(), item.getString("model"), extend.getString("sourceModel"));
        String address = firstText(channel.getAddress(), item.getString("address"), DEFAULT_ADDRESS);
        String ipAddress = firstText(channel.getIpAddress(), item.getString("ipAddress"),
            extend.getString("sourceIp"), deviceIp);
        Integer ptzType = resolvePtzType(channel.getPtzType(), item.getInteger("ptzType"));
        return new CascadeChannelMeta(manufacturer, model, address, ipAddress, ptzType);
    }

    /**
     * 厂商归一：上级目录里的 Manufacturer 是枚举化展示值。参考实现（CMS 存数字厂商枚举，
     * CSS 的 {@code convertManuTypeToString}）只输出三种写法：大华 {@code Dahua}、中维
     * {@code Jovision}，其余（海康、宇视、天地、科达…）一律 {@code Hik}。这里沿用它，
     * 否则同一台设备在两级平台上会显示成两个厂商。
     */
    private String normalizeVendor(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String vendor = raw.trim().toLowerCase();
        if (vendor.contains("jov")) {
            return "Jovision";
        }
        if (vendor.contains("dah")) {
            return "Dahua";
        }
        return DEFAULT_MANUFACTURER;
    }

    /** 摄像机类型只认 1/2/3；目录里常见的 0、9 等非标准值统一按枪机处理。 */
    private Integer resolvePtzType(Integer override, Integer fromCatalog) {
        if (isValidPtzType(override)) {
            return override;
        }
        if (isValidPtzType(fromCatalog)) {
            return fromCatalog;
        }
        return DEFAULT_PTZ_TYPE;
    }

    private boolean isValidPtzType(Integer ptzType) {
        return ptzType != null && ptzType >= 1 && ptzType <= 3;
    }

    /** 设备 IP 兜底：设备数量通常个位数，逐设备取一次比构造 IN 查询更直观。 */
    private Map<String, String> loadDeviceIp(Collection<String> deviceIds) {
        Map<String, String> ipMap = new HashMap<>(deviceIds.size());
        for (String deviceId : deviceIds) {
            try {
                DeviceDTO device = deviceManager.getDtoByDeviceId(deviceId);
                if (device != null && StringUtils.isNotBlank(device.getIp())) {
                    ipMap.put(deviceId, device.getIp());
                }
            } catch (Exception e) {
                /* 兜底数据取不到不应让整棵目录下发失败 */
                log.warn("取设备 IP 失败，跳过兜底: deviceId={}, error={}", deviceId, e.getMessage());
            }
        }
        return ipMap;
    }

    private JSONObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSON.parseObject(json);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception e) {
            /* channelInfo 是历史落库的原文，格式坏掉时只丢派生值，不能影响目录上报 */
            log.debug("通道扩展信息解析失败，按无派生值处理: {}", e.getMessage());
            return new JSONObject();
        }
    }

    private Map<String, String> safeMap(Map<String, String> map) {
        return map == null ? Collections.emptyMap() : map;
    }

    /** 依次取第一个非空白值。 */
    private String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.isNotBlank(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }
}
