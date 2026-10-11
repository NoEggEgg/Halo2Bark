package com.wuqishi.halo.bark.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Parses Bark push acknowledgements without trusting HTTP 2xx alone as proof of delivery. */
final class BarkPushResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BarkPushResponse() {
    }

    /** Returns null when the response cannot be safely associated with the requested devices. */
    static Parsed parse(String body, List<String> keys) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject() || !integer(root.get("code"))) {
                return null;
            }
            int code = root.get("code").intValue();
            if (code != 200 || keys.size() == 1) {
                return new Parsed(code, List.of());
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.size() != keys.size()) {
                return null;
            }
            var requested = new HashSet<>(keys);
            if (requested.size() != keys.size()) {
                return null;
            }
            var codesByKey = new HashMap<String, Integer>();
            for (JsonNode entry : data) {
                if (!entry.isObject() || !integer(entry.get("code"))) {
                    return null;
                }
                String key = entry.path("device_key").textValue();
                if (!requested.contains(key)
                        || codesByKey.putIfAbsent(key, entry.get("code").intValue()) != null) {
                    return null;
                }
            }
            if (codesByKey.size() != keys.size()) {
                return null;
            }
            return new Parsed(code, keys.stream().map(codesByKey::get).toList());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean integer(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt();
    }

    record Parsed(int code, List<Integer> deviceCodes) {
    }
}
