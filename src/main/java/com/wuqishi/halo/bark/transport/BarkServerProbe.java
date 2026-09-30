package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.json.Json;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bark 服务端能力探测：{@code GET {serverUrl}/info}。
 *
 * <p>判定规则（PRD 5.2）：探测到 V2 才走 {@code POST /push}；未探测到 V2 可用则回退 V1。
 * 结果按服务地址缓存，**不每次发送都探测**。
 *
 * <p>缓存策略区分正负结果：「支持 V2」是稳定结论，长期缓存；「不可达 / 不支持 V2」只短期缓存
 * （{@value BarkConstants#SERVER_PROBE_TTL_MILLIS} ms）——它很可能只是瞬时网络问题，
 * 若永久缓存会把该地址钉死在 V1 回退与禁用批量上，直到插件重启。
 *
 * <p>配置页的「测试该设备」需要**新鲜**的探测结果以反馈可达性与版本号，因此提供
 * {@link #probe(String, boolean)} 的强制刷新入口。
 *
 * <p>日志只记录掩码后的服务地址，异常消息一律先经 {@link Masking#sanitize(String)}（PRD 5.8）。
 */
@Slf4j
@Component
public class BarkServerProbe {

    private static final String INFO_PATH = "/info";

    private final BarkHttpClient http;
    private final LongSupplier clock;
    private final ConcurrentMap<String, ProbeEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public BarkServerProbe(BarkHttpClient http) {
        this(http, System::currentTimeMillis);
    }

    public BarkServerProbe(BarkHttpClient http, LongSupplier clock) {
        this.http = http;
        this.clock = clock;
    }

    /** 探测并使用缓存结果。 */
    public ServerInfo probe(String serverUrl) {
        return probe(serverUrl, false);
    }

    /**
     * 探测服务端能力。
     *
     * @param serverUrl 设备级服务地址
     * @param force 为 {@code true} 时绕过缓存重新探测（用于「测试该设备」）
     */
    public ServerInfo probe(String serverUrl, boolean force) {
        if (!force) {
            var cached = cache.get(serverUrl);
            if (cached != null && cached.fresh(clock.getAsLong())) {
                return cached.info();
            }
        }
        var info = request(serverUrl);
        cache.put(serverUrl, new ProbeEntry(info, clock.getAsLong()));
        if (log.isDebugEnabled()) {
            log.debug("{} 探测服务端 {}：可达={} 版本={} 支持V2={}", BarkConstants.LOG_PREFIX,
                    Masking.maskUrl(serverUrl), info.reachable(), info.version(), info.v2Supported());
        }
        return info;
    }

    private ServerInfo request(String serverUrl) {
        try {
            var response = http.get(stripTrailingSlash(serverUrl) + INFO_PATH);
            if (response.status() != 200) {
                return ServerInfo.unreachable(DeliveryFailure.UNREACHABLE,
                        DeliveryFailure.UNREACHABLE.describe() + "（HTTP " + response.status() + "）");
            }
            var version = Json.field(response.body(), "version");
            if (version.isEmpty()) {
                log.warn("{} 服务端 {} 的 /info 未返回版本号，按 V1 处理",
                        BarkConstants.LOG_PREFIX, Masking.maskUrl(serverUrl));
            }
            return ServerInfo.reachable(version);
        } catch (HttpTimeoutException e) {
            return ServerInfo.unreachable(DeliveryFailure.TIMEOUT, DeliveryFailure.TIMEOUT.describe());
        } catch (IOException e) {
            return ServerInfo.unreachable(DeliveryFailure.UNREACHABLE,
                    DeliveryFailure.UNREACHABLE.describe());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ServerInfo.unreachable(DeliveryFailure.UNREACHABLE,
                    DeliveryFailure.UNREACHABLE.describe());
        } catch (RuntimeException e) {
            var detail = Masking.sanitize(e.toString());
            log.warn("{} 探测服务端 {} 出现异常，按 V1 处理：{}", BarkConstants.LOG_PREFIX,
                    Masking.maskUrl(serverUrl), detail);
            return ServerInfo.unreachable(DeliveryFailure.UNEXPECTED,
                    DeliveryFailure.UNEXPECTED.describe(detail));
        }
    }

    /** 缓存条目：正向结果长期有效，负向结果只在 TTL 内有效。 */
    private record ProbeEntry(ServerInfo info, long cachedAtMillis) {

        boolean fresh(long nowMillis) {
            return info.v2Supported()
                    || nowMillis - cachedAtMillis < BarkConstants.SERVER_PROBE_TTL_MILLIS;
        }
    }

    /** 去除结尾斜杠：配置里带尾斜杠会让拼接出的地址出现 {@code //}，统一裁掉。 */
    private static String stripTrailingSlash(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }
}
