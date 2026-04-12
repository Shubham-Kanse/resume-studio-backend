package com.resumestudio.auth;

import com.resumestudio.auth.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SupabaseJwtVerifier verifier;
    private final UserService userService;

    public AuthController(SupabaseJwtVerifier verifier, UserService userService) {
        this.verifier = verifier;
        this.userService = userService;
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Missing token"));
        }
        try {
            String jwt = authHeader.substring(7);
            SupabaseJwtVerifier.UserClaims claims = verifier.verify(jwt);
            User user = userService.getOrCreate(claims.userId(), claims.email());
            return ResponseEntity.ok(Map.of(
                "userId", user.getId(),
                "email", user.getEmail(),
                "plan", user.getPlan().name()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token"));
        }
    }

    /**
     * DELETE /api/auth/account
     * Deletes all user data from our DB. The frontend handles Supabase-side deletion
     * via supabase.auth.admin — or we simply wipe our records and sign the user out.
     */
    @DeleteMapping("/account")
    public ResponseEntity<?> deleteAccount(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        try {
            SupabaseJwtVerifier.UserClaims claims = verifier.verify(authHeader.substring(7));
            userService.deleteUser(claims.userId());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token"));
        }
    }
}
