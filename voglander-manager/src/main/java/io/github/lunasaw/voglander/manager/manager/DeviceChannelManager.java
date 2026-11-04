package io.github.lunasaw.voglander.manager.manager;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.luna.common.check.Assert;
import io.github.lunasaw.voglander.common.constant.device.DeviceConstant;
import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.common.exception.ServiceExceptionEnum;
import io.github.lunasaw.voglander.manager.assembler.DeviceChannelAssembler;
import io.github.lunasaw.voglander.manager.cache.DeviceChannelCacheKey;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.service.DeviceChannelService;
import io.github.lunasaw.voglander.repository.cache.redis.RedisCache;
import io.github.lunasaw.voglander.repository.cache.redis.RedisLockUtil;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 设备通道管理器
 * 处理设备通道相关的复杂业务逻辑
 *
 * <p>
 * 架构设计：
 * </p>
 * <ul>
 * <li>统一修改入口：{@link #deviceChannelInternal(DeviceChannelDO, String)} - 所有修改操作的核心方法</li>
 * <li>统一删除入口：{@link #deleteDeviceChannelInternal(Long, String, boolean)} - 所有删除操作的核心方法</li>
 * <li>统一缓存管理：{@link #clearDeviceChannelCache(String, String, Long)} - 统一的缓存清理逻辑</li>
 * </ul>
 *
 * <p>
 * 优势：
 * </p>
 * <ul>
 * <li>缓存一致性：所有修改和删除操作都通过统一入口，确保缓存清理的一致性</li>
 * <li>日志规范：统一的日志记录格式和内容</li>
 * <li>异常处理：统一的数据校验和异常处理逻辑</li>
 * <li>维护便捷：业务逻辑变更只需修改核心方法</li>
 * </ul>
 *
 * @author luna
 * @date 2023/12/31
 */
@Slf4j
@Component
public class DeviceChannelManager {

    /**
     * 设备通道缓存Key前缀
     */
    private static final String  DEVICE_CHANNEL_CACHE_PREFIX   = "device:channel:";

    /**
     * 设备通道列表缓存Key
     */
    private static final String  DEVICE_CHANNEL_LIST_CACHE_KEY = "device:channel:list";

    /**
     * 设备通道分布式锁前缀
     */
    private static final String  DEVICE_CHANNEL_LOCK_PREFIX    = "device:channel:lock:";

    @Autowired
    private DeviceChannelService deviceChannelService;

    @Autowired
    private DeviceManager deviceManager;

    @Autowired
    private DeviceChannelAssembler deviceChannelAssembler;

    /**
     * 发布层撤销与清理：通道被删后，各平台的推送配置与被推上去的目录节点必须一起消失
     */
    @Autowired
    private CascadeAssetCleanup cascadeAssetCleanup;

    /**
     * 手动开事务：向上级取消上报要同步等回应（最长 10s），不能包在事务里占住数据库连接，
     * 因此删除流程被拆成「事务外撤销 + 事务内删本地」两段，由这里显式控制事务边界。
     */
    @Autowired
    private TransactionTemplate  transactionTemplate;

    @Autowired
    private RedisCache             redisCache;

    @Autowired
    private RedisLockUtil          redisLockUtil;

    @Autowired
    private CacheManager           cacheManager;

    @Value("${voglander.device-channel.cascade-offline-batch-size:500}")
    private int    cascadeOfflineBatchSize;

    @Value("${voglander.device-channel.missing-threshold:1}")
    private int    missingThreshold;

    @Value("${voglander.device-channel.enable-missing-scan:false}")
    private boolean missingScanEnabled;

    @Value("${voglander.device-channel.enable-status-from-catalog:true}")
    private boolean enableStatusFromCatalog;

    @Value("${voglander.device-channel.enable-offline-cascade:true}")
    private boolean enableOfflineCascade;

    @Value("${voglander.device-channel.enable-session-promotion:false}")
    private boolean enableSessionPromotion;

    /**
     * 删除设备通道（用户主动删除）：先向上级取消上报并拿到确认，才删本地记录
     *
     * <p>没确认就抛 {@link IllegalArgumentException}，本地一条不动；否则上级目录里会留下
     * 一个本地已不存在的节点，还能对着它发起点播。
     *
     * @param deviceId 设备ID
     * @param channelId 通道ID
     * @return 是否删除成功
     */
    public Boolean deleteDeviceChannel(String deviceId, String channelId) {
        Assert.hasText(deviceId, "设备ID不能为空");
        Assert.hasText(channelId, "通道ID不能为空");

        DeviceChannelDO deviceChannelDO = getByDeviceId(deviceId, channelId);
        if (deviceChannelDO == null) {
            throw new ServiceException("设备通道不存在，设备ID: " + deviceId + ", 通道ID: " + channelId);
        }

        // 事务外先撤销（发布行也一并清掉），再开事务删本地
        cascadeAssetCleanup.revokeChannel(deviceChannelDO.getDeviceId(), deviceChannelDO.getChannelId());
        return transactionTemplate.execute(
            status -> deleteDeviceChannelInternal(deviceChannelDO.getId(), "删除设备通道", false));
    }

    /**
     * 跟随设备上报的 Catalog {@code DEL} 删通道：不阻本地流程，只异步告知上级发布层同步清理。
     *
     * <p>与 {@link #deleteDeviceChannel} 的差别在于“谁发起”：设备自己已经把通道从台账里删了，
     * 本端跟随清理时再去等上级确认没有意义，也会让通道永远删不掉。
     *
     * @param deviceId 设备ID
     * @param channelId 通道ID
     * @return 是否删除成功
     */
    public Boolean deleteDeviceChannelUpstreamDeleted(String deviceId, String channelId) {
        Assert.hasText(deviceId, "设备ID不能为空");
        Assert.hasText(channelId, "通道ID不能为空");

        DeviceChannelDO deviceChannelDO = getByDeviceId(deviceId, channelId);
        if (deviceChannelDO == null) {
            throw new ServiceException("设备通道不存在，设备ID: " + deviceId + ", 通道ID: " + channelId);
        }

        // 调用统一删除入口方法
        return transactionTemplate.execute(
            status -> deleteDeviceChannelInternal(deviceChannelDO.getId(), "删除设备通道", true));
    }

    public DeviceChannelDO getByDeviceId(String deviceId, String channelId) {
        Assert.hasText(deviceId, "设备ID不能为空");
        Assert.hasText(channelId, "通道ID不能为空");
        QueryWrapper<DeviceChannelDO> queryWrapper = new QueryWrapper<DeviceChannelDO>().eq("device_id", deviceId)
                .eq("channel_id", channelId).last("limit 1");
        return deviceChannelService.getOne(queryWrapper);
    }

    /**
     * 按通道国标 ID 反查所属设备。
     *
     * <p>级联发布行只存 {@code deviceId:channelId}，而页面与推送通知常常只给通道编码；
     * 国标通道 ID 全局唯一，一次索引查询就能补齐设备段。
     *
     * @param channelId 通道国标 ID
     * @return 通道行；不存在返回 null
     */
    public DeviceChannelDO getByChannelId(String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return null;
        }
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(DeviceChannelDO::getChannelId, channelId).last("limit 1");
        return deviceChannelService.getOne(qw);
    }

    public DeviceChannelDTO getDtoByDeviceId(String deviceId, String channelId) {
        DeviceChannelDO byDeviceId = getByDeviceId(deviceId, channelId);
        return DeviceChannelDTO.convertDTO(byDeviceId);
    }

    /**
     * 根据ID获取设备通道DTO
     */
    public DeviceChannelDTO getDeviceChannelDTOById(Long id) {
        DeviceChannelDO deviceChannelDO = deviceChannelService.getById(id);
        return DeviceChannelDTO.convertDTO(deviceChannelDO);
    }

    /**
     * 根据条件获取设备通道DTO
     */
    public DeviceChannelDTO getDeviceChannelDTOByEntity(DeviceChannelDO deviceChannel) {
        QueryWrapper<DeviceChannelDO> query = new QueryWrapper<>(deviceChannel).last("limit 1");
        DeviceChannelDO deviceChannelDO = deviceChannelService.getOne(query);
        return DeviceChannelDTO.convertDTO(deviceChannelDO);
    }

    /**
     * 获取设备通道DTO列表
     */
    public List<DeviceChannelDTO> listDeviceChannelDTO(DeviceChannelDO deviceChannel) {
        QueryWrapper<DeviceChannelDO> query = new QueryWrapper<>(deviceChannel);
        List<DeviceChannelDO> deviceChannelList = deviceChannelService.list(query);
        return deviceChannelList.stream()
                .map(DeviceChannelDTO::convertDTO)
                .collect(Collectors.toList());
    }

    /**
     * 统计指定设备下的通道数（S1 设备列表 channelCount）。
     *
     * <p>
     * 用 IService 基础 count + 带 condition 的 LambdaQueryWrapper，禁止自定义 SQL。
     * 设备列表页内逐设备调用（页大小通常 ≤20，轻量可接受）。
     * </p>
     *
     * @param deviceId 设备国标 ID
     * @return 通道数；deviceId 为空返回 0
     */
    public long countByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return 0L;
        }
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(DeviceChannelDO::getDeviceId, deviceId);
        return deviceChannelService.count(qw);
    }

    /**
     * 统计直接归属到某组织的通道数（不含通过设备继承归属的通道）。
     *
     * <p>删除组织前用它做占用校验：通道自带 {@code org_id} 是为了表达
     * "设备在 A 组织、某路通道归 B 业务分组"，因此这里只数显式归属。
     *
     * @param orgId 组织主键
     * @return 直接归属的通道数
     */
    public long countByOrgId(Long orgId) {
        if (orgId == null) {
            return 0L;
        }
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(DeviceChannelDO::getOrgId, orgId);
        return deviceChannelService.count(qw);
    }

    /**
     * 列出直接归属到某组织的通道（组装器与通道页共用）。
     *
     * @param orgId 组织主键
     * @return 通道 DO 列表；orgId 为空返回空列表
     */
    public List<DeviceChannelDO> listByOrgId(Long orgId) {
        if (orgId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(DeviceChannelDO::getOrgId, orgId);
        return deviceChannelService.list(qw);
    }

    /**
     * 通道 → 组织归属的轻量投影（只取 {@code device_id} / {@code org_id}）。
     *
     * <p>刻意全表扫：组织树统计通道数要同时覆盖"通道显式归属组织"与"通道跟随设备归属"，
     * 只取 {@code org_id} 非空的通道会漏掉后者。
     */
    public List<DeviceChannelDO> listOrgLinkProjection() {
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.select(DeviceChannelDO::getDeviceId, DeviceChannelDO::getOrgId);
        return deviceChannelService.list(qw);
    }

    /**
     * 统计给定设备下"未显式归属组织"的通道数（这些通道跟随设备归属）。
     *
     * @param deviceIds 设备国标 ID 集合
     * @return 通道数；集合为空返回 0
     */
    public long countUnownedByDeviceIds(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0L;
        }
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.in(DeviceChannelDO::getDeviceId, deviceIds)
            .isNull(DeviceChannelDO::getOrgId);
        return deviceChannelService.count(qw);
    }

    /**
     * 批量取通道在线状态：按设备集合一次查出，避免目录应答时逐通道回表（N+1）。
     *
     * <p>级联目录应答里每个通道都要带 ON/OFF，一次查询可能涉及上百通道；
     * 用一条 {@code in(deviceId)} 查询取回后，在内存按 {@link #channelKey} 查找。
     * </p>
     *
     * @param deviceIds 设备国标 ID 集合
     * @return key 为 {@code deviceId#channelId}，value 为库内状态（1 在线 0 离线）
     */
    public Map<String, Integer> mapChannelStatus(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.select(DeviceChannelDO::getDeviceId, DeviceChannelDO::getChannelId, DeviceChannelDO::getStatus)
            .in(DeviceChannelDO::getDeviceId, deviceIds);
        Map<String, Integer> statusMap = new HashMap<>();
        for (DeviceChannelDO channel : deviceChannelService.list(qw)) {
            statusMap.put(channelKey(channel.getDeviceId(), channel.getChannelId()), channel.getStatus());
        }
        return statusMap;
    }

    /**
     * 通道状态 Map 的键：设备 ID 与通道 ID 用 {@code #} 拼接，避免前缀歧义。
     */
    public static String channelKey(String deviceId, String channelId) {
        return deviceId + "#" + channelId;
    }

    /**
     * 按设备集合一次取回通道行（含名称/组织/启停/状态）。
     *
     * <p>供发布层组装目录时用：发布行只存业务键，节点名称、本地归属与本地启停都在这里，
     * 逐通道回表会让百路目录变成百次查询。
     *
     * @param deviceIds 设备国标 ID 集合
     * @return 通道行；集合为空返回空列表
     */
    public List<DeviceChannelDO> listByDeviceIds(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.in(DeviceChannelDO::getDeviceId, deviceIds);
        return deviceChannelService.list(qw);
    }

    /**
     * 批量取通道扩展信息原文（{@code tb_device_channel.extend}）。
     *
     * <p>级联目录要按通道派生厂商/型号/地址/IP/PTZ 类型，这些字段全在 extend 里：
     * GB28181 通道是目录项 JSON（{@code channelInfo}），发现类通道是 {@code vendor/sourceModel/sourceIp}。
     * 与 {@link #mapChannelStatus} 同样一次 {@code in(deviceId)} 取回，避免逐通道回表。
     *
     * @param deviceIds 设备国标 ID 集合
     * @return key 为 {@link #channelKey}，value 为 extend 原文（可能为空串）
     */
    public Map<String, String> mapChannelExtend(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.select(DeviceChannelDO::getDeviceId, DeviceChannelDO::getChannelId, DeviceChannelDO::getExtend)
            .in(DeviceChannelDO::getDeviceId, deviceIds);
        Map<String, String> extendMap = new HashMap<>();
        for (DeviceChannelDO channel : deviceChannelService.list(qw)) {
            extendMap.put(channelKey(channel.getDeviceId(), channel.getChannelId()), channel.getExtend());
        }
        return extendMap;
    }

    /**
     * 分页查询设备通道DTO
     */
    public Page<DeviceChannelDTO> pageQuery(int page, int size, QueryWrapper<DeviceChannelDO> query) {
        Page<DeviceChannelDO> queryPage = new Page<>(page, size);
        Page<DeviceChannelDO> pageInfo = deviceChannelService.page(queryPage, query);

        // 转换为DTO分页对象
        Page<DeviceChannelDTO> resultPage = new Page<>(page, size);
        List<DeviceChannelDTO> dtoList = pageInfo.getRecords().stream()
                .map(DeviceChannelDTO::convertDTO)
                .collect(Collectors.toList());

        resultPage.setRecords(dtoList);
        resultPage.setTotal(pageInfo.getTotal());
        resultPage.setCurrent(pageInfo.getCurrent());
        resultPage.setSize(pageInfo.getSize());
        resultPage.setPages(pageInfo.getPages());

        return resultPage;
    }

    /**
     * 简单分页查询设备通道DTO
     */
    public Page<DeviceChannelDTO> pageQuerySimple(int page, int size) {
        Page<DeviceChannelDO> queryPage = new Page<>(page, size);
        Page<DeviceChannelDO> pageInfo = deviceChannelService.page(queryPage);

        // 转换为DTO分页对象
        Page<DeviceChannelDTO> resultPage = new Page<>(page, size);
        List<DeviceChannelDTO> dtoList = pageInfo.getRecords().stream()
                .map(DeviceChannelDTO::convertDTO)
                .collect(Collectors.toList());

        resultPage.setRecords(dtoList);
        resultPage.setTotal(pageInfo.getTotal());
        resultPage.setCurrent(pageInfo.getCurrent());
        resultPage.setSize(pageInfo.getSize());
        resultPage.setPages(pageInfo.getPages());

        return resultPage;
    }

    /**
     * 创建设备通道
     *
     * @param deviceChannelDTO 设备通道DTO
     * @return 设备通道ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createDeviceChannel(DeviceChannelDTO deviceChannelDTO) {
        Assert.notNull(deviceChannelDTO, "设备通道信息不能为空");
        Assert.hasText(deviceChannelDTO.getDeviceId(), "设备ID不能为空");
        Assert.hasText(deviceChannelDTO.getChannelId(), "通道ID不能为空");

        // 检查设备是否存在
        DeviceDTO deviceDTO = deviceManager.getDtoByDeviceId(deviceChannelDTO.getDeviceId());
        if (deviceDTO == null) {
            throw new ServiceException("设备不存在: " + deviceChannelDTO.getDeviceId());
        }

        // 检查通道ID是否已存在
        DeviceChannelDO existingChannel = getByDeviceId(deviceChannelDTO.getDeviceId(), deviceChannelDTO.getChannelId());
        if (existingChannel != null) {
            throw new ServiceException("设备通道已存在: " + deviceChannelDTO.getChannelId());
        }

        DeviceChannelDO deviceChannelDO = DeviceChannelDTO.convertDO(deviceChannelDTO);
        deviceChannelDO.setCreateTime(LocalDateTime.now());

        // 调用统一入口方法
        return deviceChannelInternal(deviceChannelDO, "创建设备通道");
    }

    /**
     * 批量创建设备通道 - 使用统一入口保证缓存一致性
     *
     * @param deviceChannelDTOList 设备通道DTO列表
     * @return 成功创建的数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchCreateDeviceChannel(List<DeviceChannelDTO> deviceChannelDTOList) {
        if (deviceChannelDTOList == null || deviceChannelDTOList.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        for (DeviceChannelDTO dto : deviceChannelDTOList) {
            try {
                createDeviceChannel(dto);
                successCount++;
            } catch (Exception e) {
                log.error("批量创建设备通道失败，设备ID: {}, 通道ID: {}, error: {}",
                    dto.getDeviceId(), dto.getChannelId(), e.getMessage());
            }
        }

        log.info("批量创建设备通道完成，成功创建: {} 个通道，总计: {} 个", successCount, deviceChannelDTOList.size());
        return successCount;
    }

    /**
     * 更新设备通道
     *
     * @param deviceChannelDTO 设备通道DTO
     * @return 设备通道ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long updateDeviceChannel(DeviceChannelDTO deviceChannelDTO) {
        Assert.notNull(deviceChannelDTO, "设备通道信息不能为空");
        Assert.notNull(deviceChannelDTO.getId(), "设备通道ID不能为空");

        // 检查设备通道是否存在
        DeviceChannelDO existingChannel = deviceChannelService.getById(deviceChannelDTO.getId());
        if (existingChannel == null) {
            throw new ServiceException("设备通道不存在，ID: " + deviceChannelDTO.getId());
        }

        // 更新入参一般只带 name/status：extend 里的 RTSP 地址、编码、分辨率由发现与点播回填写入，
        // 必须以库中现值为基底合并，否则一次"改名"就把这些探测结果冲成 null
        if (deviceChannelDTO.getExtendInfo() != null || StringUtils.isNotBlank(existingChannel.getExtend())) {
            deviceChannelDTO.setExtendInfo(
                DeviceChannelDTO.mergeExtend(existingChannel.getExtend(), deviceChannelDTO.getExtendInfo()));
        }

        DeviceChannelDO deviceChannelDO = DeviceChannelDTO.convertDO(deviceChannelDTO);
        deviceChannelDO.setUpdateTime(LocalDateTime.now());

        // 调用统一入口方法
        return deviceChannelInternal(deviceChannelDO, "更新设备通道");
    }

    /**
     * 批量更新设备通道 - 使用统一入口保证缓存一致性
     *
     * @param deviceChannelDTOList 设备通道DTO列表
     * @return 成功更新的数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateDeviceChannel(List<DeviceChannelDTO> deviceChannelDTOList) {
        if (deviceChannelDTOList == null || deviceChannelDTOList.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        for (DeviceChannelDTO dto : deviceChannelDTOList) {
            try {
                updateDeviceChannel(dto);
                successCount++;
            } catch (Exception e) {
                log.error("批量更新设备通道失败，设备通道ID: {}, error: {}", dto.getId(), e.getMessage());
            }
        }

        log.info("批量更新设备通道完成，成功更新: {} 个通道，总计: {} 个", successCount, deviceChannelDTOList.size());
        return successCount;
    }

    /**
     * 设备通道数据操作统一入口 - 负责统一的缓存清理、日志记录和数据校验
     *
     * @param deviceChannelDO 设备通道数据对象
     * @param operationType 操作类型描述
     * @return 设备通道ID
     */
    private Long deviceChannelInternal(DeviceChannelDO deviceChannelDO, String operationType) {
        Assert.notNull(deviceChannelDO, "设备通道数据不能为空");
        Assert.hasText(operationType, "操作类型不能为空");

        String lockKey = DEVICE_CHANNEL_LOCK_PREFIX + deviceChannelDO.getDeviceId() + ":" +
            (deviceChannelDO.getChannelId() != null ? deviceChannelDO.getChannelId() : "new");

        try {
            // 获取分布式锁
            if (!redisLockUtil.tryLock(lockKey, 5)) {
                throw new ServiceException("系统繁忙，请稍后重试");
            }

            boolean isUpdate = deviceChannelDO.getId() != null;
            boolean result;

            if (isUpdate) {
                result = deviceChannelService.updateById(deviceChannelDO);
            } else {
                result = deviceChannelService.save(deviceChannelDO);
            }

            if (result) {
                // 清理相关缓存
                clearDeviceChannelCache(deviceChannelDO.getDeviceId(), deviceChannelDO.getChannelId(), deviceChannelDO.getId());

                log.info("{}成功，设备ID：{}，通道ID：{}，数据库ID：{}", operationType,
                    deviceChannelDO.getDeviceId(), deviceChannelDO.getChannelId(), deviceChannelDO.getId());
                return deviceChannelDO.getId();
            } else {
                throw new ServiceException(operationType + "失败");
            }
        } catch (Exception e) {
            log.error("{}失败，设备ID：{}，通道ID：{}，错误信息：{}", operationType,
                deviceChannelDO.getDeviceId(), deviceChannelDO.getChannelId(), e.getMessage());
            throw e;
        } finally {
            // 释放分布式锁
            redisLockUtil.unLock(lockKey);
        }
    }

    /**
     * 设备通道删除统一入口 - 负责统一的缓存清理、日志记录和数据校验
     *
     * @param deviceChannelId 设备通道数据库ID
     * @param operationType 操作类型描述
     * @param notifyUpstream 是否走“删完再异步通知上级”的被动链路；{@code false} 表示发布层已由
     *                       调用方同步撤销并确认，这里不能再发一次 {@code DEL}
     * @return 是否删除成功
     */
    private boolean deleteDeviceChannelInternal(Long deviceChannelId, String operationType, boolean notifyUpstream) {
        Assert.notNull(deviceChannelId, "设备通道ID不能为空");
        Assert.hasText(operationType, "操作类型不能为空");

        DeviceChannelDO existingChannel = deviceChannelService.getById(deviceChannelId);
        if (existingChannel == null) {
            throw new ServiceException("设备通道不存在，ID: " + deviceChannelId);
        }

        String lockKey = DEVICE_CHANNEL_LOCK_PREFIX + existingChannel.getDeviceId() + ":" + existingChannel.getChannelId();

        try {
            // 获取分布式锁
            if (!redisLockUtil.tryLock(lockKey, 5)) {
                throw new ServiceException("系统繁忙，请稍后重试");
            }

            boolean result = deviceChannelService.removeById(deviceChannelId);

            if (result) {
                // 清理相关缓存
                clearDeviceChannelCache(existingChannel.getDeviceId(), existingChannel.getChannelId(), deviceChannelId);

                /* 被动链路：本地通道没了，发布行与被推上去的目录节点都是孤儿；不显式 DEL，上级会永久留着那个节点 */
                if (notifyUpstream) {
                    cascadeAssetCleanup.purgeChannel(existingChannel.getDeviceId(), existingChannel.getChannelId());
                }

                log.info("{}成功，设备ID：{}，通道ID：{}，数据库ID：{}", operationType,
                    existingChannel.getDeviceId(), existingChannel.getChannelId(), deviceChannelId);
                return true;
            } else {
                throw new ServiceException(operationType + "失败");
            }
        } catch (Exception e) {
            log.error("{}失败，设备ID：{}，通道ID：{}，错误信息：{}", operationType,
                existingChannel.getDeviceId(), existingChannel.getChannelId(), e.getMessage());
            throw e;
        } finally {
            // 释放分布式锁
            redisLockUtil.unLock(lockKey);
        }
    }

    /**
     * 清理设备通道相关缓存
     *
     * @param deviceId 设备ID（可为null）
     * @param channelId 通道ID（可为null）
     * @param dbId 数据库ID（可为null）
     */
    private void clearDeviceChannelCache(String deviceId, String channelId, Long dbId) {
        try {
            // 清理设备通道缓存
            if (deviceId != null && channelId != null) {
                redisCache.deleteKey(DEVICE_CHANNEL_CACHE_PREFIX + deviceId + ":" + channelId);
            }
            if (dbId != null) {
                redisCache.deleteKey(DEVICE_CHANNEL_CACHE_PREFIX + dbId);
            }

            // 清理设备通道列表缓存
            redisCache.deleteKey(DEVICE_CHANNEL_LIST_CACHE_KEY);

            log.debug("清理设备通道缓存成功，设备ID：{}，通道ID：{}，数据库ID：{}", deviceId, channelId, dbId);
        } catch (Exception e) {
            log.error("清理设备通道缓存失败，设备ID：{}，通道ID：{}，数据库ID：{}，错误信息：{}", deviceId, channelId, dbId, e.getMessage());
        }
    }

    /**
     * 保存或更新设备通道 - 兼容现有调用的方法
     * 如果设备通道不存在则创建，如果存在则更新
     *
     * @param deviceChannelDTO 设备通道DTO对象
     * @return 设备通道ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveOrUpdate(DeviceChannelDTO deviceChannelDTO) {
        Assert.notNull(deviceChannelDTO, "设备通道信息不能为空");
        Assert.hasText(deviceChannelDTO.getDeviceId(), "设备ID不能为空");
        Assert.hasText(deviceChannelDTO.getChannelId(), "通道ID不能为空");

        // 检查设备通道是否已存在
        DeviceChannelDO existingChannel = getByDeviceId(deviceChannelDTO.getDeviceId(), deviceChannelDTO.getChannelId());

        if (existingChannel != null) {
            // 设备通道存在，更新设备通道
            deviceChannelDTO.setId(existingChannel.getId());
            return updateDeviceChannel(deviceChannelDTO);
        } else {
            // 设备通道不存在，创建新设备通道
            return createDeviceChannel(deviceChannelDTO);
        }
    }

    // ================================
    // 核心模板方法（必须实现）
    // ================================

    /**
     * 批量幂等 upsert 设备通道（Phase 4：修 P4 目录 N+1 + §8/M1 跨节点幂等）。
     * <p>
     * 取代逐条 {@code saveOrUpdate} 的 N+1。流程：按 (device_id, channel_id) 业务键<strong>一次性</strong>
     * 查出已存在记录 → 分流为新增/更新两批 → 各自批量落库（单事务）。命中 UNIQUE
     * {@code (channel_id, device_id)} 的并发插入（跨节点重传/漂移）通过捕获 {@code DuplicateKeyException}
     * 转更新兜底，保证幂等、不撞唯一键。DB 无关（不依赖 MySQL/SQLite 各自 upsert 语法）。
     * </p>
     *
     * @param dtoList 通道 DTO 列表（同一 deviceId 下的目录条目）
     * @return 处理的有效记录数（新增 + 更新）
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchUpsert(List<DeviceChannelDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            return 0;
        }

        // 过滤无效项 + 同批内按 (deviceId, channelId) 去重（保留后者，重传目录可能含重复）
        java.util.Map<String, DeviceChannelDTO> dedup = new java.util.LinkedHashMap<>();
        for (DeviceChannelDTO dto : dtoList) {
            if (dto == null || dto.getDeviceId() == null || dto.getChannelId() == null) {
                continue;
            }
            dedup.put(dto.getDeviceId() + ":" + dto.getChannelId(), dto);
        }
        if (dedup.isEmpty()) {
            return 0;
        }

        // 一次性查出这批业务键已存在的记录（避免逐条查）
        List<String> deviceIds = dedup.values().stream().map(DeviceChannelDTO::getDeviceId).distinct().collect(Collectors.toList());
        List<String> channelIds = dedup.values().stream().map(DeviceChannelDTO::getChannelId).distinct().collect(Collectors.toList());
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.in(DeviceChannelDO::getDeviceId, deviceIds).in(DeviceChannelDO::getChannelId, channelIds);
        java.util.Map<String, DeviceChannelDO> existing = deviceChannelService.list(qw).stream()
            .collect(Collectors.toMap(d -> d.getDeviceId() + ":" + d.getChannelId(), d -> d, (a, b) -> a));

        List<DeviceChannelDO> toInsert = new ArrayList<>();
        List<DeviceChannelDO> toUpdate = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (java.util.Map.Entry<String, DeviceChannelDTO> e : dedup.entrySet()) {
            DeviceChannelDO target = deviceChannelAssembler.dtoToDo(e.getValue());
            target.setUpdateTime(now);
            DeviceChannelDO exist = existing.get(e.getKey());
            if (exist != null) {
                target.setId(exist.getId());
                target.setCreateTime(exist.getCreateTime());
                toUpdate.add(target);
            } else {
                target.setCreateTime(now);
                toInsert.add(target);
            }
        }

        int affected = 0;
        if (!toUpdate.isEmpty()) {
            deviceChannelService.updateBatchById(toUpdate);
            affected += toUpdate.size();
        }
        if (!toInsert.isEmpty()) {
            try {
                deviceChannelService.saveBatch(toInsert);
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 跨节点并发：另一节点已插入同 (channel_id, device_id) → 逐条转更新兜底（M1）
                log.warn("批量插入命中 UNIQUE 冲突，转逐条 upsert 兜底 - {}", dup.getMessage());
                for (DeviceChannelDO ins : toInsert) {
                    DeviceChannelDO cur = getByDeviceId(ins.getDeviceId(), ins.getChannelId());
                    if (cur != null) {
                        ins.setId(cur.getId());
                        deviceChannelService.updateById(ins);
                    } else {
                        deviceChannelService.save(ins);
                    }
                }
            }
            affected += toInsert.size();
        }

        // 清理列表缓存（单对象缓存按需在各自入口清理）
        clearCache(null, null, null);
        log.info("批量 upsert 设备通道完成 - 新增={}, 更新={}, 合计={}", toInsert.size(), toUpdate.size(), affected);
        return affected;
    }

    /**
     * 模板方法：新增数据
     * 标准流程：校验参数 -> 转换DO -> 插入数据库 -> 返回ID
     * 注意：默认值依赖数据库字段默认值，不在代码中设置
     */
    @Transactional(rollbackFor = Exception.class)
    public Long add(DeviceChannelDTO deviceChannelDTO) {
        // 校验必要参数
        Assert.notNull(deviceChannelDTO, "设备通道信息不能为空");
        Assert.hasText(deviceChannelDTO.getDeviceId(), "设备ID不能为空");
        Assert.hasText(deviceChannelDTO.getChannelId(), "通道ID不能为空");
        assertEnabledValid(deviceChannelDTO.getEnabled());

        try {
            // 转为DO
            DeviceChannelDO deviceChannelDO = deviceChannelAssembler.dtoToDo(deviceChannelDTO);
            // 新增时 0 与 null 等价：组织主键从 1 开始，0 永远不是合法组织
            deviceChannelDO.setOrgId(normalizeOrgId(deviceChannelDTO.getOrgId()));
            deviceChannelDO.setCreateTime(LocalDateTime.now());
            deviceChannelDO.setUpdateTime(LocalDateTime.now());

            // 插入DB（依赖数据库默认值）
            boolean success = deviceChannelService.save(deviceChannelDO);
            if (!success) {
                throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, "数据库插入失败");
            }

            // 清理相关缓存
            clearCache(deviceChannelDO.getId(), null, buildDeviceChannelKey(deviceChannelDO.getDeviceId(), deviceChannelDO.getChannelId()));

            return deviceChannelDO.getId();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("新增设备通道失败 - 错误: {}", e.getMessage(), e);
            throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, e.getMessage());
        }
    }

    /**
     * 模板方法：条件更新数据（通用版本）
     * 标准流程：校验参数 -> 根据查询条件查找记录 -> 应用更新内容 -> 更新数据库
     * 查询条件和更新内容完全分离，提供最大的灵活性
     */
    @Transactional(rollbackFor = Exception.class)
    public Long update(DeviceChannelDTO queryDTO, DeviceChannelDTO updateDTO) {
        Assert.notNull(queryDTO, "查询条件不能为空");
        Assert.notNull(updateDTO, "更新内容不能为空");

        try {
            // 构建查询条件
            DeviceChannelDO queryDO = deviceChannelAssembler.dtoToDo(queryDTO);
            LambdaQueryWrapper<DeviceChannelDO> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(queryDO.getId() != null, DeviceChannelDO::getId, queryDO.getId())
                .eq(queryDO.getDeviceId() != null, DeviceChannelDO::getDeviceId, queryDO.getDeviceId())
                .eq(queryDO.getChannelId() != null, DeviceChannelDO::getChannelId, queryDO.getChannelId())
                .eq(queryDO.getStatus() != null, DeviceChannelDO::getStatus, queryDO.getStatus())
                .eq(queryDO.getEnabled() != null, DeviceChannelDO::getEnabled, queryDO.getEnabled())
                .like(queryDO.getName() != null, DeviceChannelDO::getName, queryDO.getName());

            appendOrgIdCondition(queryWrapper, queryDO.getOrgId());
            queryWrapper.last("limit 1");

            DeviceChannelDO existingRecord = deviceChannelService.getOne(queryWrapper);
            if (existingRecord == null) {
                throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, "未找到要更新的记录");
            }

            String oldKey = buildDeviceChannelKey(existingRecord.getDeviceId(), existingRecord.getChannelId());

            // 应用更新内容
            DeviceChannelDO updateDO = deviceChannelAssembler.dtoToDo(updateDTO);
            updateDO.setId(existingRecord.getId());
            updateDO.setUpdateTime(LocalDateTime.now());

            assertEnabledValid(updateDTO.getEnabled());
            // org_id 用 0 表示"清空归属"（落库为 null，改为跟随设备归属）：updateById 跳过 null 列、
            // 无法把"有值"改回"无值"，故先把 0 从实体里摘掉，更新成功后再补一条 SET org_id = NULL
            boolean clearOrgId = updateDTO.getOrgId() != null && updateDTO.getOrgId() == 0L;
            if (clearOrgId) {
                updateDO.setOrgId(null);
            }

            boolean success = deviceChannelService.updateById(updateDO);
            if (!success) {
                throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, "数据库更新失败");
            }
            if (clearOrgId) {
                resetOrgId(existingRecord.getId());
            }

            String newKey = buildDeviceChannelKey(updateDO.getDeviceId(), updateDO.getChannelId());
            clearCache(updateDO.getId(), oldKey, newKey);

            return updateDO.getId();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("更新设备通道失败 - 错误: {}", e.getMessage(), e);
            throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, e.getMessage());
        }
    }

    /**
     * 扩展方法：通过ID更新指定字段
     * 最常用的更新方式，直接通过主键ID更新指定字段
     */
    @Transactional(rollbackFor = Exception.class)
    public Long updateById(Long id, DeviceChannelDTO updateDTO) {
        Assert.notNull(id, "设备通道ID不能为空");
        Assert.notNull(updateDTO, "更新内容不能为空");

        DeviceChannelDTO queryDTO = new DeviceChannelDTO();
        queryDTO.setId(id);

        return update(queryDTO, updateDTO);
    }

    /**
     * 模板方法：单条查询
     * 标准流程：校验参数 -> 转换DO条件 -> 查询数据库 -> 转换并返回DTO
     * 查询实现：使用LambdaQueryWrapper进行类型安全的条件构建
     */
    public DeviceChannelDTO get(DeviceChannelDTO deviceChannelDTO) {
        Assert.notNull(deviceChannelDTO, "查询条件不能为空");

        try {
            DeviceChannelDO deviceChannelDO = deviceChannelAssembler.dtoToDo(deviceChannelDTO);
            LambdaQueryWrapper<DeviceChannelDO> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(deviceChannelDO.getId() != null, DeviceChannelDO::getId, deviceChannelDO.getId())
                .eq(deviceChannelDO.getDeviceId() != null, DeviceChannelDO::getDeviceId, deviceChannelDO.getDeviceId())
                .eq(deviceChannelDO.getChannelId() != null, DeviceChannelDO::getChannelId, deviceChannelDO.getChannelId())
                .eq(deviceChannelDO.getStatus() != null, DeviceChannelDO::getStatus, deviceChannelDO.getStatus())
                .eq(deviceChannelDO.getEnabled() != null, DeviceChannelDO::getEnabled, deviceChannelDO.getEnabled())
                .like(deviceChannelDO.getName() != null, DeviceChannelDO::getName, deviceChannelDO.getName());

            appendOrgIdCondition(queryWrapper, deviceChannelDO.getOrgId());
            queryWrapper.last("limit 1");

            DeviceChannelDO existingRecord = deviceChannelService.getOne(queryWrapper);
            if (existingRecord == null) {
                return null;
            }

            return deviceChannelAssembler.doToDto(existingRecord);
        } catch (Exception e) {
            log.error("查询设备通道失败 - 错误: {}", e.getMessage(), e);
            throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, e.getMessage());
        }
    }

    /**
     * 模板方法：删除单条记录
     * 标准流程：校验参数 -> 通过条件查找 -> 向上级取消上报并确认 -> 删除数据库记录 -> 清理缓存
     * 删除实现：使用LambdaQueryWrapper构建查询条件
     */
    public Boolean deleteOne(DeviceChannelDTO deviceChannelDTO) {
        Assert.notNull(deviceChannelDTO, "删除条件不能为空");

        try {
            DeviceChannelDO deviceChannelDO = deviceChannelAssembler.dtoToDo(deviceChannelDTO);
            LambdaQueryWrapper<DeviceChannelDO> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(deviceChannelDO.getId() != null, DeviceChannelDO::getId, deviceChannelDO.getId())
                .eq(deviceChannelDO.getDeviceId() != null, DeviceChannelDO::getDeviceId, deviceChannelDO.getDeviceId())
                .eq(deviceChannelDO.getChannelId() != null, DeviceChannelDO::getChannelId, deviceChannelDO.getChannelId())
                .eq(deviceChannelDO.getStatus() != null, DeviceChannelDO::getStatus, deviceChannelDO.getStatus())
                .like(deviceChannelDO.getName() != null, DeviceChannelDO::getName, deviceChannelDO.getName())
                .last("limit 1");

            DeviceChannelDO existingRecord = deviceChannelService.getOne(queryWrapper);
            if (existingRecord == null) {
                log.warn("删除设备通道：记录不存在");
                return true;
            }

            String oldKey = buildDeviceChannelKey(existingRecord.getDeviceId(), existingRecord.getChannelId());

            /* 先取消上报再删本地，且放在事务外：未获确认直接抛出，本地一条都不动 */
            cascadeAssetCleanup.revokeChannel(existingRecord.getDeviceId(), existingRecord.getChannelId());

            return transactionTemplate.execute(status -> {
                boolean success = deviceChannelService.removeById(existingRecord.getId());
                if (!success) {
                    throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, "数据库删除失败");
                }
                clearCache(existingRecord.getId(), oldKey, null);
                return true;
            });
        } catch (ServiceException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            /* 撤销未确认的原因必须原样透到调用方，洗成通用失败后用户不知道该先去哪个平台处理 */
            throw e;
        } catch (Exception e) {
            log.error("删除设备通道失败 - 错误: {}", e.getMessage(), e);
            throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, e.getMessage());
        }
    }

    /**
     * 模板方法：批量删除记录
     * 标准流程：校验参数 -> 转换DO条件 -> 查询匹配记录 -> 向上级取消上报并确认 -> 批量删除 -> 清理缓存
     * 批量实现：使用LambdaQueryWrapper构建批量删除条件
     */
    public Boolean deleteBatch(DeviceChannelDTO deviceChannelDTO) {
        Assert.notNull(deviceChannelDTO, "删除条件不能为空");

        try {
            DeviceChannelDO deviceChannelDO = deviceChannelAssembler.dtoToDo(deviceChannelDTO);
            LambdaQueryWrapper<DeviceChannelDO> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(deviceChannelDO.getId() != null, DeviceChannelDO::getId, deviceChannelDO.getId())
                .eq(deviceChannelDO.getDeviceId() != null, DeviceChannelDO::getDeviceId, deviceChannelDO.getDeviceId())
                .eq(deviceChannelDO.getChannelId() != null, DeviceChannelDO::getChannelId, deviceChannelDO.getChannelId())
                .eq(deviceChannelDO.getStatus() != null, DeviceChannelDO::getStatus, deviceChannelDO.getStatus())
                .like(deviceChannelDO.getName() != null, DeviceChannelDO::getName, deviceChannelDO.getName());

            List<DeviceChannelDO> recordsToDelete = deviceChannelService.list(queryWrapper);
            if (recordsToDelete.isEmpty()) {
                log.warn("批量删除设备通道：无匹配记录");
                return true;
            }

            /* 先取消上报：任一平台未确认就抛出，本地通道与发布行都保持原样 */
            revokePublishRows(recordsToDelete);

            return transactionTemplate.execute(status -> {
                boolean success = deviceChannelService.remove(queryWrapper);
                if (!success) {
                    throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, "数据库批量删除失败");
                }
                // 清理所有相关缓存
                for (DeviceChannelDO record : recordsToDelete) {
                    String key = buildDeviceChannelKey(record.getDeviceId(), record.getChannelId());
                    clearCache(record.getId(), key, null);
                }
                return true;
            });
        } catch (ServiceException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("批量删除设备通道失败 - 错误: {}", e.getMessage(), e);
            throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, e.getMessage());
        }
    }

    /**
     * 批量删除前的发布层撤销：按设备分组一次查完，避免逐通道回表。
     */
    private void revokePublishRows(List<DeviceChannelDO> channels) {
        Map<String, List<String>> channelIdsByDevice = new LinkedHashMap<>();
        for (DeviceChannelDO channel : channels) {
            if (StringUtils.isBlank(channel.getDeviceId()) || StringUtils.isBlank(channel.getChannelId())) {
                continue;
            }
            channelIdsByDevice.computeIfAbsent(channel.getDeviceId(), key -> new ArrayList<>())
                .add(channel.getChannelId());
        }
        channelIdsByDevice.forEach(cascadeAssetCleanup::revokeChannels);
    }

    /**
     * 模板方法：分页查询
     * 标准流程：校验参数 -> 转换DO条件 -> 分页查询数据库 -> 转换记录为DTO -> 返回Page<DTO>
     * 分页实现：使用LambdaQueryWrapper + 默认排序（在线优先 status 降序，同状态内创建时间降序）
     */
    public Page<DeviceChannelDTO> getPage(DeviceChannelDTO deviceChannelDTO, int page, int size) {
        if (page < 1)
            throw new IllegalArgumentException("页码必须大于0");
        if (size < 1 || size > 1000)
            throw new IllegalArgumentException("页大小必须在1-1000之间");

        try {
            DeviceChannelDO deviceChannelDO = deviceChannelAssembler.dtoToDo(deviceChannelDTO);
            LambdaQueryWrapper<DeviceChannelDO> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(deviceChannelDO.getId() != null, DeviceChannelDO::getId, deviceChannelDO.getId())
                .eq(deviceChannelDO.getDeviceId() != null, DeviceChannelDO::getDeviceId, deviceChannelDO.getDeviceId())
                .eq(deviceChannelDO.getChannelId() != null, DeviceChannelDO::getChannelId, deviceChannelDO.getChannelId())
                .eq(deviceChannelDO.getStatus() != null, DeviceChannelDO::getStatus, deviceChannelDO.getStatus())
                .eq(deviceChannelDO.getEnabled() != null, DeviceChannelDO::getEnabled, deviceChannelDO.getEnabled())
                .like(deviceChannelDO.getName() != null, DeviceChannelDO::getName, deviceChannelDO.getName())
                // 在线优先：status=1(在线) 降序排前、0(离线) 在后；同状态内再按创建时间降序
                .orderByDesc(DeviceChannelDO::getStatus)
                .orderByDesc(DeviceChannelDO::getCreateTime);

            appendOrgIdCondition(queryWrapper, deviceChannelDO.getOrgId());

            Page<DeviceChannelDO> pageQuery = new Page<>(page, size);
            Page<DeviceChannelDO> doPage = deviceChannelService.page(pageQuery, queryWrapper);

            // 转换为DTO分页结果
            Page<DeviceChannelDTO> dtoPage = new Page<>(page, size);
            dtoPage.setTotal(doPage.getTotal());
            dtoPage.setPages(doPage.getPages());
            dtoPage.setCurrent(doPage.getCurrent());
            dtoPage.setSize(doPage.getSize());

            List<DeviceChannelDTO> dtoRecords = deviceChannelAssembler.doListToDtoList(doPage.getRecords());
            dtoPage.setRecords(dtoRecords);

            return dtoPage;
        } catch (Exception e) {
            log.error("分页查询设备通道失败 - 错误: {}", e.getMessage(), e);
            throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, e.getMessage());
        }
    }

    // ================================
    // 本地资产层：组织归属与本地启停
    // ================================

    /**
     * 组织归属筛选：{@code 0} 表示"未归组织"，按 IS NULL 查；其余按 id 精确匹配。
     *
     * <p>{@code null} 表示本次不按组织筛（不限）。
     */
    private static void appendOrgIdCondition(LambdaQueryWrapper<DeviceChannelDO> wrapper, Long orgId) {
        if (orgId == null) {
            return;
        }
        if (orgId == 0L) {
            wrapper.isNull(DeviceChannelDO::getOrgId);
        } else {
            wrapper.eq(DeviceChannelDO::getOrgId, orgId);
        }
    }

    /**
     * 新增时的组织归属归一：{@code 0} 与 {@code null} 等价（组织主键从 1 开始，0 不是合法组织）。
     */
    private static Long normalizeOrgId(Long orgId) {
        return orgId != null && orgId == 0L ? null : orgId;
    }

    /**
     * 校验本地启用标记：只接受 0/1，{@code null} 表示本次不改。
     *
     * <p>该列决定通道是否进入上级平台目录，脏值会让组装器与前端开关判断同时失效。
     */
    private static void assertEnabledValid(Integer enabled) {
        if (enabled != null && enabled != 0 && enabled != 1) {
            throw new ServiceException(ServiceExceptionEnum.CHANNEL_OPERATION_FAILED, "本地启用标记只能是 0 或 1: " + enabled);
        }
    }

    /**
     * 清空通道的组织归属（落库为 null，改为跟随设备归属）。
     *
     * <p>{@code updateById} 默认跳过 null 列，没有这条定向 UPDATE 就无法把通道从组织里移走。
     */
    private void resetOrgId(Long id) {
        deviceChannelService.lambdaUpdate()
            .eq(DeviceChannelDO::getId, id)
            .set(DeviceChannelDO::getOrgId, null)
            .update();
    }

    // ================================
    // 私有工具方法
    // ================================

    /**
     * 统一缓存清理模板方法
     * 支持主键ID、业务键双重缓存清理策略
     */
    private void clearCache(Long id, String oldKey, String newKey) {
        try {
            // 根据ID清理缓存
            if (id != null) {
                Optional.ofNullable(cacheManager.getCache("deviceChannel"))
                    .ifPresent(cache -> cache.evict(id));
            }

            // 根据旧业务键清理缓存
            if (oldKey != null) {
                Optional.ofNullable(cacheManager.getCache("deviceChannel"))
                    .ifPresent(cache -> cache.evict(oldKey));
            }

            // 根据新业务键清理缓存（如果与旧键不同）
            if (newKey != null && !newKey.equals(oldKey)) {
                Optional.ofNullable(cacheManager.getCache("deviceChannel"))
                    .ifPresent(cache -> cache.evict(newKey));
            }

            // 清理设备通道列表缓存
            redisCache.deleteKey(DEVICE_CHANNEL_LIST_CACHE_KEY);
        } catch (Exception e) {
            log.warn("缓存清理异常，但不影响业务流程: {}", e.getMessage());
        }
    }

    /**
     * 构建设备通道业务键
     */
    private String buildDeviceChannelKey(String deviceId, String channelId) {
        if (deviceId == null || channelId == null) {
            return null;
        }
        return deviceId + ":" + channelId;
    }

    // ================================
    // 1.0.4 Stage 0：精确缓存清理 API
    // ================================

    /**
     * 精确 evict 单通道缓存（C4：不循环触发）
     */
    private void clearCacheByChannel(Long id, String deviceId, String channelId) {
        try {
            Optional.ofNullable(cacheManager.getCache(DeviceChannelCacheKey.CACHE_NAME))
                .ifPresent(cache -> {
                    if (id != null) cache.evict(DeviceChannelCacheKey.byId(id));
                    if (deviceId != null && channelId != null) {
                        cache.evict(DeviceChannelCacheKey.byBizKey(deviceId, channelId));
                    }
                });
            if (deviceId != null) {
                Optional.ofNullable(cacheManager.getCache(DeviceChannelCacheKey.LIST_CACHE_NAME))
                    .ifPresent(cache -> cache.evict(DeviceChannelCacheKey.byDevice(deviceId)));
            }
        } catch (Exception e) {
            log.warn("clearCacheByChannel 异常 - id={}, deviceId={}, channelId={}: {}", id, deviceId, channelId, e.getMessage());
        }
    }

    /**
     * 精确 evict 整设备列表缓存（批量操作后调用一次，C4）
     */
    private void clearCacheByDevice(String deviceId) {
        try {
            Optional.ofNullable(cacheManager.getCache(DeviceChannelCacheKey.LIST_CACHE_NAME))
                .ifPresent(cache -> cache.evict(DeviceChannelCacheKey.byDevice(deviceId)));
        } catch (Exception e) {
            log.warn("clearCacheByDevice 异常 - deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    // ================================
    // 1.0.4 Stage 1：batchUpsertWithStatus
    // ================================

    /**
     * 目录响应批量幂等 upsert，带 status / lastSeenTime / statusSource 显式落地。
     *
     * R3 同一 deviceId 下所有 channel 一次事务落库；R5 一次 SELECT snapshot 复用；
     * R6 入口加设备粒度锁，竞争丢弃；R2 lastSeenTime 单调；C2 DuplicateKey 条件 UPDATE 兜底。
     *
     * @return 实际处理记录数；锁竞争或参数异常返回 0
     */
    public int batchUpsertWithStatus(String deviceId, List<DeviceChannelDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty() || deviceId == null) return 0;
        if (!enableStatusFromCatalog) {
            return batchUpsert(dtoList);
        }

        String lockKey = DEVICE_CHANNEL_LOCK_PREFIX + deviceId;
        String lockValue = redisLockUtil.generateLockValue();
        if (!redisLockUtil.tryLock(lockKey, lockValue, 10)) {
            log.warn("batchUpsertWithStatus 锁竞争，丢弃本次 catalog - deviceId={}, size={}", deviceId, dtoList.size());
            return 0;
        }
        try {
            return doBatchUpsertWithStatus(deviceId, dtoList);
        } finally {
            redisLockUtil.unLock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected int doBatchUpsertWithStatus(String deviceId, List<DeviceChannelDTO> dtoList) {
        /* 同批内去重，保留后者 */
        Map<String, DeviceChannelDTO> dedup = new LinkedHashMap<>();
        for (DeviceChannelDTO dto : dtoList) {
            if (dto == null || dto.getChannelId() == null) continue;
            dedup.put(dto.getChannelId(), dto);
        }
        if (dedup.isEmpty()) return 0;

        /* R5：一次 SELECT snapshot */
        LambdaQueryWrapper<DeviceChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(DeviceChannelDO::getDeviceId, deviceId)
          .select(DeviceChannelDO::getId, DeviceChannelDO::getChannelId,
                  DeviceChannelDO::getLastSeenTime, DeviceChannelDO::getStatus,
                  DeviceChannelDO::getMissingCount);
        Map<String, DeviceChannelDO> snapshot = deviceChannelService.list(qw).stream()
            .collect(Collectors.toMap(DeviceChannelDO::getChannelId, d -> d, (a, b) -> a));

        List<DeviceChannelDO> toInsert = new ArrayList<>();
        List<DeviceChannelDO> toUpdate = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<String, DeviceChannelDTO> e : dedup.entrySet()) {
            DeviceChannelDTO dto = e.getValue();
            DeviceChannelDO exist = snapshot.get(e.getKey());
            DeviceChannelDO target = deviceChannelAssembler.dtoToDo(dto);
            target.setUpdateTime(now);

            if (exist != null) {
                /* R2 单调判定 */
                if (dto.getLastSeenTime() != null && exist.getLastSeenTime() != null
                        && dto.getLastSeenTime().isBefore(exist.getLastSeenTime())) {
                    target.setStatus(null);
                    target.setLastSeenTime(null);
                    target.setStatusSource(null);
                }
                target.setMissingCount(0);
                target.setId(exist.getId());
                toUpdate.add(target);
            } else {
                target.setCreateTime(now);
                target.setMissingCount(0);
                toInsert.add(target);
            }
        }

        int affected = 0;
        if (!toUpdate.isEmpty()) {
            deviceChannelService.updateBatchById(toUpdate);
            affected += toUpdate.size();
        }
        if (!toInsert.isEmpty()) {
            try {
                deviceChannelService.saveBatch(toInsert);
                affected += toInsert.size();
            } catch (DuplicateKeyException dup) {
                affected += duplicateKeyFallback(toInsert);
            }
        }

        /* Stage 4 失踪扫描（同事务 R3） */
        if (missingScanEnabled) {
            markMissingChannelsInternal(deviceId, dedup.keySet(), snapshot, now);
        }

        /* C4：事务结束后一次清缓存 */
        clearCacheByDevice(deviceId);
        log.info("batchUpsertWithStatus 完成 - deviceId={}, 新增={}, 更新={}", deviceId, toInsert.size(), toUpdate.size());
        return affected;
    }

    /** C2：DuplicateKey 兜底，条件 UPDATE 不退化 N+1 */
    private int duplicateKeyFallback(List<DeviceChannelDO> toInsert) {
        log.warn("batchUpsertWithStatus UNIQUE 冲突 - size={}", toInsert.size());
        try { Thread.sleep(50 + ThreadLocalRandom.current().nextInt(150)); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }

        int n = 0;
        for (DeviceChannelDO ins : toInsert) {
            LambdaUpdateWrapper<DeviceChannelDO> uw = new LambdaUpdateWrapper<DeviceChannelDO>()
                .eq(DeviceChannelDO::getDeviceId, ins.getDeviceId())
                .eq(DeviceChannelDO::getChannelId, ins.getChannelId())
                .set(ins.getStatus() != null, DeviceChannelDO::getStatus, ins.getStatus())
                .set(ins.getLastSeenTime() != null, DeviceChannelDO::getLastSeenTime, ins.getLastSeenTime())
                .set(ins.getStatusSource() != null, DeviceChannelDO::getStatusSource, ins.getStatusSource())
                .set(ins.getName() != null, DeviceChannelDO::getName, ins.getName())
                .set(ins.getExtend() != null, DeviceChannelDO::getExtend, ins.getExtend())
                .set(DeviceChannelDO::getMissingCount, 0)
                .set(DeviceChannelDO::getUpdateTime, LocalDateTime.now());
            if (deviceChannelService.update(null, uw)) {
                n++;
            } else {
                try { deviceChannelService.save(ins); n++; }
                catch (DuplicateKeyException ignored) {
                    log.warn("二次冲突，放弃 - deviceId={}, channelId={}", ins.getDeviceId(), ins.getChannelId());
                }
            }
        }
        return n;
    }

    // ================================
    // 1.0.4 Stage 2：cascadeOffline
    // ================================

    /**
     * 设备级离线级联：把该设备下所有 channel 写 OFFLINE（终态，无单调条件 R1）。
     * R6 设备粒度锁；R7 超过 batch-size 时分批 UPDATE。
     */
    public int cascadeOffline(String deviceId) {
        Assert.hasText(deviceId, "设备ID不能为空");
        if (!enableOfflineCascade) return 0;

        String lockKey = DEVICE_CHANNEL_LOCK_PREFIX + deviceId;
        String lockValue = redisLockUtil.generateLockValue();
        if (!redisLockUtil.tryLock(lockKey, lockValue, 10)) {
            log.warn("cascadeOffline 锁竞争，丢弃 - deviceId={}", deviceId);
            return 0;
        }
        try {
            long total = deviceChannelService.count(
                new LambdaQueryWrapper<DeviceChannelDO>()
                    .eq(DeviceChannelDO::getDeviceId, deviceId)
                    .ne(DeviceChannelDO::getStatus, DeviceConstant.Status.OFFLINE));
            if (total == 0) return 0;

            int totalAffected = (total <= cascadeOfflineBatchSize)
                ? doCascadeOfflineSingleBatch(deviceId)
                : doCascadeOfflineMultiBatch(deviceId);

            if (totalAffected > 0) {
                clearCacheByDevice(deviceId);
                log.info("cascadeOffline 完成 - deviceId={}, 行数={}", deviceId, totalAffected);
            }
            return totalAffected;
        } finally {
            redisLockUtil.unLock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected int doCascadeOfflineSingleBatch(String deviceId) {
        LambdaUpdateWrapper<DeviceChannelDO> uw = new LambdaUpdateWrapper<>();
        uw.eq(DeviceChannelDO::getDeviceId, deviceId)
          .ne(DeviceChannelDO::getStatus, DeviceConstant.Status.OFFLINE)
          .set(DeviceChannelDO::getStatus, DeviceConstant.Status.OFFLINE)
          .set(DeviceChannelDO::getStatusSource, "OFFLINE_CASCADE")
          .set(DeviceChannelDO::getUpdateTime, LocalDateTime.now());
        return deviceChannelService.getBaseMapper().update(null, uw);
    }

    protected int doCascadeOfflineMultiBatch(String deviceId) {
        int total = 0;
        Long lastId = 0L;
        while (true) {
            List<Long> ids = deviceChannelService.listObjs(
                new LambdaQueryWrapper<DeviceChannelDO>()
                    .select(DeviceChannelDO::getId)
                    .eq(DeviceChannelDO::getDeviceId, deviceId)
                    .gt(DeviceChannelDO::getId, lastId)
                    .ne(DeviceChannelDO::getStatus, DeviceConstant.Status.OFFLINE)
                    .orderByAsc(DeviceChannelDO::getId)
                    .last("LIMIT " + cascadeOfflineBatchSize),
                o -> ((Number) o).longValue());
            if (ids.isEmpty()) break;
            LambdaUpdateWrapper<DeviceChannelDO> uw = new LambdaUpdateWrapper<DeviceChannelDO>()
                .in(DeviceChannelDO::getId, ids)
                .set(DeviceChannelDO::getStatus, DeviceConstant.Status.OFFLINE)
                .set(DeviceChannelDO::getStatusSource, "OFFLINE_CASCADE")
                .set(DeviceChannelDO::getUpdateTime, LocalDateTime.now());
            total += deviceChannelService.getBaseMapper().update(null, uw);
            lastId = ids.get(ids.size() - 1);
        }
        return total;
    }

    // ================================
    // 1.0.4 Stage 3：patchChannelStatus（单调写）
    // ================================

    /**
     * 通道在线态轻量定向更新。R2 lastSeenTime 单调；R6 通道粒度锁。
     */
    public boolean patchChannelStatus(String deviceId, String channelId,
                                      Integer status, LocalDateTime lastSeenTime, String source) {
        Assert.hasText(deviceId, "设备ID不能为空");
        Assert.hasText(channelId, "通道ID不能为空");
        String lockKey = DEVICE_CHANNEL_LOCK_PREFIX + deviceId + ":" + channelId;
        String lockValue = redisLockUtil.generateLockValue();
        if (!redisLockUtil.tryLock(lockKey, lockValue, 5)) {
            throw new ServiceException("系统繁忙，请稍后重试");
        }
        try {
            LambdaUpdateWrapper<DeviceChannelDO> uw = new LambdaUpdateWrapper<>();
            uw.eq(DeviceChannelDO::getDeviceId, deviceId)
              .eq(DeviceChannelDO::getChannelId, channelId)
              .set(status != null, DeviceChannelDO::getStatus, status)
              .set(lastSeenTime != null, DeviceChannelDO::getLastSeenTime, lastSeenTime)
              .set(source != null, DeviceChannelDO::getStatusSource, source)
              .set(DeviceChannelDO::getUpdateTime, LocalDateTime.now())
              /* R2 单调条件 */
              .and(lastSeenTime != null, w -> w
                  .isNull(DeviceChannelDO::getLastSeenTime)
                  .or().lt(DeviceChannelDO::getLastSeenTime, lastSeenTime));
            boolean updated = deviceChannelService.update(null, uw);
            if (updated) clearCacheByChannel(null, deviceId, channelId);
            return updated;
        } finally {
            redisLockUtil.unLock(lockKey, lockValue);
        }
    }

    /**
     * @deprecated 改用 {@link #patchChannelStatus(String, String, Integer, LocalDateTime, String)}
     */
    @Deprecated
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String deviceId, String channelId, int status) {
        Assert.hasText(deviceId, "设备ID不能为空");
        Assert.hasText(channelId, "通道ID不能为空");

        DeviceChannelDO deviceChannelDO = getByDeviceId(deviceId, channelId);
        if (deviceChannelDO == null) {
            throw new ServiceException("设备通道不存在，设备ID: " + deviceId + ", 通道ID: " + channelId);
        }

        deviceChannelDO.setStatus(status);
        deviceChannelDO.setUpdateTime(LocalDateTime.now());

        // 调用统一入口方法
        deviceChannelInternal(deviceChannelDO, "更新设备通道状态");
    }

    // ================================
    // 通道编码回填（拉流点播成功后由编排层调用）
    // ================================

    /**
     * 回填通道的视频编码与分辨率。
     *
     * <p>
     * 只改 {@code extend} 里的探测字段，不动地址、状态与时间戳：地址由通道发现负责，
     * 状态由目录/会话负责，本方法只补"拉起来才知道"的那部分信息。
     * 值没变化时直接跳过，避免每次点播都写一次库。
     * </p>
     *
     * @param deviceId 设备ID
     * @param channelId 通道ID
     * @param videoCodec 视频编码（H264/H265/UNKNOWN）
     * @param videoWidth 视频宽度（可空）
     * @param videoHeight 视频高度（可空）
     * @return 是否实际写入
     */
    public boolean updateChannelVideoProbe(String deviceId, String channelId, String videoCodec,
                                           Integer videoWidth, Integer videoHeight) {
        if (deviceId == null || deviceId.isEmpty() || channelId == null || channelId.isEmpty()
            || videoCodec == null || videoCodec.isEmpty()) {
            return false;
        }
        DeviceChannelDO existing = getByDeviceId(deviceId, channelId);
        if (existing == null) {
            return false;
        }
        DeviceChannelDTO dto = deviceChannelAssembler.doToDto(existing);
        DeviceChannelDTO.ExtendInfo extendInfo = dto.getExtendInfo() == null
            ? new DeviceChannelDTO.ExtendInfo() : dto.getExtendInfo();
        // 刚建流时 ZLM 还没解析出分辨率，会回 0；这种"未知"不能覆盖已知值，也不该落成 0x0
        Integer width = resolveDimension(videoWidth, extendInfo.getVideoWidth());
        Integer height = resolveDimension(videoHeight, extendInfo.getVideoHeight());
        if (videoCodec.equals(extendInfo.getVideoCodec())
            && Objects.equals(width, extendInfo.getVideoWidth())
            && Objects.equals(height, extendInfo.getVideoHeight())) {
            return false;
        }
        extendInfo.setVideoCodec(videoCodec);
        extendInfo.setVideoWidth(width);
        extendInfo.setVideoHeight(height);

        LambdaUpdateWrapper<DeviceChannelDO> uw = new LambdaUpdateWrapper<DeviceChannelDO>()
            .eq(DeviceChannelDO::getId, existing.getId())
            .set(DeviceChannelDO::getExtend, JSON.toJSONString(extendInfo))
            .set(DeviceChannelDO::getUpdateTime, LocalDateTime.now());
        boolean updated = deviceChannelService.update(null, uw);
        if (updated) {
            clearCacheByChannel(existing.getId(), deviceId, channelId);
            log.info("通道编码回填 - deviceId={}, channelId={}, codec={}, {}x{}", deviceId, channelId,
                videoCodec, videoWidth, videoHeight);
        }
        return updated;
    }

    /**
     * 分辨率取值：非正数视为"未知"，此时优先保留已探测到的合法值；两者都无效则落 null（不写 0x0）。
     */
    private static Integer resolveDimension(Integer incoming, Integer current) {
        if (incoming != null && incoming > 0) {
            return incoming;
        }
        return current != null && current > 0 ? current : null;
    }

    // ================================
    // 1.0.4 Stage 4：markMissingChannels（失踪扫描，由 doBatchUpsertWithStatus 内调用）
    // ================================

    private int markMissingChannelsInternal(String deviceId, Set<String> presentIds,
                                            Map<String, DeviceChannelDO> snapshot, LocalDateTime now) {
        if (!missingScanEnabled || snapshot.isEmpty()) return 0;

        List<Long> missingIds = snapshot.entrySet().stream()
            .filter(e -> !presentIds.contains(e.getKey()))
            .map(e -> e.getValue().getId())
            .collect(Collectors.toList());
        if (missingIds.isEmpty()) return 0;

        int totalAffected = 0;
        int batchSize = 500;
        for (int i = 0; i < missingIds.size(); i += batchSize) {
            List<Long> batch = missingIds.subList(i, Math.min(i + batchSize, missingIds.size()));
            totalAffected += updateMissingBatch(batch, now);
        }
        log.info("markMissingChannels - deviceId={}, missing={}, affected={}", deviceId, missingIds.size(), totalAffected);
        return totalAffected;
    }

    private int updateMissingBatch(List<Long> ids, LocalDateTime now) {
        /* 步骤 1：missing_count += 1 */
        LambdaUpdateWrapper<DeviceChannelDO> incrWrapper = new LambdaUpdateWrapper<DeviceChannelDO>()
            .in(DeviceChannelDO::getId, ids)
            .setSql("missing_count = missing_count + 1")
            .set(DeviceChannelDO::getUpdateTime, now);
        int affected = deviceChannelService.getBaseMapper().update(null, incrWrapper);

        /* 步骤 2：达阈值的写 OFFLINE */
        LambdaUpdateWrapper<DeviceChannelDO> offlineWrapper = new LambdaUpdateWrapper<DeviceChannelDO>()
            .in(DeviceChannelDO::getId, ids)
            .ge(DeviceChannelDO::getMissingCount, missingThreshold)
            .ne(DeviceChannelDO::getStatus, DeviceConstant.Status.OFFLINE)
            .set(DeviceChannelDO::getStatus, DeviceConstant.Status.OFFLINE)
            .set(DeviceChannelDO::getStatusSource, "MISSING")
            .set(DeviceChannelDO::getUpdateTime, now);
        deviceChannelService.getBaseMapper().update(null, offlineWrapper);
        return affected;
    }

    // ================================
    // 1.0.4 Stage 5：promoteOnlineIfOffline（媒体会话挂钩通道状态）
    // ================================

    /**
     * 仅当通道当前 OFFLINE 时升 ONLINE（条件 UPDATE）。R2 lastSeenTime 单调；R6 通道粒度锁。
     */
    public boolean promoteOnlineIfOffline(String deviceId, String channelId, LocalDateTime now) {
        if (!enableSessionPromotion) return false;
        Assert.hasText(deviceId, "设备ID不能为空");
        Assert.hasText(channelId, "通道ID不能为空");
        Assert.notNull(now, "时间戳不能为空");

        String lockKey = DEVICE_CHANNEL_LOCK_PREFIX + deviceId + ":" + channelId;
        String lockValue = redisLockUtil.generateLockValue();
        if (!redisLockUtil.tryLock(lockKey, lockValue, 5)) return false;
        try {
            LambdaUpdateWrapper<DeviceChannelDO> uw = new LambdaUpdateWrapper<DeviceChannelDO>()
                .eq(DeviceChannelDO::getDeviceId, deviceId)
                .eq(DeviceChannelDO::getChannelId, channelId)
                .eq(DeviceChannelDO::getStatus, DeviceConstant.Status.OFFLINE)
                .set(DeviceChannelDO::getStatus, DeviceConstant.Status.ONLINE)
                .set(DeviceChannelDO::getLastSeenTime, now)
                .set(DeviceChannelDO::getStatusSource, "SESSION")
                .set(DeviceChannelDO::getUpdateTime, now)
                .and(w -> w.isNull(DeviceChannelDO::getLastSeenTime).or().lt(DeviceChannelDO::getLastSeenTime, now));
            boolean updated = deviceChannelService.update(null, uw);
            if (updated) clearCacheByChannel(null, deviceId, channelId);
            return updated;
        } finally {
            redisLockUtil.unLock(lockKey, lockValue);
        }
    }
}
