package io.github.lunasaw.voglander.manager.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 设备删除编排：先向上级取消上报并拿到确认，再删本地（发布层 → 通道 → 设备本体）。
 *
 * <p><b>为什么需要这一层</b>：设备与通道是两个管理器（{@link DeviceManager}、
 * {@link DeviceChannelManager}），而通道管理器又反过来依赖设备管理器——让
 * {@code DeviceManager} 自己去删通道会形成循环依赖，让 Controller 顺次调两个管理器
 * 则会让「设备必须带通道一起删」这条规则只存在于某一个页面。设备删除是唯一的调用方，
 * 收敛到这里之后，任何入口（单删、批删、以后的管理接口）都不会漏。
 *
 * <p><b>为什么事务边界被拆成两段</b>：向上级取消上报要同步等 SIP 2xx（最长 10s），
 * 把它包在数据库事务里会让连接被网络等待占住——SQLite 下更是整库写锁。所以这里
 * 先在<b>事务外</b>完成撤销与发布层清理，确认之后才开事务删本地；撤销未确认直接抛出，
 * 本地一条都不动。
 *
 * <p><b>顺序为什么不能换</b>：
 * <ul>
 *   <li>撤销排最先：一台设备可能推给多个上级，通道节点与设备节点必须落在同一次撤销里
 *       按「子 → 父」发出；拆到两步会产生两个事件，异步执行器不保证顺序；</li>
 *   <li>本地通道排在设备之前：通道行的删除条件是按 {@code device_id} 匹配的，设备没了就再也选不中，
 *       留下的是永久孤儿；</li>
 *   <li>设备本体排最后：前一步失败时设备还在，用户能重试，而不是只剩一堆无法追溯的通道。</li>
 * </ul>
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceRemovalManager {

    private final DeviceManager        deviceManager;
    private final DeviceChannelManager deviceChannelManager;
    private final CascadeAssetCleanup  cascadeAssetCleanup;
    private final TransactionTemplate  transactionTemplate;

    /**
     * 删除一台设备：连同它名下全部通道与这些对象的级联发布关系。
     *
     * @param id 设备主键
     * @return 是否删除；设备不存在返回 {@code false}
     * @throws IllegalArgumentException 上级未确认取消上报——此时本地数据一条都没动
     */
    public boolean removeById(Long id) {
        if (id == null) {
            return false;
        }
        DeviceDTO device = deviceManager.getDeviceDTOById(id);
        if (device == null) {
            log.warn("要删除的设备不存在: id={}", id);
            return false;
        }
        /* 1) 事务外取消上报并确认（发布行一并清掉） */
        revokePublishLayer(device);
        /* 2) 确认之后才删本地 */
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> removeLocal(device)));
    }

    /**
     * 批量删除设备：整批先撤销，全部确认后才在一个事务里删本地。
     *
     * <p>不做「单台失败跳过」：设备删除会同时清掉通道与发布关系，半成功对用户来说
     * 比整批失败更难收拾——他无法判断哪些设备已经删干净。批内任何一台的撤销没确认，
     * 整批本地数据都不动，与「取消成功后才可以删除本地数据」的口径一致。
     *
     * @param ids 设备主键集合
     * @return 实际删除的设备数
     * @throws IllegalArgumentException 有设备未被上级确认撤销——此时本地数据一条都没动
     */
    public int removeByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<DeviceDTO> devices = new ArrayList<>(ids.size());
        for (Long id : new LinkedHashSet<>(ids)) {
            if (id == null) {
                continue;
            }
            DeviceDTO device = deviceManager.getDeviceDTOById(id);
            if (device == null) {
                log.warn("要删除的设备不存在，跳过: id={}", id);
                continue;
            }
            devices.add(device);
        }
        if (devices.isEmpty()) {
            return 0;
        }
        for (DeviceDTO device : devices) {
            revokePublishLayer(device);
        }
        Integer removed = transactionTemplate.execute(status -> {
            int count = 0;
            for (DeviceDTO device : devices) {
                if (removeLocal(device)) {
                    count++;
                }
            }
            return count;
        });
        int result = removed == null ? 0 : removed;
        log.info("批量删除设备完成: 请求={}, 实删={}", ids.size(), result);
        return result;
    }

    /**
     * 取消上报 + 清发布层，见类注释里对事务边界的说明。
     *
     * <p>通道节点与设备节点在一次调用里发完，不拆成两步——异步事件被多线程执行会乱序，
     * 父节点先消失会让子节点的删除被部分上级忽略。
     */
    private void revokePublishLayer(DeviceDTO device) {
        String deviceId = device.getDeviceId();
        if (StringUtils.isBlank(deviceId)) {
            log.warn("设备缺少设备编码，跳过级联撤销与通道清理: id={}", device.getId());
            return;
        }
        cascadeAssetCleanup.revokeDevice(deviceId);
    }

    /**
     * 撤销已确认后的本地删除：通道 → 设备本体。
     *
     * <p>通道批量删除入口内部还会再撤一次发布层，但发布行已在上一步清空，
     * 那次撤销自然是一条报文都不发的空转，不必为此再开一个「只删本地」的通道入口。
     *
     * @return 设备本体是否真的删掉了
     */
    private boolean removeLocal(DeviceDTO device) {
        String deviceId = device.getDeviceId();
        if (StringUtils.isNotBlank(deviceId)) {
            DeviceChannelDTO query = new DeviceChannelDTO();
            query.setDeviceId(deviceId);
            deviceChannelManager.deleteBatch(query);
        }
        DeviceDTO delete = new DeviceDTO();
        delete.setId(device.getId());
        boolean removed = Boolean.TRUE.equals(deviceManager.deleteOne(delete));
        log.info("设备已删除: id={}, deviceId={}, removed={}", device.getId(), deviceId, removed);
        return removed;
    }
}
