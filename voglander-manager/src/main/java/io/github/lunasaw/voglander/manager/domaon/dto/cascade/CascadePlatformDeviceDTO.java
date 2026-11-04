package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import lombok.Data;

/**
 * 某上级平台目录里的设备节点投影：本地设备 + 该平台的编码/名称/排序覆盖。
 *
 * <p><b>它只在平台开了设备层时有意义</b>：设备层是上级建树习惯（域 → 组织 → 设备 → 通道），
 * 不开的时候通道直挂组织，设备表只是数据来源而不是目录节点。
 *
 * <p>与 {@link CascadePlatformOrgDTO} 同样是派生视图，不落库：每次由
 * {@code CascadePublishedAssetResolver#listDevices} 现算。父节点编码由组装器按组织视图解析——
 * 设备的归属组织在该平台立不起来时，设备节点也不出现（否则就是挂不住的孤儿）。
 */
@Data
public class CascadePlatformDeviceDTO {

    /** 本地设备国标 ID（{@code tb_device.device_id}），也是发布行的业务键 */
    private String  localDeviceId;

    /** 该平台目录里的编码：发布行有覆盖用覆盖值，否则等于 {@link #localDeviceId} */
    private String  platformCode;

    /** 该平台目录里的名称：发布行有覆盖用覆盖值，否则等于本地设备名 */
    private String  name;

    /** 本地归属组织编码（{@code tb_device.org_id} 对应的 {@code org_code}），空=挂域节点 */
    private String  localOrgCode;

    /** 该平台目录里的排序：发布行有覆盖用覆盖值，否则等于 0（设备层不参与本地组织排序） */
    private Integer sortOrder;

    /** 在线状态 1在线 0离线；只影响报文的 {@code Status}，不影响节点是否存在 */
    private Integer status;
}
