package io.github.lunasaw.voglander.web.api.cascade.assembler;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeBlockedOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeClientDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgCatalog;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRevokePreviewDTO;
import io.github.lunasaw.voglander.manager.manager.CatalogVisibility;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelBatchBindReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelPageReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeChannelUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeClientCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeClientUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadeOrgUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadePlatformCodeRuleReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadePlatformCreateReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadePlatformPageReq;
import io.github.lunasaw.voglander.web.api.cascade.req.CascadePlatformUpdateReq;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeOrgVO;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadePlatformOrgCatalogVO;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadePublishVO;
import io.github.lunasaw.voglander.web.api.cascade.vo.CascadeRevokePreviewVO;

import java.util.ArrayList;
import java.util.List;

/**
 * Web 层级联数据转换器，负责 Req → DTO 转换。
 *
 * <p>
 * 字段名严格对齐 {@code tb_cascade_platform} / {@code tb_cascade_channel}（platformIp / localIp 等），
 * 不引入 serverIp 等臆造名。
 * </p>
 *
 * @author luna
 * @date 2026/06/16
 */
@Component
public class CascadeWebAssembler {

    // ==================== 本端客户端 ====================

    /**
     * 本端客户端新增请求 → DTO。
     */
    public CascadeClientDTO toDTO(CascadeClientCreateReq req) {
        if (req == null) {
            return null;
        }
        CascadeClientDTO dto = new CascadeClientDTO();
        dto.setClientId(req.getClientId());
        dto.setClientName(req.getClientName());
        dto.setClientIp(req.getClientIp());
        dto.setIsDefault(req.getIsDefault());
        dto.setEnabled(req.getEnabled());
        dto.setRemark(req.getRemark());
        return dto;
    }

    /**
     * 本端客户端更新请求 → DTO（无 clientId：编码创建后不可改）。
     */
    public CascadeClientDTO toDTO(CascadeClientUpdateReq req) {
        if (req == null) {
            return null;
        }
        CascadeClientDTO dto = new CascadeClientDTO();
        dto.setClientName(req.getClientName());
        dto.setClientIp(req.getClientIp());
        dto.setIsDefault(req.getIsDefault());
        dto.setEnabled(req.getEnabled());
        dto.setRemark(req.getRemark());
        return dto;
    }

    // ==================== 平台 ====================

    /**
     * 平台新增请求 → DTO。
     */
    public CascadePlatformDTO toDTO(CascadePlatformCreateReq req) {
        if (req == null) {
            return null;
        }
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setPlatformId(req.getPlatformId());
        dto.setPlatformIp(req.getPlatformIp());
        dto.setPlatformPort(req.getPlatformPort());
        dto.setPlatformDomain(req.getPlatformDomain());
        dto.setPlatformName(req.getPlatformName());
        dto.setUsername(req.getUsername());
        dto.setPassword(req.getPassword());
        /* 本端身份只传引用：编码与通告 IP 由客户端表提供，装配层不再接触这两个值 */
        dto.setClientRefId(req.getClientRefId());
        dto.setKeepaliveInterval(req.getKeepaliveInterval());
        dto.setRegisterExpires(req.getRegisterExpires());
        dto.setCharset(req.getCharset());
        dto.setTransport(req.getTransport());
        dto.setCatalogWithDeviceLayer(req.getCatalogWithDeviceLayer());
        applyPlatformCodeRule(dto, req.getCodeProvince(), req.getCodeCity(), req.getCodeCounty(),
            req.getCodeIndustry(), req.getCodeNetwork(), req.getCodeStartSeq(), req.getCodeRuleEnabled(),
            req.getPublishAncestors());
        return dto;
    }

    /**
     * 平台更新请求 → DTO（id 透传，platformId 展示不参与更新键）。
     */
    public CascadePlatformDTO toDTO(CascadePlatformUpdateReq req) {
        if (req == null) {
            return null;
        }
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setId(req.getId());
        dto.setPlatformId(req.getPlatformId());
        dto.setPlatformIp(req.getPlatformIp());
        dto.setPlatformPort(req.getPlatformPort());
        dto.setPlatformDomain(req.getPlatformDomain());
        /* 名称三态：null=不改，空串=清空为未命名（列是 NOT NULL DEFAULT ''，空串能落库） */
        dto.setPlatformName(req.getPlatformName());
        dto.setUsername(req.getUsername());
        dto.setPassword(req.getPassword());
        /* 本端身份只传引用：编码与通告 IP 由客户端表提供，装配层不再接触这两个值 */
        dto.setClientRefId(req.getClientRefId());
        dto.setKeepaliveInterval(req.getKeepaliveInterval());
        dto.setRegisterExpires(req.getRegisterExpires());
        dto.setCharset(req.getCharset());
        dto.setTransport(req.getTransport());
        /* 空值不传：updateById 会跳过 null 列，正好保持“没提交就不改”；编码段的空串=清空该段 */
        dto.setCatalogWithDeviceLayer(req.getCatalogWithDeviceLayer());
        applyPlatformCodeRule(dto, req.getCodeProvince(), req.getCodeCity(), req.getCodeCounty(),
            req.getCodeIndustry(), req.getCodeNetwork(), req.getCodeStartSeq(), req.getCodeRuleEnabled(),
            req.getPublishAncestors());
        return dto;
    }

