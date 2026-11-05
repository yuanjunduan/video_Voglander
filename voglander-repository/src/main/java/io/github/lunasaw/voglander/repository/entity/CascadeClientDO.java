package io.github.lunasaw.voglander.repository.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 级联本端客户端表
 *
 * <p>登记「本端以什么身份向上级注册」：{@code client_id} 是本端平台编码（20 位国标 ID，
 * 也是 From 头的用户部分），{@code client_ip} 是 Contact 头里对外通告的地址。
 * 端口不落库：一个 SIP UA 只绑定一个 socket（{@code sip.client.port}），
 * 按客户端各存一个端口只会配出与真实监听点对不上的值。
 *
 * <p>注意与 {@code sip.client.clientId}（设备侧客户端身份）区分：两者都是 20 位编码，
 * 但这里是级联链路里代表本平台的编码，改名会让上级把它当成另一个平台。
 */
@Data
@TableName("tb_cascade_client")
public class CascadeClientDO implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long          id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 本端平台编码（20 位国标 ID）：创建后不可改，全局唯一 */
    private String        clientId;
    /** 便于本地辨识的名称；空串表示未命名 */
    private String        clientName;
    /**
     * Contact 头对外通告的 IP；空串表示未填写。
     *
     * <p>注册是主动外发，填错仍然会显示「在线」，只在上级回叫（INVITE / 订阅通知）时暴露，
     * 所以这个值必须是上级能回包的真实地址，不是监听地址（{@code sip.client.listenIp}）。
     */
    private String        clientIp;

    /** 1=新增平台时的默认选项；全局最多一行 */
    @TableField("is_default")
    private Integer       isDefault;

    /** 1-启用 0-禁用 */
    private Integer       enabled;
    private String        remark;
    private String        extend;
}
