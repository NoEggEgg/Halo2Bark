package com.wuqishi.halo.bark.config;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 配置快照的持有者：负责加载、校验与热更新。
 *
 * <p>线程约定（PRD 10.4.2）：
 * <ul>
 *   <li>初始化在插件 {@code start()} 中同步完成——该钩子运行在插件启动线程，不是 Reactor 调度线程，
 *       因此允许一次性阻塞等待，使插件启动后配置立即可用。</li>
 *   <li>热更新走 {@code PluginConfigUpdatedEvent}，全程非阻塞；为避免事件发布线程被 I/O 占用，
 *       读取被调度到 {@code boundedElastic} 执行。</li>
 * </ul>
 *
 * <p>快照在生成时完成两件事：把 ConfigMap 中的 Secret 引用解析成实际凭据
 * （{@link CredentialResolver}），以及校验取值合法性（{@link SettingsValidator}）。
 * 结论随快照一起传递，投递线程据此决定是否停止推送——见 {@link #apply(BarkSettings)}。
 */
@Slf4j
@Component
public class SettingsStore {

    private static final String PUSH_GROUP = "push";
    private static final String EVENTS_GROUP = "events";
    private static final Duration INITIAL_LOAD_TIMEOUT = Duration.ofSeconds(10);

    private final ReactiveSettingFetcher settingFetcher;
    private final CredentialResolver credentialResolver;

    /** 刷新序号：单调递增，用于丢弃乱序到达的过期刷新结果。 */
    private final AtomicLong latestSeq = new AtomicLong(-1);

    private volatile BarkSettings snapshot = BarkSettings.defaults();

    public SettingsStore(ReactiveSettingFetcher settingFetcher, CredentialResolver credentialResolver) {
        this.settingFetcher = settingFetcher;
        this.credentialResolver = credentialResolver;
    }

    /** 当前生效的配置快照。 */
    public BarkSettings current() {
        return snapshot;
    }

    /** 插件启动时加载一次配置。 */
    public void loadInitial() {
        try {
            var loaded = load().block(INITIAL_LOAD_TIMEOUT);
            apply(loaded == null ? BarkSettings.defaults() : loaded);
        } catch (RuntimeException e) {
            log.warn("{} 读取配置失败，本次使用默认配置：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            apply(BarkSettings.defaults());
        }
    }

    /** 配置变更后刷新快照，无需重启插件（PRD 5.7）。 */
    @EventListener
    public void onConfigUpdated(PluginConfigUpdatedEvent event) {
        // 捕获本次刷新的序号；结果到达时若已有更新的刷新，则丢弃本次，避免旧配置覆盖新配置。
        long seq;
        synchronized (latestSeq) {
            seq = latestSeq.incrementAndGet();
        }
        load().subscribeOn(Schedulers.boundedElastic())
                .subscribe(candidate -> applyIfLatest(seq, candidate),
                        e -> log.warn("{} 配置刷新失败，保留最后有效配置：{}",
                                BarkConstants.LOG_PREFIX, Masking.sanitize(e.toString())));
    }

    /**
     * 仅当本次刷新仍为最新时才生效。
     *
     * <p>配置保存会触发刷新事件，而刷新是异步的；若短时间内连续保存两次，两次加载可能乱序完成，
     * 后发出的加载先完成会把较早（已过期）的配置写回。靠单调递增序号丢弃过期结果。
     */
    void applyIfLatest(long seq, BarkSettings candidate) {
        synchronized (latestSeq) {
            if (seq != latestSeq.get()) {
                log.debug("{} 丢弃过期配置刷新（序号 {}，已有更新刷新生效）", BarkConstants.LOG_PREFIX, seq);
                return;
            }
            apply(candidate);
        }
    }

    private Mono<BarkSettings> load() {
        return Mono.zip(
                        settingFetcher.fetch(PUSH_GROUP, PushSettings.class)
                                .defaultIfEmpty(PushSettings.defaults()),
                        settingFetcher.fetch(EVENTS_GROUP, EventToggles.class)
                                .defaultIfEmpty(EventToggles.defaults()))
                // ConfigMap 中的设备 Key 与加密密钥是 Secret 名称，必须解析成实际值再进快照，
                // 否则投递层会拿名称当密钥使用；读不到的引用按级归因一并带入快照。
                .flatMap(tuple -> credentialResolver.resolve(tuple.getT1())
                        .map(resolution -> new BarkSettings(resolution.push(), tuple.getT2(),
                                resolution.blocking(), resolution.warnings())));
    }

    /**
     * 使一份候选配置生效。
     *
     * <p>始终采用新快照并把结论一并写入，不保留上一份配置：若保留旧快照，投递线程
     * 就无法感知「新配置不可用」这一状态。
     *
     * <p>两级结论（PRD 5.7）：**阻断级**（加密不可用，无法安全投递）让投递线程停止全部推送，
     * 日志以 ERROR 输出；**提示级**（服务地址笔误、长度 / 时间 / 音量非法）有确定的降级路径，
     * 只以 WARN 提示，不影响投递。
     */
    private void apply(BarkSettings candidate) {
        var report = SettingsValidator.inspect(candidate);
        var blocking = new ArrayList<>(candidate.issues());
        blocking.addAll(report.blocking());
        var warnings = new ArrayList<>(candidate.warnings());
        warnings.addAll(report.warnings());
        snapshot = new BarkSettings(candidate.push(), candidate.events(), blocking, warnings);
        if (!blocking.isEmpty()) {
            // 校验信息面向用户可读，其中含服务地址原文；写入日志前统一脱敏（PRD 5.8）
            log.error("{} 配置校验未通过，已停止全部推送。请到插件设置页修正以下问题：{}",
                    BarkConstants.LOG_PREFIX, Masking.sanitize(blocking.toString()));
        }
        if (!warnings.isEmpty()) {
            log.warn("{} 配置存在不影响推送的问题，相关取值已按降级处理：{}",
                    BarkConstants.LOG_PREFIX, Masking.sanitize(warnings.toString()));
        }
        if (blocking.isEmpty()) {
            log.info("{} 配置已生效：{}", BarkConstants.LOG_PREFIX, describe(candidate));
        }
    }

    /** 生成脱敏的配置摘要：只输出数量与开关状态，不含设备 Key 与加密密钥。 */
    private String describe(BarkSettings settings) {
        var push = settings.push();
        var events = settings.events();
        var deliverable = push.deliverableDevices();
        var serverCount = deliverable.stream().map(DeviceSetting::serverUrl).distinct().count();
        return ("分组=%s 设备=%d(可投递 %d, 服务地址 %d 个) 互动类级别=%s 普通类级别=%s 归档=%s/%s "
                + "加密=%s 合并窗口=%s 静默时段=%s 事件开关[评论=%s 回复=%s 待审核=%s 新设备=%s "
                + "发布=%s 注册=%s 删除=%s]").formatted(
                push.group().isBlank() ? "(站点名称)" : push.group(),
                push.devices().size(), deliverable.size(), serverCount,
                push.interactiveLevel(), push.normalLevel(), push.archiveEnabled(), push.archiveTtl(),
                push.encryptEnabled(), push.mergeWindow(), push.quietHoursEnabled(),
                events.newCommentEnabled(), events.newReplyEnabled(), events.commentPendingEnabled(),
                events.newDeviceLoginEnabled(),
                events.postPublishedEnabled(), events.userRegisteredEnabled(), events.postRemovedEnabled());
    }
}
