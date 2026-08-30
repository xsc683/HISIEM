package com.xscsiem.hsiem_platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthUserViewTest {

    @Test
    void responseViewDoesNotExposePasswordHash() {
        AuthUser user = new AuthUser();
        user.id = "u-1";
        user.username = "analyst";
        user.passwordHash = "$2a$10$never-return-this";
        user.role = "analyst";
        user.status = "active";
        user.passwordChangeRequired = true;

        AuthUserView view = AuthUserView.from(user);
        assertEquals("analyst", view.username());
        assertEquals(true, view.passwordChangeRequired());
        try {
            String json = new ObjectMapper().writeValueAsString(view);
            assertFalse(json.contains("passwordHash"));
            assertFalse(json.contains("never-return-this"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
