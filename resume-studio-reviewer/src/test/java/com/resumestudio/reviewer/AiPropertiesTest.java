package com.resumestudio.reviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiPropertiesTest {

    private AiProperties build(String key, String url) {
        AiProperties p = new AiProperties();
        p.setKey(key);
        p.setUrl(url);
        return p;
    }

    @Test
    void validate_throwsWhenKeyIsNull() {
        AiProperties p = build(null, "https://api.example.com");
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void validate_throwsWhenKeyIsBlank() {
        AiProperties p = build("   ", "https://api.example.com");
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void validate_throwsWhenUrlIsNull() {
        AiProperties p = build("some-key", null);
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void validate_throwsWhenUrlIsBlank() {
        AiProperties p = build("some-key", "");
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void validate_passesWhenBothPresent() {
        AiProperties p = build("sk-test", "https://api.groq.com/openai/v1/chat/completions");
        assertDoesNotThrow(p::validate);
    }
}
