package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Users / auth_sessions / login_attempts persistence (identity & access). SQL in {@code
 * mybatis/control/UserAuthMapper.xml}.
 */
@Mapper
public interface UserAuthMapper {

    List<ControlPlaneRow.UserRow> selectUsers();

    ControlPlaneRow.UserRow selectUser(@Param("username") String username);

    int insertUser(
            @Param("id") String id,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("passwordChangeRequired") boolean passwordChangeRequired,
            @Param("role") String role,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt);

    int updateUser(
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("passwordChangeRequired") boolean passwordChangeRequired,
            @Param("role") String role,
            @Param("status") String status);

    int deleteUser(@Param("username") String username);

    String selectSessionUsername(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    void deleteExpiredSession(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    void touchSession(@Param("tokenHash") String tokenHash);

    int insertSession(
            @Param("tokenHash") String tokenHash,
            @Param("username") String username,
            @Param("expiresAt") Instant expiresAt);

    void deleteSession(@Param("tokenHash") String tokenHash);

    void deleteSessionsForUser(@Param("username") String username);

    void deleteExpiredSessions(@Param("now") Instant now);

    int countActiveLoginBlock(@Param("username") String username, @Param("now") Instant now);

    List<ControlPlaneRow.LoginAttemptRow> selectLoginAttemptForUpdate(
            @Param("username") String username);

    int insertLoginAttempt(@Param("username") String username, @Param("now") Instant now);

    int updateLoginAttempt(
            @Param("username") String username,
            @Param("failures") int failures,
            @Param("firstFailureAt") Instant firstFailureAt,
            @Param("lockedUntil") Instant lockedUntil);

    void deleteLoginAttempts(@Param("username") String username);
}
