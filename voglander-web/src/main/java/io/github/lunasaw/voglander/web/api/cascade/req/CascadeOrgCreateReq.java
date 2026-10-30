package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import lombok.Data;

/**
 * 级联组织新增请求（Web 入参）。
 *
 * <p>组织编码由后端按国标规则生成，入参不接受 {@code cascadeOrgId}：
 * 手填容易出现类型位错误或与平台前缀不一致，上级会把这种节点判为非法。
 *
 * @author luna
 */
@Data
public class CascadeOrgCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属上级平台国标 ID（必填）
     */
    private String  platformId;

    /**
     * 父组织主键；为空表示根组织，直接挂在平台节点下
     */
    private Long    parentOrgId;

    /**
     * 上报给上级的组织名称（必填）
     */
    private String  orgName;

    /**
     * 同级排序，越小越靠前
     */
    private Integer sortOrder;

    /**
     * 是否上报 1上报 / 0不上报（停用会连带整棵子树一起从目录中剪掉）
     */
    private Integer enabled;
}
