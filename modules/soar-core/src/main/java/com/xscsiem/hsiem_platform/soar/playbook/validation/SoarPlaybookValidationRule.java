package com.xscsiem.hsiem_platform.soar.playbook.validation;

/** One independently registered publication gate. */
public interface SoarPlaybookValidationRule {

    default int order() {
        return 100;
    }

    void validate(SoarValidationContext context);
}
