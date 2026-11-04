package io.github.lunasaw.voglander.manager.code;

/**
 * 编码规则服务：决定「推给上级的编码」从哪来。
 *
 * <p><b>为什么先有接口再有效果</b>：国标 20 位编码的分段配置（省市区 + 行业 + 类型 + 网络 + 序号）
 * 已经落库到组织表，但「按段生成」牵动序号分配、平台内查重，以及编码变更后必须对上级
 * 先 {@code DEL} 旧节点再 {@code ADD} 新节点，属于一次高危批量覆盖。先把「取编码」收敛到一个入口，
 * 通道发布与未来的覆盖接口都只认它，接入真实规则时不必再改调用方。
 *
 * <p>当前实现 {@link LocalCascadeCodeRuleService} 恒为未启用：所有方法只回本地编码，
 * 行为与接入前逐字节一致。
 */
public interface CascadeCodeRuleService {

    /**
     * 规则是否已启用。
     *
     * <p>{@code false} 时所有编码方法只返回本地编码，{@link #reapply} 不落库、不发报文。
     * 独立暴露这个方法，是为了让「开关关着」可被观测，而不是靠调用方猜。
     */
    boolean enabled();

    /** 组织编码；规则未启用时返回本地 {@code org_code}，组织不存在返回 {@code null}。 */
    String orgCode(Long orgId);

    /** 设备编码；规则未启用时返回本地 {@code device_id}，设备不存在返回 {@code null}。 */
    String deviceCode(Long deviceId);

    /** 通道编码；规则未启用时返回本地 {@code channel_id}，通道不存在返回 {@code null}。 */
    String channelCode(Long channelId);

    /**
     * 覆盖动作：按当前规则重算编码并落库，返回受影响对象清单。
     *
     * <p>这是高危批量操作——编码一变，上级侧的旧节点必须显式 {@code DEL}、新节点再 {@code ADD}，
     * 因此先支持 {@code dryRun} 预览。规则未启用时只返回空结果，绝不写库。
     *
     * @param orgId  重算范围的根组织
     * @param dryRun 只预览不落库
     */
    CascadeCodeReapplyResult reapply(Long orgId, boolean dryRun);
}
