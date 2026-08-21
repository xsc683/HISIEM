package com.xscsiem.hsiem_platform.onboarding;

import java.nio.charset.StandardCharsets;

/** 统一限制解析样例，避免接口把超大正文送入 Grok/日志。 */
public final class SampleSizeValidator {

    public static final int API_MAX_BYTES = 1024 * 1024;
    public static final int UI_MAX_BYTES = 8 * 1024;

    private SampleSizeValidator() {
    }

    public static void requireApiSample(String sample) {
        if (sample == null || sample.isBlank()) {
            throw new IllegalArgumentException("样例日志不能为空");
        }
        if (sample.getBytes(StandardCharsets.UTF_8).length > API_MAX_BYTES) {
            throw new IllegalArgumentException("样例日志不能超过 1 MiB");
        }
    }
}
