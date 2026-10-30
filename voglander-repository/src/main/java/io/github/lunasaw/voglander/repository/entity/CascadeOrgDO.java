package io.github.lunasaw.voglander.repository.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 级联组织树表。
 *
 * <p>上级平台按 Catalog Item 的 {@code ParentID} 建目录树：通道必须挂在组织节点下才可见。
 * 本表每平台一套，与本地部门表 {@code tb_dept} 解耦——改级联组织不影响平台内部权限与数据归属。
 *
 * <p>{@code parentOrgId} 为空表示根组织（直接挂平台节点）；只存树内父节点 id，
 * 上报报文里的 {@code ParentID} 由 Manager 实时解析成父级的 {@code cascadeOrgId}，
 * 避免冗余字段写坏后出现"本地说一套、上级看另一套"。
 */
@Data
@TableName("tb_cascade_org")
public class CascadeOrgDO implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 上级平台ID */
    private String        platformId;
    /** 父组织（本表 id），空=根组织 */
    private Long          parentOrgId;
    /**
     * 物化路径：根到自身的 {@code cascadeOrgId} 链，形如 {@code /A/B/}。
     *
     * <p>只读冗余列，由 {@code CascadeOrgManager} 在新增时生成。存编码而非 id，
     * 是为了和 {@code tb_cascade_channel.cascade_org_id} 直接对齐。
     * 因为父节点与编码都不可变，该列写入后不会失效。
     */
    private String        orgPath;
    /** 上报给上级的组织编码（20 位国标编码，下发后不可变更） */
    private String        cascadeOrgId;
    /** 上报名称 */
    private String        orgName;
    /** 同级排序 */
    private Integer       sortOrder;
    /** 1-上报 0-不上报（整棵子树剪掉） */
    private Integer       enabled;
}
