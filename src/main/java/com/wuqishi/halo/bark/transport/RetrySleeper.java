package com.wuqishi.halo.bark.transport;

/**
 * 重试退避的执行器。
 *
 * <p>抽成接口的唯一理由：真实退避累计可达 21 s（1+5+15），
 * 测试需要以零等待替换它，否则集成测试的耗时会不可接受（PRD 10.4.5 要求传输层有集成测试）。
 */
@FunctionalInterface
public interface RetrySleeper {

    /**
     * 等待指定毫秒。
     *
     * @param millis 退避时长
     */
    void sleep(long millis);
}
