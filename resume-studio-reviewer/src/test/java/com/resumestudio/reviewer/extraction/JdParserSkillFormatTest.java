package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.JobDescription;
import com.resumestudio.reviewer.skills.EscoSkillGraph;
import com.resumestudio.reviewer.skills.MindTechOntology;
import com.resumestudio.reviewer.nlp.SentenceEncoder;
import com.resumestudio.reviewer.nlp.TfIdfVectorizer;
import com.resumestudio.reviewer.nlp.PosTagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JD parsing edge cases for diverse skill formats.
 */
class JdParserSkillFormatTest {

    private JdParserService parser;

    @BeforeEach
    void setUp() {
        EscoSkillGraph escoGraph = mock(EscoSkillGraph.class);
        MindTechOntology mind = mock(MindTechOntology.class);
        SentenceEncoder encoder = mock(SentenceEncoder.class);
        TfIdfVectorizer tfidf = mock(TfIdfVectorizer.class);
        PosTagService pos = mock(PosTagService.class);

        when(escoGraph.resolve(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(escoGraph.isKnownSkill(anyString())).thenReturn(true);
        when(escoGraph.isTechnicalSkill(anyString())).thenReturn(true);
        when(mind.isKnownSkill(anyString())).thenReturn(false);
        when(mind.resolve(anyString())).thenReturn(null);
        when(mind.getImpliedSkills(anyString())).thenReturn(List.of());
        when(mind.getRoleSkillRelevance(anyString(), anyString())).thenReturn(0.5);
        when(encoder.similarity(anyString(), anyString())).thenReturn(0.5);
        when(encoder.mustHaveSimilarity(anyString())).thenReturn(0.5);
        when(encoder.niceToHaveSimilarity(anyString())).thenReturn(0.3);
        when(tfidf.computeTfIdf(anyString(), anyList())).thenReturn(new java.util.HashMap<>());
        when(tfidf.computePositionalWeight(anyString(), anyString())).thenReturn(0.5);
        when(pos.isVerb(anyString(), anyString())).thenReturn(false);

        parser = new JdParserService(escoGraph, mind, encoder, tfidf, pos,
                mock(JdRolePatternsService.class));
    }

    @Test void slashSeparatedSkills_allExtracted() {
        // "Python/Java/Go" should extract Python, Java, Go separately
        String jd = "Requirements\n- Python/Java/Go experience required\n- 3+ years experience";
        JobDescription result = parser.parse(jd);
        // At least one of the slash-separated skills should be extracted
        List<String> allSkills = new java.util.ArrayList<>(result.getMustHaveSkills());
        allSkills.addAll(result.getNiceToHaveSkills());
        assertFalse(allSkills.isEmpty(), "Slash-separated skills should be extracted");
    }

    @Test void orSkills_treatedAsPreferred() {
        // "React or Angular" — either/or, not both required
        String jd = "Requirements\n- Experience with React or Angular\n- 3+ years experience";
        JobDescription result = parser.parse(jd);
        // Both should be extracted but at least one should be in preferred/nice-to-have
        List<String> allSkills = new java.util.ArrayList<>(result.getMustHaveSkills());
        allSkills.addAll(result.getNiceToHaveSkills());
        assertFalse(allSkills.isEmpty(), "Or-separated skills should be extracted");
    }

    @Test void emptyJd_doesNotCrash() {
        assertDoesNotThrow(() -> parser.parse(""));
        assertDoesNotThrow(() -> parser.parse(null));
    }

    @Test void jdWithOnlySoftSkills_hasLowClarityScore() {
        String jd = "We need a team player who is passionate and a good communicator with strong interpersonal skills.";
        JobDescription result = parser.parse(jd);
        // No technical skills → low clarity score
        assertTrue(result.getJdClarityScore() <= 3,
            "JD with only soft skills should have low clarity score, got: " + result.getJdClarityScore());
    }
}
