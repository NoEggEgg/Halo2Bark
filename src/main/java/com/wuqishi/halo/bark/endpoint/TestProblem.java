package com.wuqishi.halo.bark.endpoint;

/**
 * 测试端点的失败响应，遵循 RFC 7807 Problem Details。
 *
 * <p>Halo 的 {@code verificationForm} 仅按 HTTP 状态码判定成败，成功时忽略响应体
 * （显示通用「验证通过」）；失败时读取 {@code detail} 渲染可操作提示。
 * 因此两级校验的「区分地址错 / Key 错」完全体现在 {@code detail} 上（PRD 10.6.3）。
 *
 * <p>{@code title} 为简短结论，{@code detail} 为具体可执行的操作指引，{@code status} 与 HTTP 状态码一致。
 */
public record TestProblem(String title, int status, String detail) {
}
