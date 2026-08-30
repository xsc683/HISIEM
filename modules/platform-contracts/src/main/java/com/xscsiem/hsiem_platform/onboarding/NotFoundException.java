package com.xscsiem.hsiem_platform.onboarding;

/** 资源不存在 → HTTP 404(统一异常处理)。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
