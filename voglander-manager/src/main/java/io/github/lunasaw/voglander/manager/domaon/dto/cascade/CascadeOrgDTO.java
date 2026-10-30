package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 级联组织树 DTO。
 *
 * <p>组织是上级目录树里的<b>中间节点</b>：域节点 → 组织节点 → 通道节点。
 * 通道的 {@code ParentID} 指向组织编码；缺组织层时通道是孤儿节点，上级界面看不到。
 *
 * <p>{@code cascadeOrgId} 是 20 位国标编码，由 {@code CascadeOrgManager} 在新增时生成，
 * <b>下发后不可变更</b>——上级按该编码建树，改编码等价于删旧节点建新节点。
 */
@Data
public class CascadeOrgDTO {

    private Long              id;
    private LocalDateTime     createTime;
    private LocalDateTime     updateTime;

    /** 上级平台ID */
    private String            platformId;
    /** 父组织（tb_cascade_org.id），空=根组织，直接挂平台节点 */
    private Long              parentOrgId;
    /**
     * 物化路径：根到自身的 {@code cascadeOrgId} 链，形如 {@code /A/B/}。
     *
     * <p>用于「以某组织为根取整棵子树」：{@code orgPath LIKE '/A/%'} 一条查询即覆盖。
     * 只读，由 {@code CascadeOrgManager} 生成，接口不接受外部传入。
     */
    private String            orgPath;
    /** 上报给上级的组织编码（20 位） */
    private String            cascadeOrgId;
    /** 上报名称 */
    private String            orgName;
    /** 同级排序 */
    private Integer           sortOrder;
    /** 1-上报 0-不上报（整棵子树剪掉） */
    private Integer           enabled;

    /** 子组织，仅树形出参使用 */
    private List<CascadeOrgDTO> children;
    /** 直属通道数，仅树形出参使用（便于前端提示"删除前需先迁走通道"） */
    private Integer           channelCount;

    /**
     * 创建时间转 Unix 毫秒时间戳（Web VO 出参规约）。
     *
     * @return unix 时间戳（毫秒），createTime 为空返回 null
     */
    public Long createTimeToEpochMilli() {
        return createTime != null ? createTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }

    /**
     * 更新时间转 Unix 毫秒时间戳（Web VO 出参规约）。
     *
     * @return unix 时间戳（毫秒），updateTime 为空返回 null
     */
    public Long updateTimeToEpochMilli() {
        return updateTime != null ? updateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }
}
