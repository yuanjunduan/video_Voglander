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
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.config.StubCascadeCatalogRevokeService;
import io.github.lunasaw.voglander.config.StubCascadeCatalogRevokeService.Invocation;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeAssetCleanup;
import io.github.lunasaw.voglander.manager.manager.CascadePublishManager;
import io.github.lunasaw.voglander.manager.service.CascadePublishService;
import io.github.lunasaw.voglander.repository.entity.CascadePublishDO;
import io.github.lunasaw.voglander.support.UniqueKeyFactory;

/**
 * {@link CascadeAssetCleanup} 集成测试：删本地资产时，发布层残留与上级目录通知都要跟着消失。
 *
 * <p><b>两类删除在本用例里分别验证</b>：
 * <ul>
 *   <li>{@code revokeXxx}（用户主动删）：先通过同步出站通道向上级取消上报并拿到确认，
 *       确认之后才清发布行；未确认则抛异常且一行不少。因为 DEL 是同步直接发的，
 *       这条路<strong>不该</strong>再出现 {@code LocalCascadeCatalogChangeEvent}，否则上级会收到两份删除。</li>
 *   <li>{@code purgeChannel}（设备自己上报 Catalog {@code DEL}，本端跟随清理）：不阻断，
 *       靠事务提交后的事件链路异步通知。</li>
 * </ul>
 *
 * <p><b>为什么只造发布行、不造本地设备与通道</b>：这个组件只认 {@code tb_cascade_publish}，
 * 本地行有没有都不影响它的判断；不建本地资产可以让用例聚焦在「删了哪几行、什么时候通知的上级」。
 *
 * <p><b>为什么用 {@code NOT_SUPPORTED} 而不是基类的事务</b>：要断言的正是
 * {@code afterCommit} 之后才发出的 {@code DEL}；套在外层事务里回调永远不执行，
 * 断言只会得到「事件没发」的假阴性。真提交落库的数据按平台编码在 {@link #cleanup()} 里物理清掉。
 *
 * <p><b>上级确认靠 {@link StubCascadeCatalogRevokeService} 桩</b>：{@code BaseTest} 启用了出站能力，
 * 真实实现会真发 UDP 并等满超时。桩默认「已确认」，用例可切成拒绝 / 无响应来验证严格阻断。
 *
 * @author luna
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RecordApplicationEvents
@DisplayName("CascadeAssetCleanup 集成测试")
class CascadeAssetCleanupTest extends BaseTest {

    @Autowired
    private CascadeAssetCleanup cascadeAssetCleanup;

    @Autowired
    private CascadePublishManager cascadePublishManager;

    @Autowired
    private CascadePublishService cascadePublishService;

    @Autowired
    private ApplicationEvents applicationEvents;

    /** 上级撤销通道的测试桩；结论与调用序列都在这里，供「先撤销后删本地」的次序断言使用 */
    @Autowired
    private StubCascadeCatalogRevokeService revokeStub;

    /** 本用例的设备编码：通道的对象键是 {@code deviceId:channelId}，整类共用一台设备即可 */
    private final String       deviceId  = UniqueKeyFactory.deviceId();

    /** 本用例占用的平台编码，清理按它批量删，避免残留污染其它用例的目录视图 */
    private final List<String> platforms = new ArrayList<>();

    /** 每个用例独占一个平台编码：发布行按 {@code (platform, type, objectKey)} 唯一，撞了会互相覆盖 */
    private String platform() {
        String platformId = UniqueKeyFactory.deviceId();
        platforms.add(platformId);
        return platformId;
    }

    private void publishChannel(String platformId, String channelId, String publishCode, String parentKey,
        int enabled) {
        publish(PublishObjectType.CHANNEL.name(), PublishKeys.channel(deviceId, channelId),
            publishCode, parentKey, enabled, platformId);
    }

    private void publishDevice(String platformId, String parentKey, int enabled) {
        publish(PublishObjectType.DEVICE.name(), deviceId, null, parentKey, enabled, platformId);
    }

    private void publish(String objectType, String objectKey, String publishCode, String parentKey, int enabled,
        String platformId) {
        CascadePublishDTO dto = new CascadePublishDTO();
        dto.setPlatformId(platformId);
        dto.setObjectType(objectType);
        dto.setObjectKey(objectKey);
        dto.setPublishCode(publishCode);
        dto.setPublishName(publishCode);
        dto.setParentKey(parentKey);
        dto.setEnabled(enabled);
        cascadePublishManager.publish(dto);
    }

    private List<CascadePublishDO> rows(String platformId, String objectType, String objectKey) {
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadePublishDO::getPlatformId, platformId)
            .eq(CascadePublishDO::getObjectType, objectType)
            .eq(CascadePublishDO::getObjectKey, objectKey);
        return cascadePublishService.list(qw);
    }

    private List<LocalCascadeCatalogChangeEvent> delEvents() {
        return applicationEvents.stream(LocalCascadeCatalogChangeEvent.class)
            .filter(e -> "DEL".equals(e.getEvent()))
            .collect(Collectors.toList());
    }

    @AfterEach
    void cleanup() {
        /* 桩是全容器单例，结论一旦被改成拒绝会串到其它测试类，必须恢复 */
        revokeStub.reset();
        if (platforms.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<CascadePublishDO> qw = new LambdaQueryWrapper<>();
        qw.in(CascadePublishDO::getPlatformId, platforms);
        cascadePublishService.remove(qw);
    }

    @Test
    @DisplayName("删单条通道：只清自己的发布行，DEL 的节点编码取编码覆盖、父编码取父节点覆盖")
    void purge_channel_should_only_touch_its_own_rows() {
        String platformId = platform();
        String channelId = UniqueKeyFactory.channelId();
        String otherChannelId = UniqueKeyFactory.channelId();
        String cascadeCode = "3402" + UniqueKeyFactory.channelId();
        publishChannel(platformId, channelId, cascadeCode, "org-parent", 1);
        publishChannel(platformId, otherChannelId, "code-other", "org-parent", 1);

        assertEquals(1, cascadeAssetCleanup.purgeChannel(deviceId, channelId));

        assertTrue(rows(platformId, PublishObjectType.CHANNEL.name(),
            PublishKeys.channel(deviceId, channelId)).isEmpty(), "本通道的发布行必须删掉");
        assertEquals(1, rows(platformId, PublishObjectType.CHANNEL.name(),
            PublishKeys.channel(deviceId, otherChannelId)).size(), "同一台设备的其它通道不能被误删");

        List<LocalCascadeCatalogChangeEvent> events = delEvents();
        assertEquals(1, events.size(), "一个平台只发一个 DEL 事件");
        LocalCascadeCatalogChangeEvent del = events.get(0);
        assertEquals(platformId, del.getPlatformId());
        assertEquals(1, del.getNodes().size());
        assertEquals(cascadeCode, del.getNodes().get(0).cascadeId(), "上级靠上报编码认节点，不能退回本地编码");
        assertEquals("org-parent", del.getNodes().get(0).parentCode(), "父编码要带上，上级才能定位树");
    }

    @Test
    @DisplayName("删停用通道：发布行照样清，但不发 DEL（上级那里本来就没有这个节点）")
    void purge_disabled_channel_should_skip_del() {
        String platformId = platform();
        String channelId = UniqueKeyFactory.channelId();
        publishChannel(platformId, channelId, null, null, 0);

        assertEquals(1, cascadeAssetCleanup.purgeChannel(deviceId, channelId));

        assertTrue(rows(platformId, PublishObjectType.CHANNEL.name(),
            PublishKeys.channel(deviceId, channelId)).isEmpty(), "停用行也是孤儿，必须删掉");
        assertTrue(delEvents().isEmpty(), "停用行没推上去过，发 DEL 只会让上级报「节点不存在」");
    }

    @Test
    @DisplayName("主动撤销一批通道：上级确认后才清发布行，前缀必须带分隔符")
    void revoke_channels_should_match_by_prefix() {
        String platformId = platform();
        String channelA = UniqueKeyFactory.channelId();
        String channelB = UniqueKeyFactory.channelId();
        publishChannel(platformId, channelA, null, null, 1);
        publishChannel(platformId, channelB, null, null, 1);
        publishDevice(platformId, null, 1);
        /* 另一台设备：它的通道键以 deviceId 为前缀但不带分隔符，不能被前缀查询顺手捞走 */
        CascadePublishDTO otherDeviceChannel = new CascadePublishDTO();
        otherDeviceChannel.setPlatformId(platformId);
        otherDeviceChannel.setObjectType(PublishObjectType.CHANNEL.name());
        otherDeviceChannel.setObjectKey(deviceId + "X:" + channelA);
        otherDeviceChannel.setEnabled(1);
        cascadePublishManager.publish(otherDeviceChannel);

        assertEquals(2, cascadeAssetCleanup.revokeChannels(deviceId, List.of(channelA, channelB)));

        assertTrue(rows(platformId, PublishObjectType.CHANNEL.name(),
            PublishKeys.channel(deviceId, channelA)).isEmpty());
        assertTrue(rows(platformId, PublishObjectType.CHANNEL.name(),
            PublishKeys.channel(deviceId, channelB)).isEmpty());
        assertEquals(1, rows(platformId, PublishObjectType.DEVICE.name(), deviceId).size(),
            "只清通道时设备行必须保留");
        assertEquals(1, rows(platformId, PublishObjectType.CHANNEL.name(), deviceId + "X:" + channelA).size(),
            "前缀必须带上分隔符，否则会误伤编码相近的其它设备");

        List<Invocation> invocations = revokeStub.getInvocations();
        assertEquals(1, invocations.size(), "同一平台的节点合并成一次撤销，不发多条报文");
        assertEquals(platformId, invocations.get(0).platformId());
        assertEquals(2, invocations.get(0).nodeCount());
        assertTrue(delEvents().isEmpty(), "主动路径已经同步发过 DEL，不能再走事件链路发第二遍");
    }

    @Test
    @DisplayName("删设备：通道节点排在设备节点之前，上级确认前发布行一行不少")
    void revoke_device_should_confirm_before_purge_and_put_channels_first() {
        String platformId = platform();
        String channelId = UniqueKeyFactory.channelId();
        publishChannel(platformId, channelId, "code-ch", "code-org", 1);
        publishDevice(platformId, "code-org", 1);
        /* 第二个平台只有设备行：验证「一个平台一次撤销」而不是「所有平台混在一起」 */
        String otherPlatform = platform();
        publishDevice(otherPlatform, null, 1);

        /* 撤销时刻两个平台的设备行都还在：先删本地再通知上级会留下「本地已无、上级仍有」的悬挂节点 */
        revokeStub.setOnRevoke(invocation -> assertFalse(
            rows(invocation.platformId(), PublishObjectType.DEVICE.name(), deviceId).isEmpty(),
            "上级确认前发布行不能先删: platformId=" + invocation.platformId()));

        assertEquals(3, cascadeAssetCleanup.revokeDevice(deviceId));

        assertTrue(rows(platformId, PublishObjectType.CHANNEL.name(),
            PublishKeys.channel(deviceId, channelId)).isEmpty());
        assertTrue(rows(platformId, PublishObjectType.DEVICE.name(), deviceId).isEmpty());
        assertTrue(rows(otherPlatform, PublishObjectType.DEVICE.name(), deviceId).isEmpty());

        List<Invocation> invocations = revokeStub.getInvocations();
        assertEquals(2, invocations.size(), "两个平台各自一次撤销");

        Invocation main = invocations.stream()
            .filter(i -> platformId.equals(i.platformId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("缺少主平台的撤销调用"));
        assertEquals(2, main.nodes().size());
        assertEquals("code-ch", main.nodes().get(0).cascadeId(), "通道节点必须排在设备节点之前");
        assertEquals(deviceId, main.nodes().get(1).cascadeId(), "设备没配编码覆盖时取设备编码");
        assertEquals("code-org", main.nodes().get(0).parentCode());

        Invocation other = invocations.stream()
            .filter(i -> otherPlatform.equals(i.platformId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("缺少第二平台的撤销调用"));
        assertEquals(1, other.nodeCount());
        assertTrue(delEvents().isEmpty(), "主动路径不发事件");
    }

    @Test
    @DisplayName("上级拒绝撤销：抛异常且发布行原封不动，原因里要带平台编码与响应码")
    void revoke_refused_should_block_and_keep_publish_rows() {
        String platformId = platform();
        String channelId = UniqueKeyFactory.channelId();
        publishChannel(platformId, channelId, "code-refuse", null, 1);
        revokeStub.refuse(403);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> cascadeAssetCleanup.revokeChannel(deviceId, channelId));

        assertTrue(e.getMessage().contains("上级未确认取消上报"), e.getMessage());
        assertTrue(e.getMessage().contains(platformId), "要把没确认的平台报出来，否则用户不知道该找谁："
            + e.getMessage());
        assertTrue(e.getMessage().contains("403"), "响应码要带上：" + e.getMessage());
        assertEquals(1, rows(platformId, PublishObjectType.CHANNEL.name(),
            PublishKeys.channel(deviceId, channelId)).size(), "上级没确认就不能删发布行，重试还要靠它");
        assertTrue(delEvents().isEmpty());
    }

    @Test
    @DisplayName("上级无响应：同样阻断，本地一行不少")
    void revoke_timeout_should_block() {
        String platformId = platform();
        publishDevice(platformId, null, 1);
        revokeStub.noResponse();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> cascadeAssetCleanup.revokeDevice(deviceId));

        assertTrue(e.getMessage().contains("上级未响应撤销请求"), e.getMessage());
        assertEquals(1, rows(platformId, PublishObjectType.DEVICE.name(), deviceId).size());
    }

    @Test
    @DisplayName("没有发布关系时不做任何事：不发撤销、不发事件")
    void revoke_without_publish_rows_should_be_noop() {
        assertEquals(0, cascadeAssetCleanup.revokeChannel(deviceId, UniqueKeyFactory.channelId()));
        assertEquals(0, cascadeAssetCleanup.revokeChannels(deviceId, List.of(UniqueKeyFactory.channelId())));
        assertEquals(0, cascadeAssetCleanup.revokeDevice(deviceId));
        assertEquals(0, cascadeAssetCleanup.revokeOrg("org-" + UniqueKeyFactory.channelId()));
        assertEquals(0, revokeStub.invocationCount(), "没推上去过就不该去打扰上级");
        assertTrue(delEvents().isEmpty());
    }
}
