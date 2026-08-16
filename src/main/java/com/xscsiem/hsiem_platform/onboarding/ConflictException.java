package com.xscsiem.hsiem_platform.onboarding;

/** 并发冲突(409):乐观锁版本不匹配(如两人同时处置同一告警)。 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
