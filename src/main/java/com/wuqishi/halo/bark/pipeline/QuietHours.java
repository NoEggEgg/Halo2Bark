package com.wuqishi.halo.bark.pipeline;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * 静默时段判定（PRD 5.4）。
 *
 * <p>支持**跨天**区间，例如 {@code 23:00–07:00}：此时「静默」跨越午夜，
 * 判定条件为 {@code now >= 23:00 或 now < 07:00}。
 *
 * <p>纯函数，便于单测；时间来源由调用方传入。
 */
public final class QuietHours {

    // STRICT：{@code 24:00} 这类越界值按非法处理（返回 null → 该边界失效），而非被 SMART 解析器
    // 翻转为 00:00 导致跨天静默区间被静默反转（例如「00:00–24:00」本意全天静默，翻转后变成不静默）。
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    /**
     * @param now 当前本地时间
     * @param from 静默开始（{@code HH:mm}）
     * @param to 静默结束（{@code HH:mm}）
     * @return 是否处于静默时段；任一时间缺失/非法、或起止相同（视为未设置）时返回 {@code false}
     */
    public static boolean isQuiet(LocalTime now, String from, String to) {
        var start = parse(from);
        var end = parse(to);
        if (now == null || start == null || end == null || start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            // 同日区间：[start, end)
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 跨天区间：[start, 24:00) ∪ [00:00, end)
        return !now.isBefore(start) || now.isBefore(end);
    }

    public static LocalTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim(), FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private QuietHours() {
    }
}
