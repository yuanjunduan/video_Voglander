package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.client.service.cascade.CascadeCatalogRevokeService;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link CascadeCatalogRevokeService} 的 GB28181 实现：把 manager 的"撤销并要结论"
 * 转成一次 {@code Event=DEL} 的同步等待。
 *
 * <p>单独一个适配器而不是让 {@link CascadeNotifyPublisher} 直接实现该接口，
 * 是为了让出站契约与报文拼装解耦：契约面向业务（"取消上报成功了没"），
 * publisher 面向协议（NOTIFY、Call-ID、Event 头），换协议时只动这一层。
 *
 * <p>与 publisher 一样受 {@code sip.client.enabled} 约束——没有出站能力时这个 bean
 * 就不存在，manager 侧用 {@code ObjectProvider} 兜底（详见 {@code CascadeAssetCleanup}）。
 *
 * @author luna
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sip.client.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CascadeCatalogRevokeGateway implements CascadeCatalogRevokeService {

    private final CascadeNotifyPublisher notifyPublisher;

    @Override
    public RevokeResult revoke(String platformId, List<LocalCascadeCatalogChangeEvent.Node> nodes) {
        return notifyPublisher.revokeCatalogNodes(platformId, nodes);
    }
}
