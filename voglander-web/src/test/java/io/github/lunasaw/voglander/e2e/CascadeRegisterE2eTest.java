package io.github.lunasaw.voglander.e2e;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.github.lunasaw.gbproxy.client.eventbus.event.ClientRegisterFailureEvent;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientRegisterSuccessEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeClientDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeClientManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.repository.entity.CascadePlatformDO;
import io.github.lunasaw.voglander.repository.mapper.CascadePlatformMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * TC-CAS-REG-02/03：级联注册事件端到端测试。
 * <p>
 * 使用 ApplicationEventPublisher 发布 Spring 事件，由 CascadeClientRegisterListener 处理。
 * 注意：框架注册响应事件的 userId 来自 REGISTER 响应 To 头，优先按 platformId 匹配。
 */
@Slf4j
class CascadeRegisterE2eTest extends BaseE2eTest {

    private static final String PLATFORM_ID = "34020000002000000099";
    private static final String LOCAL_CLIENT_ID = "34020000001320000099";

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private CascadePlatformMapper     platformMapper;
    @Autowired private CascadePlatformManager    platformManager;
    @Autowired private CascadeClientManager      clientManager;

    @BeforeEach
    void setupPlatform() {
        CascadePlatformDTO dto = new CascadePlatformDTO();
        dto.setPlatformId(PLATFORM_ID);
        dto.setClientRefId(ensureLocalClient());
        dto.setPlatformIp("127.0.0.1");
        dto.setPlatformPort(5060);
        dto.setPlatformDomain(PLATFORM_ID);
        dto.setRegisterStatus(0);
        platformManager.add(dto);
    }

    /** 本端身份现在只在客户端表里登记：先复用既有行，没有才新建（本类不在事务里，重跑不能报唯一键冲突） */
    private Long ensureLocalClient() {
        CascadeClientDTO existed = clientManager.getByClientId(LOCAL_CLIENT_ID);
        if (existed != null) {
            return existed.getId();
        }
        CascadeClientDTO client = new CascadeClientDTO();
        client.setClientId(LOCAL_CLIENT_ID);
        client.setClientName("E2E 本端");
        client.setClientIp("127.0.0.1");
        return clientManager.add(client);
    }

    @AfterEach
    void cleanup() {
        platformMapper.delete(Wrappers.<CascadePlatformDO>lambdaQuery()
            .eq(CascadePlatformDO::getPlatformId, PLATFORM_ID));
        CascadeClientDTO client = clientManager.getByClientId(LOCAL_CLIENT_ID);
        if (client != null) {
            clientManager.delete(client.getId());
        }
    }

    @Test
    @DisplayName("TC-CAS-REG-02 注册成功事件 → register_status=1（ONLINE）")
    void registerSuccess_setsOnlineStatus() {
        eventPublisher.publishEvent(new ClientRegisterSuccessEvent(this, PLATFORM_ID));

        await().atMost(3, SECONDS).untilAsserted(() -> {
            CascadePlatformDO p = platformMapper.selectOne(Wrappers.<CascadePlatformDO>lambdaQuery()
                .eq(CascadePlatformDO::getPlatformId, PLATFORM_ID));
            assertThat(p.getRegisterStatus()).isEqualTo(1);
        });
        log.info("✅ TC-CAS-REG-02 通过");
    }

    @Test
    @DisplayName("TC-CAS-REG-03 注册失败事件 → register_status=3（FAILED）")
    void registerFailure_setsFailedStatus() {
        eventPublisher.publishEvent(new ClientRegisterFailureEvent(this, PLATFORM_ID, 403));

        await().atMost(3, SECONDS).untilAsserted(() -> {
            CascadePlatformDO p = platformMapper.selectOne(Wrappers.<CascadePlatformDO>lambdaQuery()
                .eq(CascadePlatformDO::getPlatformId, PLATFORM_ID));
            assertThat(p.getRegisterStatus()).isEqualTo(3);
        });
        log.info("✅ TC-CAS-REG-03 通过");
    }
}
