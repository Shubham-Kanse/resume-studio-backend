package com.resumestudio.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.ECParameterSpec;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies Supabase-issued JWTs using the project's JWKS endpoint.
 * Supports ES256 (P-256 elliptic curve) — the algorithm used by new Supabase projects.
 * Keys are cached in-memory; refreshed on unknown kid.
 */
@Component
public class SupabaseJwtVerifier {

    private final String jwksUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ConcurrentHashMap<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    private final Object refreshLock = new Object();

    public SupabaseJwtVerifier(@Value("${supabase.url}") String supabaseUrl) {
        this.jwksUrl = supabaseUrl + "/auth/v1/.well-known/jwks.json";
    }

    public record UserClaims(String userId, String email) {}

    public UserClaims verify(String jwt) {
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Malformed JWT");

        // 1. Decode header to get kid and alg
        JsonNode header;
        try {
            header = mapper.readTree(Base64.getUrlDecoder().decode(padBase64(parts[0])));
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed JWT header");
        }
        String alg = header.path("alg").asText("");
        String kid = header.path("kid").asText(null);

        // 2. Get public key
        PublicKey publicKey = resolveKey(kid);

        // 3. Verify signature
        try {
            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
            byte[] sigBytes = Base64.getUrlDecoder().decode(padBase64(parts[2]));

            if ("ES256".equals(alg)) {
                // JWT ES256 signature is raw R||S (64 bytes), but Java needs DER format
                byte[] derSig = rawToDer(sigBytes);
                Signature sig = Signature.getInstance("SHA256withECDSA");
                sig.initVerify(publicKey);
                sig.update(signingInput);
                if (!sig.verify(derSig)) throw new IllegalArgumentException("Invalid token signature");
            } else {
                throw new IllegalArgumentException("Unsupported algorithm: " + alg);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Signature verification failed", e);
        }

        // 4. Validate claims
        try {
            JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(padBase64(parts[1])));

            long exp = payload.path("exp").asLong(-1);
            if (exp < 0) throw new IllegalArgumentException("Missing exp claim");
            if (exp < System.currentTimeMillis() / 1000) throw new IllegalArgumentException("Token has expired");

            String sub = payload.path("sub").asText(null);
            if (sub == null || sub.isBlank()) throw new IllegalArgumentException("Missing sub claim");

            String email = payload.path("email").asText("");
            return new UserClaims(sub, email);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JWT payload", e);
        }
    }

    private PublicKey resolveKey(String kid) {
        if (kid != null && keyCache.containsKey(kid)) return keyCache.get(kid);
        // Synchronize to prevent concurrent JWKS fetches (B4)
        synchronized (refreshLock) {
            // Re-check after acquiring lock — another thread may have refreshed already
            if (kid != null && keyCache.containsKey(kid)) return keyCache.get(kid);
            refreshKeys();
        }
        if (kid != null && keyCache.containsKey(kid)) return keyCache.get(kid);
        if (!keyCache.isEmpty()) return keyCache.values().iterator().next();
        throw new IllegalArgumentException("No public key available");
    }

    private void refreshKeys() {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(jwksUrl)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new RuntimeException("JWKS fetch failed: " + res.statusCode());

            JsonNode jwks = mapper.readTree(res.body());
            for (JsonNode key : jwks.path("keys")) {
                String kty = key.path("kty").asText();
                String keyId = key.path("kid").asText(null);
                if ("EC".equals(kty)) {
                    PublicKey pk = buildEcPublicKey(key);
                    String cacheKey = keyId != null ? keyId : "default";
                    keyCache.put(cacheKey, pk);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to refresh JWKS", e);
        }
    }

    private PublicKey buildEcPublicKey(JsonNode key) throws Exception {
        byte[] xBytes = Base64.getUrlDecoder().decode(padBase64(key.path("x").asText()));
        byte[] yBytes = Base64.getUrlDecoder().decode(padBase64(key.path("y").asText()));

        java.security.AlgorithmParameters params = java.security.AlgorithmParameters.getInstance("EC");
        params.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);

        ECPoint point = new ECPoint(new BigInteger(1, xBytes), new BigInteger(1, yBytes));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecSpec));
    }

    /**
     * Converts raw R||S signature (64 bytes for P-256) to DER format required by Java.
     */
    private static byte[] rawToDer(byte[] rawSig) {
        if (rawSig.length != 64) throw new IllegalArgumentException("Invalid ES256 signature length");
        byte[] r = toUnsignedByteArray(rawSig, 0, 32);
        byte[] s = toUnsignedByteArray(rawSig, 32, 32);
        // DER: SEQUENCE { INTEGER r, INTEGER s }
        byte[] rDer = derInteger(r);
        byte[] sDer = derInteger(s);
        byte[] content = new byte[rDer.length + sDer.length];
        System.arraycopy(rDer, 0, content, 0, rDer.length);
        System.arraycopy(sDer, 0, content, rDer.length, sDer.length);
        byte[] seq = new byte[2 + content.length];
        seq[0] = 0x30; // SEQUENCE
        seq[1] = (byte) content.length;
        System.arraycopy(content, 0, seq, 2, content.length);
        return seq;
    }

    private static byte[] toUnsignedByteArray(byte[] src, int offset, int len) {
        // Strip leading zeros, keep at least 1 byte
        int start = offset;
        while (start < offset + len - 1 && src[start] == 0) start++;
        byte[] result = new byte[offset + len - start];
        System.arraycopy(src, start, result, 0, result.length);
        return result;
    }

    private static byte[] derInteger(byte[] value) {
        // If high bit set, prepend 0x00 to indicate positive
        boolean pad = (value[0] & 0x80) != 0;
        int len = value.length + (pad ? 1 : 0);
        byte[] der = new byte[2 + len];
        der[0] = 0x02; // INTEGER
        der[1] = (byte) len;
        if (pad) der[2] = 0x00;
        System.arraycopy(value, 0, der, pad ? 3 : 2, value.length);
        return der;
    }

    private static String padBase64(String s) {
        return switch (s.length() % 4) {
            case 2 -> s + "==";
            case 3 -> s + "=";
            default -> s;
        };
    }
}