    /**
     * 平台编码规则保存请求 → DTO（整份覆盖：未传的段就是未配置，与通用更新的「null 不改」不同）。
     */
    public CascadePlatformDTO toDTO(CascadePlatformCodeRuleReq req) {
        if (req == null) {
            return null;
        }
        CascadePlatformDTO dto = new CascadePlatformDTO();
        applyPlatformCodeRule(dto, req.getCodeProvince(), req.getCodeCity(), req.getCodeCounty(),
            req.getCodeIndustry(), req.getCodeNetwork(), req.getCodeStartSeq(), req.getCodeRuleEnabled(),
            req.getPublishAncestors());
        return dto;
    }

    /**
     * 平台编码规则八列透传：新增、编辑、专用规则接口共用一份映射。
     *
     * <p>段位已在 Req 入口 trim（粘贴进来的 {@code "  "} 按未填处理），装配层不再重复处理：
     * 位宽由 Req 的 {@code @Pattern} 校验，「启用时必填」由 Manager 校验；
     * 装配层再插一手只会让三条写入路径对同一个表单值有不同理解。
     */
    private void applyPlatformCodeRule(CascadePlatformDTO dto, String province, String city, String county,
        String industry, String network, Integer startSeq, Integer ruleEnabled, Integer publishAncestors) {
        dto.setCodeProvince(province);
        dto.setCodeCity(city);
        dto.setCodeCounty(county);
        dto.setCodeIndustry(industry);
        dto.setCodeNetwork(network);
        dto.setCodeStartSeq(startSeq);
        dto.setCodeRuleEnabled(ruleEnabled);
        dto.setPublishAncestors(publishAncestors);
    }

    /**
     * 平台分页查询请求 → 查询条件 DTO；入参为空返回空条件（查全量）。
     */
    public CascadePlatformDTO pageReqToQueryDto(CascadePlatformPageReq req) {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        if (req == null) {
            return dto;
        }
        dto.setPlatformName(req.getPlatformName());
        dto.setPlatformId(req.getPlatformId());
        dto.setPlatformIp(req.getPlatformIp());
        dto.setEnabled(req.getEnabled());
        dto.setRegisterStatus(req.getRegisterStatus());
        return dto;
    }

    // ==================== 通道 ====================

    /**
     * 通道新增请求 → DTO。
     */
    public CascadeChannelDTO toDTO(CascadeChannelCreateReq req) {
        if (req == null) {
            return null;
        }
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setPlatformId(req.getPlatformId());
        dto.setLocalDeviceId(req.getLocalDeviceId());
        dto.setLocalChannelId(req.getLocalChannelId());
        dto.setCascadeChannelId(req.getCascadeChannelId());
        dto.setCascadeOrgId(req.getCascadeOrgId());
        dto.setCascadeName(req.getCascadeName());
        dto.setManufacturer(req.getManufacturer());
        dto.setModel(req.getModel());
        dto.setAddress(req.getAddress());
        dto.setIpAddress(req.getIpAddress());
        dto.setPtzType(req.getPtzType());
        return dto;
    }

    /**
     * 通道更新请求 → DTO（id 透传，身份字段展示不参与更新键）。
     */
    public CascadeChannelDTO toDTO(CascadeChannelUpdateReq req) {
        if (req == null) {
            return null;
        }
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setId(req.getId());
        dto.setPlatformId(req.getPlatformId());
        dto.setLocalDeviceId(req.getLocalDeviceId());
        dto.setLocalChannelId(req.getLocalChannelId());
        dto.setCascadeChannelId(req.getCascadeChannelId());
        dto.setCascadeOrgId(req.getCascadeOrgId());
        dto.setCascadeName(req.getCascadeName());
        dto.setEnabled(req.getEnabled());
        /* 元数据三态：null 不改，空串/0 改回自动派生，其余为覆盖值 */
        dto.setManufacturer(req.getManufacturer());
        dto.setModel(req.getModel());
        dto.setAddress(req.getAddress());
        dto.setIpAddress(req.getIpAddress());
        dto.setPtzType(req.getPtzType());
        return dto;
    }

