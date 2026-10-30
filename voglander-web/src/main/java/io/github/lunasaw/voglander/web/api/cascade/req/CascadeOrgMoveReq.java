package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import lombok.Data;

/**
 * 级联组织移动请求（Web 入参）。
 *
 * <p>只传目标父节点：组织编码不变，变的只有父节点与整棵子树的物化路径。
 * 上级没有“移动节点”语义，后端会先按旧父逐条 DEL 再重推快照，
 * 因此这个动作在上级侧是不可逆的重建，前端必须让用户先看到影响面。
 *
 * @author luna
 */
@Data
public class CascadeOrgMoveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标父组织主键；{@code null} 表示移动到平台根节点（不传或传空都是这个含义）
     */
    private Long targetParentId;
}
