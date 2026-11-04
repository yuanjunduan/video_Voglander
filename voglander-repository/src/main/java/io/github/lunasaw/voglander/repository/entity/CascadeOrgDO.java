package io.github.lunasaw.voglander.repository.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 级联组织树表（<b>本地资产</b>，全局一套，不绑定上级平台）。
 *
 * <p>上级平台按 Catalog Item 的 {@code ParentID} 建目录树：设备与通道必须挂在组织节点下才可见。
 * 本表<b>不出现 {@code platform_id}</b>——组织先存在于本地，再被「发布」到一个或多个上级，
 * 推给谁、用什么编码，全部由 {@code tb_cascade_publish} 表达。结构一份，可见性 N 份。
 *
 * <p>{@code parentId} 为空表示根组织（直接挂域节点）；只存树内父节点 id，
 * 上报报文里的 {@code ParentID} 由 Manager 实时解析成父级的 {@code orgCode}，
 * 避免冗余字段写坏后出现「本地说一套、上级看另一套」。
 *
 * <p>本表与本地部门表 {@code tb_dept} 解耦：改级联组织不影响平台内部权限与数据归属。
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

    /** 父组织（本表 id），空=根组织 */
    private Long          parentId;
    /**
     * 物化路径：根到自身的 {@code orgCode} 链，形如 {@code /A/B/}。
     *
     * <p>只读冗余列，由 {@code CascadeOrgManager} 在新增时生成。存编码而非 id，
     * 是为了让「以某组织为根取整棵子树」一条前缀查询就能覆盖任意层级。
     * 父节点与编码都不允许原地修改，只有移动组织会使该列失效（由 move 重算）。
     */
    private String        orgPath;
    /** 组织编码（本地唯一，20 位国标编码；本地改编码不影响其它平台） */
    private String        orgCode;
    /** 组织名称 */
    private String        orgName;
    /** 同级排序 */
    private Integer       sortOrder;
    /**
     * 本地启用开关：1-可用 0-停用（其下设备与通道一并从所有平台的目录里消失）。
     *
     * <p>与发布层的 {@code tb_cascade_publish.enabled} 是两件事：本地停用表示这个组织不用了；
     * 发布停用只表示「不推给那个平台」，组织在本地照常可用。
     */
    private Integer       enabled;

    /* ---------------- 编码段规则（预留，本轮只落结构不参与生成） ---------------- */

    /** 省编码（2 位，GB/T 2260） */
    private String        codeProvince;
    /** 市编码（2 位） */
    private String        codeCity;
    /** 县区编码（4 位） */
    private String        codeCounty;
    /** 行业编码（2 位） */
    private String        codeIndustry;
    /** 类型编码（3 位）：215 业务分组 / 216 虚拟组织 */
    private String        codeType;
    /** 网络标识（1 位） */
    private String        codeNetwork;
    /** 起始序号（6 位）：该组织占用的号段起点 */
    private Integer       codeStartSeq;
    /** 0=未启用（走后端默认生成），1=按上面七段生成 */
    private Integer       codeRuleEnabled;
}
