package io.github.lunasaw.voglander.web.api.device;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import io.github.lunasaw.voglander.common.constant.ApiConstant;
import io.github.lunasaw.voglander.common.constant.device.SubscriptionConstant;
import io.github.lunasaw.voglander.common.enums.DeviceAgreementEnum;
import io.github.lunasaw.voglander.intergration.wrapper.rtsp.RtspProbeClient;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceQueryDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceSubscriptionDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.manager.manager.DeviceRemovalManager;
import io.github.lunasaw.voglander.manager.manager.DeviceSubscriptionManager;
import io.github.lunasaw.voglander.web.api.device.assembler.DeviceWebAssembler;
import io.github.lunasaw.voglander.web.api.device.req.DeviceCreateReq;
import io.github.lunasaw.voglander.web.api.device.req.DevicePageReq;
import io.github.lunasaw.voglander.web.api.device.req.DeviceUpdateReq;
import io.github.lunasaw.voglander.web.api.device.req.RtspProbeReq;
import io.github.lunasaw.voglander.web.api.device.resp.DeviceListResp;
import io.github.lunasaw.voglander.web.api.device.vo.DeviceVO;
import io.github.lunasaw.voglander.web.api.device.vo.RtspProbeVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.common.domain.AjaxResult;
import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.common.exception.ServiceExceptionEnum;
import io.github.lunasaw.voglander.manager.service.DeviceService;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import io.github.lunasaw.voglander.service.device.DeviceChannelDiscoveryService;
import io.github.lunasaw.voglander.service.device.dto.ChannelSyncResultDTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 设备管理
 *
 * @Author: chenzhangyue
 * @CreateTime: 2024-01-30 14:19:15
 */
@Slf4j
@RestController
@RequestMapping(ApiConstant.API_INDEX_V1 + "/device")
@Tag(name = "设备管理", description = "设备管理相关接口")
public class DeviceController {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DeviceManager deviceManager;

    @Autowired
    private DeviceRemovalManager deviceRemovalManager;

    @Autowired
    private CascadeOrgManager cascadeOrgManager;

    @Autowired
    private DeviceChannelManager deviceChannelManager;

    @Autowired
    private DeviceSubscriptionManager deviceSubscriptionManager;

    @Autowired
    private DeviceWebAssembler deviceWebAssembler;

    @Autowired
    private RtspProbeClient rtspProbeClient;

    @Autowired
    private DeviceChannelDiscoveryService deviceChannelDiscoveryService;

    /**
     * 新增设备后自动补通道用的执行器。
     *
     * <p>
     * 复用通用业务线程池，不用 {@code sipNotifierExecutor}：通道发现要发 HTTP 管理面请求
     * （默认 3s 超时），挂在 SIP 入站线程上会拖慢注册与心跳。
     * </p>
     */
    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private TaskExecutor      channelDiscoveryExecutor;

    @PostMapping("/getPage")
    @Operation(summary = "分页条件查询", description = "全量分页条件搜索，前端灵活组装条件（deviceId/名称/状态/类型/IP/时间范围）")
    @ApiResponse(responseCode = "200", description = "成功",
        content = @Content(schema = @Schema(implementation = AjaxResult.class)))
    public AjaxResult getPage(
        @RequestBody(required = false) DevicePageReq pageReq,
        @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
        @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int size) {
        DeviceQueryDTO query = deviceWebAssembler.pageReqToQueryDto(pageReq);
        Page<DeviceDTO> dtoPage = deviceManager.getPage(query, page, size);

        List<DeviceVO> items = dtoPage.getRecords().stream()
            .map(DeviceVO::convertVO)
            .peek(vo -> vo.setChannelCount((int) deviceChannelManager.countByDeviceId(vo.getDeviceId())))
            .collect(Collectors.toList());

        // 批量回填三类订阅意图状态（避免 N+1）
        fillSubscriptionState(items);
        // 批量回填组织名称（避免 N+1）
        fillOrgName(items);

        DeviceListResp resp = new DeviceListResp();
        resp.setTotal(dtoPage.getTotal());
        resp.setItems(items);
        return AjaxResult.success(resp);
    }

