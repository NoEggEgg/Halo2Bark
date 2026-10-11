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
 * <p>缓存策略区分正负结果：「支持 V2」按较长的 TTL 缓存（{@value BarkConstants#SERVER_PROBE_POSITIVE_TTL_MILLIS} ms），
 * 「不可达 / 不支持 V2」只短期缓存（{@value BarkConstants#SERVER_PROBE_TTL_MILLIS} ms）——后者很可能只是瞬时网络
 * 问题，若长期缓存会把该地址钉死在 V1 回退与禁用批量上。两者都不永久缓存，使服务端降级能在 TTL 内被发现。
 *
 * <p>并发：同一地址的冷缓存探测按分片锁单飞，避免多个投递线程同时发起 {@code /info}。
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

    /** 单飞分片锁数量：用固定数量的锁避免「按 key 建锁」在并发下产生 ABA 问题，同时限制内存。 */
    private static final int LOCK_STRIPES = 16;

    /** 缓存条目上限。缓存只服务管理员配置的服务地址（数量有限），超限时按过期项清理。 */
    private static final int MAX_CACHE_ENTRIES = 64;

    private final BarkHttpClient http;
    private final LongSupplier clock;
    private final ConcurrentMap<String, ProbeEntry> cache = new ConcurrentHashMap<>();
    private final Object[] locks = createLocks();

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
            var cached = freshInfo(serverUrl);
            if (cached != null) {
                return cached;
            }
        }
        // 单飞：多个投递线程可能同时遇到冷缓存，按地址分片加锁，避免同一地址被重复探测
        synchronized (lockFor(serverUrl)) {
            if (!force) {
                var cached = freshInfo(serverUrl);
                if (cached != null) {
                    return cached;
                }
            }
            var info = request(serverUrl);
            cache.put(serverUrl, new ProbeEntry(info, clock.getAsLong()));
            evictIfOversized();
            if (log.isDebugEnabled()) {
                log.debug("{} 探测服务端 {}：可达={} 版本={} 支持V2={}", BarkConstants.LOG_PREFIX,
                        Masking.maskUrl(serverUrl), info.reachable(), info.version(), info.v2Supported());
            }
            return info;
        }
    }

    /** @return 仍在有效期内的探测结果；无缓存或已过期时为空 */
    private ServerInfo freshInfo(String serverUrl) {
        var cached = cache.get(serverUrl);
        return cached != null && cached.fresh(clock.getAsLong()) ? cached.info() : null;
    }

    private Object lockFor(String serverUrl) {
        return locks[Math.floorMod(serverUrl == null ? 0 : serverUrl.hashCode(), LOCK_STRIPES)];
    }

    /**
     * 缓存超限时先清理过期项；仍超限则整体清空。
     *
     * <p>缓存只服务管理员配置的服务地址（设备上限 20），正常情况下远不会触发；这里只是兜底，
     * 避免「测试该设备」传入的任意地址让映射无界增长。
     */
    private void evictIfOversized() {
        if (cache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        var now = clock.getAsLong();
        cache.entrySet().removeIf(entry -> !entry.getValue().fresh(now));
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.clear();
        }
    }

    private static Object[] createLocks() {
        var created = new Object[LOCK_STRIPES];
        for (int i = 0; i < created.length; i++) {
            created[i] = new Object();
        }
        return created;
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

    /** 缓存条目：正向（支持 V2）与负向结论使用不同的有效期。 */
    private record ProbeEntry(ServerInfo info, long cachedAtMillis) {

        boolean fresh(long nowMillis) {
            var ttl = info.v2Supported()
                    ? BarkConstants.SERVER_PROBE_POSITIVE_TTL_MILLIS
                    : BarkConstants.SERVER_PROBE_TTL_MILLIS;
            return nowMillis - cachedAtMillis < ttl;
        }
    }

    /** 去除结尾斜杠：配置里带尾斜杠会让拼接出的地址出现 {@code //}，统一裁掉。 */
    private static String stripTrailingSlash(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }
}
