package com.wuqishi.halo.bark.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.config.SecretAccessor;
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
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DeviceTester} 两级校验逻辑的集成测试：用真实传输层打 {@link MockBarkServer}。
 *
 * <p>覆盖 PRD 5.6 的全部分支：校验拒绝、第一级不可达、第二级 Key 无效 / 5xx / 加密错误、以及成功。
 */
class DeviceTesterTest {

    private MockBarkServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void successWhenReachableAndKeyValid() {
        server = new MockBarkServer();
        server.respond(req -> req.path().equals("/info")
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(200, "{\"code\":200}"));
        var tester = testerFor(server, PushSettingsBuilder.push().build());

        var problem = tester.test(new DeviceTestRequest("iPhone", "realkey", server.url(), true));

        assertNull(problem, "可达且 Key 有效时应返回 null（成功）");
        assertEquals(2, server.count(), "应先探测 /info 再推送 /push");
        var push = server.requests().stream().filter(r -> "/push".equals(r.path())).findFirst().orElseThrow();
        assertTrue(push.body().contains("\"device_key\""), "单台设备应使用 device_key");
        assertFalse(push.body().contains("\"device_keys\""), "单台设备不应使用 device_keys");
        assertTrue(push.body().contains("Halo2Bark 测试通知"), "测试消息标题应发出");
        assertFalse(push.body().contains("isArchive"), "测试消息不归档");
    }

