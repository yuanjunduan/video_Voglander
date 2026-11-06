package io.github.lunasaw.voglander.web.api.cascade.resp;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 级联组织批量推送结果。
 *
 * <p>逐条返回而不是只给一个总数：批量推送失败往往集中在个别组织（祖先未推送、本地已停用、
 * 平台的目录订阅没建起来），把 {@code message} 带回去用户才能直接定位要补哪一级，
 * 不用整批重试去猜。
 *
 * <p>{@link #total} 是<b>实际执行</b>的目标数，不是请求里的 id 数：默认会把祖先补进来执行，
 * 差额见 {@link #ancestorsAdded}。只按请求数报总数会让「勾了 3 个、跑了 5 条」看起来像 bug。
 *
 * @author luna
 */
@Data
public class CascadeOrgPublishBatchResp implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实际执行的组织数（含自动补入的祖先） */
    private Integer      total;

    /** 成功条数 */
    private Integer      succeeded;

    /** 失败条数 */
    private Integer      failed;

    /** 其中因「祖先不完整」被自动补入的目标数；请求勾选的组织本身不计入 */
    private Integer      ancestorsAdded;

    /** 逐条结果（按执行顺序：层级由浅到深） */
    private List<Item>   items = new ArrayList<>();

    /** 单条结果 */
    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 组织主键 */
        private Long    id;

        /** 组织名称（取不到时为 null） */
        private String  orgName;

        /** 本地组织编码 */
        private String  orgCode;

        /**
         * 是否为「自动补入的祖先」。
         *
         * <p>前端据此区分「你勾的」与「为了让它立起来而顺带推的」——不标出来的话
         * 用户会以为自己误勾了一个组织。
         */
        private Boolean ancestor;

        /** 是否成功 */
        private Boolean ok;

        /** 失败原因；成功时为 null */
        private String  message;
    }
}
