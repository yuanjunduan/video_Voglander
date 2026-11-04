package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 级联组织树 DTO（<b>本地资产</b>，不携带上级平台）。
 *
 * <p>组织是上级目录树里的<b>中间节点</b>：域节点 → 组织节点 → 设备节点 → 通道节点。
 * 同一棵组织树可以被发布到任意多个上级平台，推给谁由 {@code tb_cascade_publish} 表达，
 * 因此本 DTO 没有 {@code platformId}——出现它就意味着组织又被绑死在某个平台上了。
 *
 * <p>{@code orgCode} 是 20 位国标编码，由 {@code CascadeOrgManager} 在新增时生成。
 * 它是<b>本地</b>编码：给某个上级用别的编码时写到该平台的 {@code publish_code}，
 * 本地改编码只影响未做编码覆盖的平台。
 */
@Data
public class CascadeOrgDTO {

    private Long              id;
    private LocalDateTime     createTime;
    private LocalDateTime     updateTime;

    /** 父组织（tb_cascade_org.id），空=根组织，直接挂域节点 */
    private Long              parentId;
    /**
     * 物化路径：根到自身的 {@code orgCode} 链，形如 {@code /A/B/}。
     *
     * <p>用于「以某组织为根取整棵子树」：{@code orgPath LIKE '/A/%'} 一条查询即覆盖。
     * 只读，由 {@code CascadeOrgManager} 生成，接口不接受外部传入。
     */
    private String            orgPath;
    /** 本地组织编码（20 位） */
    private String            orgCode;
    /** 组织名称 */
    private String            orgName;
    /** 同级排序 */
    private Integer           sortOrder;
    /** 1-启用 0-停用（停用后其下设备/通道从所有平台目录里消失） */
    private Integer           enabled;

    /* ---------------- 编码段规则（预留，不参与生成本轮） ---------------- */

    private String            codeProvince;
    private String            codeCity;
    private String            codeCounty;
    private String            codeIndustry;
    private String            codeType;
    private String            codeNetwork;
    private Integer           codeStartSeq;
    private Integer           codeRuleEnabled;

    /**
     * 已发布到的上级平台编码（只读，列表/树出参填充）。
     *
     * <p>含 {@code enabled=0} 的发布行——页面上要能区分「配了但没开」与「根本没配」。
     */
    private List<String>      publishedPlatformIds;

    /**
     * <b>入参专用</b>：本次要发布到的平台编码集合。
     *
     * <p>只在新增组织时使用——「建组织 + 选推给哪些平台」一次完成，否则新组织一落地就是
     * 一个谁都看不到的节点。出参不要读它，用 {@link #publishedPlatformIds}。
     */
    private List<String>      platformIds;

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
