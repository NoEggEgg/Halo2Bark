package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 单次请求的发送与重试：只负责「把已构造好的请求体发出去，并按策略重试」。
 *
 * <p>职责边界：设备分组、批量与降级、参数构造都不在这里——它们在 {@link BarkTransport}
 * 与 {@link BarkPayloadFactory}。拆分的直接原因是 {@code BarkTransport} 会超出单类 300 行的约束。
 *
 * <p>重试规则（PRD 5.2）：仅对超时、网络异常与 5xx 重试；4xx 属配置或凭据问题，不重试。
 * 是否重试由 {@link DeliveryFailure#retryable()} 判定，本类不重复判断业务语义。
 *
 * <p><b>线程约定</b>：阻塞，只允许在投递线程调用（PRD 10.4.2）。
 */
@Slf4j
public class BarkRequestSender {

    /** V2 推送路径；V1 回退时路径为 {@code /{deviceKey}}。 */
    private static final String PUSH_PATH = "/push";

    private final BarkHttpClient http;
    private final RetrySleeper sleeper;

    public BarkRequestSender(BarkHttpClient http, RetrySleeper sleeper) {
        this.http = http;
        this.sleeper = sleeper;
    }

    /**
     * 发送请求并按策略重试。
     *
     * @param serverUrl 设备级服务地址
     * @param deviceKeys 本次请求的目标设备 Key；V1 回退时取第一个拼入路径
     * @param body 已构造好的请求体
     * @param v2 服务端是否支持 V2
     * @param maxRetries 最大重试次数（不含首次请求）
     */
    public Outcome send(String serverUrl, List<String> deviceKeys, String body, boolean v2,
            int maxRetries) {
        var base = stripTrailingSlash(serverUrl);
        // V1 的 Key 位于路径中：它来自管理员配置（「测试该设备」还允许未保存的表单值），
        // 含 / ? # 空格等字符时会破坏 URL 结构或改变路径语义，必须编码后再拼接。
        // URLEncoder 是查询串口径（空格 → +），但路径段中的 + 是合法字面量，
        // 且带空格的 Key 本就是无效凭据——这里追求的是结构安全而非语义还原。
        var url = v2 ? base + PUSH_PATH : base + "/" + URLEncoder.encode(deviceKeys.get(0),
                StandardCharsets.UTF_8);
        var startedAt = System.nanoTime();
        var attempt = 0;
        while (true) {
            attempt++;
            var outcome = post(url, body, deviceKeys);
            if (outcome.success() || attempt > maxRetries || !outcome.retryable()) {
                return outcome.withAttempts(attempt, elapsedMillis(startedAt));
            }
            // 任务已被取消（如 shutdownNow 或线程中断）：不再重试，立即返回，避免关闭期间
            // 继续发出 HTTP 请求（中断后回 UNREACHABLE 会被当作可重试，导致关不掉投递）。
            if (Thread.currentThread().isInterrupted()) {
                return outcome.withAttempts(attempt, elapsedMillis(startedAt));
            }
            log.debug("{} 第 {} 次投递失败，准备重试：服务={} 原因={}", BarkConstants.LOG_PREFIX,
                    attempt, Masking.maskUrl(serverUrl), outcome.failure());
            sleeper.sleep(backoffMillis(attempt));
        }
    }

    /** 单次请求；所有异常就地转换为失败分类，绝不外抛（PRD 10.4.3）。 */
    private Outcome post(String url, String body, List<String> deviceKeys) {
        try {
            var response = http.postJson(url, body);
            var status = response.status();
            if (status >= 200 && status < 300) {
                var parsed = BarkPushResponse.parse(response.body(), deviceKeys);
                if (parsed == null) {
                    return Outcome.failed(DeliveryFailure.UNEXPECTED,
                            DeliveryFailure.UNEXPECTED.describe("Invalid Bark acknowledgement or mismatched device results"));
                }
                if (parsed.code() >= 500) {
                    return Outcome.failed(DeliveryFailure.SERVER_ERROR,
                            DeliveryFailure.SERVER_ERROR.describe(parsed.code()));
                }
                if (parsed.code() != 200) {
                    return Outcome.failed(DeliveryFailure.CLIENT_ERROR,
                            DeliveryFailure.CLIENT_ERROR.describe(parsed.code()));
                }
                return Outcome.ok(parsed.deviceCodes());
            }
            if (status >= 500) {
                return Outcome.failed(DeliveryFailure.SERVER_ERROR,
                        DeliveryFailure.SERVER_ERROR.describe(status));
            }
            return Outcome.failed(DeliveryFailure.CLIENT_ERROR,
                    DeliveryFailure.CLIENT_ERROR.describe(status));
        } catch (HttpTimeoutException e) {
            return Outcome.failed(DeliveryFailure.TIMEOUT, DeliveryFailure.TIMEOUT.describe());
        } catch (IOException e) {
            return Outcome.failed(DeliveryFailure.UNREACHABLE,
                    DeliveryFailure.UNREACHABLE.describe());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failed(DeliveryFailure.UNREACHABLE,
                    DeliveryFailure.UNREACHABLE.describe());
        } catch (RuntimeException e) {
            // 异常消息可能携带完整 URL——V1 路径形如 {serverUrl}/{设备Key}，故必须脱敏后再记录
            var detail = Masking.sanitize(e.toString());
            log.warn("{} 推送请求异常终止：{}", BarkConstants.LOG_PREFIX, detail);
            return Outcome.failed(DeliveryFailure.UNEXPECTED,
                    DeliveryFailure.UNEXPECTED.describe(detail));
        }
    }

    private static long backoffMillis(int attempt) {
        var backoff = BarkConstants.RETRY_BACKOFF_MILLIS;
        return backoff[Math.min(attempt - 1, backoff.length - 1)];
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /** 去除结尾斜杠：配置里带尾斜杠会让拼接出的地址出现 {@code //}，统一裁掉。 */
    private static String stripTrailingSlash(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 单次请求的结果。
     *
     * @param failure 失败分类；为 {@code null} 表示成功
     * @param message 可操作的提示文案
     * @param attempts 实际请求次数
     * @param elapsedMillis 总耗时
     */
    public record Outcome(DeliveryFailure failure, String message, int attempts, long elapsedMillis,
                          List<Integer> deviceCodes) {

        static Outcome ok(List<Integer> deviceCodes) {
            return new Outcome(null, "", 0, 0L, deviceCodes);
        }

        static Outcome failed(DeliveryFailure failure, String message) {
            return new Outcome(failure, message == null ? "" : message, 0, 0L, List.of());
        }

        boolean success() {
            return failure == null;
        }

        boolean retryable() {
            return failure != null && failure.retryable();
        }

        Outcome withAttempts(int attempts, long elapsedMillis) {
            return new Outcome(failure, message, attempts, elapsedMillis, deviceCodes);
        }
    }
}
