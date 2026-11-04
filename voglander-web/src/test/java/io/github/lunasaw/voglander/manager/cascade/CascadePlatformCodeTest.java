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

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.config.StubCascadeCatalogRevokeService;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * 阶段一四项修复的回归用例：DEL 用生效码、单平台撤回两段式、父链缺失报错、生效码查重。
 *
 * <p><b>为什么这四条要放一个类</b>：它们共用同一个前提——「上级看到的编码 = publish_code ?: 本地码」。
 * 任何一条退回按本地码（或按列值）判断，其余三条的断言都会跟着失效，放在一起改的时候一眼能看出影响面。
 *
 * @author luna
 */
@RecordApplicationEvents
@DisplayName("级联：平台编码与撤回链路")
class CascadePlatformCodeTest extends BaseTest {

    @Autowired
    private CascadeOrgManager                    cascadeOrgManager;

    @Autowired
    private CascadePublishManager                cascadePublishManager;

    @Autowired
    private StubCascadeCatalogRevokeService       revokeService;

    @Autowired
    private ApplicationEvents                     applicationEvents;

    /** 每个用例独占一套平台编码，避免与库里既有发布行互串 */
    private final String                          platformA = UniqueKeyFactory.deviceId();
    private final String                          platformB = UniqueKeyFactory.deviceId();

    @AfterEach
    void resetStub() {
        revokeService.reset();
    }

    private Long createOrg(Long parentId, String name, List<String> platformIds) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setParentId(parentId);
        dto.setOrgName(name);
        dto.setPlatformIds(platformIds);
        return cascadeOrgManager.add(dto);
    }

    private String overrideCode(Long id, String platformId, String code) {
        String orgCode = cascadeOrgManager.getById(id).getOrgCode();
        CascadePublishDTO publish = new CascadePublishDTO();
        publish.setPlatformId(platformId);
        publish.setObjectType(PublishObjectType.ORG.name());
        publish.setObjectKey(orgCode);
        publish.setPublishCode(code);
        cascadePublishManager.publish(publish);
        return orgCode;
    }

    // ---------------------------------------------------------------- D2：DEL 用生效码

    @Test
    @DisplayName("停用组织：DEL 节点用该平台的编码覆盖，不是本地码")
    void should_use_publish_code_in_del_nodes() {
        Long id = createOrg(null, "园区", List.of(platformA));
        String localCode = cascadeOrgManager.getById(id).getOrgCode();
        String platformCode = UniqueKeyFactory.deviceId();
        overrideCode(id, platformA, platformCode);

        assertTrue(cascadeOrgManager.disable(id));

        List<String> delCodes = applicationEvents.stream(LocalCascadeCatalogChangeEvent.class)
            .filter(e -> "DEL".equals(e.getEvent()) && e.getNodes() != null)
            .flatMap(e -> e.getNodes().stream())
            .map(LocalCascadeCatalogChangeEvent.Node::cascadeId)
            .collect(Collectors.toList());
        assertTrue(delCodes.contains(platformCode),
            "上级目录里存的是 publish_code，发本地码删不到节点: " + delCodes);
        assertFalse(delCodes.contains(localCode),
            "同一个节点不能既发本地码又发平台码: " + delCodes);
    }

    // ------------------------------------------------------------ D3：单平台撤回两段式

    @Test
    @DisplayName("单平台撤回：先等上级确认，再只停该平台的发布行")
    void should_revoke_single_platform_after_ack() {
        Long id = createOrg(null, "园区", List.of(platformA, platformB));
        String platformCode = UniqueKeyFactory.deviceId();
        overrideCode(id, platformA, platformCode);

        assertTrue(cascadeOrgManager.revokeFromPlatform(id, platformA));

        assertEquals(1, revokeService.invocationCount(), "撤回必须走上报撤销通道，不能只翻开关");
        StubCascadeCatalogRevokeService.Invocation invocation = revokeService.getInvocations().get(0);
        assertEquals(platformA, invocation.platformId(), "只应通知被撤回的那一路平台");
        assertEquals(List.of(platformCode),
            invocation.nodes().stream().map(LocalCascadeCatalogChangeEvent.Node::cascadeId).toList());

        CascadePublishDTO revoked = cascadePublishManager.get(platformA, PublishObjectType.ORG.name(),
            cascadeOrgManager.getById(id).getOrgCode());
        assertEquals(0, revoked.getEnabled(), "撤回后该平台的发布行必须停用，否则快照会把它推回去");

        CascadePublishDTO untouched = cascadePublishManager.get(platformB, PublishObjectType.ORG.name(),
            cascadeOrgManager.getById(id).getOrgCode());
        assertEquals(1, untouched.getEnabled(), "其它平台不受影响");
    }

    @Test
    @DisplayName("单平台撤回：上级拒绝时本地一条不改")
    void should_keep_local_when_revoke_refused() {
        Long id = createOrg(null, "园区", List.of(platformA));
        revokeService.refuse(500);

        assertThrows(IllegalArgumentException.class, () -> cascadeOrgManager.revokeFromPlatform(id, platformA));

        CascadePublishDTO publish = cascadePublishManager.get(platformA, PublishObjectType.ORG.name(),
            cascadeOrgManager.getById(id).getOrgCode());
        assertEquals(1, publish.getEnabled(), "上级没确认，发布行必须保持启用，便于重试");
    }

    @Test
    @DisplayName("单平台撤回：从未发布到该平台时直接拒绝")
    void should_reject_revoke_of_unpublished_platform() {
        Long id = createOrg(null, "园区", List.of(platformA));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> cascadeOrgManager.revokeFromPlatform(id, platformB));
        assertTrue(ex.getMessage().contains("未发布"), ex.getMessage());
    }

    // ------------------------------------------------------------ D4：父链缺失明确报错

    @Test
    @DisplayName("配置发布：祖先未推送到该平台时报出具体组织")
    void should_reject_publish_when_ancestor_not_published() {
        Long parentId = createOrg(null, "集团", List.of());
        Long childId = createOrg(parentId, "分公司", List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> cascadeOrgManager.assertAncestorsPublished(childId, List.of(platformA)));
        assertTrue(ex.getMessage().contains("集团"), "报错要指明缺的是哪一级: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("尚未推送"), ex.getMessage());
    }

    @Test
    @DisplayName("配置发布：祖先已发布时放行；空平台集合不校验")
    void should_pass_when_ancestor_published() {
        Long parentId = createOrg(null, "集团", List.of(platformA));
        Long childId = createOrg(parentId, "分公司", List.of());

        assertDoesNotThrow(() -> cascadeOrgManager.assertAncestorsPublished(childId, List.of(platformA)));
        assertDoesNotThrow(() -> cascadeOrgManager.assertAncestorsPublished(childId, List.of()));
    }

    // ---------------------------------------------------------------- D5：生效码查重

    @Test
    @DisplayName("编码查重：占用别人的本地码同样算冲突（跨对象类型）")
    void should_detect_conflict_with_local_code_of_other_object() {
        Long id = createOrg(null, "园区", List.of(platformA));
        String orgCode = cascadeOrgManager.getById(id).getOrgCode();

        CascadePublishDTO channelRow = new CascadePublishDTO();
        channelRow.setPlatformId(platformA);
        channelRow.setObjectType(PublishObjectType.CHANNEL.name());
        channelRow.setObjectKey(UniqueKeyFactory.deviceId() + ":" + UniqueKeyFactory.deviceId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            channelRow.setPublishCode(orgCode);
            cascadePublishManager.publish(channelRow);
        });
        assertTrue(ex.getMessage().contains("已被占用"), ex.getMessage());
    }
}
