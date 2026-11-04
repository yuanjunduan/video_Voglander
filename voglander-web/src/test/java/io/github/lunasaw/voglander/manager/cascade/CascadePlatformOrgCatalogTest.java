package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.lunasaw.voglander.BaseTest;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeBlockedOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgCatalog;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeOrgManager;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;

/**
 * 平台组织视角（目录 + 阻断）的行为约束。
 *
 * <p>这两份清单回答的是同一个问题的两面：「这个上级收到了哪些组织」与「我勾了推送为什么上级没有」。
 * 它们必须出自同一次计算——分开算会出现既不在目录、也不在阻断清单里的节点，
 * 用户对着页面只会以为系统丢数据。
 *
 * <p>测试平台编码用独立前缀，保证发布行不与其他用例的组织交叉；
 * 目录里只会出现本用例发布过的组织，其余全库组织因没有发布行被拦在目录外。
 */
@DisplayName("平台组织视角")
class CascadePlatformOrgCatalogTest extends BaseTest {

    /** 测试平台编码序号：固定 12 位前缀，避免与真实平台（3402/3700 开头）或别的用例撞上 */
    private static final AtomicInteger PLATFORM_SEQ = new AtomicInteger();

    @Autowired
    private CascadeOrgManager        cascadeOrgManager;

    @Autowired
    private CascadePublishManager    cascadePublishManager;

    private String newPlatformId() {
        return "3499000000" + String.format("%010d", PLATFORM_SEQ.incrementAndGet());
    }

    /** 建组织并回读：回读是为了拿到库里真实的编码（编码由后端生成） */
    private CascadeOrgDTO newOrg(Long parentId, String name, Integer enabled) {
        CascadeOrgDTO dto = new CascadeOrgDTO();
        dto.setOrgName(name + "-" + System.nanoTime());
        dto.setParentId(parentId);
        dto.setEnabled(enabled);
        return cascadeOrgManager.getById(cascadeOrgManager.add(dto));
    }

    private void publishOrg(String platformId, String orgCode, String publishCode) {
        publishOrg(platformId, orgCode, publishCode, null, 1);
    }

    private void publishOrg(String platformId, String orgCode, String publishCode, String revokePolicy,
        Integer enabled) {
        CascadePublishDTO dto = new CascadePublishDTO();
        dto.setPlatformId(platformId);
        dto.setObjectType("ORG");
        dto.setObjectKey(orgCode);
        dto.setPublishCode(publishCode);
        dto.setRevokePolicy(revokePolicy);
        dto.setEnabled(enabled);
        cascadePublishManager.publish(dto);
    }

