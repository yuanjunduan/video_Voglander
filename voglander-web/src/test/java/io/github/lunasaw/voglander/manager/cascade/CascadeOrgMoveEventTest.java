package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.manager.service.CascadePublishService;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;
import io.github.lunasaw.voglander.repository.entity.CascadePublishDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 组织移动的<b>事件</b>侧验证：验证「提交之后才发 DEL」与「DEL 的父编码取移动前的旧父」。
 *
 * <p><b>为什么不跟其它 {@code CascadeOrgManager} 用例放一起</b>：本项目的集成测试基类带
 * {@code @Transactional}，而这里关心的恰恰是事务<b>提交之后</b>才触发的
 * {@code afterCommit} 回调——套在外层事务里回调永远不会执行，断言只会是「事件没发出来」的假阴性。
 * 因此显式声明 {@code NOT_SUPPORTED} 让 manager 自己开事务，并用唯一的平台编码隔离发布关系、
 * 在 {@link #cleanup()} 里物理清掉（真提交落库，不会随事务回滚）。
 *
 * <p><b>为什么清理要按 id 而不是按平台编码</b>：1.10.1 起组织是本地资产，表上已没有
 * {@code platform_id}；平台维度只存在于 {@code tb_cascade_publish}，而发布行挂在组织的业务键
 * （{@code org_code}）上。两处都得按本用例创建过的 id/编码精确删，否则残留的发布关系会让下个用例的
 * 目录视图多出节点。
 *
 * @author luna
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RecordApplicationEvents
@DisplayName("CascadeOrgManager#move 事件验证")
class CascadeOrgMoveEventTest extends BaseTest {

    @Autowired
    private CascadeOrgManager cascadeOrgManager;

    @Autowired
    private CascadeOrgService cascadeOrgService;

    @Autowired
    private CascadePublishService cascadePublishService;

    @Autowired
    private ApplicationEvents  applicationEvents;

    /** 每个用例独占一个上级平台编码，事件按它分发，也会写进发布行 */
    private final String       platformId = UniqueKeyFactory.deviceId();

    /** 本用例造过的组织主键：组织表已无平台列，清理只能按 id 精确删 */
    private final List<Long>   createdIds   = new ArrayList<>();

    /** 对应的组织编码：发布行的对象键是 {@code org_code}，不是自增 id */
    private final List<String> createdCodes = new ArrayList<>();

    private Long               rootId;
    private Long               midId;
    private Long               leafId;
    private Long               targetId;

    private CascadeOrgDTO org(Long parentId, String name) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setParentId(parentId);
        dto.setOrgName(name);
        /* 事件按已发布的平台逐个分发：不建发布行，移动后一条 DEL 都不会发出去 */
        dto.setPlatformIds(List.of(platformId));
        return dto;
    }

    private Long createOrg(Long parentId, String name) {
        Long id = cascadeOrgManager.add(org(parentId, name));
        createdIds.add(id);
        createdCodes.add(cascadeOrgManager.getById(id).getOrgCode());
        return id;
    }

    /** 造一棵 root → mid → leaf 的树，外加另一个根 targetId，返回四者主键 */
    private void buildTree() {
        rootId = createOrg(null, "一层");
        midId = createOrg(rootId, "二层");
        leafId = createOrg(midId, "三层");
        targetId = createOrg(null, "另一棵树的根");
    }

    @AfterEach
    void cleanup() {
        if (createdIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<CascadePublishDO> publishQw = new LambdaQueryWrapper<>();
        publishQw.eq(CascadePublishDO::getObjectType, PublishObjectType.ORG.name())
            .in(CascadePublishDO::getObjectKey, createdCodes);
        cascadePublishService.remove(publishQw);

        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.in(CascadeOrgDO::getId, createdIds);
        cascadeOrgService.remove(qw);
    }

    @Test
    @DisplayName("事务提交后发 DEL：叶子→根后序，父编码是移动前的旧父")
    void should_publish_del_with_old_parent_after_commit() {
        buildTree();
        String rootCode = cascadeOrgManager.getById(rootId).getOrgCode();
        String midCode = cascadeOrgManager.getById(midId).getOrgCode();
        String leafCode = cascadeOrgManager.getById(leafId).getOrgCode();

        assertTrue(cascadeOrgManager.move(midId, targetId));

        LocalCascadeCatalogChangeEvent del = applicationEvents.stream(LocalCascadeCatalogChangeEvent.class)
            .filter(e -> "DEL".equals(e.getEvent()) && e.getNodes() != null && !e.getNodes().isEmpty())
            .findFirst()
            .orElseThrow(() -> new AssertionError("移动后必须发一条带节点清单的 DEL 事件"));

        List<String> codes = del.getNodes().stream()
            .map(LocalCascadeCatalogChangeEvent.Node::cascadeId)
            .collect(Collectors.toList());
        assertEquals(List.of(leafCode, midCode), codes, "先子后父：反过来上级会先丢父节点，子节点变孤儿");
        assertEquals(rootCode, del.getNodes().get(1).parentCode(),
            "删除报文必须用移动前的旧父定位，用新父会删到刚挂上去的那棵树");
        assertEquals(platformId, del.getPlatformId());
    }

    @Test
    @DisplayName("提交前的旧数据不会被当成删除依据：删完库里已是新父")
    void should_see_new_parent_after_commit() {
        buildTree();

        assertTrue(cascadeOrgManager.move(midId, targetId));

        assertEquals(targetId, cascadeOrgManager.getById(midId).getParentId());
        assertEquals(cascadeOrgManager.getById(targetId).getOrgPath()
            + cascadeOrgManager.getById(midId).getOrgCode() + "/",
            cascadeOrgManager.getById(midId).getOrgPath());
    }
}
