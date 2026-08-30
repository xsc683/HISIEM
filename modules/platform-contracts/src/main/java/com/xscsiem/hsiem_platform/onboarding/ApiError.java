package com.xscsiem.hsiem_platform.onboarding;

import java.time.Instant;

/** 所有 HTTP 失败响应的统一结构。 */
public record ApiError(Instant timestamp, int status, String code, String message, String path) {
}