    private static CascadePlatformOrgDTO findCatalog(CascadePlatformOrgCatalog catalog, String localCode) {
        return catalog.catalogOrgs().stream()
            .filter(o -> localCode.equals(o.getLocalCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("目录里没有组织: " + localCode));
    }

    private static CascadeBlockedOrgDTO findBlocked(CascadePlatformOrgCatalog catalog, String localCode) {
        return catalog.blockedOrgs().stream()
            .filter(o -> localCode.equals(o.getLocalCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("阻断清单里没有组织: " + localCode));
    }

    @Test
    @DisplayName("目录内组织带平台编码覆盖，子组织的父编码取该平台眼里的父节点")
    void catalog_should_expose_platform_code_and_parent_code() {
        String platformId = newPlatformId();
        CascadeOrgDTO parent = newOrg(null, "目录父", 1);
        CascadeOrgDTO child = newOrg(parent.getId(), "目录子", 1);

        /* 父组织在该平台用覆盖码：子组织没配覆盖时回落本地码，父编码必须映射成覆盖值，
           否则上级收到的 ParentID 指向一个它不认识的节点 */
        String parentPlatformCode = "3700020000200000" + String.format("%04d", PLATFORM_SEQ.incrementAndGet());
        publishOrg(platformId, parent.getOrgCode(), parentPlatformCode);
        publishOrg(platformId, child.getOrgCode(), null);

        CascadePlatformOrgCatalog catalog = cascadeOrgManager.platformOrgCatalog(platformId);

        CascadePlatformOrgDTO parentItem = findCatalog(catalog, parent.getOrgCode());
        CascadePlatformOrgDTO childItem = findCatalog(catalog, child.getOrgCode());
        assertEquals(parentPlatformCode, parentItem.getPlatformCode(), "发布行有覆盖时用覆盖值");
        assertNull(parentItem.getParentPlatformCode(), "根组织的父编码由发送方回落域编码");
        assertEquals(child.getOrgCode(), childItem.getPlatformCode(), "没有覆盖时回落本地码");
        assertEquals(parentPlatformCode, childItem.getParentPlatformCode(),
            "父编码必须是该平台眼里的父节点，不能用本地码");
        assertTrue(catalog.blockedOrgs().isEmpty(), "两条链都通，不应有阻断");
    }

    @Test
    @DisplayName("父组织未发布：子组织进不了目录，并作为「祖先未发布」阻断被列出")
    void blocked_should_report_ancestor_not_published() {
        String platformId = newPlatformId();
        CascadeOrgDTO parent = newOrg(null, "孤父", 1);
        CascadeOrgDTO child = newOrg(parent.getId(), "孤子", 1);

        /* 只勾子组织：这是页面上最容易犯的错，也是 configurePublish 会拦、存量数据里仍会出现的情况 */
        publishOrg(platformId, child.getOrgCode(), null);

        CascadePlatformOrgCatalog catalog = cascadeOrgManager.platformOrgCatalog(platformId);

        assertTrue(catalog.catalogOrgs().stream()
            .noneMatch(o -> child.getOrgCode().equals(o.getLocalCode())),
            "祖先没发布，子组织在上级立不起来");
        CascadeBlockedOrgDTO blocked = findBlocked(catalog, child.getOrgCode());
        assertEquals(CascadeBlockedOrgDTO.REASON_ANCESTOR_NOT_PUBLISHED, blocked.getReason());
        assertEquals(parent.getOrgCode(), blocked.getBlockedByCode(), "阻断节点要指到那一级祖先");
        assertEquals(1, catalog.blockedOrgs().size(), "没勾的父组织不算阻断");
    }

    @Test
    @DisplayName("组织本地停用：发布行还勾着，也只报本地停用，且不进目录")
    void blocked_should_report_local_disabled_when_self_off() {
        String platformId = newPlatformId();
        CascadeOrgDTO off = newOrg(null, "停用组织", 0);

        publishOrg(platformId, off.getOrgCode(), null);

        CascadePlatformOrgCatalog catalog = cascadeOrgManager.platformOrgCatalog(platformId);

        assertTrue(catalog.catalogOrgs().isEmpty(), "本地停用的组织不会出现在任何平台目录里");
        CascadeBlockedOrgDTO blocked = findBlocked(catalog, off.getOrgCode());
        assertEquals(CascadeBlockedOrgDTO.REASON_LOCAL_DISABLED, blocked.getReason());
        assertEquals(off.getOrgCode(), blocked.getBlockedByCode(), "阻断节点就是它自己");

        /* 协议层只要目录：同一个平台换 platformOrgView 不应把阻断清单带出来 */
        assertTrue(cascadeOrgManager.platformOrgView(platformId).catalogOrgs().isEmpty());
    }

    @Test
    @DisplayName("阻断节点策略为 REROUTE：组织上不去，但通道改挂域节点，阻断明细要看得到")
    void blocked_should_flag_rerouted_when_policy_reroute() {
        String platformId = newPlatformId();
        CascadeOrgDTO parent = newOrg(null, "改挂父", 1);
        CascadeOrgDTO child = newOrg(parent.getId(), "改挂子", 1);

        /* 父组织配了 REROUTE 且发布行停用：父进不了目录，子的通道按策略改挂域节点继续上报 */
        publishOrg(platformId, parent.getOrgCode(), null, "REROUTE", 0);
        publishOrg(platformId, child.getOrgCode(), null);

        CascadePlatformOrgCatalog catalog = cascadeOrgManager.platformOrgCatalog(platformId);

        CascadeBlockedOrgDTO blocked = findBlocked(catalog, child.getOrgCode());
        assertEquals(Boolean.TRUE, blocked.getRerouted(), "阻断节点策略为 REROUTE，通道仍在上级");
        assertTrue(catalog.reroutedLocalCodes().contains(child.getOrgCode()));
    }

    @Test
    @DisplayName("平台没有任何组织发布：返回空视角，而不是把全库组织当成目录")
    void empty_when_platform_has_no_publish() {
        CascadePlatformOrgCatalog catalog = cascadeOrgManager.platformOrgCatalog(newPlatformId());

        assertTrue(catalog.catalogOrgs().isEmpty());
        assertTrue(catalog.blockedOrgs().isEmpty());
        assertTrue(catalog.reroutedLocalCodes().isEmpty());
    }
}
