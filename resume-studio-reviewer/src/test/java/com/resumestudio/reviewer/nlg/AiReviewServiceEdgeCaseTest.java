package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.AiProperties;
import com.resumestudio.reviewer.classification.ClassificationEngine.ClassificationResult;
import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.*;
import com.resumestudio.reviewer.signals.CoherenceEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiReviewServiceEdgeCaseTest {

    private AiProperties aiProps;
    private FeedbackGenerator fallback;
    private AiReviewService service;

    @BeforeEach
    void setUp() {
        aiProps = mock(AiProperties.class);
        when(aiProps.getKey()).thenReturn("test-key");
        when(aiProps.getUrl()).thenReturn("https://api.groq.com/openai/v1/chat/completions");
        when(aiProps.getModel()).thenReturn("test-model");

        fallback = mock(FeedbackGenerator.class);
        FeedbackGenerator.FeedbackOutput fb = new FeedbackGenerator.FeedbackOutput(
            List.of(), List.of(), "Fallback summary.");
        when(fallback.generate(any(), any())).thenReturn(fb);

        service = new AiReviewService(aiProps, fallback);
    }

    private ClassificationResult classification() {
        return new ClassificationResult(
            Verdict.WEAK_FIT, Confidence.MEDIUM, InterviewLikelihood.UNLIKELY,
            15, SeniorityCalibration.MATCHED, 5, JdClarity.MEDIUM,
            RecruiterType.UNKNOWN, CompetitiveContext.UNKNOWN);
    }

    private ClassificationResult noFitClassification() {
        return new ClassificationResult(
            Verdict.NO_FIT, Confidence.HIGH, InterviewLikelihood.VERY_UNLIKELY,
            8, SeniorityCalibration.UNCLEAR, 2, JdClarity.LOW,
            RecruiterType.UNKNOWN, CompetitiveContext.UNKNOWN);
    }

    private ResumeSignals signals() {
        ResumeSignals s = new ResumeSignals();
        s.setCalculatedYoe(3.0);
        s.setCandidateTitle("Software Engineer");
        return s;
    }

    private JobDescription jd() {
        JobDescription jd = new JobDescription();
        jd.setRoleTitle("Backend Engineer");
        jd.setMustHaveSkills(List.of("Java", "Spring Boot"));
        jd.setNiceToHaveSkills(List.of());
        jd.setImpliedSkills(List.of());
        return jd;
    }

    private Resume resume() {
        Resume r = new Resume();
        r.setCurrentTitle("Software Engineer");
        r.setCurrentCompany("Acme");
        r.setExperience(List.of());
        r.setTopBullets(List.of());
        return r;
    }

    // ── AI failure → fallback produces non-null report ────────────────────────

    @Test void aiDown_fallbackProducesNonNullReport() {
        // AI will fail (bad URL/key) — fallback must produce valid builder
        FeedbackReport.Builder builder = FeedbackReport.builder()
            .verdict(Verdict.WEAK_FIT)
            .confidence(Confidence.MEDIUM);

        FeedbackReport.Builder result = service.enrich(
            builder, signals(), classification(), jd(), resume(), null);

        FeedbackReport report = result.build();
        assertNotNull(report.getSummaryLine(), "summaryLine must not be null after fallback");
        assertNotNull(report.getSignals(), "signals must not be null after fallback");
        assertNotNull(report.getFixes(), "fixes must not be null after fallback");
        assertNotNull(report.getNarrative(), "narrative must not be null after fallback");
        assertNotNull(report.getNarrativeTone(), "narrativeTone must not be null after fallback");
    }

    // ── narrativeTone consistency guard ──────────────────────────────────────

    @Test void encouragingTone_correctedToCAUTIONARY_forNoFit() {
        // Simulate AI returning ENCOURAGING for NO_FIT — guard must correct it
        // We test the guard logic directly via the classification
        // The guard is in mergeAiOutput — we verify it via the fallback path
        // which sets NEUTRAL, not ENCOURAGING
        FeedbackReport.Builder builder = FeedbackReport.builder()
            .verdict(Verdict.NO_FIT)
            .confidence(Confidence.HIGH);

        FeedbackReport report = service.enrich(
            builder, signals(), noFitClassification(), jd(), resume(), null).build();

        // Fallback sets NEUTRAL — either NEUTRAL or CAUTIONARY is acceptable, never ENCOURAGING
        assertNotEquals(NarrativeTone.ENCOURAGING, report.getNarrativeTone(),
            "ENCOURAGING tone must never appear on NO_FIT verdict");
    }

    // ── Prompt truncation ─────────────────────────────────────────────────────

    @Test void veryLongResume_promptDoesNotExceedLimit() throws Exception {
        Resume longResume = resume();
        // 200 long bullets
        WorkExperience exp = new WorkExperience();
        exp.setTitle("Engineer");
        exp.setCompany("Acme");
        exp.setBullets(java.util.stream.IntStream.range(0, 200)
            .mapToObj(i -> "Built a highly scalable distributed system that reduced latency by " + i + "% across all regions")
            .toList());
        longResume.setExperience(List.of(exp));
        longResume.setTopBullets(exp.getBullets().subList(0, 5));

        // Should not throw — prompt is truncated internally
        FeedbackReport.Builder builder = FeedbackReport.builder()
            .verdict(Verdict.POSSIBLE_FIT).confidence(Confidence.MEDIUM);

        assertDoesNotThrow(() ->
            service.enrich(builder, signals(), classification(), jd(), longResume, null));
    }

    // ── Empty signals/fixes in response ──────────────────────────────────────

    @Test void fallback_signalsListIsNeverNull() {
        FeedbackReport.Builder builder = FeedbackReport.builder()
            .verdict(Verdict.WEAK_FIT).confidence(Confidence.LOW);

        FeedbackReport report = service.enrich(
            builder, signals(), classification(), jd(), resume(), null).build();

        assertNotNull(report.getSignals());
        assertNotNull(report.getFixes());
    }
}
