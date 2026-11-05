package io.github.lunasaw.voglander.web.api.cascade.vo;

import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeClientDTO;
import lombok.Data;

/**
 * 级联本端客户端 VO（Web 出参，时间字段统一 Unix 毫秒）。
 *
 * <p>一条记录代表「本端以哪个身份向上级注册」：编码 + 对外通告 IP。
 * 上级平台的 {@code clientRefId} 指向它，所以这里额外给出 {@code platformRefCount}——
 * 页面要能在删除前说清楚「还有 N 个平台在用这个身份」。
 */
@Data
public class CascadeClientVO {

    private Long    id;
    /** 创建时间（Unix 毫秒） */
    private Long    createTime;
    /** 更新时间（Unix 毫秒） */
    private Long    updateTime;

    /** 本端平台编码（20 位国标 ID）：创建后不可改 */
    private String  clientId;
    /** 名称：空=未命名，前端按 name || clientId 回落 */
    private String  clientName;
    /** Contact 头对外通告 IP */
    private String  clientIp;
    /** 1=新增平台时的默认选项 */
    private Integer isDefault;
    /** 1-启用 0-禁用 */
    private Integer enabled;
    private String  remark;

    /** 仍引用该客户端的上级平台数量；删除会被它挡住 */
    private Long    platformRefCount;

    /**
     * DTO → VO。
     *
     * @param dto           客户端
     * @param platformRefs  引用该客户端的平台数量；未知传 0
     * @return VO，入参为空返回 null
     */
    public static CascadeClientVO convertVO(CascadeClientDTO dto, long platformRefs) {
        if (dto == null) {
            return null;
        }
        CascadeClientVO vo = new CascadeClientVO();
        vo.setId(dto.getId());
        vo.setCreateTime(dto.createTimeToEpochMilli());
        vo.setUpdateTime(dto.updateTimeToEpochMilli());
        vo.setClientId(dto.getClientId());
        vo.setClientName(dto.getClientName());
        vo.setClientIp(dto.getClientIp());
        vo.setIsDefault(dto.getIsDefault());
        vo.setEnabled(dto.getEnabled());
        vo.setRemark(dto.getRemark());
        vo.setPlatformRefCount(platformRefs);
        return vo;
    }
}
