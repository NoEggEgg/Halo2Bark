package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.config.DeviceSetting;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import com.wuqishi.halo.bark.support.MockBarkServer;
import com.wuqishi.halo.bark.support.PushSettingsBuilder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 投递编排的集成测试（Mock 服务端）。
 *
 * <p>覆盖 PRD 5.3 的投递模式与 PRD 5.2 的重试策略：
 * 按服务地址分组、批量优先、批量失败降级逐设备、仅对超时/网络异常/5xx 重试、V1 回退、加密推送。
 *
 * <p>不依赖真实 Halo 实例（PRD 10.4.5）。重试退避由 {@link RecordingSleeper} 接管，
 * 避免测试用例真实等待累计 21 s。
 */
class BarkTransportTest {

    private MockBarkServer server;
    private RecordingSleeper sleeper;

    @BeforeEach
    void startServer() {
        server = new MockBarkServer();
        sleeper = new RecordingSleeper();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("无可投递设备时不发请求")
    void skipsWhenNoDeliverableDevice() {
        respondInfo("v2.3.6");
        var devices = List.of(device("空 Key", "", server.url()));

        var report = transport().send(notification(NotificationCategory.NORMAL),
                PushSettingsBuilder.push().build(), devices);

        assertThat(report.results()).isEmpty();
        assertThat(server.count()).isZero();
    }

    @Test
    @DisplayName("单台设备走 V2 的 /push 并使用 device_key")
    void singleDevicePostsToV2() {
        respondInfo("v2.3.6");

        var report = transport().send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().build(), List.of(device("iPhone", "KEY1", server.url())));

        assertThat(report.allSucceeded()).isTrue();
        assertThat(pushRequests()).hasSize(1);
        assertThat(server.lastRequest().path()).isEqualTo("/push");
        assertThat(server.lastRequest().body()).contains("\"device_key\":\"KEY1\"");
    }

    @Test
    @DisplayName("同一服务地址的多台设备合并为一次批量请求")
    void batchesDevicesOnSameServer() {
        respondInfo("v2.3.6");

        var report = transport().send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().build(),
                List.of(device("iPhone", "KEY1", server.url()),
                        device("iPad", "KEY2", server.url())));