    /**
     * 批量回填所属组织名称：列表只回传 orgId，名称用一次组织树索引补齐。
     */
    private void fillOrgName(List<DeviceVO> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        java.util.Map<Long, String> nameById = cascadeOrgManager.nameIndexById();
        for (DeviceVO vo : items) {
            if (vo.getOrgId() != null) {
                vo.setOrgName(nameById.get(vo.getOrgId()));
            }
        }
    }

    /**
     * 批量按 deviceId 查 tb_device_subscription，填充每个设备 VO 的订阅意图开关状态。
     */
    private void fillSubscriptionState(List<DeviceVO> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<String> deviceIds = items.stream().map(DeviceVO::getDeviceId).collect(Collectors.toList());
        List<DeviceSubscriptionDTO> subs = deviceSubscriptionManager.listByDeviceIds(deviceIds);
        // deviceId → SubscriptionVO（按 sub_type + enabled 聚合）
        java.util.Map<String, DeviceVO.SubscriptionVO> byDevice = new java.util.HashMap<>();
        for (DeviceSubscriptionDTO sub : subs) {
            DeviceVO.SubscriptionVO vo = byDevice.computeIfAbsent(sub.getDeviceId(), k -> new DeviceVO.SubscriptionVO());
            boolean on = sub.getEnabled() != null && sub.getEnabled() == 1;
            if (SubscriptionConstant.Type.CATALOG.name().equals(sub.getSubType())) {
                vo.setCatalog(on);
            } else if (SubscriptionConstant.Type.MOBILE_POSITION.name().equals(sub.getSubType())) {
                vo.setPosition(on);
            } else if (SubscriptionConstant.Type.ALARM.name().equals(sub.getSubType())) {
                vo.setAlarm(on);
            }
        }
        for (DeviceVO vo : items) {
            vo.setSubscription(byDevice.getOrDefault(vo.getDeviceId(), new DeviceVO.SubscriptionVO()));
        }
    }

    @GetMapping("/get/{id}")
    @Operation(summary = "根据ID获取设备", description = "通过设备ID获取设备详细信息")
    @ApiResponse(responseCode = "200", description = "成功",
        content = @Content(schema = @Schema(implementation = AjaxResult.class)))
    public AjaxResult getById(@Parameter(description = "设备ID") @PathVariable(value = "id") Long id) {
        DeviceDTO deviceDTO = deviceManager.getDeviceDTOById(id);
        if (deviceDTO == null) {
            return AjaxResult.error("设备不存在");
        }
        DeviceVO deviceVO = DeviceVO.convertVO(deviceDTO);
        fillOrgName(java.util.Collections.singletonList(deviceVO));
        return AjaxResult.success(deviceVO);
    }

    @GetMapping("/get")
    @Operation(summary = "根据条件查询设备", description = "通过设备实体条件查询设备信息")
    public AjaxResult getByEntity(DeviceDO device) {
        DeviceDTO deviceDTO = deviceManager.getDeviceDTOByEntity(device);
        if (deviceDTO == null) {
            return AjaxResult.error("设备不存在");
        }
        DeviceVO deviceVO = DeviceVO.convertVO(deviceDTO);
        return AjaxResult.success(deviceVO);
    }

    @GetMapping("/list")
    @Operation(summary = "获取设备列表", description = "根据条件获取设备列表")
    public AjaxResult list(DeviceDO device) {
        List<DeviceDTO> deviceDTOList = deviceManager.listDeviceDTO(device);
        List<DeviceVO> deviceVOList = deviceDTOList.stream()
                .map(DeviceVO::convertVO)
                .collect(Collectors.toList());
        return AjaxResult.success(deviceVOList);
    }

