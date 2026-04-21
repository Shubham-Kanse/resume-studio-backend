package com.resumestudio.auth;

import com.resumestudio.auth.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
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
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return unauthorized("Missing token");
        try {
            SupabaseJwtVerifier.UserClaims claims = verifier.verify(authHeader.substring(7));
            User user = userService.getOrCreate(claims.userId());
            return ResponseEntity.ok(userPayload(user, claims.email()));
        } catch (IllegalArgumentException e) {
            return unauthorized(e.getMessage());
        } catch (Exception e) {
            return unauthorized("Invalid token");
        }
    }

    @GetMapping("/settings")
    public ResponseEntity<?> settings(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return unauthorized("Unauthorized");
        try {
            SupabaseJwtVerifier.UserClaims claims = verifier.verify(authHeader.substring(7));
            User user = userService.getOrCreate(claims.userId());
            return ResponseEntity.ok(userPayload(user, claims.email()));
        } catch (IllegalArgumentException e) {
            return unauthorized(e.getMessage());
        } catch (Exception e) {
            return unauthorized("Invalid token");
        }
    }

    @PatchMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                            @RequestBody Map<String, Object> body) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return unauthorized("Unauthorized");
        try {
            SupabaseJwtVerifier.UserClaims claims = verifier.verify(authHeader.substring(7));
            User user = userService.getOrCreate(claims.userId());

            if (body.containsKey("reminderEmailsEnabled")) {
                user.setReminderEmailsEnabled(asBoolean(body.get("reminderEmailsEnabled"), "reminderEmailsEnabled"));
            }
            if (body.containsKey("reminderFrequencyDays")) {
                int days = asInt(body.get("reminderFrequencyDays"), "reminderFrequencyDays");
                if (days < 1 || days > 14) return badRequest("reminderFrequencyDays must be between 1 and 14");
                user.setReminderFrequencyDays(days);
            }
            if (body.containsKey("reminderTimezone")) {
                String tz = asString(body.get("reminderTimezone"));
                if (tz == null || tz.isBlank()) return badRequest("reminderTimezone is required");
                try {
                    ZoneId.of(tz);
                } catch (Exception e) {
                    return badRequest("Invalid reminderTimezone");
                }
                user.setReminderTimezone(tz);
            }
            if (body.containsKey("quietHoursEnabled")) {
                user.setQuietHoursEnabled(asBoolean(body.get("quietHoursEnabled"), "quietHoursEnabled"));
            }
            if (body.containsKey("quietHoursStart")) {
                int start = asInt(body.get("quietHoursStart"), "quietHoursStart");
                if (start < 0 || start > 23) return badRequest("quietHoursStart must be between 0 and 23");
                user.setQuietHoursStart(start);
            }
            if (body.containsKey("quietHoursEnd")) {
                int end = asInt(body.get("quietHoursEnd"), "quietHoursEnd");
                if (end < 0 || end > 23) return badRequest("quietHoursEnd must be between 0 and 23");
                user.setQuietHoursEnd(end);
            }

            userService.save(user);
            return ResponseEntity.ok(userPayload(user, claims.email()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return unauthorized("Invalid token");
        }
    }

    /**
     * DELETE /api/auth/account
     * Deletes all user data from our DB. The frontend handles Supabase-side deletion
     * via supabase.auth.admin — or we simply wipe our records and sign the user out.
     */
    @DeleteMapping("/account")
    public ResponseEntity<?> deleteAccount(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return unauthorized("Unauthorized");
        try {
            SupabaseJwtVerifier.UserClaims claims = verifier.verify(authHeader.substring(7));
            userService.deleteUser(claims.userId());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return unauthorized(e.getMessage());
        } catch (Exception e) {
            return unauthorized("Invalid token");
        }
    }

    private static Map<String, Object> userPayload(User user, String email) {
        String safeEmail = email == null ? "" : email;
        String safePlan = user.getPlan() == null ? "FREE" : user.getPlan().name();
        return Map.of(
            "userId", user.getId(),
            "email", safeEmail,
            "plan", safePlan,
            "reminderEmailsEnabled", user.isReminderEmailsEnabled(),
            "reminderFrequencyDays", user.getReminderFrequencyDays(),
            "reminderTimezone", user.getReminderTimezone(),
            "quietHoursEnabled", user.isQuietHoursEnabled(),
            "quietHoursStart", user.getQuietHoursStart(),
            "quietHoursEnd", user.getQuietHoursEnd()
        );
    }

    private static ResponseEntity<Map<String, String>> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", message));
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }

    private static String asString(Object value) {
        if (value == null) return null;
        return String.valueOf(value);
    }

    private static boolean asBoolean(Object value, String field) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        throw new IllegalArgumentException("Invalid boolean value for " + field);
    }

    private static int asInt(Object value, String field) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new IllegalArgumentException("Invalid number value for " + field);
    }
}
