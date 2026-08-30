package com.xscsiem.hsiem_platform.soar.playbook.validation;

import org.springframework.stereotype.Component;

@Component
public class NodeTypeValidationRule implements SoarPlaybookValidationRule {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void validate(SoarValidationContext context) {
        context.graph().nodes().forEach(node -> context.handlers().require(node.type()));
    }
}
