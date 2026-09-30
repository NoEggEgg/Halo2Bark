package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 阻塞式 HTTP 客户端，基于 JDK 内置 {@link HttpClient}（PRD 10.4.4：不引入第三方 HTTP 依赖）。
 *
 * <p>阻塞调用只允许发生在**投递线程**；事件线程与 Reactor 调度线程不得调用本类（PRD 10.4.2）。
 *
 * <p>超时说明：JDK {@code HttpClient} 没有「每读超时」，只有覆盖整次交换的请求超时。
 * 因此以「连接 {@value BarkConstants#CONNECT_TIMEOUT_MILLIS} ms +
 * 读取 {@value BarkConstants#READ_TIMEOUT_MILLIS} ms」之和作为请求超时，
 * 与 PRD 5.2 的两个超时对齐。
 *
 * <p>重定向：只跟随**同源**重定向（协议 + 主机 + 端口相同），最多 {@value #MAX_REDIRECTS} 跳。
 * V1 的设备 Key 位于 URL 路径中，若交给 JDK 自动跟随，一次 302 就能把整条 URL 与通知正文
 * 转发到任意主机。
 */
@Slf4j
@Component
public class BarkHttpClient {

    /** 手动跟随重定向的最大跳数。 */
    private static final int MAX_REDIRECTS = 3;

    private final HttpClient client;

    public BarkHttpClient() {
        // 不交给 JDK 自动跟随：V1 的设备 Key 就在 URL 路径里，自动跟随会把整条 URL（乃至
        // 通知正文）转发到重定向指向的任意主机。改为下面按同源白名单手动跟随。
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(BarkConstants.CONNECT_TIMEOUT_MILLIS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 发送 JSON 请求体。
     *
     * @throws java.net.http.HttpTimeoutException 超时（是 {@link IOException} 的子类）
     * @throws IOException 网络异常
     * @throws InterruptedException 线程被中断
     */
    public BarkResponse postJson(String url, String json) throws IOException, InterruptedException {
        return send(url, "POST", () -> HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json; charset=utf-8"), MAX_REDIRECTS);
    }

    /**
     * 发起 GET 请求。
     *
     * @throws IOException 网络异常或超时
     * @throws InterruptedException 线程被中断
     */
    public BarkResponse get(String url) throws IOException, InterruptedException {
        return send(url, "GET", HttpRequest.BodyPublishers::noBody, Map.of(), MAX_REDIRECTS);
    }

    /**
     * 发一次请求；3xx 时只跟随**同源**重定向，跨源一律拒绝。
     *
     * <p>请求体用 {@code Supplier} 提供，因为重定向需要重新发布一次正文，
     * 而 {@link HttpRequest.BodyPublisher} 不保证可重复订阅。
     */
    private BarkResponse send(String url, String method, Supplier<HttpRequest.BodyPublisher> body,
            Map<String, String> headers, int redirectsLeft)
            throws IOException, InterruptedException {
        var uri = URI.create(url);
        var builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(BarkConstants.REQUEST_TIMEOUT_MILLIS))
                .method(method, body.get());
        headers.forEach(builder::header);
        var response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (redirectsLeft > 0 && isRedirect(response.statusCode())) {
            var target = response.headers().firstValue("Location").map(uri::resolve).orElse(null);
            if (target != null && isHttp(target) && isSameOrigin(uri, target)) {
                return send(target.toString(), method, body, headers, redirectsLeft - 1);
            }
            if (target != null) {
                log.warn("{} 拒绝跟随跨源重定向：{} -> {}", BarkConstants.LOG_PREFIX,
                        Masking.maskUrl(uri.toString()), Masking.maskUrl(target.toString()));
            }
        }
        var responseBody = response.body();
        return new BarkResponse(response.statusCode(), responseBody == null ? "" : responseBody);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isHttp(URI uri) {
        var scheme = uri.getScheme();
        return scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    }

    /**
     * 是否同源：协议、主机、端口（含默认端口）三者相同。
     *
     * <p>只认同源，是因为设备 Key 与通知正文都绑定在管理员配置的那台主机上。
     */
    private static boolean isSameOrigin(URI left, URI right) {
        return isHttp(right)
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** HTTP 响应；只保留状态码与响应体，避免把请求对象泄漏到上层。 */
    public record BarkResponse(int status, String body) {
    }
}
