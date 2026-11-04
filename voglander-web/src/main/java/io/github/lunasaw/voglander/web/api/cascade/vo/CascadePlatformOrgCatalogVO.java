package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 某个上级平台的组织视角：目录里立得起来的组织，加上「勾了要推、却立不起来」的组织。
 *
 * <p><b>为什么两个清单一起给</b>：只给目录内节点，用户看到「我明明勾了推送，列表里却没有」
 * 时无处可查；阻断清单把「谁挡住了、该改哪一步」直接摆出来，页面不必再拼第二次查询。
 *
 * <p>目录内组织的 {@code platformCode} 是该平台眼里的编码（发布行覆盖优先），
 * 与本地编码 {@code localCode} 一起给出：本地编码用于跳回本地组织树，
 * 平台编码用于和上级目录逐条对账——两者可能不同，正是「同组织多平台不同编码」的落点。
 */
@Data
public class CascadePlatformOrgCatalogVO {

    /** 上级平台编码（请求参数原样回显，前端多处并发请求时用于对齐结果） */
    private String platformId;

    /** 目录内组织，按该平台的排序值升序 */
    private List<ItemVO> items = new ArrayList<>();

    /** 已勾选发布但立不起来的组织（阻断） */
    private List<BlockedVO> blocked = new ArrayList<>();

    /** 目录内组织数，前端不必再 length 统计 */
    private Integer deliveredCount;

    /** 阻断组织数 */
    private Integer blockedCount;

    /** 通道需上浮到域节点的组织数（覆盖全部被阻断组织，不只是已勾选的） */
    private Integer reroutedCount;

    /** 目录内组织节点：上级这一刻真实收到的样子 */
    @Data
    public static class ItemVO {

        /** 本地组织主键 */
        private Long id;

        /** 本地父组织主键 */
        private Long parentId;

        /** 本地组织编码：通道归属、跳回组织树用 */
        private String localCode;

        /** 该平台目录里的编码：发布行有覆盖用覆盖值 */
        private String platformCode;

        /** 该平台目录里的名称 */
        private String name;

        /** 该平台目录里的排序值 */
        private Integer sortOrder;

        /** 上级眼里的父节点编码；根组织为空，由发送方回落域编码 */
        private String parentPlatformCode;
    }

    /** 阻断明细：勾了发布、上级却看不到，需要用户处理的那条链路 */
    @Data
    public static class BlockedVO {

        private Long id;

        private String localCode;

        private String name;

        /** 阻断节点编码（可能是自己） */
        private String blockedByCode;

        /** 阻断节点名称 */
        private String blockedByName;

        /** LOCAL_DISABLED / ANCESTOR_LOCAL_DISABLED / ANCESTOR_NOT_PUBLISHED */
        private String reason;

        /** 阻断节点的策略是否为 REROUTE：为真表示该组织的通道改挂域节点，业务仍在 */
        private Boolean rerouted;
    }
}
