package io.github.lunasaw.voglander.manager.sys;

/**
 * 单个可配置项的定义（代码内单一事实源）。
 *
 * <p>默认值、类型、取值范围、说明都放在代码里，而不是靠建库时插数据：新增一个配置项
 * 只改本类即可，老库不需要跑 INSERT 才能让页面显示出来。
 *
 * @param key         配置键，与 yml 属性名一致；同时作为 {@code tb_sys_config.config_key}
 * @param group       分组名，页面按组展示
 * @param valueType   值类型 {@code STRING/INT/LONG/BOOLEAN}
 * @param defaultValue 代码内默认值（yml 未配置且库里无覆盖值时生效）
 * @param description 面向运维的说明：改它会发生什么
 * @param editable    {@code true}=改完立即生效；{@code false}=改动需重启（调度表达式在启动期解析）
 * @param min         数值型下限，非数值型为 {@code null}
 * @param max         数值型上限，非数值型为 {@code null}
 * @author luna
 */
public record SysConfigOption(String key, String group, String valueType, String defaultValue,
    String description, boolean editable, Long min, Long max) {
}