    /**
     * 通道分页查询请求 → 查询条件 DTO；入参为空返回空条件（查全量）。
     */
    public CascadeChannelDTO pageReqToQueryDto(CascadeChannelPageReq req) {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        if (req == null) {
            return dto;
        }
        dto.setPlatformId(req.getPlatformId());
        dto.setLocalDeviceId(req.getLocalDeviceId());
        dto.setLocalChannelId(req.getLocalChannelId());
        dto.setCascadeChannelId(req.getCascadeChannelId());
        dto.setCascadeOrgId(req.getCascadeOrgId());
        return dto;
    }

    // ==================== 组织 ====================

    /**
     * 组织新增请求 → DTO（{@code orgCode} 与物化路径由 Manager 生成，不从入参透传）。
     */
    public CascadeOrgDTO toDTO(CascadeOrgCreateReq req) {
        if (req == null) {
            return null;
        }
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setParentId(req.getParentId());
        dto.setOrgName(req.getOrgName());
        dto.setSortOrder(req.getSortOrder());
        dto.setEnabled(req.getEnabled());
        dto.setPlatformIds(req.getPlatformIds());
        applyCodeRule(dto, req.getCodeProvince(), req.getCodeCity(), req.getCodeCounty(),
            req.getCodeIndustry(), req.getCodeType(), req.getCodeNetwork(), req.getCodeStartSeq(),
            req.getCodeRuleEnabled());
        return dto;
    }

    /**
     * 组织更新请求 → DTO（组织编码不在入参里；父节点透了但只用于让 Manager 拒掉非法调用）。
     */
    public CascadeOrgDTO toDTO(CascadeOrgUpdateReq req) {
        if (req == null) {
            return null;
        }
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setId(req.getId());
        dto.setParentId(req.getParentId());
        dto.setOrgName(req.getOrgName());
        dto.setSortOrder(req.getSortOrder());
        dto.setEnabled(req.getEnabled());
        applyCodeRule(dto, req.getCodeProvince(), req.getCodeCity(), req.getCodeCounty(),
            req.getCodeIndustry(), req.getCodeType(), req.getCodeNetwork(), req.getCodeStartSeq(),
            req.getCodeRuleEnabled());
        return dto;
    }

    /**
     * 组织 DTO → VO，递归带上子节点。
     *
     * @param dto 组织 DTO；入参为空返回 null
     */
    public CascadeOrgVO toVO(CascadeOrgDTO dto) {
        return toVO(dto, null);
    }

    /**
     * 组织 DTO → VO，附带<b>本地</b>可达性（{@code effective} / {@code blockedByOrgName}）。
     *
     * <p>可见性由整库快照算出，必须与列表同源：只看 {@code enabled} 会把
     * 「被祖先连带停用」的节点误显示成启用。
     *
     * <p>发布范围（{@code publishedPlatformIds}）随 DTO 下发，与本地可达性分开看：
     * 本地可达 ∩ 已发布，才是某个上级真能看到。
     *
     * @param dto        组织 DTO；入参为空返回 null
     * @param visibility 本地可见性快照；为空时不做可见性填充
     */
    public CascadeOrgVO toVO(CascadeOrgDTO dto, CatalogVisibility visibility) {
        if (dto == null) {
            return null;
        }
        CascadeOrgVO vo = new CascadeOrgVO();
        vo.setId(dto.getId());
        vo.setCreateTime(dto.createTimeToEpochMilli());
        vo.setUpdateTime(dto.updateTimeToEpochMilli());
        vo.setParentId(dto.getParentId());
        vo.setOrgPath(dto.getOrgPath());
        vo.setOrgCode(dto.getOrgCode());
        vo.setOrgName(dto.getOrgName());
        vo.setSortOrder(dto.getSortOrder());
        vo.setEnabled(dto.getEnabled());
        if (visibility != null) {
            vo.setEffective(visibility.isOrgEffective(dto.getOrgCode()));
            vo.setBlockedByOrgName(visibility.blockedOrgName(dto.getOrgCode()));
        }
        if (dto.getPublishedPlatformIds() != null) {
            vo.setPublishedPlatformIds(new ArrayList<>(dto.getPublishedPlatformIds()));
        }
        /* 六段对外只保留一种「未配置」表示：updateById 会把入参里的空串/空白原样写库，
           不归一的话前端要同时判 null 与 "" 才算得对 */
        vo.setCodeProvince(StringUtils.trimToNull(dto.getCodeProvince()));
        vo.setCodeCity(StringUtils.trimToNull(dto.getCodeCity()));
        vo.setCodeCounty(StringUtils.trimToNull(dto.getCodeCounty()));
        vo.setCodeIndustry(StringUtils.trimToNull(dto.getCodeIndustry()));
        vo.setCodeType(StringUtils.trimToNull(dto.getCodeType()));
        vo.setCodeNetwork(StringUtils.trimToNull(dto.getCodeNetwork()));
        vo.setCodeStartSeq(dto.getCodeStartSeq());
        vo.setCodeRuleEnabled(dto.getCodeRuleEnabled());
        vo.setChannelCount(dto.getChannelCount());
        if (dto.getChildren() != null) {
            List<CascadeOrgVO> children = new ArrayList<>(dto.getChildren().size());
            for (CascadeOrgDTO child : dto.getChildren()) {
                children.add(toVO(child, visibility));
            }
            vo.setChildren(children);
        }
        return vo;
    }