    @Test
    void level1FailsWhenInfoUnreachable() {
        server = new MockBarkServer();
        server.respondStatus(404); // /info 404 → 不可达
        var tester = testerFor(server, PushSettingsBuilder.push().build());

        var problem = tester.test(new DeviceTestRequest("iPhone", "k", server.url(), true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("服务地址不可达或不是 Bark 服务", problem.title());
        assertEquals(1, server.count(), "第一级失败不应发送 /push");
        assertTrue(server.requests().stream().noneMatch(r -> "/push".equals(r.path())));
    }

    @Test
    void level2FailsWhenKeyInvalid() {
        server = new MockBarkServer();
        server.respond(req -> req.path().equals("/info")
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(401, "{\"code\":401}"));
        var tester = testerFor(server, PushSettingsBuilder.push().build());

        var problem = tester.test(new DeviceTestRequest("iPhone", "badkey", server.url(), true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("设备 Key 无效", problem.title());
        assertEquals("Bark 服务端返回 4xx，设备 Key 可能错误，请从 Bark App 首页重新复制。", problem.detail());
    }

    @Test
    void level2FailsWhenServerError() {
        server = new MockBarkServer();
        server.respond(req -> req.path().equals("/info")
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(500, "{\"code\":500}"));
        var tester = testerFor(server, PushSettingsBuilder.push().build());

        var problem = tester.test(new DeviceTestRequest("iPhone", "k", server.url(), true));

        assertNotNull(problem);
        assertEquals(502, problem.status(), "5xx 应映射为 502");
        assertEquals("Bark 服务端暂时不可用", problem.title());
    }

    @Test
    void level2FailsWhenEncryptionMisconfigured() {
        server = new MockBarkServer();
        server.respond(req -> req.path().equals("/info")
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(200, "{\"code\":200}"));
        var settings = PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptKey("1234567890") // 10 位，AES128 需 16 位 → 加密失败
                .build();
        var tester = testerFor(server, settings);

        var problem = tester.test(new DeviceTestRequest("iPhone", "k", server.url(), true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("加密配置有误", problem.title());
        assertTrue(server.requests().stream().noneMatch(r -> "/push".equals(r.path())),
                "加密失败不应发出推送");
    }

    @Test
    void validationRejectsEmptyKeyWithoutRequest() {
        server = new MockBarkServer();
        server.respondStatus(200);
        var tester = testerFor(server, PushSettingsBuilder.push().build());

        var problem = tester.test(new DeviceTestRequest("iPhone", "", server.url(), true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("设备 Key 不能为空", problem.title());
        assertEquals(0, server.count(), "校验失败不应发起任何请求");
    }

    @Test
    void validationRejectsEmptyServerUrlWithoutRequest() {
        server = new MockBarkServer();
        server.respondStatus(200);
        var tester = testerFor(server, PushSettingsBuilder.push().build());

        var problem = tester.test(new DeviceTestRequest("iPhone", "k", "", true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("Bark 服务地址不能为空", problem.title());
        assertEquals(0, server.count());
    }

    @Test
    void validationRejectsNonHttpSchemeWithoutRequest() {
        server = new MockBarkServer();
        server.respondStatus(200);
        var tester = testerFor(server, PushSettingsBuilder.push().build());

        var problem = tester.test(new DeviceTestRequest("iPhone", "k", "ftp://bark.example/key", true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("Bark 服务地址不可用", problem.title());
        assertEquals(0, server.count(), "非 http(s) 地址不得被当作出网目标");
    }

    @Test
    void validationRejectsUrlWithQueryWithoutRequest() {
        server = new MockBarkServer();
        server.respondStatus(200);
        var tester = testerFor(server, PushSettingsBuilder.push().build());

        var problem = tester.test(new DeviceTestRequest("iPhone", "k", server.url() + "?token=x", true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("Bark 服务地址不可用", problem.title());
        assertEquals(0, server.count(), "带查询串的地址会让 /info 与 /push 拼错位置，不得放行");
    }

    @Test
    void configIssuesBlockTestWithoutAnyRequest() {
        server = new MockBarkServer();
        server.respondStatus(200);
        var issues = List.of("加密密钥必须是 16 个字节（AES128 要求，按 UTF-8 计算，当前 5 个字节）");
        var tester = testerFor(server, PushSettingsBuilder.push().build(), issues);

        var problem = tester.test(new DeviceTestRequest("iPhone", "k", server.url(), true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("推送配置校验未通过，已停止推送", problem.title());
        assertTrue(problem.detail().contains("加密密钥必须是 16 个字节"),
                "必须把具体哪一条配置有问题返回给用户");
        assertTrue(problem.detail().contains("请到插件设置页修正"), "必须说明如何修正");
        assertEquals(0, server.count(), "配置非法时不得发起任何出网请求");
    }

    @Test
    void validationRejectsUnresolvableKeyReferenceWithoutRequest() {
        server = new MockBarkServer();
        server.respondStatus(200);
        var tester = testerFor(server, PushSettingsBuilder.push().build(), List.of(),
                FakeSecretAccessor.of(Map.of("secret-known", "realkey")));

        var problem = tester.test(new DeviceTestRequest("iPhone", "secret-gone", server.url(), true));

        assertNotNull(problem);
        assertEquals(400, problem.status());
        assertEquals("设备密钥读不到", problem.title());
        assertTrue(problem.detail().contains("secret-gone"), "提示必须指明是哪一个引用读不到");
        assertEquals(0, server.count(), "引用读不到时不得把 Secret 名称当 Key 发出请求");
    }

    @Test
    void keyReferenceIsResolvedBeforeDelivery() {
        server = new MockBarkServer();
        server.respond(req -> req.path().equals("/info")
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(200, "{\"code\":200}"));
        var tester = testerFor(server, PushSettingsBuilder.push().build(), List.of(),
                FakeSecretAccessor.of(Map.of("secret-known", "realkey")));

        var problem = tester.test(new DeviceTestRequest("iPhone", "secret-known", server.url(), true));

        assertNull(problem, "引用可解析时应正常测试");
        var push = server.requests().stream().filter(r -> "/push".equals(r.path())).findFirst().orElseThrow();
        assertTrue(push.body().contains("realkey"), "请求必须携带 Secret 中的实际 Key");
        assertFalse(push.body().contains("secret-known"), "请求不得携带 Secret 名称");
    }

    private DeviceTester testerFor(MockBarkServer server, PushSettings settings) {
        return testerFor(server, settings, List.of());
    }

    private DeviceTester testerFor(MockBarkServer server, PushSettings settings,
            List<String> configIssues) {
        return testerFor(server, settings, configIssues, FakeSecretAccessor.identity());
    }

    private DeviceTester testerFor(MockBarkServer server, PushSettings settings,
            List<String> configIssues, SecretAccessor secretAccessor) {
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
        return new DeviceTester(probe, transport, supplier, () -> configIssues, secretAccessor);
    }
}
