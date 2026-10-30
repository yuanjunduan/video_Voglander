package io.github.lunasaw.voglander.web.api.cascade.resp;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 级联组织批量「取消同步」结果。
 *
 * <p>逐条返回而不是只给一个总数：批量场景下失败往往集中在个别组织（例如子组织已被别的
 * 组织取消过、组织已被删除），把 {@code message} 带回去才能让用户直接定位是哪一条出了问题，
 * 不用逐个重试猜。
 *
 * @author luna
 */
@Data
public class CascadeRevokeBatchResp implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求的组织数 */
    private Integer      total;

    /** 成功条数 */
    private Integer      succeeded;

    /** 失败条数 */
    private Integer      failed;

    /** 逐条结果 */
    private List<Item>   items = new ArrayList<>();

    /** 单条结果 */
    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 组织主键 */
        private Long   id;

        /** 组织名称（取不到时为 null） */
        private String orgName;

        /** 是否成功 */
        private Boolean ok;

        /** 失败原因；成功时为 null */
        private String message;
    }
}