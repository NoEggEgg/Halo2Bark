package com.wuqishi.halo.bark.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 极小的 JSON 读写封装，仅覆盖本插件用到的两种形态：扁平参数对象的序列化、单字段的读取。
 *
 * <p><b>为什么用 {@code com.fasterxml.jackson}（Jackson 2）</b>：
 * <ul>
 *   <li>Halo 2.26.1 的 api 模块以 compile 作用域依赖 {@code spring-boot-jackson2}，
 *       该库已在插件编译期类路径上，无需新增依赖、无需自行指定版本（PRD 10.4.4）。</li>
 *   <li>运行时可见性由父类加载器提供：未自带 Jackson 的插件，其字节码直接引用
 *       {@code com/fasterxml/jackson/databind/ObjectMapper} 仍能正常运行。</li>
 * </ul>
 *
 * <p>不使用 {@code run.halo.app.infra.utils.JsonUtils}：该工具自 2.23.0 起标记
 * {@code @Deprecated(forRemoval = true)}，官方注释要求改为直接使用 Jackson。
 * 本类不引用 Halo 的 API，因此传输层在无 Halo 环境下也可测试（PRD 10.4.5）。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /**
     * 序列化扁平参数对象。
     *
     * <p>使用 {@code LinkedHashMap} 传入时保持插入顺序，便于在服务端日志中人工核对参数。
     *
     * @throws IllegalStateException 序列化失败；调用方无需处理业务降级，属编程错误
     */
    public static String write(Map<String, Object> params) {
        try {
            return MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化推送参数失败", e);
        }
    }

    /**
     * 读取顶层字符串字段。
     *
     * @param json 响应体；为 {@code null}、空白或非对象时返回空字符串
     * @param field 字段名
     * @return 字段值；字段缺失或不是文本节点时返回空字符串
     */
    public static String field(String json, String field) {
        if (json == null || json.isBlank()) {
            return "";
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return "";
        }
        var node = root == null ? null : root.get(field);
        // 仅接受文本节点：Bark 的 version 恒为字符串；非文本（如 {"version":2}）一律视为缺失，
        // 避免把数字误判为 V2 而选错协议。
        return node != null && node.isTextual() ? node.asText("").trim() : "";
    }
}
