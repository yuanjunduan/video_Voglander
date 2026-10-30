package io.github.lunasaw.voglander.manager.manager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.common.exception.ServiceExceptionEnum;
import io.github.lunasaw.voglander.manager.domaon.dto.sys.SysConfigDTO;
import io.github.lunasaw.voglander.manager.service.SysConfigService;
import io.github.lunasaw.voglander.manager.sys.SysConfigCatalog;
import io.github.lunasaw.voglander.manager.sys.SysConfigOption;
import io.github.lunasaw.voglander.repository.entity.SysConfigDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 运行时系统配置管理器：{@code tb_sys_config} 覆盖值 + 代码内默认值。
 *
 * <p><b>为什么要有它</b>：{@code gateway.cascade.catalog.*} 这类参数原先只能改
 * {@code application.yml} 再重启，现场调分片大小/超时都要停机。本类把覆盖值放进库，
 * 读取方按「覆盖值 → yml → 清单默认值」取生效值，改完立即对新请求生效。
 *
 * <p><b>为什么用内存缓存而不是每次查库</b>：{@code scan-timeout} 看门狗每秒都会读
 * {@code ok-timeout-ms}，目录分片每页都会读 {@code batch-size}。缓存一次加载后在写入时刷新，
 * 读路径零 IO；缓存没加载成功（如建表迁移尚未跑完的启动早期）就回落默认值，不影响启动。
 *
 * @author luna
 */
@Slf4j
@Component
public class SysConfigManager {

    private static final int    MAX_VALUE_LENGTH = 512;

    @Autowired
    private SysConfigService    sysConfigService;

    /** 用于取 yml 里显式配置的值：页面要显示"程序实际在用"的值，而不是只有代码默认值 */
    @Autowired
    private Environment         environment;

    /** 覆盖值缓存：configKey → configValue，只含非空值 */
    private volatile Map<String, String>        overrides      = Map.of();

    /** 覆盖值的最后修改时间：configKey → updateTime，缺记录时回落 createTime */
    private volatile Map<String, LocalDateTime>   overrideTimes  = Map.of();

    /** 是否已成功加载过一次 */
    private volatile boolean             loaded;