    /**
     * 组织 DTO 列表 → VO 列表。
     */
    public List<CascadeOrgVO> toVOList(List<CascadeOrgDTO> dtos) {
        return toVOList(dtos, null);
    }

    /**
     * 组织 DTO 列表 → VO 列表，附带上级目录可见性。
     */
    public List<CascadeOrgVO> toVOList(List<CascadeOrgDTO> dtos, CatalogVisibility visibility) {
        List<CascadeOrgVO> list = new ArrayList<>();
        if (dtos == null) {
            return list;
        }
        for (CascadeOrgDTO dto : dtos) {
            list.add(toVO(dto, visibility));
        }
        return list;
    }

    /**
     * 停用影响面 DTO → VO。
     *
     * <p>数量字段在这里一次算好再下发，避免前端各自 {@code length} 统计出现口径不一致；
     * 逐平台的投递条件（{@code platformOnline} / {@code catalogSubscribed} / {@code warnings}）
     * 由 Controller 探明后回填，装配层不依赖运行时组件。
     */
    public CascadeRevokePreviewVO toVO(CascadeRevokePreviewDTO dto) {
        if (dto == null) {
            return null;
        }
        CascadeRevokePreviewVO vo = new CascadeRevokePreviewVO();
        vo.setOrgId(dto.getOrgId());
        vo.setOrgCode(dto.getOrgCode());
        vo.setOrgName(dto.getOrgName());
        vo.setOrgs(toNodeVOList(dto.getOrgs()));
        vo.setChannels(toNodeVOList(dto.getChannels()));
        vo.setOrgCount(dto.orgCount());
        vo.setChannelCount(dto.channelCount());
        vo.setDeleteCount(dto.deleteCount());
        return vo;
    }

    /**
     * 发布配置 DTO → VO。
     */
    public CascadePublishVO toVO(CascadePublishDTO dto) {
        if (dto == null) {
            return null;
        }
        CascadePublishVO vo = new CascadePublishVO();
        vo.setId(dto.getId());
        vo.setPlatformId(dto.getPlatformId());
        vo.setObjectType(dto.getObjectType());
        vo.setObjectKey(dto.getObjectKey());
        vo.setParentKey(dto.getParentKey());
        vo.setPublishCode(dto.getPublishCode());
        vo.setPublishName(dto.getPublishName());
        vo.setEnabled(dto.getEnabled());
        vo.setSortOrder(dto.getSortOrder());
        vo.setRevokePolicy(dto.getRevokePolicy());
        return vo;
    }

    /**
     * 发布配置 DTO 列表 → VO 列表。
     */
    public List<CascadePublishVO> toPublishVOList(List<CascadePublishDTO> dtos) {
        List<CascadePublishVO> list = new ArrayList<>();
        if (dtos == null) {
            return list;
        }
        for (CascadePublishDTO dto : dtos) {
            list.add(toVO(dto));
        }
        return list;
    }

