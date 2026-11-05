package io.github.lunasaw.voglander.manager.manager;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import io.github.lunasaw.voglander.manager.assembler.CascadeAssembler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.service.CascadePlatformService;
import io.github.lunasaw.voglander.repository.entity.CascadePlatformDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联上级平台管理器
 */
@Slf4j
@Component
public class CascadePlatformManager {

    @Autowired
    private CascadePlatformService cascadePlatformService;

    public Long add(CascadePlatformDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.hasText(dto.getPlatformId(), "platformId 不能为空");
        Assert.hasText(dto.getPlatformIp(), "platformIp 不能为空");
        Assert.hasText(dto.getLocalClientId(), "localClientId 不能为空");
        assertCodeRuleValid(dto);

        CascadePlatformDO doObj = CascadeAssembler.toDO(dto);
        /* 五段「未配置」在库里只留 NULL 一种表示：Req 把纯空白归一成空串，
           原样落库会让「没配」同时存在 '' 与 NULL，将来按 IS NULL 写的统计会漏掉这些行 */
        doObj.setCodeProvince(StringUtils.trimToNull(dto.getCodeProvince()));
        doObj.setCodeCity(StringUtils.trimToNull(dto.getCodeCity()));
        doObj.setCodeCounty(StringUtils.trimToNull(dto.getCodeCounty()));
        doObj.setCodeIndustry(StringUtils.trimToNull(dto.getCodeIndustry()));
        doObj.setCodeNetwork(StringUtils.trimToNull(dto.getCodeNetwork()));
        /* 名称只 trim 不 trimToNull：空串是合法的「未命名」状态，列本身 NOT NULL DEFAULT ''，
           统一落空串比落 NULL 更贴近读回口径（前端一律 name || platformId 回落） */
        doObj.setPlatformName(StringUtils.trim(dto.getPlatformName()));
        cascadePlatformService.save(doObj);
        return doObj.getId();
    }

