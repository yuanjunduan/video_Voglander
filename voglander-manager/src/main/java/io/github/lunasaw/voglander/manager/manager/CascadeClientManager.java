package io.github.lunasaw.voglander.manager.manager;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.lunasaw.voglander.manager.assembler.CascadeAssembler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeClientDTO;
import io.github.lunasaw.voglander.manager.service.CascadeClientService;
import io.github.lunasaw.voglander.manager.service.CascadePlatformService;
import io.github.lunasaw.voglander.repository.entity.CascadeClientDO;
import io.github.lunasaw.voglander.repository.entity.CascadePlatformDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联本端客户端管理器
 *
 * <p>本端客户端是「向上级注册时代表本平台的身份」：20 位平台编码 + 对外通告 IP。
 * 上级平台只引用它的主键（{@code tb_cascade_platform.client_ref_id}），
 * 因此改一次 IP 或名称对所有引用它的平台同时生效，不必逐条平台重填。
 *
 * <p><b>为什么编码不可改</b>：上级按 From 头的用户部分识别本端，换编码等于换了一个平台，
 * 已建立的目录、订阅、点播关系全部失效，而这个后果在界面上看不出来，只能靠保存时拦住。
 *
 * <p><b>为什么端口不在这里</b>：一个 SIP UA 只绑定一个 socket，端口是事实而不是选项，
 * 取值恒为 {@code sip.client.port}。
 */
@Slf4j
@Component
public class CascadeClientManager {

    /** 国标 20 位编码：只接受 ASCII 数字，全角数字拼出的串在上级报文里必然报错 */
    private static final String CLIENT_ID_PATTERN = "\\d{20}";

    @Autowired
    private CascadeClientService cascadeClientService;

    /**
     * 平台侧只用 Mapper 级的 service 而不是 {@link CascadePlatformManager}：
     * 后者反过来要注入本类填充派生字段，互相注入会形成循环依赖。
     */
    @Autowired
    private CascadePlatformService cascadePlatformService;

    /**
     * 新增本端客户端。
     *
     * <p>库里一条都没有时，第一条自动成为默认项：新增上级平台的下拉框需要一个预选项，
     * 否则用户第一次进来会看到空下拉并且不知道要先回本页面建一条。
     *
     * @param dto 客户端配置
     * @return 新增记录主键
     */
    public Long add(CascadeClientDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        String clientId = StringUtils.trimToNull(dto.getClientId());
        Assert.notNull(clientId, "clientId 不能为空");
        assertClientIdFormat(clientId);
        assertClientIdUnique(clientId, null);

        CascadeClientDO doObj = CascadeAssembler.toDO(dto);
        doObj.setClientId(clientId);
        /* 名称与 IP 只 trim 不 trimToNull：两列都是 NOT NULL DEFAULT ''，
           统一落空串比落 NULL 更贴近「未填写」的读回口径 */
        doObj.setClientName(StringUtils.trim(dto.getClientName()));
        doObj.setClientIp(StringUtils.trim(dto.getClientIp()));
        doObj.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());

