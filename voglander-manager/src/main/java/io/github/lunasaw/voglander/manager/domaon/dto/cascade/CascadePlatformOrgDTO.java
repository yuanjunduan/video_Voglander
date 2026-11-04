package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import lombok.Data;

/**
 * 某上级平台目录里的组织节点投影：本地组织 + 该平台的编码/名称/排序覆盖。
 *
 * <p><b>为什么不能直接复用 {@link CascadeOrgDTO}</b>：一个组织推给两个上级时，
 * 在两个平台上的编码可能不同（{@code tb_cascade_publish.publish_code}）。协议层组装报文只要
 * 「这个平台眼里的编码」，但通道侧的 {@code cascade_org_id} 仍指向<b>本地</b>编码，
 * 两者必须能同时拿到，否则通道会挂到一个上级不认识的父节点上。
 *
 * <p>本类是<b>派生视图</b>，不落库：由 {@code CascadeOrgManager#platformOrgView} 每次现算。
 */
@Data
public class CascadePlatformOrgDTO {

    /** 本地组织主键（{@code tb_cascade_org.id}） */
    private Long    id;

    /** 本地父组织主键，空=根组织挂域节点 */
    private Long    parentId;

    /** 本地组织编码：通道归属、Web 出参都用它 */
    private String  localCode;

    /** 该平台目录里的编码：发布行有覆盖用覆盖值，否则等于 {@link #localCode} */
    private String  platformCode;

    /** 该平台目录里的名称：发布行有覆盖用覆盖值，否则等于本地名称 */
    private String  name;

    /** 该平台目录里的排序：发布行有覆盖用覆盖值，否则等于本地排序 */
    private Integer sortOrder;

    /** 上级眼里的父节点编码；根组织为空，由发送方回落域编码 */
    private String  parentPlatformCode;
}
