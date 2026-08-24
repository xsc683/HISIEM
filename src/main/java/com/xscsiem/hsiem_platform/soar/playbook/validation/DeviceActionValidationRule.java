package com.xscsiem.hsiem_platform.soar.playbook.validation;

import com.xscsiem.hsiem_platform.soar.device.SoarConnector;
import com.xscsiem.hsiem_platform.soar.device.SoarConnectorRegistry;
import org.springframework.stereotype.Component;

@Component
public class DeviceActionValidationRule implements SoarPlaybookValidationRule {

    private final SoarConnectorRegistry connectors;

    public DeviceActionValidationRule(SoarConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void validate(SoarValidationContext context) {
        context.graph().nodes().stream().filter(node -> "connector".equals(node.type())).forEach(node -> {
            String runtimeKey = text(node.config().get("runtimeKey"));
            String action = text(node.config().get("action"));
            SoarConnector connector = connectors.require(runtimeKey);
            if (!connector.capabilities().contains(action)) {
                throw new IllegalArgumentException("Connector " + runtimeKey + " 不支持动作 " + action);
            }
        });
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
