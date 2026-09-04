package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 通知有界上下文的持久化端口。由控制面 MyBatis 实现({@link MyBatisControlPlaneStore})提供, 是 {@link ControlPlaneStore}
 * 的子面之一。
 */
public interface NotificationStore {

    List<Map<String, Object>> listNotifications(Boolean unread);

    boolean createNotificationIfAllowed(
            String type, String target, String message, Instant notBefore);

    boolean markNotificationRead(String id);

    void markAllNotificationsRead();

    boolean deleteNotification(String id);

    /** 删除已读且超过保留期的通知，返回删除数。 */
    int deleteReadNotificationsBefore(Instant before);
}
