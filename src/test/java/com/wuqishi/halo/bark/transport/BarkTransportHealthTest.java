package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.config.DeviceSetting;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import com.wuqishi.halo.bark.support.MockBarkServer;
import com.wuqishi.halo.bark.support.PushSettingsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("健康通知：跨服务投递与告警接收者的恢复闭环")
class BarkTransportHealthTest {

    private final TestServer first = new TestServer();
    private final TestServer second = new TestServer();
    private final BarkTransport transport = transport();
    private final PushNotification event = new PushNotification("新评论", "", "正文", "",
            NotificationCategory.INTERACTIVE, true);

    @AfterEach
    void closeServers() {
        first.server.close();
        second.server.close();
    }

    @Test
    @DisplayName("服务地址交错且设备同名：投递结果保持输入顺序，故障只归属实际失败设备")
    void interleavedServersKeepDeviceResultsAligned() {
        var a = first.device("同名手机", "KEY1");
        var b = second.device("同名手机", "KEY2");
        var c = first.device("故障手机", "KEY3");
        var devices = List.of(a, b, c);
        var settings = PushSettingsBuilder.push().devices(devices).build();
        first.codes.put("KEY3", 400);

        var report = transport.send(event, settings, devices);
        transport.send(event, settings, devices);

        assertThat(report.results()).extracting(DeviceResult::deviceName)
                .containsExactly("同名手机", "同名手机", "故障手机");
        assertThat(report.results()).extracting(DeviceResult::success)
                .containsExactly(true, true, false);
        assertThat(noticeKeys("推送失败")).containsExactlyInAnyOrder("KEY1", "KEY2");
        assertThat(notices("推送失败")).allSatisfy(request ->
                assertThat(request.body()).contains("设备「故障手机」"));
    }

