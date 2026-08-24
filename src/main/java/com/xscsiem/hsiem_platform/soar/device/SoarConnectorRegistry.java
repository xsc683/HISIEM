package com.xscsiem.hsiem_platform.soar.device;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Spring-assembled registry; adding a connector does not change the engine. */
@Component
public class SoarConnectorRegistry {

    private final Map<String, SoarConnector> connectors;

    public SoarConnectorRegistry(List<SoarConnector> candidates) {
        Map<String, SoarConnector> registered = new LinkedHashMap<>();
        for (SoarConnector connector : candidates) {
            String key = connector.runtimeKey();
            if (key == null || key.isBlank()) throw new IllegalStateException("Connector runtimeKey 不能为空");
            if (registered.put(key, connector) != null) {
                throw new IllegalStateException("Connector 重复注册: " + key);
            }
        }
        connectors = Map.copyOf(registered);
    }

    public SoarConnector require(String runtimeKey) {
        SoarConnector connector = connectors.get(runtimeKey);
        if (connector == null) throw new IllegalArgumentException("不支持的 Connector: " + runtimeKey);
        return connector;
    }

    public Set<String> runtimeKeys() {
        return connectors.keySet();
    }
}
