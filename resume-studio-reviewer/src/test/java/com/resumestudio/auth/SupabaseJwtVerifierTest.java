package com.resumestudio.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SupabaseJwtVerifier structural validation (pre-signature checks).
 * Signature verification against real ES256 keys requires network access to JWKS
 * and is covered by integration tests.
 */
class SupabaseJwtVerifierTest {

    private SupabaseJwtVerifier verifier() {
        return new SupabaseJwtVerifier("https://dniptbjiqglpbpkttwrb.supabase.co");
    }

    @Test
    void malformedJwt_twoPartOnly_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> verifier().verify("header.payload"));
    }

    @Test
    void emptyString_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> verifier().verify(""));
    }

    @Test
    void malformedHeader_throwsIllegalArgument() {
        // valid 3-part structure but header is not valid base64 JSON
        assertThrows(IllegalArgumentException.class, () -> verifier().verify("!!!.payload.sig"));
    }
}
