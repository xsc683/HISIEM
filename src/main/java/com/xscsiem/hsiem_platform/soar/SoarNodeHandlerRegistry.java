package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SoarNodeHandlerRegistry {

    private final Map<String, SoarNodeHandler> handlers;

    public SoarNodeHandlerRegistry(List<SoarNodeHandler> candidates) {
        Map<String, SoarNodeHandler> registered = new LinkedHashMap<>();
        for (SoarNodeHandler handler : candidates) {
            if (handler.type() == null || handler.type().isBlank()) {
                throw new IllegalStateException("SOAR NodeHandler type 不能为空");
            }
            SoarNodeHandler previous = registered.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("SOAR NodeHandler 重复注册: " + handler.type());
            }
        }
        handlers = Map.copyOf(registered);
    }

    public SoarNodeHandler require(String type) {
        SoarNodeHandler handler = handlers.get(type);
        if (handler == null) throw new IllegalArgumentException("不支持的节点类型: " + type);
        return handler;
    }

    public Set<String> types() {
        return handlers.keySet();
    }
}