    public CascadePlatformDTO getByPlatformId(String platformId) {
        if (platformId == null) return null;
        LambdaQueryWrapper<CascadePlatformDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePlatformDO::getPlatformId, platformId);
        return CascadeAssembler.toDTO(cascadePlatformService.getOne(qw));
    }

    public CascadePlatformDTO getByLocalClientId(String localClientId) {
        if (localClientId == null) return null;
        LambdaQueryWrapper<CascadePlatformDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePlatformDO::getLocalClientId, localClientId);
        return CascadeAssembler.toDTO(cascadePlatformService.getOne(qw));
    }

    public boolean updateRegisterStatus(Long id, int status) {
        Assert.notNull(id, "id 不能为空");
        CascadePlatformDO update = new CascadePlatformDO();
        update.setId(id);
        update.setRegisterStatus(status);
        return cascadePlatformService.updateById(update);
    }

    public boolean deleteOne(CascadePlatformDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        LambdaQueryWrapper<CascadePlatformDO> qw = new LambdaQueryWrapper<>();
        qw.eq(dto.getPlatformId() != null, CascadePlatformDO::getPlatformId, dto.getPlatformId())
          .eq(dto.getId() != null, CascadePlatformDO::getId, dto.getId());
        return cascadePlatformService.remove(qw);
    }

    public CascadePlatformDTO getById(Long id) {
        if (id == null) return null;
        return CascadeAssembler.toDTO(cascadePlatformService.getById(id));
    }

    public boolean update(CascadePlatformDTO dto) {
        Assert.notNull(dto.getId(), "id 不能为空");
        /* 通用更新是 patch 语义：只传了规则列才校验，且要按「库里现值 + 本次提交」合并后的值判——
           否则用户只改县编码，其余段在 DTO 里是 null，会被误报「前 14 位必填」 */
        if (touchesCodeRule(dto)) {
            CascadePlatformDO existed = cascadePlatformService.getById(dto.getId());
            if (existed != null) {
                assertCodeRuleValid(codeRuleMergedWith(existed, dto));
            }
        }
        CascadePlatformDO update = CascadeAssembler.toDO(dto);
        /* 名称不需要二次收口：null 被 updateById 跳过（不改），空串能直接落库（清空），
           与编码段那种「未配置必须归一成 NULL」的字段不是一类问题 */
        boolean updated = cascadePlatformService.updateById(update);
        /* 五段再收口一次：updateById 的 null 是「不改」，写不出「清空」，理由见 normalizeSubmittedSegments */
        normalizeSubmittedSegments(dto);
        return updated;
    }

    /**
     * 保存平台编码规则（<b>整份覆盖</b>）。
     *
     * <p><b>为什么不复用 update</b>：通用更新走 {@code updateById} 的「null 不改」语义，
     * 而规则弹窗必须能把某一段清空、把开关从 1 关回 0、把上一次填错的起始序号一起清掉。
     * 这里用条件更新把八列一次写满，语义是「保存后库里就是这份规则」。
     *
     * <p><b>不改写既有上报编码</b>：已上报的码是上级侧历史录像、订阅、点播的引用键，
     * 改码等于删旧建新；规则只影响后续生成，需要换码时走批量补齐等显式入口。
     *
     * <p><b>{@code publish_ancestors} 是整份覆盖里唯一的例外</b>：它描述的是「勾选组织时要不要
     * 补推祖先」，与编码规则没有因果关系。未提交时保留库中现值——若按 0 落库，会把「自动补推」
     * 悄悄改成「缺祖先即报错」，而失败点在组织发布入口，用户很难把两件事联系起来。
     *
     * @param id  平台主键
     * @param dto 规则配置；null 字段按「未配置」落库（{@code publish_ancestors} 除外）
     * @return 恒为 true：存在性已由校验保证，「是否命中行」不等于「是否保存成功」
     */
    public boolean updateCodeRule(Long id, CascadePlatformDTO dto) {
        Assert.notNull(id, "id 不能为空");
        Assert.notNull(dto, "dto 不能为空");
        CascadePlatformDO existed = cascadePlatformService.getById(id);
        Assert.notNull(existed, "上级平台不存在：" + id);
        assertCodeRuleValid(dto);

        Integer publishAncestors = keepPublishAncestors(dto.getPublishAncestors(), existed);
        cascadePlatformService.lambdaUpdate()
            .eq(CascadePlatformDO::getId, id)
            .set(CascadePlatformDO::getCodeProvince, StringUtils.trimToNull(dto.getCodeProvince()))
            .set(CascadePlatformDO::getCodeCity, StringUtils.trimToNull(dto.getCodeCity()))
            .set(CascadePlatformDO::getCodeCounty, StringUtils.trimToNull(dto.getCodeCounty()))
            .set(CascadePlatformDO::getCodeIndustry, StringUtils.trimToNull(dto.getCodeIndustry()))
            .set(CascadePlatformDO::getCodeNetwork, StringUtils.trimToNull(dto.getCodeNetwork()))
            .set(CascadePlatformDO::getCodeStartSeq, dto.getCodeStartSeq())
            .set(CascadePlatformDO::getCodeRuleEnabled, defaultSwitch(dto.getCodeRuleEnabled()))
            .set(CascadePlatformDO::getPublishAncestors, publishAncestors)
            .update();
        log.info("平台编码规则保存: id={}, ruleEnabled={}, publishAncestors={}, prefix={}, startSeq={}",
            id, defaultSwitch(dto.getCodeRuleEnabled()), publishAncestors, prefixOf(dto),
            dto.getCodeStartSeq());
        /* 不能用 update() 的返回值：MySQL 默认对「命中但无值变化」的 UPDATE 返回 0，
           而重复保存同一份规则是完全合法的操作，前端会把它当成保存失败 */
        return true;
    }

    /**
     * 校验平台编码规则「启用即前 14 位必填」。
     *
     * <p><b>为什么必须填满</b>：平台没有父级可回落。启用规则却留空某段，运行期只能退到组织规则
     * 或全局前缀，结果是「以为按平台规则生成、实际前几位来自别处」——这种错在上级报文里看不出来。
     * 前 14 位 = 省(2) + 市(2) + 县(4) + 行业(2) + 类型(3) + 网络(1)，其中类型位由对象类型决定
     * （组织 215/216、设备 132、通道 131），所以平台只填五段、合计 11 位。
     *
     * <p><b>类型位不在入参里</b>：允许平台填类型位就会配出「通道码以 216 开头」的串，
     * 而这类串一眼看不出错，排查成本远高于保存时多拦一次。
     *
     * <p>全空且未启用是合法状态：编码沿用组织规则与全局兜底，与升级前等价。
     *
     * @param dto 规则配置
     * @throws IllegalArgumentException 启用但缺段、段位宽不对或开关越界
     */
    public void assertCodeRuleValid(CascadePlatformDTO dto) {
        if (dto == null) {
            return;
        }
        assertSwitch(dto.getCodeRuleEnabled(), "平台编码规则开关");
        assertSwitch(dto.getPublishAncestors(), "父链补推开关");
        Integer startSeq = dto.getCodeStartSeq();
        if (startSeq != null && (startSeq < 0 || startSeq > 999_999)) {
            throw new IllegalArgumentException("起始序号范围 0-999999，当前值：" + startSeq);
        }

        boolean required = Integer.valueOf(1).equals(dto.getCodeRuleEnabled());
        assertSegment(dto.getCodeProvince(), 2, "省编码", required);
        assertSegment(dto.getCodeCity(), 2, "市编码", required);
        assertSegment(dto.getCodeCounty(), 4, "县区编码", required);
        assertSegment(dto.getCodeIndustry(), 2, "行业编码", required);
        assertSegment(dto.getCodeNetwork(), 1, "网络标识", required);
    }

    public boolean delete(Long id) {
        Assert.notNull(id, "id 不能为空");
        return cascadePlatformService.removeById(id);
    }

    public boolean enablePlatform(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadePlatformDO update = new CascadePlatformDO();
        update.setId(id);
        update.setEnabled(1);
        return cascadePlatformService.updateById(update);
    }

    public boolean disablePlatform(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadePlatformDO update = new CascadePlatformDO();
        update.setId(id);
        update.setEnabled(0);
        return cascadePlatformService.updateById(update);
    }

    public Page<CascadePlatformDTO> getPage(CascadePlatformDTO query, int page, int size) {
        LambdaQueryWrapper<CascadePlatformDO> qw = new LambdaQueryWrapper<>();
        if (query != null) {
            qw.eq(query.getPlatformId() != null, CascadePlatformDO::getPlatformId, query.getPlatformId());
            qw.like(query.getPlatformIp() != null, CascadePlatformDO::getPlatformIp, query.getPlatformIp());
            qw.like(StringUtils.isNotBlank(query.getPlatformName()), CascadePlatformDO::getPlatformName,
                StringUtils.trim(query.getPlatformName()));
            qw.eq(query.getEnabled() != null, CascadePlatformDO::getEnabled, query.getEnabled());
            qw.eq(query.getRegisterStatus() != null, CascadePlatformDO::getRegisterStatus, query.getRegisterStatus());
        }
        qw.orderByDesc(CascadePlatformDO::getCreateTime);
        Page<CascadePlatformDO> doPage = cascadePlatformService.page(new Page<>(page, size), qw);
        Page<CascadePlatformDTO> result = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
        result.setRecords(doPage.getRecords().stream().map(CascadeAssembler::toDTO).toList());
        return result;
    }

    // ------------------------------------------------------------------ 编码规则内部

    /**
     * 开关只允许 0/1（null 合法，表示未传/未配置）。
     *
     * <p>Req 上已有 {@code @Min/@Max}，这里再兜一层：Manager 不是只被 Web 调用，
     * 拿到一个 2 却当作已启用/未启用两种解释都会变成难查的行为不一致。
     */
    private void assertSwitch(Integer value, String label) {
        if (value != null && value != 0 && value != 1) {
            throw new IllegalArgumentException(label + "只能为 0 或 1，当前值：" + value);
        }
    }

    /**
     * 段位校验：启用时必填；填了就必须是规定位宽的数字。
     *
     * <p>不能用 {@code Character.isDigit}：它会放行全角数字与部分 Unicode 数字，
     * 而国标编码只接受 ASCII 0-9，拼出全角串只会在上级报文里报错。
     */
    private void assertSegment(String value, int width, String label, boolean required) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            if (required) {
                throw new IllegalArgumentException("启用平台编码规则时前 14 位必填，缺少"
                    + label + "（" + width + " 位）");
            }
            return;
        }
        if (!normalized.matches("\\d{" + width + "}")) {
            throw new IllegalArgumentException(label + "必须为 " + width + " 位数字，当前值：" + normalized);
        }
    }

    /**
     * 把「提交过但归一后与原值不同」的编码段显式写回，让库里「未配置」只剩 NULL 一种表示。
     *
     * <p><b>为什么不能只靠 {@code updateById}</b>：它的 null 是「不改」，表达不了「把这一段清空」。
     * 停用态下提交 {@code ""} 要的是写 NULL，若提前归一成 null 再交给 {@code updateById}，
     * 这个动作会静默失效——界面显示清空了，库里还是旧值。
     *
     * <p><b>为什么排在 {@code updateById} 之后</b>：先归一再交给 {@code updateById}，
     * 只提交段位的 patch 会变成空 SET 的非法 SQL。代价是提交了带空白的段时多发一条 UPDATE，
     * 换掉整类边界问题。
     */
    private void normalizeSubmittedSegments(CascadePlatformDTO dto) {
        LambdaUpdateWrapper<CascadePlatformDO> wrapper = new LambdaUpdateWrapper<>();
        boolean dirty = setSegmentIfDirty(wrapper, CascadePlatformDO::getCodeProvince, dto.getCodeProvince());
        dirty |= setSegmentIfDirty(wrapper, CascadePlatformDO::getCodeCity, dto.getCodeCity());
        dirty |= setSegmentIfDirty(wrapper, CascadePlatformDO::getCodeCounty, dto.getCodeCounty());
        dirty |= setSegmentIfDirty(wrapper, CascadePlatformDO::getCodeIndustry, dto.getCodeIndustry());
        dirty |= setSegmentIfDirty(wrapper, CascadePlatformDO::getCodeNetwork, dto.getCodeNetwork());
        if (!dirty) {
            return;
        }
        wrapper.eq(CascadePlatformDO::getId, dto.getId());
        cascadePlatformService.update(wrapper);
    }

    /**
     * 提交值已经干净、或本段压根没提交时不动这一列，交给 {@code updateById} 处理。
     *
     * @return 是否需要显式 SET 一次
     */
    private static boolean setSegmentIfDirty(LambdaUpdateWrapper<CascadePlatformDO> wrapper,
        SFunction<CascadePlatformDO, String> column, String submitted) {
        if (submitted == null) {
            return false;
        }
        String normalized = StringUtils.trimToNull(submitted);
        if (Objects.equals(normalized, submitted)) {
            return false;
        }
        /* normalized 为 null 时生成的是 column = NULL，正是「清空这一段」 */
        wrapper.set(column, normalized);
        return true;
    }

    /** 本次提交是否触及编码规则八列（只改连接参数的 patch 不必做规则校验） */
    private boolean touchesCodeRule(CascadePlatformDTO dto) {
        return dto.getCodeProvince() != null || dto.getCodeCity() != null || dto.getCodeCounty() != null
            || dto.getCodeIndustry() != null || dto.getCodeNetwork() != null || dto.getCodeStartSeq() != null
            || dto.getCodeRuleEnabled() != null || dto.getPublishAncestors() != null;
    }

    /**
     * 把「库中现值」与「本次提交」合并成最终规则，供 patch 校验用。
     *
     * <p>空串要算「显式清空」而不是「没传」：所以判断条件是 {@code != null}，
     * 与通用更新写入库的语义保持一致。
     */
    private CascadePlatformDTO codeRuleMergedWith(CascadePlatformDO existed, CascadePlatformDTO submitted) {
        CascadePlatformDTO merged = CascadeAssembler.toDTO(existed);
        if (submitted.getCodeProvince() != null) {
            merged.setCodeProvince(submitted.getCodeProvince());
        }
        if (submitted.getCodeCity() != null) {
            merged.setCodeCity(submitted.getCodeCity());
        }
        if (submitted.getCodeCounty() != null) {
            merged.setCodeCounty(submitted.getCodeCounty());
        }
        if (submitted.getCodeIndustry() != null) {
            merged.setCodeIndustry(submitted.getCodeIndustry());
        }
        if (submitted.getCodeNetwork() != null) {
            merged.setCodeNetwork(submitted.getCodeNetwork());
        }
        if (submitted.getCodeStartSeq() != null) {
            merged.setCodeStartSeq(submitted.getCodeStartSeq());
        }
        if (submitted.getCodeRuleEnabled() != null) {
            merged.setCodeRuleEnabled(submitted.getCodeRuleEnabled());
        }
        if (submitted.getPublishAncestors() != null) {
            merged.setPublishAncestors(submitted.getPublishAncestors());
        }
        return merged;
    }

    /** 开关空值归 0：两列在库里是 NOT NULL DEFAULT 0，整份保存时不能写 null */
    private static Integer defaultSwitch(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 父链补推开关：整份覆盖里唯一「未提交即保留现值」的字段。
     *
     * <p>它的 0/1 描述的是发布策略而非编码规则，且 0 会让组织发布在缺祖先时直接失败；
     * 调用方（弹窗或脚本）只提交编码五段时，不能顺手把已打开的补推关掉。
     *
     * <p>库里该列 NOT NULL，取不到值时归 0 与建表默认一致。
     */
    private static Integer keepPublishAncestors(Integer submitted, CascadePlatformDO existed) {
        if (submitted != null) {
            return defaultSwitch(submitted);
        }
        return defaultSwitch(existed == null ? null : existed.getPublishAncestors());
    }

    /** 可配的五段拼在一起（11 位），仅用于日志定位“到底按哪个前缀生成” */
    private static String prefixOf(CascadePlatformDTO dto) {
        return StringUtils.defaultString(StringUtils.trimToNull(dto.getCodeProvince()))
            + StringUtils.defaultString(StringUtils.trimToNull(dto.getCodeCity()))
            + StringUtils.defaultString(StringUtils.trimToNull(dto.getCodeCounty()))
            + StringUtils.defaultString(StringUtils.trimToNull(dto.getCodeIndustry()))
            + StringUtils.defaultString(StringUtils.trimToNull(dto.getCodeNetwork()));
    }
}
