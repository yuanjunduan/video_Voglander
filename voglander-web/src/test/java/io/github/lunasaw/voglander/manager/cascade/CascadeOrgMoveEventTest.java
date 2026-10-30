package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;

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
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 组织移动的<b>事件</b>侧验证：验证「提交之后才发 DEL」与「DEL 的父编码取移动前的旧父」。
 *
 * <p><b>为什么不跟其它 {@code CascadeOrgManager} 用例放一起</b>：本项目的集成测试基类带
 * {@code @Transactional}，而这里关心的恰恰是事务<b>提交之后</b>才触发的
 * {@code afterCommit} 回调——套在外层事务里回调永远不会执行，断言只会是「事件没发出来」的假阴性。
 * 因此显式声明 {@code NOT_SUPPORTED} 让 manager 自己开事务，并用唯一的 platformId 隔离数据、
 * 在 {@link #cleanup()} 里物理清掉（真提交落库，不会随事务回滚）。
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
    private ApplicationEvents  applicationEvents;

    /** 每个用例独占一个上级平台编码，清理时按它精确删除 */
    private final String       platformId = UniqueKeyFactory.deviceId();

    private Long               rootId;
    private Long               midId;
    private Long               leafId;
    private Long               targetId;

    private CascadeOrgDTO org(Long parentOrgId, String name) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setPlatformId(platformId);
        dto.setParentOrgId(parentOrgId);
        dto.setOrgName(name);
        return dto;
    }

    /** 造一棵 root → mid → leaf 的树，外加另一个根 targetId，返回四者主键 */
    private void buildTree() {
        rootId = cascadeOrgManager.add(org(null, "一层"));
        midId = cascadeOrgManager.add(org(rootId, "二层"));
        leafId = cascadeOrgManager.add(org(midId, "三层"));
        targetId = cascadeOrgManager.add(org(null, "另一棵树的根"));
    }

    @AfterEach
    void cleanup() {
        LambdaQueryWrapper<CascadeOrgDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeOrgDO::getPlatformId, platformId);
        cascadeOrgService.remove(qw);
    }

    @Test
    @DisplayName("事务提交后发 DEL：叶子→根后序，父编码是移动前的旧父")
    void should_publish_del_with_old_parent_after_commit() {
        buildTree();
        String rootCode = cascadeOrgManager.getById(rootId).getCascadeOrgId();
        String midCode = cascadeOrgManager.getById(midId).getCascadeOrgId();
        String leafCode = cascadeOrgManager.getById(leafId).getCascadeOrgId();

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

        assertEquals(targetId, cascadeOrgManager.getById(midId).getParentOrgId());
        assertEquals(cascadeOrgManager.getById(targetId).getOrgPath()
            + cascadeOrgManager.getById(midId).getCascadeOrgId() + "/",
            cascadeOrgManager.getById(midId).getOrgPath());
    }
}
