package com.wuqishi.halo.bark.endpoint;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 「测试该设备」端点：{@code POST /apis/console.api.bark.wuqishi.com/v1alpha1/bark/test}。
 *
 * <p>薄适配层：绑定 {@code verificationForm} 提交的扁平设备值 → 在投递线程（{@code boundedElastic}）执行
 * 阻塞的两级校验 → 把结果映射为 HTTP 响应。
 *
 * <p>响应契约遵循 Halo {@code verificationForm}：成功仅看状态码（返回 200，响应体被忽略，
 * Halo 显示通用「验证通过」）；失败返回 RFC 7807（{@code title}/{@code status}/{@code detail}），
 * UI 读取 {@code detail} 渲染可操作提示（PRD 10.6.3 的两级区分主要靠失败路径体现）。
 *
 * <p>线程约定：{@link DeviceTester#test} 内部发起阻塞 HTTP，必须离开 Reactor 调度线程，
 * 故经 {@link Schedulers#boundedElastic()} 执行（PRD 10.4.2）。
 */
@Component
public class BarkTestEndpoint implements CustomEndpoint {

    private final DeviceTester tester;

    public BarkTestEndpoint(DeviceTester tester) {
        this.tester = tester;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
                .POST("/bark/test", this::handle)
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.bark.wuqishi.com", "v1alpha1");
    }

    private Mono<ServerResponse> handle(ServerRequest request) {
        return request.bodyToMono(DeviceTestRequest.class)
                // 空请求体时 bodyToMono 不会进入 flatMap，必须显式给出确定性的 400
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("请求体为空")))
                .flatMap(req -> Mono.defer(() -> {
                    // 阻塞校验必须在投递线程执行；defer + subscribeOn 保证其运行在 boundedElastic
                    TestProblem problem = tester.test(req);
                    return problem == null
                            ? ServerResponse.ok().build()
                            : ServerResponse.status(problem.status())
                                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                                    .bodyValue(problem);
                }).subscribeOn(Schedulers.boundedElastic()))
                // 解码失败 / 空体等异常统一收敛为确定性的 400（RFC 7807）
                .onErrorResume(e -> ServerResponse.status(400)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .bodyValue(new TestProblem("请求体无效", 400,
                                "请提供包含 name、key、serverUrl 的 JSON 请求体。")));
    }
}