    /**
     * 容器就绪后加载覆盖值。
     *
     * <p>不放在 {@code @PostConstruct}：SQLite 的建表/迁移由 repository 侧的
     * {@code @PostConstruct} 完成，两个 {@code @PostConstruct} 之间没有顺序保证；
     * 提前查表会因 {@code no such table} 让启动失败。{@code ApplicationReadyEvent}
     * 在所有 bean 初始化完成之后触发，顺序天然有保证。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshQuietly();
    }

    /**
     * 重新加载覆盖值，失败只记警告不打断调用方。
     *
     * <p>启动早期（迁移未完成）与库暂时不可用都会走到这里，此时继续用默认值是正确行为。
     */
    public void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("系统配置加载失败，暂时使用代码内默认值: {}", e.getMessage());
        }
    }

    /** 重新加载覆盖值；库不可用时抛运行时异常（供测试与显式刷新使用）。 */
    public synchronized void refresh() {
        List<SysConfigDO> rows = sysConfigService.list();
        Map<String, String> loadedOverrides = new HashMap<>();
        Map<String, LocalDateTime> loadedTimes = new HashMap<>();
        for (SysConfigDO row : rows) {
            if (row == null || StringUtils.isBlank(row.getConfigKey())) {
                continue;
            }
            if (StringUtils.isBlank(row.getConfigValue())) {
                /* 空值语义是"没有覆盖"，与删除记录等价，不要当成覆盖空串 */
                continue;
            }
            loadedOverrides.put(row.getConfigKey(), row.getConfigValue().trim());
            LocalDateTime changedAt = row.getUpdateTime() != null ? row.getUpdateTime() : row.getCreateTime();
            if (changedAt != null) {
                loadedTimes.put(row.getConfigKey(), changedAt);
            }
        }
        overrides = Map.copyOf(loadedOverrides);
        overrideTimes = Map.copyOf(loadedTimes);
        loaded = true;
        log.debug("系统配置覆盖值已加载: {} 项", overrides.size());
    }

    /**
     * 读取整型配置。
     *
     * @param key      配置键
     * @param fallback 覆盖值缺失或非法时的回落值（通常传 {@code @Value} 注入的字段）
     * @return 生效值
     */
    public int getInt(String key, int fallback) {
        String value = currentValue(key);
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 的值 {} 不是合法整数，回落 {}", key, value, fallback);
            return fallback;
        }
    }

    /**
     * 读取长整型配置。
     *
     * @param key      配置键
     * @param fallback 覆盖值缺失或非法时的回落值
     * @return 生效值
     */
    public long getLong(String key, long fallback) {
        String value = currentValue(key);
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 的值 {} 不是合法整数，回落 {}", key, value, fallback);
            return fallback;
        }
    }

    /**
     * 读取布尔配置。
     *
     * @param key      配置键
     * @param fallback 覆盖值缺失或非法时的回落值
     * @return 生效值
     */
    public boolean getBoolean(String key, boolean fallback) {
        String value = currentValue(key);
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized)) {
            return false;
        }
        log.warn("配置项 {} 的值 {} 不是合法布尔值，回落 {}", key, value, fallback);
        return fallback;
    }

    /**
     * 按分组列出配置项（含键定义与当前生效值）。
     *
     * @param group 分组名；为空时返回全部
     * @return 配置项列表，顺序同 {@link SysConfigCatalog#all()}
     */
    public List<SysConfigDTO> listByGroup(String group) {
        ensureLoaded();
        Map<String, String> snapshot = overrides;
        List<SysConfigDTO> result = new ArrayList<>();
        for (SysConfigOption option : SysConfigCatalog.all()) {
            if (StringUtils.isNotBlank(group) && !option.group().equals(group)) {
                continue;
            }
            result.add(toDTO(option, snapshot.get(option.key())));
        }
        return result;
    }

    /**
     * 批量保存覆盖值。
     *
     * <p>逐项校验后写入：未知键、只读项、越界值一律整体拒绝（不做部分成功），
     * 避免运维看到"保存成功"却发现改了的一半没生效；值传空串表示删除覆盖、回落默认。
     *
     * @param items 待保存项（{@code configKey} 必填）
     * @return 实际写入/删除的条数
     */
    public int saveBatch(List<SysConfigDTO> items) {
        Assert.notNull(items, "配置项列表不能为空");
        List<SysConfigOption> options = new ArrayList<>(items.size());
        List<String> values = new ArrayList<>(items.size());
        for (SysConfigDTO item : items) {
            if (item == null || StringUtils.isBlank(item.getConfigKey())) {
                throw paramError("配置键不能为空");
            }
            SysConfigOption option = SysConfigCatalog.find(item.getConfigKey());
            if (option == null) {
                throw paramError("未知配置项: " + item.getConfigKey());
            }
            if (!option.editable()) {
                throw paramError("配置项 " + option.key() + " 需重启后生效，不支持在线修改");
            }
            options.add(option);
            values.add(normalize(option, item.getConfigValue()));
        }
        int changed = 0;
        for (int i = 0; i < options.size(); i++) {
            changed += upsert(options.get(i), values.get(i));
        }
        if (changed > 0) {
            refreshQuietly();
        }
        return changed;
    }

    /** 当前生效的覆盖值（不含 yml/默认值回落）。 */
    private String currentValue(String key) {
        if (StringUtils.isBlank(key)) {
            return null;
        }
        ensureLoaded();
        return overrides.get(key);
    }

    /** 首次读取时尝试加载，失败则保持"未加载"并继续用默认值。 */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        refreshQuietly();
    }

    private SysConfigDTO toDTO(SysConfigOption option, String override) {
        SysConfigDTO dto = new SysConfigDTO();
        dto.setConfigKey(option.key());
        dto.setConfigGroup(option.group());
        dto.setValueType(option.valueType());
        dto.setDescription(option.description());
        dto.setEditable(option.editable() ? 1 : 0);
        dto.setMin(option.min());
        dto.setMax(option.max());
        dto.setDefaultValue(option.defaultValue());
        dto.setOverridden(StringUtils.isNotBlank(override));
        dto.setConfigValue(StringUtils.isNotBlank(override) ? override : effectiveBaseline(option));
        if (StringUtils.isNotBlank(override)) {
            /* 页面"覆盖时间"列：让人知道这次覆盖是什么时候落的，排障时能对上变更窗口 */
            dto.setUpdateTime(overrideTimes.get(option.key()));
        }
        return dto;
    }

    /**
     * 无覆盖值时的生效值：yml 显式配过就用 yml，否则用清单默认值。
     *
     * <p>不直接返回清单默认值：现场往往在 yml 里调过参数（例如 batch-size=100），
     * 页面若显示 50 会让人误以为配置没生效。
     */
    private String effectiveBaseline(SysConfigOption option) {
        String fromYml = environment == null ? null : environment.getProperty(option.key());
        return StringUtils.isNotBlank(fromYml) ? fromYml.trim() : option.defaultValue();
    }

    /**
     * 校验并按类型归一化待落库的值。
     *
     * @return 归一化后的值；入参为空串返回 null（表示删除覆盖）
     */
    private String normalize(SysConfigOption option, String raw) {
        String value = StringUtils.trimToEmpty(raw);
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw paramError("配置项 " + option.key() + " 的值超过 " + MAX_VALUE_LENGTH + " 字符");
        }
        if (SysConfigCatalog.TYPE_BOOLEAN.equals(option.valueType())) {
            if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
                return "true";
            }
            if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
                return "false";
            }
            throw paramError("配置项 " + option.key() + " 只接受 true/false，当前值: " + value);
        }
        if (SysConfigCatalog.TYPE_INT.equals(option.valueType())
            || SysConfigCatalog.TYPE_LONG.equals(option.valueType())) {
            long parsed;
            try {
                parsed = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw paramError("配置项 " + option.key() + " 必须是整数，当前值: " + value);
            }
            if (option.min() != null && parsed < option.min()) {
                throw paramError("配置项 " + option.key() + " 不能小于 " + option.min());
            }
            if (option.max() != null && parsed > option.max()) {
                throw paramError("配置项 " + option.key() + " 不能大于 " + option.max());
            }
            return String.valueOf(parsed);
        }
        return value;
    }

    /**
     * 写入或删除覆盖值。
     *
     * @return 变更条数：1=有写入/删除，0=值未变化
     */
    private int upsert(SysConfigOption option, String value) {
        SysConfigDO existing = sysConfigService.getOne(
            new LambdaQueryWrapper<SysConfigDO>().eq(SysConfigDO::getConfigKey, option.key()), false);
        if (value == null) {
            if (existing == null) {
                return 0;
            }
            sysConfigService.removeById(existing.getId());
            log.info("系统配置覆盖值已删除，回落默认: {}", option.key());
            return 1;
        }
        if (existing == null) {
            SysConfigDO created = new SysConfigDO();
            created.setConfigKey(option.key());
            created.setConfigValue(value);
            created.setConfigGroup(option.group());
            created.setValueType(option.valueType());
            created.setDescription(StringUtils.abbreviate(option.description(), 255));
            created.setEditable(option.editable() ? 1 : 0);
            sysConfigService.save(created);
            log.info("系统配置覆盖值已新增: {}={}", option.key(), value);
            return 1;
        }
        if (value.equals(existing.getConfigValue())) {
            return 0;
        }
        existing.setConfigValue(value);
        sysConfigService.updateById(existing);
        log.info("系统配置覆盖值已更新: {}={}", option.key(), value);
        return 1;
    }

    private ServiceException paramError(String message) {
        return new ServiceException(ServiceExceptionEnum.PARAM_ERROR.getCode(), message);
    }
}
