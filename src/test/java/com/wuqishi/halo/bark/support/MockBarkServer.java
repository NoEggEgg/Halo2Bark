package com.wuqishi.halo.bark.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 测试用 Bark 服务端：记录收到的请求，并可按用例定制响应。
 *
 * <p>使用 JDK 内置 {@link HttpServer}，端口取 0（由系统分配空闲端口），因此无需引入额外依赖、
 * 也不会与本地开发实例抢端口（PRD 10.4.4：不引入非必要第三方依赖）。
 *
 * <p>用于验证请求构造、批量与降级、重试与 V1 回退——这些行为只有观察真实 HTTP 报文才能确认。
 */
public final class MockBarkServer implements AutoCloseable {

    /** 记录的一次请求。 */
    public record Request(String method, String path, String body) {
    }

    /** 用例指定的响应。 */
    public record Response(int status, String body) {
    }

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Request, Response> responder =
            request -> new Response(200, "{\"code\":200,\"message\":\"success\"}");
    private volatile Map<String, String> headers = Map.of();

    public MockBarkServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("启动 Mock 服务端失败", e);
        }
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var recorded = new Request(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), body);
            requests.add(recorded);
            var response = responder.apply(recorded);
            var bytes = response.body() == null
                    ? new byte[0]
                    : response.body().getBytes(StandardCharsets.UTF_8);
            headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    /** 服务地址（含系统分配的端口）。 */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 定制响应逻辑，可按路径与请求体分支。 */
    public void respond(Function<Request, Response> responder) {
        this.responder = responder;
    }

    /** 为所有响应附加响应头（例如重定向场景的 {@code Location}）。 */
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 固定返回某个状态码。 */
    public void respondStatus(int status) {
        respond(request -> new Response(status, "{\"code\":" + status + "}"));
    }

    /** 收到的全部请求（按到达顺序）。 */
    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** 收到的请求次数。 */
    public int count() {
        return requests.size();
    }

    /** 最后一次请求；未收到请求时返回 {@code null}。 */
    public Request lastRequest() {
        var snapshot = requests();
        return snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
