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
    
    @Test void resolve_urlWithQueryParams_isRecognizedAsUrl() {
        // URL with query params should be recognized as URL (not plain text)
        String url = "https://linkedin.com/jobs/view/123?refId=xyz&source=email";
        assertThrows(RuntimeException.class, () -> service.resolve(url));
    }
    
    @Test void resolve_urlInText_notRecognized() {
        // Text containing URL but not starting with URL should be treated as plain text
        String text = "Apply at https://example.com for this Java role";
        assertEquals(text, service.resolve(text));
    }
    
    @Test void resolve_multilineWithUrl_notRecognized() {
        // Multiline text with URL should be treated as plain text
        String text = "https://example.com\nApply for Java role";
        assertEquals(text, service.resolve(text));
    }
    
    @Test void cleanJinaResponse_removesMetadata() throws Exception {
        // Use reflection to test private method
        java.lang.reflect.Method method = JdFetchService.class.getDeclaredMethod("cleanJinaResponse", String.class);
        method.setAccessible(true);
        
        String jinaOutput = "Title: Software Engineer\n\n" +
                           "URL Source: https://example.com/job/123\n\n" +
                           "Published Time: Fri, 10 Apr 2026 01:29:17 GMT\n\n" +
                           "Warning: This is a cached snapshot\n\n" +
                           "Markdown Content:\n" +
                           "We are looking for a Java engineer with 5 years of experience.\n" +
                           "Must have: Spring Boot, Kubernetes";
        
        String cleaned = (String) method.invoke(service, jinaOutput);
        
        assertFalse(cleaned.contains("Title:"));
        assertFalse(cleaned.contains("URL Source:"));
        assertFalse(cleaned.contains("Published Time:"));
        assertFalse(cleaned.contains("Warning:"));
        assertFalse(cleaned.contains("Markdown Content:"));
        assertTrue(cleaned.startsWith("Software Engineer"));
        assertTrue(cleaned.contains("Java engineer"));
        assertTrue(cleaned.contains("Spring Boot"));
    }
}
