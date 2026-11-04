package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 级联组织的发布配置（Web 入参）：这个组织要推给哪些上级、每个上级用什么编码与策略。
 *
 * <p><b>为什么发布范围是独立接口</b>：组织本身属于本地资产，「推给谁」是多对多关系。
 * 若把平台塞进组织的增改接口，一次改名就有可能顺手改掉发布范围，用户很难预期；
 * 而且同一组织推给两个平台时，各平台的编码覆盖与通道处置策略本就是两套值。
 *
 * @author luna
 */
@Data
public class CascadeOrgPublishReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标上级平台编码集合。<b>未勾选的平台不会被删除发布行，而是置为停用</b>——
     * 停用保留编码覆盖，重新勾选时不用重填。
     */
    private List<String>              platformIds;

    /**
     * 各平台的通道处置策略：{@code platformId → CASCADE/REROUTE}，空按 CASCADE。
     *
     * <p>只对 {@code platformIds} 里出现的平台生效；停用的发布行也保留原策略，
     * 因为「停用该组织的上报」与「该组织被停用时通道怎么办」是两个独立决定。
     */
    private Map<String, String>       revokePolicyByPlatform = new LinkedHashMap<>();

    /**
     * 各平台的上报编码覆盖：{@code platformId → publishCode}。
     *
     * <p>留空表示沿用本地编码；填了值就必须在该平台内唯一，否则上级按 DeviceID 建索引时
     * 会丢掉其中一个节点。
     */
    private Map<String, String>       publishCodeByPlatform   = new LinkedHashMap<>();

    /**
     * 各平台的上报名称覆盖：{@code platformId → publishName}；留空沿用本地名称。
     */
    private Map<String, String>       publishNameByPlatform   = new LinkedHashMap<>();
}
