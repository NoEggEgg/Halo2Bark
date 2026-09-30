package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.infra.SystemInfo;
import run.halo.app.infra.SystemInfoGetter;

/**
 * 基于 Halo {@link SystemInfoGetter} 的站点品牌信息实现（PRD 5.5 首选方式）。
 *
 * <p>{@code SystemInfoGetter#get()} 返回 {@code Mono}。本类在**投递线程**中有限期阻塞等待：
 * 传输层允许阻塞，但必须设上限，避免站点信息读取异常时无限占用投递线程。
 *
 * <p>读取失败时返回空字符串而非抛异常：站点标题与 Logo 属锦上添花，缺失时按 PRD 11 降级
 * （分组回退 {@code Halo}、图标留空由 Bark 显示默认图标），不应导致推送失败。
 *
 * <p>缓存：一次投递会分别取标题与 Logo，若不缓存会发起两次阻塞读取。
 * 以短时 TTL 缓存读取结果，单次投递内两次调用命中同一份，跨投递也能复用、降低读取频率；
 * 站点标题/Logo 变化不频繁，短时陈旧可忽略。
 */
@Slf4j
@Component
public class SystemInfoSiteBrand implements SiteBrandSupplier {

    /** 读取结果缓存时长：足够覆盖一次投递内的两次调用，又不长期陈旧。 */
    private static final long CACHE_TTL_MILLIS = 30_000;

    private final SystemInfoGetter systemInfoGetter;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    public SystemInfoSiteBrand(SystemInfoGetter systemInfoGetter) {
        this.systemInfoGetter = systemInfoGetter;
    }

    @Override
    public String title() {
        var info = current();
        var title = info == null ? null : info.getTitle();
        return title == null ? "" : title.trim();
    }

    @Override
    public String logo() {
        var info = current();
        var logo = info == null ? null : info.getLogo();
        return logo == null ? "" : logo.trim();
    }

    /** 带短时缓存地读取站点信息：命中缓存直接返回，否则阻塞读取并回填。 */
    private SystemInfo current() {
        var now = System.currentTimeMillis();
        var cached = cache.get();
        if (cached != null && now - cached.fetchedAt() < CACHE_TTL_MILLIS) {
            return cached.info();
        }
        var info = fetch();
        cache.set(new CacheEntry(info, now));
        return info;
    }

    private SystemInfo fetch() {
        try {
            return systemInfoGetter.get()
                    .block(Duration.ofMillis(BarkConstants.SITE_INFO_TIMEOUT_MILLIS));
        } catch (RuntimeException e) {
            // 异常消息可能来自任意层级，统一脱敏后再记录（PRD 5.8）
            log.warn("{} 读取站点信息失败，本次使用降级值：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return null;
        }
    }

    /** 缓存条目。 */
    private record CacheEntry(SystemInfo info, long fetchedAt) {
    }
}
