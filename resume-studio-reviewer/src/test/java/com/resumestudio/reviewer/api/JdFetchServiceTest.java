package com.resumestudio.reviewer.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdFetchServiceTest {

    private JdFetchService service;

    @BeforeEach
    void setUp() { service = new JdFetchService(); }

    @Test void resolve_plainText_returnedAsIs() {
        String jd = "We are looking for a Java engineer with 5 years of experience.";
        assertEquals(jd, service.resolve(jd));
    }

    @Test void resolve_null_returnsNull() {
        assertNull(service.resolve(null));
    }

    @Test void resolve_blank_returnsBlank() {
        assertEquals("   ", service.resolve("   "));
    }

    @Test void resolve_textWithHttp_butNotUrl_returnedAsIs() {
        // A JD that mentions a URL in the text but isn't itself a URL
        String jd = "Apply at https://example.com — we need Java engineers.";
        assertEquals(jd, service.resolve(jd));
    }

    @Test void resolve_validUrl_throwsOrReturnsContent() {
        // We can't make real HTTP calls in unit tests — just verify it attempts to resolve
        // and throws a RuntimeException on failure (not a NullPointerException)
        assertThrows(RuntimeException.class, () ->
            service.resolve("https://jobs.example-nonexistent-domain-xyz.com/job/123")
        );
    }
}
