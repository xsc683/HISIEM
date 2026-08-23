package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SoarTemplateResolver {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");

    public Map<String, Object> resolveMap(Map<?, ?> input, Map<String, Object> context) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(String.valueOf(key), resolve(value, context)));
        return result;
    }

    private Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof Map<?, ?> map) return resolveMap(map, context);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(resolve(item, context)));
            return result;
        }
        if (!(value instanceof String text)) return value;
        Matcher exact = TOKEN.matcher(text);
        if (exact.matches()) return requireValue(context, exact.group(1));
        Matcher matcher = TOKEN.matcher(text);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            Object replacement = requireValue(context, matcher.group(1));
            matcher.appendReplacement(output, Matcher.quoteReplacement(String.valueOf(replacement)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private Object requireValue(Map<String, Object> context, String path) {
        Object current = context;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                throw new IllegalArgumentException("模板变量不存在: ${" + path + "}");
            }
            current = map.get(part);
        }
        if (current == null) throw new IllegalArgumentException("模板变量为空: ${" + path + "}");
        return current;
    }
}
