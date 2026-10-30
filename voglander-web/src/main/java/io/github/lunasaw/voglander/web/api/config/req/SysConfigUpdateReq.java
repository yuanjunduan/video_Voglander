package io.github.lunasaw.voglander.web.api.config.req;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 系统配置批量保存入参。
 *
 * <p>一次提交整组，服务端逐项校验后整体落库：任一项不合法就整批拒绝，
 * 避免出现"改了一半"的中间态。
 *
 * @author luna
 */
@Data
public class SysConfigUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待保存配置项 */
    private List<Item>        items;

    /**
     * 单项：键 + 值。
     *
     * <p>{@code configValue} 传空串表示删除覆盖值、回落 yml/默认值。
     */
    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 配置键 */
        private String            configKey;

        /** 配置值 */
        private String            configValue;
    }
}
