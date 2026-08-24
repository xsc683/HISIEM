package com.xscsiem.hsiem_platform.soar.playbook.validation;

import org.springframework.stereotype.Component;

/** Keeps condition-specific publication checks replaceable as the DSL grows. */
@Component
public class ConditionValidationRule implements SoarPlaybookValidationRule {

    @Override
    public int order() {
        return 60;
    }

    @Override
    public void validate(SoarValidationContext context) {
        context.graph().nodes().stream().filter(node -> "condition".equals(node.type()))
                .forEach(node -> context.handlers().require("condition").validate(context.entryType(), node));
    }
}