        boolean firstClient = cascadeClientService.count() == 0;
        boolean asDefault = firstClient || dto.defaultClient();
        clearDefaultFlag();
        doObj.setIsDefault(asDefault ? 1 : 0);
        cascadeClientService.save(doObj);
        log.info("本端客户端新增: id={}, clientId={}, ip={}, isDefault={}", doObj.getId(), clientId,
            doObj.getClientIp(), doObj.getIsDefault());
        return doObj.getId();
    }

    /**
     * 更新本端客户端（patch 语义：null=不改、空串=清空）。
     *
     * @param dto 待更新字段；{@code clientId} 只允许与原值相同
     * @return 是否更新成功
     */
    public boolean update(CascadeClientDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.notNull(dto.getId(), "id 不能为空");

        CascadeClientDO existed = cascadeClientService.getById(dto.getId());
        Assert.notNull(existed, "本端客户端不存在：" + dto.getId());
        if (dto.getClientId() != null && !Objects.equals(StringUtils.trim(dto.getClientId()), existed.getClientId())) {
            throw new IllegalArgumentException("本端客户端的平台编码创建后不可修改："
                + existed.getClientId() + " -> " + dto.getClientId());
        }

        CascadeClientDO update = CascadeAssembler.toDO(dto);
        update.setClientId(null);
        update.setClientName(StringUtils.trim(dto.getClientName()));
        update.setClientIp(StringUtils.trim(dto.getClientIp()));

        boolean updated;
        if (dto.defaultClient()) {
            /* 默认项全局唯一：先把别人的标记清掉再置本行，否则列表里会出现两个默认 */
            clearDefaultFlag();
            update.setIsDefault(1);
            updated = cascadeClientService.updateById(update);
        } else {
            /* 不提交 isDefault 时交给 updateById 跳过；显式退出默认由 setDefault 之外的入口负责，
               这里保留原值——「没有默认」只会让下拉失去预选项，不会影响既有平台的注册 */
            update.setIsDefault(null);
            updated = cascadeClientService.updateById(update);
        }
        log.info("本端客户端更新: id={}, updated={}, defaultFlagSubmitted={}", dto.getId(), updated,
            dto.getIsDefault());
        return updated;
    }

    /**
     * 把指定客户端设为默认，并清掉其他行的默认标记。
     *
     * @param id 客户端主键
     * @return 是否命中记录
     */
    public boolean setDefault(Long id) {
        Assert.notNull(id, "id 不能为空");
        Assert.notNull(cascadeClientService.getById(id), "本端客户端不存在：" + id);
        clearDefaultFlag();
        CascadeClientDO update = new CascadeClientDO();
        update.setId(id);
        update.setIsDefault(1);
        return cascadeClientService.updateById(update);
    }

    /**
     * 删除本端客户端。
     *
     * <p>仍被上级平台引用时直接拒绝：平台表只存外键，删掉客户端会让那些平台在
     * 注册、组 From 头时取不到身份，表现为「配置看得到、注册一律失败」。
     *
     * @param id 客户端主键
     * @return 是否删除成功
     * @throws IllegalStateException 仍被平台引用
     */
    public boolean delete(Long id) {
        Assert.notNull(id, "id 不能为空");
        long refs = countPlatformRefs(id);
        if (refs > 0) {
            throw new IllegalStateException("该本端客户端仍被 " + refs + " 个上级平台引用，请先改用其他客户端");
        }
        return cascadeClientService.removeById(id);
    }

    /**
     * 统计引用了该客户端的上级平台数量。
     *
     * @param clientRefId 客户端主键
     * @return 引用数
     */
    public long countPlatformRefs(Long clientRefId) {
        if (clientRefId == null) {
            return 0L;
        }
        LambdaQueryWrapper<CascadePlatformDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePlatformDO::getClientRefId, clientRefId);
        return cascadePlatformService.count(qw);
    }

    /**
     * 一次算出所有客户端的引用数（管理页列表用，避免逐行 count）。
     *
     * @return clientRefId -> 引用平台数；未被引用的客户端不会出现在表里
     */
    public Map<Long, Long> countPlatformRefsGrouped() {
        LambdaQueryWrapper<CascadePlatformDO> qw = new LambdaQueryWrapper<>();
        qw.select(CascadePlatformDO::getClientRefId).isNotNull(CascadePlatformDO::getClientRefId);
        return cascadePlatformService.list(qw).stream()
            .map(CascadePlatformDO::getClientRefId)
            .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
    }

    public CascadeClientDTO getById(Long id) {
        if (id == null) {
            return null;
        }
        return CascadeAssembler.toDTO(cascadeClientService.getById(id));
    }

    public CascadeClientDTO getByClientId(String clientId) {
        String normalized = StringUtils.trimToNull(clientId);
        if (normalized == null) {
            return null;
        }
        LambdaQueryWrapper<CascadeClientDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeClientDO::getClientId, normalized);
        return CascadeAssembler.toDTO(cascadeClientService.getOne(qw, false));
    }

    /** 默认客户端；一条都没有时返回 null（新增平台的下拉框按「请选择」处理）。 */
    public CascadeClientDTO getDefault() {
        LambdaQueryWrapper<CascadeClientDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeClientDO::getIsDefault, 1).orderByAsc(CascadeClientDO::getId);
        return CascadeAssembler.toDTO(cascadeClientService.getOne(qw, false));
    }

    /**
     * 全部客户端，默认项排最前。
     *
     * @param onlyEnabled true=只返回启用的（新增平台的下拉框用）
     * @return 客户端列表
     */
    public List<CascadeClientDTO> listAll(boolean onlyEnabled) {
        LambdaQueryWrapper<CascadeClientDO> qw = new LambdaQueryWrapper<>();
        qw.eq(onlyEnabled, CascadeClientDO::getEnabled, 1);
        qw.orderByDesc(CascadeClientDO::getIsDefault).orderByAsc(CascadeClientDO::getId);
        return cascadeClientService.list(qw).stream().map(CascadeAssembler::toDTO).toList();
    }

    /**
     * 按主键批量取，供平台分页一次性填充派生字段（避免逐行查询）。
     *
     * @param ids 客户端主键集合，null/空集合返回空表
     * @return id -> DTO
     */
    public Map<Long, CascadeClientDTO> mapByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<CascadeClientDO> rows = cascadeClientService.listByIds(ids);
        return rows.stream()
            .collect(Collectors.toMap(CascadeClientDO::getId, CascadeAssembler::toDTO, (left, right) -> left));
    }

    // ------------------------------------------------------------------ 内部

    /** 清掉全表的默认标记；空表时也无副作用。 */
    private void clearDefaultFlag() {
        cascadeClientService.lambdaUpdate().set(CascadeClientDO::getIsDefault, 0)
            .eq(CascadeClientDO::getIsDefault, 1).update();
    }

    private void assertClientIdFormat(String clientId) {
        if (!clientId.matches(CLIENT_ID_PATTERN)) {
            throw new IllegalArgumentException("本端平台编码必须为 20 位数字，当前值：" + clientId);
        }
    }

    private void assertClientIdUnique(String clientId, Long excludeId) {
        LambdaQueryWrapper<CascadeClientDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeClientDO::getClientId, clientId);
        qw.ne(excludeId != null, CascadeClientDO::getId, excludeId);
        if (cascadeClientService.count(qw) > 0) {
            throw new IllegalArgumentException("本端平台编码已存在：" + clientId);
        }
    }
}
