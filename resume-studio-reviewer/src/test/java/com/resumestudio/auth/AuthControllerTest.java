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
        var user = new User("uid-1", "user@example.com");
        user.setPlan(Plan.PRO);
        when(userService.getOrCreate("uid-1", "user@example.com")).thenReturn(user);

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
        when(userService.getOrCreate("uid-new", "new@example.com")).thenReturn(new User("uid-new", "new@example.com"));

        var r = controller.validate("Bearer new-token");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getBody();
        assertEquals("FREE", body.get("plan"));
    }
}