    @GetMapping("/pageListByEntity/{page}/{size}")
    @Operation(summary = "分页查询设备", description = "根据条件分页查询设备列表")
    public AjaxResult listPageByEntity(
        @Parameter(description = "页码") @PathVariable(value = "page") int page,
        @Parameter(description = "每页大小") @PathVariable(value = "size") int size,
        DeviceDO device) {
        QueryWrapper<DeviceDO> query = Wrappers.query(device);
        Page<DeviceDTO> pageInfo = deviceManager.pageQuery(page, size, query);

        // 转换为 VO 模型
        List<DeviceVO> deviceVOList = pageInfo.getRecords().stream()
                .map(DeviceVO::convertVO)
                .collect(Collectors.toList());

        // 构建返回的分页对象
        Page<DeviceVO> resultPage = new Page<>(page, size);
        resultPage.setRecords(deviceVOList);
        resultPage.setTotal(pageInfo.getTotal());
        resultPage.setCurrent(pageInfo.getCurrent());
        resultPage.setSize(pageInfo.getSize());
        resultPage.setPages(pageInfo.getPages());

        return AjaxResult.success(resultPage);
    }

    @GetMapping("/pageList/{page}/{size}")
    @Operation(summary = "简单分页查询", description = "分页查询所有设备")
    public AjaxResult listPage(
        @Parameter(description = "页码") @PathVariable(value = "page") int page,
        @Parameter(description = "每页大小") @PathVariable(value = "size") int size) {
        Page<DeviceDTO> pageInfo = deviceManager.pageQuerySimple(page, size);

        // 转换为 VO 模型
        List<DeviceVO> deviceVOList = pageInfo.getRecords().stream()
                .map(DeviceVO::convertVO)
                .collect(Collectors.toList());

        // 构建返回的分页对象
        Page<DeviceVO> resultPage = new Page<>(page, size);
        resultPage.setRecords(deviceVOList);
        resultPage.setTotal(pageInfo.getTotal());
        resultPage.setCurrent(pageInfo.getCurrent());
        resultPage.setSize(pageInfo.getSize());
        resultPage.setPages(pageInfo.getPages());

        return AjaxResult.success(resultPage);
    }

    /**
     * 探测 RTSP 拉流地址连通性（TCP + RTSP OPTIONS）。
     *
     * <p>
     * 只判断"地址能不能通"：2xx 可用、401/403 说明缺凭据、404/454 说明路径错、
     * 连不上说明网络/端口问题。不返回视频信息，也不拉起流（拉流仍走 /live/start）。
     * </p>
     */
    @PostMapping("/probe-rtsp")
    @Operation(summary = "探测RTSP地址连通性", description = "TCP 建连 + RTSP OPTIONS，保存前预检拉流地址")
    @ApiResponse(responseCode = "200", description = "探测完成",
        content = @Content(schema = @Schema(implementation = RtspProbeVO.class)))
    public AjaxResult probeRtsp(@Valid @RequestBody RtspProbeReq probeReq) {
        try {
            return AjaxResult.success(RtspProbeVO.convertVO(rtspProbeClient.probe(probeReq.getRtspUrl())));
        } catch (IllegalArgumentException e) {
            // 地址格式问题属调用方错误，不否成"不可达"，直接回原文案
            return AjaxResult.error(e.getMessage());
        }
    }

    @PostMapping("/insert")
    @Operation(summary = "创建设备", description = "添加新的设备；ONVIF/RTSP 拉流设备会在落库后异步补一次通道发现")
    @ApiResponse(responseCode = "200", description = "创建成功")
    public AjaxResult insert(@RequestBody DeviceCreateReq createReq) {
        // 手填新增必须归属一个组织：推送时按该组织挂目录节点，没有组织就无处可挂。
        validateOrgId(createReq.getOrgId(), true);

        // Req -> DTO (使用 Web 层转换器)
        DeviceDTO deviceDTO = deviceWebAssembler.toDeviceDTO(createReq);

        // 通过 Manager 层处理业务逻辑（事务边界在 DeviceManager#createDevice，返回即已提交）
        Long deviceId = deviceManager.createDevice(deviceDTO);

        // 拉流类设备的通道不在平台侧生成，落库后异步从设备管理面读一次
        triggerChannelDiscovery(deviceDTO);

        return AjaxResult.success(deviceId);
    }

