package com.xscsiem.hsiem_platform.soar.playbook.validation;

import org.springframework.stereotype.Component;

import java.util.Locale;

/** Prevents graphs that validate after normalization but fail exact runtime routing. */
@Component
public class EdgePortValidationRule implements SoarPlaybookValidationRule {

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void validate(SoarValidationContext context) {
        context.graph().edges().forEach(edge -> {
            String branch = edge.branch() == null ? "" : edge.branch().trim();
            if (branch.isBlank() || !branch.equals(branch.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("连线 branch 必须使用非空小写标识: " + edge.id());
            }
        });
    }
}
