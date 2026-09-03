package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** notifications persistence. SQL in {@code mybatis/control/NotificationMapper.xml}. */
@Mapper
public interface NotificationMapper {

    List<ControlPlaneRow.NotificationRow> selectAllNotifications();

    List<ControlPlaneRow.NotificationRow> selectUnreadNotifications();

    int countNotificationsSince(
            @Param("type") String type,
            @Param("target") String target,
            @Param("since") Instant since);

    int insertNotification(
            @Param("id") String id,
            @Param("type") String type,
            @Param("target") String target,
            @Param("message") String message);

    int markRead(@Param("id") String id);

    int markAllRead();

    int deleteById(@Param("id") String id);

    int deleteReadBefore(@Param("before") Instant before);
}
