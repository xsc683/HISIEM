package com.xscsiem.hsiem_platform.soar;

/** The worker no longer owns the execution lease and must not mutate durable state. */
public class SoarLeaseLostException extends RuntimeException {

    public SoarLeaseLostException(String executionId) {
        super("SOAR 执行租约已失效: " + executionId);
    }
}
