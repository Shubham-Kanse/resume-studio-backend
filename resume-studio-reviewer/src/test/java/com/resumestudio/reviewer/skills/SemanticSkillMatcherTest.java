package com.resumestudio.reviewer.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticSkillMatcherTest {

    private SkillEmbeddingIndex embeddingIndex;
    private SemanticSkillMatcher matcher;

    @BeforeEach
    void setUp() {
        embeddingIndex = mock(SkillEmbeddingIndex.class);
        matcher = new SemanticSkillMatcher(embeddingIndex);
    }

    @Test void similarity_exactMatch_returns1() {
        assertEquals(1.0f, matcher.similarity("java", "java"), 0.001f);
    }

    @Test void similarity_usesEmbeddingIndex_whenAvailable() {
        when(embeddingIndex.isAvailable()).thenReturn(true);
        when(embeddingIndex.cosineSimilarity("kubernetes", "k8s")).thenReturn(0.95f);
        assertEquals(0.95f, matcher.similarity("kubernetes", "k8s"), 0.001f);
    }

    @Test void similarity_returnsZero_whenNotInIndex() {
        when(embeddingIndex.isAvailable()).thenReturn(true);
        when(embeddingIndex.cosineSimilarity(anyString(), anyString())).thenReturn(-1f);
        assertEquals(0f, matcher.similarity("obscureskill", "anotherskill"), 0.001f);
    }

    @Test void similarity_fallsBackToJaccard_whenIndexUnavailable() {
        when(embeddingIndex.isAvailable()).thenReturn(false);
        // "java spring" vs "java spring boot" — partial overlap
        float sim = matcher.similarity("java spring", "java spring boot");
        assertTrue(sim > 0f && sim < 1f);
    }

    @Test void isSemanticallySimilar_trueAboveThreshold() {
        when(embeddingIndex.isAvailable()).thenReturn(true);
        when(embeddingIndex.cosineSimilarity("postgresql", "postgres")).thenReturn(0.90f);
        assertTrue(matcher.isSemanticallySimilar("postgresql", "postgres"));
    }

    @Test void isSemanticallySimilar_falseBelowThreshold() {
        when(embeddingIndex.isAvailable()).thenReturn(true);
        when(embeddingIndex.cosineSimilarity("java", "python")).thenReturn(0.50f);
        assertFalse(matcher.isSemanticallySimilar("java", "python"));
    }

    @Test void isSemanticallySimilar_nullInputs_returnsFalse() {
        assertFalse(matcher.isSemanticallySimilar(null, "java"));
        assertFalse(matcher.isSemanticallySimilar("java", null));
    }

    @Test void jaccard_identicalTokens_returns1() {
        when(embeddingIndex.isAvailable()).thenReturn(false);
        assertEquals(1.0f, matcher.similarity("spring boot", "spring boot"), 0.001f);
    }

    @Test void jaccard_noOverlap_returns0() {
        when(embeddingIndex.isAvailable()).thenReturn(false);
        assertEquals(0f, matcher.similarity("java", "python"), 0.001f);
    }

    @Test void similarity_nullInputs_returns0() {
        assertEquals(0f, matcher.similarity(null, "java"), 0.001f);
        assertEquals(0f, matcher.similarity("java", null), 0.001f);
        assertEquals(0f, matcher.similarity(null, null), 0.001f);
    }
}
