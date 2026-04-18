package com.resumestudio.auth;

import com.resumestudio.auth.model.Plan;
import com.resumestudio.auth.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Stripe Checkout integration.
 *
 * POST /api/billing/checkout  — creates a Stripe Checkout Session and returns the URL.
 * POST /api/billing/webhook   — handles Stripe webhook events (plan upgrades/cancellations).
 *
 * Price IDs are set via environment variables:
 *   STRIPE_SECRET_KEY
 *   STRIPE_PRICE_BASIC   (e.g. price_xxxxx)
 *   STRIPE_PRICE_PRO     (e.g. price_xxxxx)
 *   STRIPE_WEBHOOK_SECRET
 *   APP_BASE_URL         (e.g. https://resume-studio-frontend.vercel.app)
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final SupabaseJwtVerifier verifier;
    private final UserService userService;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${stripe.price.basic:}")
    private String priceBasic;

    @Value("${stripe.price.pro:}")
    private String pricePro;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${app.base-url:https://resume-studio-frontend.vercel.app}")
    private String appBaseUrl;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public BillingController(SupabaseJwtVerifier verifier, UserService userService) {
        this.verifier = verifier;
        this.userService = userService;
    }

    public record CheckoutRequest(String plan) {} // "BASIC" or "PRO"

    /**
     * Creates a Stripe customer portal session so users can manage subscriptions.
     * Returns { url: "https://billing.stripe.com/..." }
     */
    @GetMapping("/portal")
    public ResponseEntity<?> portal(
        @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required."));

        if (stripeSecretKey == null || stripeSecretKey.isBlank())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Billing not configured."));

        SupabaseJwtVerifier.UserClaims claims;
        try { claims = verifier.verify(authHeader.substring(7)); }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token."));
        }

        // Look up stripe_customer_id for this user
        com.resumestudio.auth.model.User user = null;
        try { user = userService.getOrCreate(claims.userId()); } catch (Exception ignored) {}
        if (user == null || user.getStripeCustomerId() == null)
            return ResponseEntity.badRequest().body(Map.of("error", "No active subscription found."));

        try {
            String formBody = "customer=" + user.getStripeCustomerId()
                + "&return_url=" + java.net.URLEncoder.encode(appBaseUrl, java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/billing_portal/sessions"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + stripeSecretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Could not open billing portal."));

            var json = mapper.readTree(resp.body());
            return ResponseEntity.ok(Map.of("url", json.path("url").asText()));
        } catch (Exception e) {
            log.error("Portal session failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Could not open billing portal."));
        }
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> createCheckout(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @RequestBody CheckoutRequest body
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required."));

        if (stripeSecretKey == null || stripeSecretKey.isBlank())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Payments not configured. Contact support."));

        SupabaseJwtVerifier.UserClaims claims;
        try { claims = verifier.verify(authHeader.substring(7)); }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token."));
        }

        String priceId = "PRO".equalsIgnoreCase(body.plan()) ? pricePro : priceBasic;
        if (priceId == null || priceId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid plan."));

        try {
            // Build Stripe Checkout Session via API
            String formBody = "mode=subscription"
                + "&line_items[0][price]=" + priceId
                + "&line_items[0][quantity]=1"
                + "&client_reference_id=" + claims.userId()
                + "&customer_email=" + java.net.URLEncoder.encode(claims.email(), java.nio.charset.StandardCharsets.UTF_8)
                + "&success_url=" + java.net.URLEncoder.encode(appBaseUrl + "?upgraded=true", java.nio.charset.StandardCharsets.UTF_8)
                + "&cancel_url=" + java.net.URLEncoder.encode(appBaseUrl, java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/checkout/sessions"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + stripeSecretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("Stripe error {}: {}", resp.statusCode(), resp.body());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Payment provider error. Please try again."));
            }

            var json = mapper.readTree(resp.body());
            String url = json.path("url").asText(null);
            if (url == null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "No checkout URL returned."));

            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            log.error("Checkout creation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Could not create checkout session. Please try again."));
        }
    }

    /**
     * Stripe webhook — handles checkout.session.completed and customer.subscription.deleted.
     * Verifies signature, then upgrades/downgrades user plan in DB.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(
        @RequestHeader(value = "Stripe-Signature", required = false) String sig,
        @RequestBody byte[] payload
    ) {
        if (webhookSecret == null || webhookSecret.isBlank())
            return ResponseEntity.ok("webhook-disabled");

        // Signature verification
        if (sig == null || !verifyStripeSignature(payload, sig)) {
            log.warn("Stripe webhook: invalid signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid-signature");
        }

        try {
            var event = mapper.readTree(payload);
            String type = event.path("type").asText("");
            var data = event.path("data").path("object");

            switch (type) {
                case "checkout.session.completed" -> {
                    String userId = data.path("client_reference_id").asText(null);
                    String customerId = data.path("customer").asText(null);
                    String priceId = data.path("line_items")
                        .path("data").path(0).path("price").path("id").asText(null);
                    if (userId != null) {
                        Plan plan = pricePro != null && pricePro.equals(priceId) ? Plan.PRO : Plan.BASIC;
                        userService.upgradePlan(userId, plan);
                        if (customerId != null) userService.setStripeCustomerId(userId, customerId);
                        log.info("User {} upgraded to {}", userId, plan);
                    }
                }
                case "customer.subscription.deleted" -> {
                    // Downgrade to free on cancellation
                    String customerId = data.path("customer").asText(null);
                    if (customerId != null) {
                        userService.downgradeByStripeCustomerId(customerId);
                        log.info("Subscription cancelled for customer {}", customerId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Webhook processing failed", e);
        }

        return ResponseEntity.ok("ok");
    }

    /** HMAC-SHA256 Stripe signature verification. */
    private boolean verifyStripeSignature(byte[] payload, String sigHeader) {
        try {
            // sig header format: "t=timestamp,v1=hash"
            String[] parts = sigHeader.split(",");
            String timestamp = null, v1 = null;
            for (String part : parts) {
                if (part.startsWith("t=")) timestamp = part.substring(2);
                if (part.startsWith("v1=")) v1 = part.substring(3);
            }
            if (timestamp == null || v1 == null) return false;
            String signedPayload = timestamp + "." + new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(signedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().equals(v1);
        } catch (Exception e) {
            return false;
        }
    }
}
