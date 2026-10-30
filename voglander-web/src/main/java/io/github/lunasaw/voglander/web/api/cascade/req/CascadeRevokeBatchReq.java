package io.github.lunasaw.voglander.web.api.cascade.req;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 级联组织批量「取消同步」请求。
 *
 * <p>批量只做<b>逐条独立执行</b>，不做事务包裹：每个组织的报文条数不同、失败原因也不同，
 * 一条失败不该回滚另外几条已经推给上级的 DEL——上级目录是外部状态，回滚本地记录
 * 反而会造成「本地显示在上报、上级其实已经删了」的错位。出参逐条返回结果，由调用方决定是否重试。
 *
 * @author luna
 */
@Data
public class CascadeRevokeBatchReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待取消同步的组织主键列表
     */
    private List<Long> ids;

    /**
     * 通道处置策略：{@code CASCADE} / {@code REROUTE}；传空则每个组织沿用各自已保存的策略
     */
    private String     policy;
}