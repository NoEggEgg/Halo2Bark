package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V1 回退路径的 URL 结构安全测试。
 *
 * <p>V1 的设备 Key 拼在路径中（{@code {serverUrl}/{key}}），而 Key 来自管理员配置
 * （「测试该设备」还允许提交未保存的表单值）。含 {@code / ? #} 等字符时若不编码，
 * 会破坏 URL 结构或改变路径语义。
 */
@DisplayName("BarkRequestSender：请求 URL 构造")
class BarkRequestSenderTest {

    @Test
    @DisplayName("V1 回退时设备 Key 编码后拼入路径，特殊字符不破坏 URL 结构")
    void encodesV1KeyInPath() throws IOException, InterruptedException {
        var http = new RecordingHttpClient();
        var sender = new BarkRequestSender(http, millis -> { });

        sender.send("https://api.day.app/", List.of("abc/def?key=1#frag"), "{}", false, 0);

        assertThat(http.urls.get(0))
                .isEqualTo("https://api.day.app/abc%2Fdef%3Fkey%3D1%23frag");
    }

    @Test
    @DisplayName("V1 回退时先裁掉服务地址的结尾斜杠再拼接")
    void stripsTrailingSlashOnV1() throws IOException, InterruptedException {
        var http = new RecordingHttpClient();
        var sender = new BarkRequestSender(http, millis -> { });

        sender.send("https://api.day.app/", List.of("KEY1"), "{}", false, 0);

        assertThat(http.urls.get(0)).isEqualTo("https://api.day.app/KEY1");
    }

    @Test
    @DisplayName("V2 走 /push 路径，Key 不进入 URL")
    void v2UsesPushPath() throws IOException, InterruptedException {
        var http = new RecordingHttpClient();
        var sender = new BarkRequestSender(http, millis -> { });

        sender.send("https://api.day.app/", List.of("KEY1"), "{}", true, 0);

        assertThat(http.urls.get(0)).isEqualTo("https://api.day.app/push");
    }

    /** 记录请求 URL 并返回固定成功应答的假客户端。 */
    private static final class RecordingHttpClient extends BarkHttpClient {

        final List<String> urls = new ArrayList<>();

        @Override
        public BarkResponse postJson(String url, String json) {
            urls.add(url);
            return new BarkResponse(200, "{\"code\":200}");
        }
    }
}
