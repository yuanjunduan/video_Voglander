package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 级联本端客户端更新请求（patch 语义：null=不改、空串=清空）。
 *
 * <p>刻意不提供 {@code clientId}：编码创建后不可改，提交了也不接受——
 * 换编码等于换平台身份，上级侧的历史对象会全部认不出来。
 */
@Data
public class CascadeClientUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 名称；null=不改，空串=清空为未命名
     */
    @Size(max = 128, message = "名称长度不能超过 128")
    private String  clientName;

    /**
     * Contact 头对外通告 IP；null=不改，空串=清空（启用平台时会回填当前绑定地址）
     */
    @Pattern(regexp = "^$|" + CascadeConstant.IPV4_REGEX, message = "对外通告IP格式不正确")
    private String  clientIp;

    /**
     * 1=设为默认（会清掉其他行的默认标记）；0 或不传表示不改
     */
    @Min(value = 0, message = "默认标记只能为 0 或 1")
    @Max(value = 1, message = "默认标记只能为 0 或 1")
    private Integer isDefault;

    /**
     * 1-启用 0-禁用；null=不改
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
