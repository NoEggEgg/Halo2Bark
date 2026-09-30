package com.wuqishi.halo.bark.event;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/**
 * 「对象创建即采集」Reconciler 的公共骨架。
 *
 * <p>三条硬性语义集中在此实现，避免各处重复出错：
 * <ul>
 *   <li>{@code syncAllOnStart(false)}：**跳过历史对象**。Reconciler 默认会在启动时对已存在对象
 *       全量 reconcile 一次；若不关闭，启用插件的瞬间会涌入大量「新用户注册」「新设备登录」。</li>
 *   <li>{@code onUpdateMatcher / onDeleteMatcher -> false}：**只处理创建**。对象更新与删除不触发
 *       采集，否则修改用户资料、刷新设备时间戳都会推送。</li>
 *   <li>异常就地消化并记日志，绝不抛给事件总线（PRD 10.4.3）。其中 {@code client.fetch}
 *       <b>瞬时失败</b>（存储抖动）必须 {@code requeue} 重试——否则评论/回复/用户/设备事件会被
 *       永久丢弃；而对象已不存在（读取竞态或已删除）与投影失败则直接结束，避免无限重试打爆日志。</li>
 * </ul>
 *
 * <p>本类不做任何超出必要的 I/O：仅按名取出对象并交给子类投影为 {@link SiteEvent}。
 * 链接获取、系统设置读取等一律留给投递线程上的流水线（PRD 10.4.1）。
 *
 * @param <T> 被监听的扩展对象类型
 */
@Slf4j
abstract class CreateReconciler<T extends Extension> implements Reconciler<Reconciler.Request> {

    private final ExtensionClient client;
    private final Class<T> type;
    private final Supplier<T> prototype;

    protected CreateReconciler(ExtensionClient client, Class<T> type, Supplier<T> prototype) {
        this.client = client;
        this.type = type;
        this.prototype = prototype;
    }

    @Override
    public Result reconcile(Request request) {
        T extension;
        try {
            var fetched = client.fetch(type, request.name());
            if (fetched.isEmpty()) {
                log.debug("{} 采集 {} 时对象不存在（可能已删除或读取竞态），跳过：{}",
                        BarkConstants.LOG_PREFIX, type.getSimpleName(), request.name());
                return Result.doNotRetry();
            }
            extension = fetched.get();
        } catch (RuntimeException e) {
            // 瞬时存储抖动：必须重试，否则评论/回复/用户/设备事件被永久丢弃
            log.warn("{} 采集 {} 失败（将重试）：{}",
                    BarkConstants.LOG_PREFIX, type.getSimpleName(), Masking.sanitize(e.toString()));
            return Result.requeue(Duration.ofSeconds(10));
        }
        try {
            onCreated(extension);
        } catch (RuntimeException e) {
            // 投影失败：多为程序缺陷，重试无意义且会打爆日志，记录后丢弃
            log.error("{} 投影 {} 失败（已采集但无法通知）：{}",
                    BarkConstants.LOG_PREFIX, type.getSimpleName(), Masking.sanitize(e.toString()));
        }
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(prototype.get())
                .syncAllOnStart(false)
                .onUpdateMatcher(extension -> false)
                .onDeleteMatcher(extension -> false)
                .build();
    }

    /** 子类把对象投影为 {@link SiteEvent} 并交给调度器；实现不得执行 I/O。 */
    protected abstract void onCreated(T extension);

    /**
     * 评论 / 回复的展示时间：优先 {@code spec.creationTime}，缺失时回退 {@code metadata.creationTimestamp}。
     *
     * <p>必要性：通过扩展 API 直接创建的评论不会填 {@code spec.creationTime}，而 {@code metadata}
     * 的时间戳恒有值——回退可保证通知的「谁 · 何时」始终带时间。
     */
    protected static Instant displayTime(Instant specTime, MetadataOperator metadata) {
        if (specTime != null) {
            return specTime;
        }
        return metadata == null ? null : metadata.getCreationTimestamp();
    }
}
