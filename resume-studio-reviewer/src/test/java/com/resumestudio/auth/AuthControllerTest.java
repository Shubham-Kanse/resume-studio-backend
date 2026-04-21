package com.resumestudio.auth;

import com.resumestudio.auth.model.Plan;
import com.resumestudio.auth.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private SupabaseJwtVerifier verifier;
    private UserService userService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(SupabaseJwtVerifier.class);
        userService = mock(UserService.class);
        controller = new AuthController(verifier, userService);
        when(userService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void missingHeader_returns401() {
        var r = controller.validate(null);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    @Test
    void headerWithoutBearer_returns401() {
        var r = controller.validate("Basic abc123");
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    @Test
    void invalidToken_illegalArgument_returns401() {
        when(verifier.verify("bad")).thenThrow(new IllegalArgumentException("Invalid token signature"));
        var r = controller.validate("Bearer bad");
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        assertTrue(r.getBody().toString().contains("Invalid token signature"));
    }

    @Test
    void invalidToken_runtimeException_returns401WithGenericMessage() {
        when(verifier.verify("bad")).thenThrow(new RuntimeException("internal error"));
        var r = controller.validate("Bearer bad");
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        // Must not leak internal message
        assertFalse(r.getBody().toString().contains("internal error"));
        assertTrue(r.getBody().toString().contains("Invalid token"));
    }

    @Test
    void validToken_returnsUserPlan() {
        var claims = new SupabaseJwtVerifier.UserClaims("uid-1", "user@example.com");
        when(verifier.verify("good-token")).thenReturn(claims);
        var user = new User("uid-1");
        user.setPlan(Plan.PRO);
        when(userService.getOrCreate("uid-1")).thenReturn(user);

        var r = controller.validate("Bearer good-token");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getBody();
        assertNotNull(body);
        assertEquals("uid-1", body.get("userId"));
        assertEquals("user@example.com", body.get("email"));
        assertEquals("PRO", body.get("plan"));
    }

    @Test
    void validToken_newUser_returnsFreeplan() {
        var claims = new SupabaseJwtVerifier.UserClaims("uid-new", "new@example.com");
        when(verifier.verify("new-token")).thenReturn(claims);
        when(userService.getOrCreate("uid-new")).thenReturn(new User("uid-new"));

        var r = controller.validate("Bearer new-token");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getBody();
        assertEquals("FREE", body.get("plan"));
    }

    @Test
    void settings_validToken_returnsReminderSettings() {
        var claims = new SupabaseJwtVerifier.UserClaims("uid-1", "user@example.com");
        when(verifier.verify("good-token")).thenReturn(claims);
        var user = new User("uid-1");
        user.setPlan(Plan.BASIC);
        user.setReminderFrequencyDays(5);
        user.setReminderTimezone("Europe/Dublin");
        user.setQuietHoursEnabled(true);
        user.setQuietHoursStart(23);
        user.setQuietHoursEnd(7);
        when(userService.getOrCreate("uid-1")).thenReturn(user);

        var r = controller.settings("Bearer good-token");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getBody();
        assertEquals("BASIC", body.get("plan"));
        assertEquals(true, body.get("quietHoursEnabled"));
        assertEquals(5, body.get("reminderFrequencyDays"));
    }

    @Test
    void updateSettings_invalidTimezone_returns400() {
        var claims = new SupabaseJwtVerifier.UserClaims("uid-1", "user@example.com");
        when(verifier.verify("good-token")).thenReturn(claims);
        when(userService.getOrCreate("uid-1")).thenReturn(new User("uid-1"));

        var r = controller.updateSettings("Bearer good-token", Map.of("reminderTimezone", "Mars/Olympus"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    @Test
    void updateSettings_validPatch_updatesAndReturnsPayload() {
        var claims = new SupabaseJwtVerifier.UserClaims("uid-1", "user@example.com");
        when(verifier.verify("good-token")).thenReturn(claims);
        var user = new User("uid-1");
        when(userService.getOrCreate("uid-1")).thenReturn(user);

        var r = controller.updateSettings("Bearer good-token", Map.of(
            "reminderEmailsEnabled", false,
            "reminderFrequencyDays", 7,
            "reminderTimezone", "UTC",
            "quietHoursEnabled", true,
            "quietHoursStart", 22,
            "quietHoursEnd", 8
        ));
        assertEquals(HttpStatus.OK, r.getStatusCode());
        verify(userService).save(user);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getBody();
        assertEquals(false, body.get("reminderEmailsEnabled"));
        assertEquals(7, body.get("reminderFrequencyDays"));
    }
}