        assertThat(report.allSucceeded()).isTrue();
        assertThat(report.results()).hasSize(2);
        assertThat(pushRequests()).hasSize(1);
        assertThat(server.lastRequest().body()).contains("\"device_keys\":[\"KEY1\",\"KEY2\"]");
    }

    @Test
    void handlesPartialBatchAcknowledgementWithoutResendingSuccesses() {
        server.respond(request -> {
            if ("/info".equals(request.path())) {
                return new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}");
            }
            if (request.body().contains("device_keys")) {
                return new MockBarkServer.Response(200,
                        "{\"code\":200,\"data\":[{\"device_key\":\"KEY1\",\"code\":200},"
                                + "{\"device_key\":\"KEY2\",\"code\":400}]}");
            }
            return new MockBarkServer.Response(200, "{\"code\":400}");
        });

        var report = transport().send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().build(), List.of(device("good", "KEY1", server.url()),
                        device("bad", "KEY2", server.url())));

        assertThat(report.successCount()).isEqualTo(1);
        assertThat(report.failureCount()).isEqualTo(1);
        assertThat(report.results().get(1).failure()).isEqualTo(DeliveryFailure.CLIENT_ERROR);
        assertThat(pushRequests()).hasSize(2);
        assertThat(pushRequests().get(1).body()).contains("\"device_key\":\"KEY2\"");
    }

    @Test
    void reorderedBatchDeviceResultsAvoidResendingSuccesses() {
        server.respond(request -> {
            if ("/info".equals(request.path())) {
                return new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}");
            }
            if (request.body().contains("device_keys")) {
                return new MockBarkServer.Response(200,
                        "{\"code\":200,\"data\":[{\"device_key\":\"KEY2\",\"code\":400},"
                                + "{\"device_key\":\"KEY1\",\"code\":200}]}");
            }
            return new MockBarkServer.Response(200, request.body().contains("KEY2")
                    ? "{\"code\":400}" : "{\"code\":200}");
        });

        var report = transport().send(notification(NotificationCategory.NORMAL),
                PushSettingsBuilder.push().build(), List.of(device("one", "KEY1", server.url()),
                        device("two", "KEY2", server.url())));

        assertThat(report.successCount()).isEqualTo(1);
        assertThat(report.failureCount()).isEqualTo(1);
        assertThat(report.results().get(0).success()).isTrue();
        assertThat(report.results().get(1).failure()).isEqualTo(DeliveryFailure.CLIENT_ERROR);
        assertThat(pushRequests()).hasSize(2);
        assertThat(pushRequests().get(1).body()).contains("\"device_key\":\"KEY2\"");
    }

    @Test
    void blankHttpSuccessIsNotDeliverySuccess() {
        server.respond(request -> "/info".equals(request.path())
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(200, ""));

        var report = transport().send(notification(NotificationCategory.NORMAL),
                PushSettingsBuilder.push().build(), List.of(device("phone", "KEY1", server.url())));

        assertThat(report.results().get(0).failure()).isEqualTo(DeliveryFailure.UNEXPECTED);
        assertThat(pushRequests()).hasSize(1);
    }

    @Test
    void retriesBusinessServerErrorEvenWithHttpSuccess() {
        respondInfo("v2.3.6");
        server.respond(request -> "/info".equals(request.path())
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(200, "{\"code\":503}"));

        var report = transport().send(notification(NotificationCategory.NORMAL),
                PushSettingsBuilder.push().build(), List.of(device("phone", "KEY1", server.url())));

        assertThat(report.results().get(0).failure()).isEqualTo(DeliveryFailure.SERVER_ERROR);
        assertThat(report.results().get(0).attempts()).isEqualTo(3);
        assertThat(pushRequests()).hasSize(3);
    }

    @Test
    void rejectsInvalidAcknowledgementInsteadOfReportingSuccess() {
        server.respond(request -> "/info".equals(request.path())
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(200, "{\"message\":\"success\"}"));

        var report = transport().send(notification(NotificationCategory.NORMAL),
                PushSettingsBuilder.push().build(), List.of(device("phone", "KEY1", server.url())));

        assertThat(report.results().get(0).failure()).isEqualTo(DeliveryFailure.UNEXPECTED);
        assertThat(pushRequests()).hasSize(1);
    }

    @Test
    void fallsBackWhenBatchResponseDoesNotIdentifyAllDevices() {
        server.respond(request -> "/info".equals(request.path())
                ? new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}")
                : new MockBarkServer.Response(200, "{\"code\":200}"));

        var report = transport().send(notification(NotificationCategory.NORMAL),
                PushSettingsBuilder.push().build(), List.of(device("one", "KEY1", server.url()),
                        device("two", "KEY2", server.url())));

        assertThat(report.allSucceeded()).isTrue();
        assertThat(pushRequests()).hasSize(3);
    }

    @Test
    @DisplayName("不同服务地址各自独立投递")
    void splitsByServerUrl() {
        respondInfo("v2.3.6");
        try (var other = new MockBarkServer()) {
            other.respond(request -> new MockBarkServer.Response(200, "/info".equals(request.path())
                    ? "{\"version\":\"v2.3.6\"}" : "{\"code\":200}"));

            var report = transport().send(notification(NotificationCategory.INTERACTIVE),
                    PushSettingsBuilder.push().build(),
                    List.of(device("官方", "KEY1", server.url()),
                            device("自建", "KEY2", other.url())));

            assertThat(report.allSucceeded()).isTrue();
            assertThat(pushRequests()).hasSize(1);
            assertThat(other.requests()).hasSize(2);
        }
    }

    @Test
    @DisplayName("批量失败时降级为逐设备，失败隔离到具体设备")
    void degradesToPerDeviceOnBatchFailure() {
        server.respond(request -> switch (request.path()) {
            case "/info" -> new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}");
            default -> request.body().contains("device_keys") || request.body().contains("BADKEY")
                    ? new MockBarkServer.Response(400, "{\"code\":400}")
                    : new MockBarkServer.Response(200, "{\"code\":200}");
        });

        var report = transport().send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().build(),
                List.of(device("正常设备", "GOODKEY", server.url()),
                        device("异常设备", "BADKEY", server.url())));

        assertThat(report.successCount()).isEqualTo(1);
        assertThat(report.failureCount()).isEqualTo(1);
        var failed = report.results().stream().filter(result -> !result.success()).findFirst().orElseThrow();
        assertThat(failed.deviceName()).isEqualTo("异常设备");
        assertThat(failed.failure()).isEqualTo(DeliveryFailure.CLIENT_ERROR);
        // 1 次批量 + 2 次逐设备
        assertThat(pushRequests()).hasSize(3);
    }

    @Test
    @DisplayName("5xx 重试至上限后失败，退避为 1s / 5s")
    void retriesOnServerError() {
        respondInfo("v2.3.6", 500);

        var report = transport().send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().build(), List.of(device("iPhone", "KEY1", server.url())));

        assertThat(report.allSucceeded()).isFalse();
        assertThat(report.results().get(0).attempts()).isEqualTo(3);
        assertThat(report.results().get(0).failure()).isEqualTo(DeliveryFailure.SERVER_ERROR);
        assertThat(sleeper.sleeps).containsExactly(1_000L, 5_000L);
        assertThat(pushRequests()).hasSize(3);
    }

    @Test
    @DisplayName("安全事件比普通事件多一次重试，退避含 15s")
    void securityEventRetriesMore() {
        respondInfo("v2.3.6", 500);

        var report = transport().send(notification(NotificationCategory.SECURITY),
                PushSettingsBuilder.push().build(), List.of(device("iPhone", "KEY1", server.url())));

        assertThat(report.results().get(0).attempts()).isEqualTo(4);
        assertThat(sleeper.sleeps).containsExactly(1_000L, 5_000L, 15_000L);
    }

    @Test
    @DisplayName("4xx 不重试")
    void doesNotRetryOnClientError() {
        respondInfo("v2.3.6", 404);

        var report = transport().send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().build(), List.of(device("iPhone", "KEY1", server.url())));

        assertThat(report.results().get(0).attempts()).isEqualTo(1);
        assertThat(report.results().get(0).failure()).isEqualTo(DeliveryFailure.CLIENT_ERROR);
        assertThat(sleeper.sleeps).isEmpty();
        assertThat(pushRequests()).hasSize(1);
    }

    @Test
    @DisplayName("服务端不支持 V2 时回退 V1 的 /{key} 形式")
    void fallsBackToV1() {
        server.respond(request -> "/info".equals(request.path())
                ? new MockBarkServer.Response(404, "{\"code\":404}")
                : new MockBarkServer.Response(200, "{\"code\":200}"));

        var report = transport().send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().archiveEnabled(true).archiveTtl("7d").build(),
                List.of(device("iPhone", "KEY1", server.url())));

        assertThat(report.allSucceeded()).isTrue();
        assertThat(server.lastRequest().path()).isEqualTo("/KEY1");
        assertThat(server.lastRequest().body())
                .contains("\"device_key\":\"KEY1\"")
                .doesNotContain("device_keys")
                .doesNotContain("isArchive")
                .doesNotContain("ttl");
    }

    @Test
    @DisplayName("启用加密时只发送密文，且设备 Key 留在密文之外")
    void sendsCiphertextOnly() {
        respondInfo("v2.3.6");
        var settings = PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptKey("1234567890123456")
                .build();

        var report = transport().send(notification(NotificationCategory.INTERACTIVE), settings,
                List.of(device("iPhone", "KEY1", server.url())));

        assertThat(report.allSucceeded()).isTrue();
        var body = server.lastRequest().body();
        assertThat(body).contains("ciphertext").contains("iv").contains("\"device_key\":\"KEY1\"");
        assertThat(body).doesNotContain("评论正文");
    }

    @Test
    @DisplayName("加密配置错误时不发送任何推送，且每台设备都得到明确原因")
    void neverFallsBackToPlaintext() {
        respondInfo("v2.3.6");
        var settings = PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptKey("short")
                .build();

        var report = transport().send(notification(NotificationCategory.INTERACTIVE), settings,
                List.of(device("iPhone", "KEY1", server.url()),
                        device("iPad", "KEY2", server.url())));

        assertThat(report.successCount()).isZero();
        assertThat(report.results()).hasSize(2);
        assertThat(report.results()).allSatisfy(result ->
                assertThat(result.failure()).isEqualTo(DeliveryFailure.ENCRYPTION));
        assertThat(pushRequests()).isEmpty();
    }

    @Test
    @DisplayName("分组留空时取站点标题，站点标题缺失时回退 Halo")
    void resolvesGroupFromSiteTitle() {
        respondInfo("v2.3.6");
        var transport = transportWith(new Brand("我的站点", ""));

        transport.send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().build(), List.of(device("iPhone", "KEY1", server.url())));

        assertThat(server.lastRequest().body()).contains("\"group\":\"我的站点 · 互动\"");
    }

    @Test
    @DisplayName("站点标题与 Logo 一次投递只解析一次，不按设备重复查询")
    void resolvesSiteBrandOncePerDelivery() {
        respondInfo("v2.3.6");
        var brand = new CountingBrand();

        transportWith(brand).send(notification(NotificationCategory.INTERACTIVE),
                PushSettingsBuilder.push().build(),
                List.of(device("A", "KEY1", server.url()), device("B", "KEY2", server.url())));

        assertThat(brand.titleCalls).isEqualTo(1);
        assertThat(brand.logoCalls).isEqualTo(1);
    }

    private BarkTransport transport() {
        return transportWith(new Brand("", ""));
    }

    private BarkTransport transportWith(SiteBrandSupplier brand) {
        var http = new BarkHttpClient();
        return new BarkTransport(new BarkServerProbe(http),
                new BarkPayloadFactory(new PayloadEncryptor()), brand,
                new BarkRequestSender(http, sleeper));
    }

    private void respondInfo(String version) {
        respondInfo(version, 200);
    }

    private void respondInfo(String version, int pushStatus) {
        server.respond(request -> "/info".equals(request.path())
                ? new MockBarkServer.Response(200, "{\"version\":\"" + version + "\"}")
                : new MockBarkServer.Response(pushStatus,
                        pushStatus == 200 && request.body().contains("device_keys")
                                ? "{\"code\":200,\"data\":[{\"device_key\":\"KEY1\",\"code\":200},"
                                        + "{\"device_key\":\"KEY2\",\"code\":200}]}"
                                : "{\"code\":" + pushStatus + "}"));
    }

    private List<MockBarkServer.Request> pushRequests() {
        return server.requests().stream()
                .filter(request -> !"/info".equals(request.path()))
                .toList();
    }

    private static PushNotification notification(NotificationCategory category) {
        return new PushNotification("💬 新评论", "文章标题", "评论正文", "https://example.com/p/1",
                category, true);
    }

    private static DeviceSetting device(String name, String key, String serverUrl) {
        return new DeviceSetting(name, key, serverUrl, true);
    }

    private record Brand(String title, String logo) implements SiteBrandSupplier {
    }

    /** 计数站点信息被读取的次数，用于验证不会按设备重复查询。 */
    private static final class CountingBrand implements SiteBrandSupplier {

        private int titleCalls;
        private int logoCalls;

        @Override
        public String title() {
            titleCalls++;
            return "我的站点";
        }

        @Override
        public String logo() {
            logoCalls++;
            return "https://example.com/logo.png";
        }
    }

    /** 记录退避时长但不真实等待。 */
    private static final class RecordingSleeper implements RetrySleeper {

        private final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }
}
