package com.wuqishi.halo.bark.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wuqishi.halo.bark.support.MockBarkServer;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HTTP 客户端：只跟随同源重定向")
class BarkHttpClientTest {

    private MockBarkServer origin;
    private MockBarkServer other;

    @AfterEach
    void tearDown() {
        if (origin != null) {
            origin.close();
        }
        if (other != null) {
            other.close();
        }
    }

    @Test
    @DisplayName("超大响应体只读取前 64 KB，不整体读入内存")
    void oversizedResponseBodyIsTruncated() throws Exception {
        origin = new MockBarkServer();
        origin.respond(request -> new MockBarkServer.Response(200, "x".repeat(200 * 1024)));

        var response = new BarkHttpClient().get(origin.url() + "/info");

        assertEquals(200, response.status());
        assertEquals(64 * 1024, response.body().length(),
                "超出上限的部分必须被丢弃，避免异常或恶意服务端把内存打满");
    }

    @Test
    @DisplayName("同源重定向会被跟随，最终返回目标响应")
    void followsSameOriginRedirect() throws Exception {
        origin = new MockBarkServer();
        origin.setHeaders(Map.of("Location", origin.url() + "/final"));
        origin.respond(request -> request.path().equals("/first")
                ? new MockBarkServer.Response(302, "")
                : new MockBarkServer.Response(200, "ok"));

        var response = new BarkHttpClient().get(origin.url() + "/first");

        assertEquals(200, response.status());
        assertEquals("ok", response.body());
        assertEquals(2, origin.count(), "应发出原请求与重定向后的请求");
    }

    @Test
    @DisplayName("跨源重定向被拒绝：不向第三方主机转发（V1 的 Key 在 URL 里）")
    void refusesCrossOriginRedirect() throws Exception {
        origin = new MockBarkServer();
        other = new MockBarkServer();
        other.respondStatus(200);
        origin.setHeaders(Map.of("Location", other.url() + "/stolen"));
        origin.respond(request -> new MockBarkServer.Response(302, ""));

        var response = new BarkHttpClient().get(origin.url() + "/key-in-path");

        assertEquals(302, response.status(), "不应跟随，直接把 3xx 交给上层按失败处理");
        assertEquals(0, other.count(), "不得向另一个主机发出任何请求");
        assertEquals(1, origin.count());
    }

    @Test
    @DisplayName("非 HTTP 协议的重定向目标同样被拒绝")
    void refusesNonHttpRedirectTarget() throws Exception {
        origin = new MockBarkServer();
        origin.setHeaders(Map.of("Location", "file:///etc/passwd"));
        origin.respond(request -> new MockBarkServer.Response(302, ""));

        var response = new BarkHttpClient().get(origin.url() + "/key-in-path");

        assertEquals(302, response.status());
        assertEquals(1, origin.count());
    }
}
