package com.wuqishi.halo.bark.config;

/**
 * 配置值的宽松归一化。
 *
 * <p>存在的理由：Halo 把 FormKit 表单值按分组写入插件 ConfigMap，但 {@code $formkit: number}
 * 字段的存储类型可能是 JSON 数字（{@code 24}），也可能是 JSON 字符串
 * （{@code "6"}）。因此数值字段一律以字符串承载并在使用处宽松解析，避免反序列化直接失败。
 * 同理，布尔字段在缺失时按 PRD 声明的默认值补齐，保证下游永远拿不到 {@code null}。
 *
 * <p>本类为跨层工具：配置层以外（如传输层换算归档时长）同样需要这套宽松解析，故对外可见。
 */
public final class ConfigValues {

    private ConfigValues() {
    }

    /** 空值或空白时回退到默认值。 */
    public static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 仅在值为 {@code null} 时回退默认值；空白保留原样。
     *
     * <p>用于「有校验规则、留空必须被判为不合法」的字段——若在此把空白归一化为默认值，
     * 对应校验分支将永远不可达（PRD 5.3、5.7：空白表示配置不完整，须与「字段缺失取默认值」区分开）。
     */
    public static String textUnlessNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** 空值时归一化为空字符串。 */
    public static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 空值时回退为 true。 */
    public static Boolean flagOrTrue(Boolean value) {
        return value == null ? Boolean.TRUE : value;
    }

    /** 空值时回退为 false。 */
    public static Boolean flagOrFalse(Boolean value) {
        return value == null ? Boolean.FALSE : value;
    }

    /**
     * 宽松解析整数，同时接受 JSON 数字与数字字符串。
     *
     * @return 解析结果；空白、非数字或超出 {@code int} 范围时返回 {@code null}
     */
    public static Integer intOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
