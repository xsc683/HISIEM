package com.xscsiem.hsiem_platform.agent;

/** 浏览器跳转所需的最小 Agent 启动结果；不向浏览器暴露服务凭据。 */
public record AgentLaunchResponse(String runId, String redirectUrl) {
}
