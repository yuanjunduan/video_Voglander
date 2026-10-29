package io.github.lunasaw.voglander.intergration.wrapper.channel;

import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoverResult;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;

/**
 * 通道发现器：问设备"你下面有哪些通道"，把结果翻译成厂商无关的 {@link DiscoverResult}。
 *
 * <p>
 * 背景：RTSP/ONVIF 拉流链路是设备级的，一台 NVR 挂 8 路通道就得建 8 条设备记录，
 * 通道号还要人工按厂商规则拼进 URL。本接口把这件事变成"读设备自己的台账"——
 * 海康走 ISAPI、大华走 CGI、通用 ONVIF 走 GetProfiles，上层只认返回结果，不关心厂商差异。
 * </p>
 *
 * <p>
 * 实现约束：
 * </p>
 * <ul>
 *   <li>发现失败不得抛业务异常，用 {@link DiscoverResult#failure} 返回——调用方要靠它区分
 *       "设备说没有通道"（成功但空列表）与"连不上设备"（失败），后者绝不能触发台账清理。</li>
 *   <li>通道地址由各家自己按规则拼装，凭据取自设备级已配置的拉流地址，不额外落库。</li>
 *   <li>发现是只读操作：不发任何会改变设备配置的请求。</li>
 * </ul>
 *
 * @author luna
 * @date 2026/10/27
 */
public interface ChannelDiscoverer {

    /**
     * 默认超时（毫秒）：查台账是两次 HTTP 请求，局域网内 3s 足够
     */
    int DEFAULT_TIMEOUT_MS = 3000;

    /**
     * 厂商标识，与 {@link DiscoverResult#getVendor()} 一致
     */
    String vendor();

    /**
     * 能否处理该设备。只看"这个设备是否具备被发现的前提"（有可解析的地址与凭据），
     * 不做网络探测——真正的可达性判断在 {@link #discover} 里。
     */
    boolean supports(DeviceDTO device);

    /**
     * 执行发现。
     *
     * @param device    设备（提供地址与凭据）
     * @param timeoutMs 单次 HTTP 超时
     * @return 成功（可能空通道列表）或失败（带原因）；不抛业务异常
     */
    DiscoverResult discover(DeviceDTO device, int timeoutMs);

    /**
     * 用默认超时执行发现。
     */
    default DiscoverResult discover(DeviceDTO device) {
        return discover(device, DEFAULT_TIMEOUT_MS);
    }
}