    /**
     * 平台组织视角 DTO → VO。
     *
     * <p>数量在这里一次算好：前端三个统计值与列表同源，避免各自过滤后出现
     * 「目录里 3 条、计数显示 5」这类对不上的情况。
     *
     * @param catalog    平台组织视角；入参为空返回 null
     * @param platformId 请求里的平台编码，原样回显给前端对齐并发结果
     */
    public CascadePlatformOrgCatalogVO toVO(CascadePlatformOrgCatalog catalog, String platformId) {
        if (catalog == null) {
            return null;
        }
        CascadePlatformOrgCatalogVO vo = new CascadePlatformOrgCatalogVO();
        vo.setPlatformId(platformId);
        for (CascadePlatformOrgDTO org : catalog.catalogOrgs()) {
            CascadePlatformOrgCatalogVO.ItemVO item = new CascadePlatformOrgCatalogVO.ItemVO();
            item.setId(org.getId());
            item.setParentId(org.getParentId());
            item.setLocalCode(org.getLocalCode());
            item.setPlatformCode(org.getPlatformCode());
            item.setName(org.getName());
            item.setSortOrder(org.getSortOrder());
            item.setParentPlatformCode(org.getParentPlatformCode());
            vo.getItems().add(item);
        }
        for (CascadeBlockedOrgDTO org : catalog.blockedOrgs()) {
            CascadePlatformOrgCatalogVO.BlockedVO blocked = new CascadePlatformOrgCatalogVO.BlockedVO();
            blocked.setId(org.getId());
            blocked.setLocalCode(org.getLocalCode());
            blocked.setName(org.getName());
            blocked.setBlockedByCode(org.getBlockedByCode());
            blocked.setBlockedByName(org.getBlockedByName());
            blocked.setReason(org.getReason());
            blocked.setRerouted(org.getRerouted());
            vo.getBlocked().add(blocked);
        }
        vo.setDeliveredCount(vo.getItems().size());
        vo.setBlockedCount(vo.getBlocked().size());
        /* 上浮集合覆盖全量被阻断的组织（含没勾发布的），与阻断清单不是同一集合，
           直接取集合大小才是「通道改挂域节点的组织数」 */
        vo.setReroutedCount(catalog.reroutedLocalCodes().size());
        return vo;
    }

    private List<CascadeRevokePreviewVO.NodeVO> toNodeVOList(List<LocalCascadeCatalogChangeEvent.Node> nodes) {
        List<CascadeRevokePreviewVO.NodeVO> list = new ArrayList<>();
        if (nodes == null) {
            return list;
        }
        for (LocalCascadeCatalogChangeEvent.Node node : nodes) {
            if (node == null) {
                continue;
            }
            CascadeRevokePreviewVO.NodeVO vo = new CascadeRevokePreviewVO.NodeVO();
            vo.setCascadeId(node.cascadeId());
            vo.setName(node.name());
            vo.setParentId(node.parentCode());
            list.add(vo);
        }
        return list;
    }

    /**
     * 编码段七列透传：新增与编辑共用一份映射，避免两条路径对同一表单字段有不同理解。
     *
     * <p>这些列是<b>预留配置</b>：{@code codeRuleEnabled=0} 时后端仍按默认规则生成编码，
     * 写入的值不参与本轮生成，只作为后续「按七段规则生码」的输入保留。
     *
     * <p>段位已在 Req 入口 trim（粘贴进来的 {@code "  "} 按未填处理），装配层不再重复处理：
     * 落库归一由 Manager 负责——新增时直接 {@code trimToNull}，编辑时要先保留「空串=清空」
     * 的原值交给 {@code updateById}，再补一次显式 SET，两者在这里都做不了。
     */
    private void applyCodeRule(CascadeOrgDTO dto, String province, String city, String county,
        String industry, String type, String network, Integer startSeq, Integer ruleEnabled) {
        dto.setCodeProvince(province);
        dto.setCodeCity(city);
        dto.setCodeCounty(county);
        dto.setCodeIndustry(industry);
        dto.setCodeType(type);
        dto.setCodeNetwork(network);
        dto.setCodeStartSeq(startSeq);
        dto.setCodeRuleEnabled(ruleEnabled);
    }

    /**
     * 通道批量绑定请求 → DTO 列表（platformId 下发到每条；cascadeChannelId 缺省由编码规则派生）。
     */
    public List<CascadeChannelDTO> toBatchDTOList(CascadeChannelBatchBindReq req) {
        List<CascadeChannelDTO> list = new ArrayList<>();
        if (req == null || req.getChannels() == null) {
            return list;
        }
        for (CascadeChannelBatchBindReq.BindItem item : req.getChannels()) {
            if (item == null) {
                continue;
            }
            CascadeChannelDTO dto = new CascadeChannelDTO();
            dto.setPlatformId(req.getPlatformId());
            /* 批次上的 cascadeOrgId 不再透传：挂载位置只看通道自身的本地归属，
               回填它只会让调用方以为还能按批次指定父节点 */
            dto.setLocalDeviceId(item.getLocalDeviceId());
            dto.setLocalChannelId(item.getLocalChannelId());
            dto.setCascadeChannelId(item.getCascadeChannelId());
            dto.setCascadeName(item.getCascadeName());
            list.add(dto);
        }
        return list;
    }
}
