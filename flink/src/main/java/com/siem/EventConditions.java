package com.siem;

/**
 * 事件动作判定:供 CEP 规则与单测复用。
 */
public final class EventConditions {

    private EventConditions() {
    }

    public static boolean isAuthenticationFailure(Event e) {
        return "authentication_failure".equals(e.getFields().get("event.action"));
    }

    public static boolean isAuthenticationSuccess(Event e) {
        return "authentication_success".equals(e.getFields().get("event.action"));
    }
}
