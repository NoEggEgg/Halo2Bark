package com.wuqishi.halo.bark.endpoint;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 运行状态端点：{@code GET /apis/console.api.bark.wuqishi.com/v1alpha1/bark/status}。
 *
 * <p>返回 {@link StatusSnapshot}：配置是否可用于投递、设备数、队列深度、各类累计丢弃与失败、
 * 最近一次投递摘要。用于回答「有没有丢通知 / 哪台设备一直失败 / 配置是否处于阻断态」。
 *
 * <p>快照读取的全部是 volatile 计数与不可变对象，不阻塞，因此直接在当前调度线程组装响应，
 * 不需要像「测试该设备」那样切到 {@code boundedElastic}。
 *
 * <p>端点位于控制台 API 组下，与其他 console 端点一样受 Halo 的鉴权保护。
 */
@Component
public class BarkStatusEndpoint implements CustomEndpoint {

    private final StatusReporter reporter;

    public BarkStatusEndpoint(StatusReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
                .GET("/bark/status", this::handle)
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.bark.wuqishi.com", "v1alpha1");
    }

    private Mono<ServerResponse> handle(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(reporter.snapshot());
    }
}
