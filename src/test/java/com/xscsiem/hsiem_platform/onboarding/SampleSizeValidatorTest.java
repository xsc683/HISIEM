package com.xscsiem.hsiem_platform.onboarding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SampleSizeValidatorTest {

    @Test
    void apiAcceptsNormalUtf8AndRejectsOversizedOrBlank() {
        assertDoesNotThrow(() -> SampleSizeValidator.requireApiSample("登录失败"));
        assertThrows(IllegalArgumentException.class, () -> SampleSizeValidator.requireApiSample(" "));
        assertThrows(IllegalArgumentException.class,
                () -> SampleSizeValidator.requireApiSample("x".repeat(SampleSizeValidator.API_MAX_BYTES + 1)));
    }
}
