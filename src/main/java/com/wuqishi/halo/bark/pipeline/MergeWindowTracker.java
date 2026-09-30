package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.config.PushSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 合并窗口跟踪器（PRD 5.4 智能合并）。
 *
 * <p>按**判定语义**分桶：窗口内前 {@code MERGE_THRESHOLD - 1} 条立即送达，其后进入暂存；
 * 暂存达到 {@code MERGE_THRESHOLD - 1} 条即产出「合并摘要」并重置窗口——正常间隔的通知
 * 零延迟，只有真正的爆发才合并。暂存仅 1 条时不做摘要（「1 条」的摘要毫无意义），
 * 留到窗口结束时按原样补发。
 *
 * <p>从 {@link NoiseReducer} 抽出，使降噪状态机保持单一职责并满足单类行数约束（PRD 10.4.4）。
 *
 * <p>线程安全：可变状态由单一锁保护——投递线程与冲刷定时器线程都会访问。
 */
final class MergeWindowTracker {

    /** 合并阈值：窗口内累计达到该条数时，其后的条目进入合并。 */
    static final int MERGE_THRESHOLD = 3;

    private static final Pattern MERGE_WINDOW_PATTERN = Pattern.compile("^(\\d+)m$");
    private static final Duration DEFAULT_MERGE_WINDOW = Duration.ofMinutes(2);

    private final Clock clock;
    private final Object lock = new Object();
    private final Map<Judgement, Window> windows = new LinkedHashMap<>();

    MergeWindowTracker(Clock clock) {
        this.clock = clock;
    }

    /**
     * 提交一条通知。
     *
     * @return 本次提交产生的动作；{@link Action#immediate()} 非空表示需立即送达，
     *     {@link Action#merged()} 非空表示需合并为一条摘要后送达
     */
    Action submit(Judgement judgement, BufferedNotification item, PushSettings settings) {
        BufferedNotification immediate = null;
        List<BufferedNotification> merged = null;
        List<BufferedNotification> expiredSingles = List.of();
        synchronized (lock) {
            var now = clock.instant();
            var window = windows.get(judgement);
            if (window != null && !now.isBefore(window.expiresAt())) {
                // 窗口已到期但冲刷尚未跑到：先取出其暂存，否则新窗口会覆盖它、暂存条目永久丢失
                var pending = window.held;
                if (pending.size() >= MERGE_THRESHOLD - 1) {
                    merged = List.copyOf(pending);
                } else if (pending.size() == 1) {
                    expiredSingles = List.of(pending.get(0));
                }
                windows.remove(judgement);
                window = null;
            }
            if (window == null) {
                window = new Window(now, windowOf(settings));
                windows.put(judgement, window);
            }
            if (window.immediate < MERGE_THRESHOLD - 1) {
                window.immediate++;
                immediate = item;
            } else {
                window.held.add(item);
                if (window.held.size() >= MERGE_THRESHOLD - 1) {
                    merged = List.copyOf(window.held);
                    windows.put(judgement, new Window(now, window.duration));
                }
            }
        }
        return new Action(immediate, merged, expiredSingles);
    }

    /**
     * 取走已到期窗口的产出。
     *
     * <p>暂存 ≥ {@code MERGE_THRESHOLD - 1} 条的进 {@link Expired#merged()}（合并为摘要），
     * 恰好 1 条的进 {@link Expired#singles()}（按原样补发）。
     */
    Expired drainExpired() {
        List<Map.Entry<Judgement, List<BufferedNotification>>> merged = new ArrayList<>();
        List<BufferedNotification> singles = new ArrayList<>();
        synchronized (lock) {
            var now = clock.instant();
            var iterator = windows.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var window = entry.getValue();
                if (now.isBefore(window.expiresAt())) {
                    continue;
                }
                if (window.held.size() >= MERGE_THRESHOLD - 1) {
                    merged.add(Map.entry(entry.getKey(), List.copyOf(window.held)));
                } else if (window.held.size() == 1) {
                    singles.add(window.held.get(0));
                }
                iterator.remove();
            }
        }
        return new Expired(merged, singles);
    }

    /**
     * 清空所有窗口的暂存条目（不产出、不补发），返回被丢弃的条目数。
     *
     * <p>供 {@link NoiseReducer#stop()} 在关闭时调用，使丢弃量可被显式记录。
     */
    int discardPending() {
        synchronized (lock) {
            int total = 0;
            for (var window : windows.values()) {
                total += window.held.size();
                window.held.clear();
            }
            windows.clear();
            return total;
        }
    }

    /**
     * 合并窗口时长：配置形如 {@code "2m"}，缺失 / 非法 / 越界 / 非正时回退默认值。
     *
     * <p>{@code Duration.ofMinutes(Long.parseLong(...))} 对超长数字（如 21 位）会抛
     * {@code NumberFormatException} / {@code ArithmeticException}，且 {@code "0m"} 会让窗口瞬间到期、
     * 退化为「每条都触发到期冲刷」失去合并意义。这些非法值不应让一次普通投递异常丢事件，故一律回退默认。
     */
    static Duration windowOf(PushSettings settings) {
        var raw = settings.mergeWindow();
        if (raw == null) {
            return DEFAULT_MERGE_WINDOW;
        }
        var matcher = MERGE_WINDOW_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            return DEFAULT_MERGE_WINDOW;
        }
        long minutes;
        try {
            minutes = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            // 超过 long 范围的分钟数无法解析，回退默认而非抛出
            return DEFAULT_MERGE_WINDOW;
        }
        if (minutes <= 0) {
            return DEFAULT_MERGE_WINDOW;
        }
        try {
            return Duration.ofMinutes(minutes);
        } catch (ArithmeticException e) {
            // minutes 在 long 范围内但换算成秒后溢出 Duration 上限，回退默认
            return DEFAULT_MERGE_WINDOW;
        }
    }

    /**
     * 一次提交产生的动作。
     *
     * @param immediate 需立即送达的条目；为 {@code null} 表示本条已进入暂存
     * @param merged 需合并为一条摘要的暂存条目；为 {@code null} 表示本次未触发合并
     * @param expiredSingles 因窗口到期而需按原样补发的单条（{@link #submit} 抢占了冲刷的工作）；
     *     未发生抢占时为空列表
     */
    record Action(BufferedNotification immediate, List<BufferedNotification> merged,
            List<BufferedNotification> expiredSingles) {
    }

    /**
     * 已到期窗口的产出。
     *
     * @param merged 需合并为摘要的桶
     * @param singles 需按原样补发的单条
     */
    record Expired(List<Map.Entry<Judgement, List<BufferedNotification>>> merged,
            List<BufferedNotification> singles) {
    }

    /** 一个合并窗口：起点、时长、已即时送达数与暂存条目。 */
    private static final class Window {

        private final Instant startedAt;
        private final Duration duration;
        private int immediate;
        private final List<BufferedNotification> held = new ArrayList<>();

        Window(Instant startedAt, Duration duration) {
            this.startedAt = startedAt;
            this.duration = duration;
        }

        Instant expiresAt() {
            return startedAt.plus(duration);
        }
    }
}