    @PostMapping("/insertBatch")
    @Operation(summary = "批量创建设备", description = "批量添加设备")
    public AjaxResult insertBatch(@RequestBody List<DeviceCreateReq> createReqList) {
        // 批量同样属于「手填新增」，逐条校验组织，与单条入口保持一致
        for (DeviceCreateReq createReq : createReqList) {
            validateOrgId(createReq.getOrgId(), true);
        }

        // 批量 Req -> DTO (使用 Web 层转换器)
        List<DeviceDTO> deviceDTOList = deviceWebAssembler.toDeviceDTOList(createReqList);

        // 通过 Manager 层处理批量业务逻辑
        int successCount = deviceManager.batchCreateDevice(deviceDTOList);

        /* Manager 只回成功条数、不区分是哪几条失败，因此只在全部成功时逐条补通道；
           部分成功的剩余条目走通道页的「同步通道」手动补 */
        if (successCount == createReqList.size()) {
            deviceDTOList.forEach(this::triggerChannelDiscovery);
        }

        return AjaxResult.success("成功创建 " + successCount + " 个设备，共 " + createReqList.size() + " 个请求");
    }

    @PutMapping("/update")
    @Operation(summary = "更新设备", description = "更新设备信息")
    public AjaxResult update(@RequestBody DeviceUpdateReq updateReq) {
        // 组织三态：null=不改（SIP 自动注册的设备保持「未归组织」）/ 0=清空 / 其余必须命中组织行
        validateOrgId(updateReq.getOrgId(), false);

        // Req -> DTO (使用 Web 层转换器)
        DeviceDTO deviceDTO = deviceWebAssembler.toDeviceDTO(updateReq);

        Long updated = deviceManager.updateDevice(deviceDTO);

        return AjaxResult.success(updated);
    }

    @PutMapping("/updateBatch")
    @Operation(summary = "批量更新设备", description = "批量更新设备信息")
    public AjaxResult updateBatch(@RequestBody List<DeviceUpdateReq> updateReqList) {
        // 逐条校验（未传 orgId 的条目跳过，语义与单条一致）
        for (DeviceUpdateReq updateReq : updateReqList) {
            validateOrgId(updateReq.getOrgId(), false);
        }

        // 批量 Req -> DTO (使用 Web 层转换器)
        List<DeviceDTO> deviceDTOList = deviceWebAssembler.toUpdateDeviceDTOList(updateReqList);

        // 通过 Manager 层处理批量业务逻辑
        int successCount = deviceManager.batchUpdateDevice(deviceDTOList);

        return AjaxResult.success("成功更新 " + successCount + " 个设备，共 " + updateReqList.size() + " 个请求");
    }

    @DeleteMapping("/delete/{id}")
    @Operation(summary = "删除设备",
        description = "级联删除：该设备下全部本地通道 + 通道/设备的级联发布关系，并向上级推 DEL 移除目录节点")
    public AjaxResult deleteOne(@Parameter(description = "设备ID") @PathVariable(value = "id") Long id) {
        return AjaxResult.success(deviceRemovalManager.removeById(id));
    }

    @DeleteMapping("/deleteIds")
    @Operation(summary = "批量删除设备",
        description = "按 ID 列表批量级联删除（同一事务），返回实际删除的设备数")
    public AjaxResult deleteBatch(@RequestBody List<Long> ids) {
        return AjaxResult.success(deviceRemovalManager.removeByIds(ids));
    }

