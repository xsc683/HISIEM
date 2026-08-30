package com.xscsiem.hsiem_platform.soar.device;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Recursively removes secrets before connector results are persisted/audited. */
public final class ConnectorAuditSanitizer {

    private static final List<String> SENSITIVE = List.of(
            "password", "passwd", "secret", "token", "authorization", "api_key", "apikey", "private_key");

    private ConnectorAuditSanitizer() { }

    public static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), sensitive(String.valueOf(key))
                    ? "[REDACTED]" : sanitize(item)));
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            collection.forEach(item -> result.add(sanitize(item)));
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) result.add(sanitize(java.lang.reflect.Array.get(value, i)));
            return result;
        }
        return value;
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        return SENSITIVE.stream().anyMatch(normalized::contains);
    }
}
