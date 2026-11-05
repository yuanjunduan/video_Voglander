package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 级联本端客户端新增请求。
 *
 * <p>编码是本端在上级眼中的身份，创建后不可改：换编码等于换了一个平台，
 * 已建立的目录、订阅、点播关系会全部失效，而这个后果在界面上看不出来。
 */
@Data
public class CascadeClientCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 本端平台编码（20 位国标 ID，必填）
     */
    @NotBlank(message = "本端平台编码不能为空")
    @Pattern(regexp = CascadeConstant.GB_ID_REGEX, message = "本端平台编码必须为 20 位数字")
    private String  clientId;

    /**
     * 名称（选填）：只用于本地辨识
     */
    @Size(max = 128, message = "名称长度不能超过 128")
    private String  clientName;

    /**
     * Contact 头对外通告 IP（选填）；留空时启用平台那一刻用当前 SIP 客户端绑定地址回填。
     *
     * <p>必须是上级能回包的真实地址，不是 {@code sip.client.listenIp}。
     */
    @Pattern(regexp = "^$|" + CascadeConstant.IPV4_REGEX, message = "对外通告IP格式不正确")
    private String  clientIp;

    /**
     * 是否设为默认客户端：0/1，空按 0 处理（库里第一条会自动成为默认）
     */
    @Min(value = 0, message = "默认标记只能为 0 或 1")
    @Max(value = 1, message = "默认标记只能为 0 或 1")
    private Integer isDefault;

    /**
     * 1-启用 0-禁用；空按 1 处理
     */
    @Min(value = 0, message = "启用状态只能为 0 或 1")
    @Max(value = 1, message = "启用状态只能为 0 或 1")
    private Integer enabled;

    @Size(max = 255, message = "备注长度不能超过 255")
    private String  remark;

    /* ---------------- 空白归一 ---------------- */

    /**
     * 通告 IP 在入口统一 trim：粘贴进来的 " 192.168.1.10 " 不应被 {@code @Pattern} 判成格式错误，
     * 而 Manager 存库时又把它 trim 成合法值——同一份输入两层结论相反。
     */
    public void setClientIp(String clientIp) {
        this.clientIp = StringUtils.trim(clientIp);
    }
}