    /**
     * 拉流类设备（ONVIF/RTSP）新增后异步补一次通道发现。
     *
     * <p>
     * 这两类设备的通道不会随新增自动产生：平台侧不生成通道号，只能从设备管理面台账里读
     * （海康 ISAPI / 大华 CGI），对应 {@link DeviceChannelDiscoveryService#sync}。国标设备的通道由
     * Catalog 目录应答维护，不在此列——对它们发管理面请求只会制造误导性的失败提示。
     * </p>
     *
     * <p>
     * 发现是纯网络 IO，放异步线程执行，<b>失败只记日志、不改变新增结果</b>：网络/凭据/厂商不匹配都可能失败，
     * 但设备本身已经建好了，用户仍可在设备通道页点「同步通道」重试。
     * </p>
     *
     * @param device 刚落库的设备；为 null 或协议类型不属于拉流类时不做任何事
     */
    private void triggerChannelDiscovery(DeviceDTO device) {
        if (device == null || StringUtils.isBlank(device.getDeviceId())) {
            return;
        }
        DeviceAgreementEnum agreement = DeviceAgreementEnum.getByType(device.getType());
        if (agreement != DeviceAgreementEnum.ONVIF_IPC && agreement != DeviceAgreementEnum.RTSP_IPC) {
            return;
        }
        String deviceId = device.getDeviceId();
        channelDiscoveryExecutor.execute(() -> {
            try {
                ChannelSyncResultDTO result = deviceChannelDiscoveryService.sync(deviceId);
                if (result.isPersisted()) {
                    log.info("[channel.discover] 新增设备自动同步完成 deviceId={} 通道数={} 在线={}", deviceId,
                        result.getDiscovered(), result.getOnlineCount());
                } else {
                    log.warn("[channel.discover] 新增设备自动同步未落库 deviceId={} reason={}", deviceId,
                        result.getFailureReason());
                }
            } catch (Exception e) {
                // 设备刚被并发删除、协议不支持、线程池已关闭都在这里收口：新增结果不受影响
                log.warn("[channel.discover] 新增设备自动同步异常 deviceId={} err={}", deviceId, e.getMessage());
            }
        });
    }

    /**
     * 校验设备所属组织。
     *
     * <p>
     * 必须留在 Web 入口，<b>不能下沉到 {@code DeviceManager#add}</b>：SIP REGISTER 自动注册的设备走
     * {@code Gb28181ProtocolHandler#handleRegister → DeviceRegisterServiceImpl#login → DeviceManager#saveOrUpdate → add}，
     * 全程没有表单、拿不到组织；一旦在 Manager 层强校验，所有 GB28181 设备都会注册失败。
     * 因此约定：<b>手填入口必填，自动注册允许为空</b>，空值在列表上显示为「未归组织」。
     * </p>
     *
     * @param orgId    目标组织主键；{@code 0} 为「清空归属」哨兵值，不当组织使用
     * @param required true=必须传（手填新增）；false=传了才校验（更新）
     */
    private void validateOrgId(Long orgId, boolean required) {
        if (orgId == null || orgId == 0L) {
            if (required) {
                throw new ServiceException(ServiceExceptionEnum.PARAM_ERROR.getCode(), "新增设备必须选择所属组织");
            }
            return;
        }
        if (cascadeOrgManager.getById(orgId) == null) {
            throw new ServiceException(ServiceExceptionEnum.PARAM_ERROR.getCode(),
                "所属组织不存在（orgId=" + orgId + "），请刷新组织树后重试");
        }
    }

    @GetMapping("/count")
    @Operation(summary = "统计设备总数", description = "获取设备总数量")
    public AjaxResult getAccount() {
        return AjaxResult.success(deviceService.count());
    }

    @GetMapping("/countByEntity")
    @Operation(summary = "按条件统计设备", description = "根据条件统计设备数量")
    public AjaxResult getAccountByEntity(DeviceDO device) {
        QueryWrapper<DeviceDO> query = Wrappers.query(device);
        return AjaxResult.success(deviceService.count(query));
    }
}