    @Test
    @DisplayName("跨服务告警：恢复同时更新原告警接收者与恢复设备，id 一致且不重复更新")
    void recoveryReachesOriginalRecipientsAcrossServers() {
        var a = first.device("故障手机", "KEY1");
        var b = second.device("工作机", "KEY2");
        var c = first.device("平板", "KEY3");
        var devices = List.of(a, b, c);
        var settings = PushSettingsBuilder.push().devices(devices).build();
        first.codes.put("KEY1", 400);
        transport.send(event, settings, devices);
        transport.send(event, settings, devices);
        var alertId = noticeId(notices("推送失败").getFirst());
        assertThat(noticeKeys("推送失败")).containsExactlyInAnyOrder("KEY2", "KEY3");

        first.codes.put("KEY1", 200);
        transport.send(event, settings, List.of(a));
        transport.send(event, settings, List.of(a));

        assertThat(noticeKeys("推送已恢复"))
                .containsExactlyInAnyOrder("KEY1", "KEY2", "KEY3");
        assertThat(notices("推送已恢复")).allSatisfy(request ->
                assertThat(noticeId(request)).isEqualTo(alertId));
        assertThat(transport.lastDelivery().orElseThrow().deviceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("接收者改名后仍能按设备身份收到恢复，禁用或移除的接收者不再投递")
    void recoveryHonorsCurrentConfigurationAndRenames() {
        var a = first.device("故障手机", "KEY1");
        var b = second.device("工作机", "KEY2");
        var c = first.device("平板", "KEY3");
        var d = second.device("备用机", "KEY4");
        var devices = List.of(a, b, c, d);
        first.codes.put("KEY1", 400);
        var settings = PushSettingsBuilder.push().devices(devices).build();
        transport.send(event, settings, devices);
        transport.send(event, settings, devices);

        var renamed = second.device("已改名", "KEY2");
        var disabled = new DeviceSetting(c.name(), c.key(), c.serverUrl(), false);
        var replacement = second.device("备用机", "KEY5");
        var changed = PushSettingsBuilder.push()
                .devices(List.of(a, renamed, disabled, replacement)).build();
        first.codes.put("KEY1", 200);
        transport.send(event, changed, List.of(a));

        assertThat(noticeKeys("推送已恢复")).containsExactlyInAnyOrder("KEY1", "KEY2");
    }

    @Test
    @DisplayName("告警部分投递失败：只向成功收到告警的设备发送恢复更新")
    void recoveryExcludesRecipientsThatRejectedTheAlert() {
        var a = first.device("故障手机", "KEY1");
        var b = second.device("拒收告警", "KEY2");
        var c = second.device("正常接收", "KEY3");
        var devices = List.of(a, b, c);
        var settings = PushSettingsBuilder.push().devices(devices).build();
        first.codes.put("KEY1", 400);
        second.rejectAlerts.add("KEY2");
        transport.send(event, settings, devices);
        transport.send(event, settings, devices);

        first.codes.put("KEY1", 200);
        transport.send(event, settings, List.of(a));

        assertThat(noticeKeys("推送已恢复")).containsExactlyInAnyOrder("KEY1", "KEY3");
    }

    @Test
    @DisplayName("不同故障周期独立记录接收者，不向上一周期的接收者误发恢复")
    void alertRecipientsResetBetweenFailureEpisodes() {
        var a = first.device("故障手机", "KEY1");
        var b = second.device("工作机", "KEY2");
        var c = second.device("平板", "KEY3");
        var settings = PushSettingsBuilder.push().devices(List.of(a, b, c)).build();
        first.codes.put("KEY1", 400);
        transport.send(event, settings, List.of(a, b));
        transport.send(event, settings, List.of(a, b));
        first.codes.put("KEY1", 200);
        transport.send(event, settings, List.of(a));
        assertThat(noticeKeys("推送已恢复")).containsExactlyInAnyOrder("KEY1", "KEY2");

        first.codes.put("KEY1", 400);
        transport.send(event, settings, List.of(a, c));
        transport.send(event, settings, List.of(a, c));
        first.codes.put("KEY1", 200);
        transport.send(event, settings, List.of(a));

        assertThat(noticeKeys("推送已恢复"))
                .containsExactlyInAnyOrder("KEY1", "KEY1", "KEY2", "KEY3");
    }

    @Test
    @DisplayName("只有一台设备：无告警接收者时，恢复后仍通知该设备")
    void recoveryWorksWithoutAnyAlertRecipient() {
        var a = first.device("唯一手机", "KEY1");
        var devices = List.of(a);
        var settings = PushSettingsBuilder.push().devices(devices).build();
        first.codes.put("KEY1", 400);
        transport.send(event, settings, devices);
        transport.send(event, settings, devices);
        assertThat(notices("推送失败")).isEmpty();

        first.codes.put("KEY1", 200);
        transport.send(event, settings, devices);

        assertThat(noticeKeys("推送已恢复")).containsExactly("KEY1");
    }

    private List<MockBarkServer.Request> notices(String title) {
        return Stream.concat(first.server.requests().stream(), second.server.requests().stream())
                .filter(request -> request.body().contains(title)).toList();
    }

    private List<String> noticeKeys(String title) {
        return notices(title).stream().flatMap(request -> keys(request.body()).stream()).toList();
    }

    private static String noticeId(MockBarkServer.Request request) {
        var matcher = Pattern.compile("halo2bark-health-[0-9a-f]+").matcher(request.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    private static List<String> keys(String body) {
        var matches = Pattern.compile("\"(KEY[0-9]+)\"").matcher(body);
        var keys = new ArrayList<String>();
        while (matches.find()) {
            keys.add(matches.group(1));
        }
        return keys;
    }

    private static BarkTransport transport() {
        var http = new BarkHttpClient();
        var brand = new SiteBrandSupplier() {
            public String title() { return "测试站点"; }
            public String logo() { return ""; }
        };
        return new BarkTransport(new BarkServerProbe(http),
                new BarkPayloadFactory(new PayloadEncryptor()), brand,
                new BarkRequestSender(http, millis -> { }));
    }

    private static final class TestServer {
        private final MockBarkServer server = new MockBarkServer();
        private final Map<String, Integer> codes = new ConcurrentHashMap<>();
        private final Set<String> rejectAlerts = ConcurrentHashMap.newKeySet();

        TestServer() {
            server.respond(request -> {
                if ("/info".equals(request.path())) {
                    return new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}");
                }
                var keys = keys(request.body());
                var results = keys.stream().map(key -> {
                    int code = request.body().contains("推送失败") && rejectAlerts.contains(key)
                            ? 400 : codes.getOrDefault(key, 200);
                    return "{\"device_key\":\"" + key + "\",\"code\":" + code + "}";
                }).toList();
                if (request.body().contains("device_keys")) {
                    return new MockBarkServer.Response(200,
                            "{\"code\":200,\"data\":[" + String.join(",", results) + "]}");
                }
                int code = request.body().contains("推送失败") && rejectAlerts.contains(keys.getFirst())
                        ? 400 : codes.getOrDefault(keys.getFirst(), 200);
                return new MockBarkServer.Response(code, "{\"code\":" + code + "}");
            });
        }

        DeviceSetting device(String name, String key) {
            return new DeviceSetting(name, key, server.url(), true);
        }
    }
}
