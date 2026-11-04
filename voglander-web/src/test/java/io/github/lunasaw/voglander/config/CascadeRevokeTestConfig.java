package io.github.lunasaw.voglander.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import lombok.extern.slf4j.Slf4j;

/**
 * 测试专用：把级联撤销出站通道换成 {@link StubCascadeCatalogRevokeService}。
 *
 * <p>删除设备 / 通道 / 组织现在都会先向上级发 {@code Event=DEL} 并同步等待 2xx，
 * 确认成功才允许删本地。测试环境没有真实上级应答，必须桩掉，否则每个删除用例都要等满
 * {@code gateway.cascade.catalog.delete-confirm-timeout-sec}（默认 10s）再失败。
 *
 * <p>{@code @Primary} 足以压过真实的 {@code CascadeCatalogRevokeGateway}：
 * {@code CascadeAssetCleanup} 通过 {@code ObjectProvider#getIfAvailable()} 取 bean，
 * 多候选时按 primary 选择，与直接按类型注入的行为一致。
 *
 * <p>返回类型写成具体的桩类而不是接口，测试才能直接 {@code @Autowired} 到桩并调用它的
 * {@code refuse()/noResponse()/getInvocations()} 等测试专用方法；桩本身 {@code implements}
 * 出站契约，接口签名变更时编译就会失败，所以无需再注册一个接口类型的重复 bean——
 * 多候选只会让 {@code getIfAvailable()} 的取舍变得难以阅读。
 *
 * @author luna
 */
@Slf4j
@TestConfiguration
public class CascadeRevokeTestConfig {

    @Bean
    @Primary
    public StubCascadeCatalogRevokeService cascadeCatalogRevokeService() {
        log.info("级联撤销通道使用测试桩：默认「上级已确认」，用例可切换为拒绝 / 无响应");
        return new StubCascadeCatalogRevokeService();
    }
}
