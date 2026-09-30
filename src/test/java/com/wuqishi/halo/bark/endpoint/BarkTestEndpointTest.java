package com.wuqishi.halo.bark.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.support.FakeSecretAccessor;
import com.wuqishi.halo.bark.support.MockBarkServer;
import com.wuqishi.halo.bark.support.PushSettingsBuilder;
import com.wuqishi.halo.bark.transport.BarkHttpClient;
import com.wuqishi.halo.bark.transport.BarkPayloadFactory;
import com.wuqishi.halo.bark.transport.BarkRequestSender;
import com.wuqishi.halo.bark.transport.BarkServerProbe;
import com.wuqishi.halo.bark.transport.BarkTransport;
import com.wuqishi.halo.bark.transport.PayloadEncryptor;
import com.wuqishi.halo.bark.transport.SiteBrandSupplier;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@link BarkTestEndpoint} 适配层测试：验证 RFC 7807 响应映射与状态码。
 *
 * <p>阻塞的两级校验在 {@link DeviceTesterTest} 已覆盖；此处只验证「逻辑结果 → HTTP 响应」的薄适配，
 * 包括成功返回 200、失败返回对应状态码与 {@link TestProblem} 结构。
 */
class BarkTestEndpointTest {

    private MockBarkServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void successReturnsOk() {
        server = new MockBarkServer();
        server.respond(req -> req.path().equals("/info")
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(200, "{\"code\":200}"));
        var client = webClient(server, PushSettingsBuilder.push().build());

        client.post().uri("/bark/test")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(server, "iPhone", "k"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unreachableReturnsBadRequestWithProblem() {
        server = new MockBarkServer();
        server.respondStatus(404);
        var client = webClient(server, PushSettingsBuilder.push().build());

        client.post().uri("/bark/test")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(server, "iPhone", "k"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(TestProblem.class)
                .consumeWith(result -> {
                    var problem = result.getResponseBody();
                    assertNotNull(problem);
                    assertEquals(400, problem.status());
                    assertEquals("服务地址不可达或不是 Bark 服务", problem.title());
                });
    }

    @Test
    void emptyBodyReturnsDeterministicBadRequest() {
        var client = webClient(null, PushSettingsBuilder.push().build());

        client.post().uri("/bark/test")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(TestProblem.class)
                .consumeWith(result -> {
                    var problem = result.getResponseBody();
                    assertNotNull(problem);
                    assertEquals(400, problem.status());
                    assertEquals("请求体无效", problem.title());
                });
    }

    @Test
    void malformedJsonReturnsDeterministicBadRequest() {
        var client = webClient(null, PushSettingsBuilder.push().build());

        client.post().uri("/bark/test")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not-json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(TestProblem.class)
                .consumeWith(result -> {
                    var problem = result.getResponseBody();
                    assertNotNull(problem);
                    assertEquals(400, problem.status());
                });
    }

    private WebTestClient webClient(MockBarkServer server, PushSettings settings) {
        var endpoint = new BarkTestEndpoint(testerFor(server, settings));
        return WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
    }

    private String body(MockBarkServer server, String name, String key) {
        return "{\"name\":\"" + name + "\",\"key\":\"" + key + "\",\"serverUrl\":\""
                + server.url() + "\",\"enabled\":true}";
    }

    private DeviceTester testerFor(MockBarkServer server, PushSettings settings) {
        var http = new BarkHttpClient();
        var probe = new BarkServerProbe(http);
        var factory = new BarkPayloadFactory(new PayloadEncryptor());
        var sender = new BarkRequestSender(http, millis -> {});
        SiteBrandSupplier noBrand = new SiteBrandSupplier() {
            @Override
            public String title() {
                return null;
            }

            @Override
            public String logo() {
                return null;
            }
        };
        var transport = new BarkTransport(probe, factory, noBrand, sender);
        Supplier<PushSettings> supplier = () -> settings;
        return new DeviceTester(probe, transport, supplier, List::of, FakeSecretAccessor.identity());
    }
}
