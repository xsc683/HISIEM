package com.xscsiem.hsiem_platform.agent;

/**
 * 统一表示 HISIEM-Agent 启动代理失败，避免把上游响应内容泄漏给浏览器。
 */
public class AgentLaunchException extends RuntimeException {

    private final int status;
    private final String code;

    public AgentLaunchException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public AgentLaunchException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
