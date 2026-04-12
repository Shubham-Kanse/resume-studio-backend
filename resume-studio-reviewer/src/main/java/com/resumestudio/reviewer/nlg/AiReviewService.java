package com.resumestudio.reviewer.nlg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.reviewer.AiProperties;
import com.resumestudio.reviewer.classification.ClassificationEngine.ClassificationResult;
import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.signals.CoherenceEngine;
import com.resumestudio.reviewer.model.enums.*;
import com.resumestudio.reviewer.model.enums.JdClarity;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Calls Groq to fill the text fields of FeedbackReport.
 *
 * The rule engine owns all structural decisions (verdict, status, impact).
 * AI only fills: narrative, summaryLine, momentOfDecision, narrativeTone,
 * signal observation/interpretation, fix action/reason/beforeAfter,
 * recruiterGutFeel, differentiators.
 *
 * Retries once on invalid JSON. Falls back to SentenceBank on second failure.
 */
@Service
public class AiReviewService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);

    private final AiProperties ai;
    private final FeedbackGenerator fallback;
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    public AiReviewService(AiProperties ai, FeedbackGenerator fallback) {
        this.ai = ai;
        this.fallback = fallback;
    }

    /**
     * Enriches the report builder with AI-generated text fields.
     * Returns the builder with all text fields populated.
     */
    public FeedbackReport.Builder enrich(
            FeedbackReport.Builder builder,
            ResumeSignals signals,
            ClassificationResult classification,
            JobDescription jd,
            Resume resume) {
        return enrich(builder, signals, classification, jd, resume, null);
    }

    public FeedbackReport.Builder enrich(
            FeedbackReport.Builder builder,
            ResumeSignals signals,
            ClassificationResult classification,
            JobDescription jd,
            Resume resume,
            CoherenceEngine.CoherenceResult coherence) {

        String prompt = buildPrompt(signals, classification, jd, resume, coherence);

        // Truncate prompt if too long (~4000 chars ≈ ~1000 tokens, safe for Groq)
        if (prompt.length() > 4000) {
            prompt = prompt.substring(0, 4000) + "\n[prompt truncated]";
        }

        try {
            String raw = callGroq(prompt);
            return mergeAiOutput(builder, raw, signals, classification);
        } catch (Exception first) {
            log.warn("AI call failed ({}), retrying once", first.getMessage());
            try {
                String retryPrompt = prompt + "\n\nPrevious attempt failed: " + first.getMessage()
                    + "\nEnsure output is valid JSON matching the schema exactly.";
                String raw = callGroq(retryPrompt);
                return mergeAiOutput(builder, raw, signals, classification);
            } catch (Exception second) {
                log.warn("AI retry failed ({}), using fallback", second.getMessage());
                return applyFallback(builder, signals, classification);
            }
        }
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private String buildPrompt(ResumeSignals signals, ClassificationResult classification,
                                JobDescription jd, Resume resume,
                                CoherenceEngine.CoherenceResult coherence) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are simulating an experienced technical recruiter reading a resume.\n");
        sb.append("You receive pre-computed signals. Your job is to interpret them — not recompute them.\n");
        sb.append("Output ONLY valid JSON matching this schema exactly:\n");
        sb.append(SCHEMA).append("\n\n");
        sb.append("Rules:\n");
        sb.append("- narrative: 3-5 sentences, warm but honest, past tense\n");
        sb.append("- never mention scores or confidence numbers\n");
        sb.append("- fixes: maximum 3, ranked by impact\n");
        if (classification.jdClarity() == JdClarity.LOW)
            sb.append("- jdClarity is LOW: hedge language in narrative, acknowledge uncertainty\n\n");

        sb.append("=== JD ===\n");
        if (jd.getTrimmedText() != null) {
            sb.append(jd.getTrimmedText()).append("\n");
        } else {
            sb.append("Role: ").append(jd.getRoleTitle()).append("\n");
            sb.append("Required: ").append(String.join(", ", jd.getMustHaveSkills())).append("\n");
        }

        sb.append("\n=== Candidate ===\n");
        sb.append("Title: ").append(resume.getCurrentTitle()).append("\n");
        sb.append("Company: ").append(resume.getCurrentCompany()).append("\n");
        sb.append("YOE: ").append(signals.getCalculatedYoe()).append("\n");

        // Top bullets (scored by BulletEnricher)
        List<String> topBullets = resume.getTopBullets();
        if (topBullets == null || topBullets.isEmpty()) {
            // fallback: first 5 raw bullets
            topBullets = resume.getExperience() == null ? List.of() :
                resume.getExperience().stream()
                    .flatMap(e -> e.getBullets().stream())
                    .filter(b -> b != null && !b.isBlank())
                    .limit(5).collect(Collectors.toList());
        }
        if (!topBullets.isEmpty()) {
            sb.append("Top bullets:\n");
            topBullets.forEach(b -> sb.append("- ").append(b).append("\n"));
        }

        sb.append("\n=== Skill matching ===\n");
        if (signals.getMustHaveResults() != null) {
            List<String> found = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() != SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill).collect(Collectors.toList());
            List<String> missing = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
                .map(r -> r.getJdSkill() + (r.getAbsenceReason() != null ? " [" + r.getAbsenceReason() + "]" : ""))
                .collect(Collectors.toList());
            sb.append("Found: ").append(String.join(", ", found)).append("\n");
            sb.append("Missing: ").append(String.join(", ", missing)).append("\n");
        }

        // Structured signals section (Layer 7 per AI-integration.md)
        sb.append("\n=== Pre-computed signals ===\n");
        appendSignal(sb, "title_match", signals.getTitleMatch());
        appendSignal(sb, "years_of_experience", signals.getYoeFit(),
            signals.getCalculatedYoe() != null ? String.format("%.1f yrs", signals.getCalculatedYoe()) : "unknown");
        appendSignal(sb, "skills_visibility",
            signals.isAllMustHavesVisible() ? "PASS" : signals.isHasBuriedMustHaves() ? "WARN" : "FAIL");
        appendSignal(sb, "impact_evidence",
            signals.getImpactVerbRatio() >= 0.7 && signals.getMetricDensity() >= 0.4 ? "STRONG" :
            signals.getImpactVerbRatio() >= 0.4 ? "MODERATE" : "WEAK");
        appendSignal(sb, "career_trajectory", signals.getTitleProgression());
        appendSignal(sb, "company_pedigree", signals.getCurrentCompanyTier());
        appendSignal(sb, "resume_readability",
            signals.isFormatWallOfText() || signals.isFormatTooManyPages() ? "POOR" : "GOOD");
        appendSignal(sb, "seniority_signal", classification.seniorityCalibration());
        appendSignal(sb, "employment_gaps",
            signals.isHasUnexplainedGap() ? "GAP_DETECTED (" + (int)signals.getLongestGapMonths() + " months)" : "NONE");
        appendSignal(sb, "jd_clarity", classification.jdClarity());

        // Coherence flags
        if (coherence != null && !coherence.flags().isEmpty()) {
            sb.append("\n=== Coherence flags ===\n");
            coherence.flags().forEach(f ->
                sb.append("- [").append(f.severity()).append("] ").append(f.type())
                  .append(": ").append(f.detail()).append("\n"));
            if (coherence.pivotType() != null)
                sb.append("pivotType: ").append(coherence.pivotType())
                  .append(" (transferable: ").append(String.format("%.0f%%", coherence.transferableSkillScore() * 100)).append(")\n");
        }

        sb.append("\n=== Classification (treat as facts) ===\n");
        sb.append("verdict: ").append(classification.verdict()).append("\n");
        sb.append("confidence: ").append(classification.confidence()).append("\n");
        sb.append("scanDuration: ").append(classification.scanDuration()).append("s\n");
        sb.append("seniorityCalibration: ").append(classification.seniorityCalibration()).append("\n");
        sb.append("tailoringScore: ").append(classification.tailoringScore()).append("/10\n");
        sb.append("jdClarity: ").append(classification.jdClarity()).append("\n");
        sb.append("interviewLikelihood: ").append(classification.interviewLikelihood()).append("\n");

        return sb.toString();
    }

    // ── Groq HTTP call ────────────────────────────────────────────────────────

    private String callGroq(String prompt) throws Exception {
        String body = mapper.writeValueAsString(new GroqRequest(ai.getModel(), prompt));

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(ai.getUrl()))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + ai.getKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("Groq returned " + resp.statusCode() + ": " + resp.body());

        JsonNode root = mapper.readTree(resp.body());
        String content = root.path("choices").get(0).path("message").path("content").asText();

        // Strip markdown code fences if present
        content = content.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

        // Extract just the JSON object — find first { and last }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            content = content.substring(start, end + 1);
        }

        // Validate JSON — throws if invalid
        mapper.readTree(content);
        return content;
    }

    // ── Merge AI output into builder ──────────────────────────────────────────

    private FeedbackReport.Builder mergeAiOutput(FeedbackReport.Builder builder, String json,
                                                   ResumeSignals signals, ClassificationResult classification) {
        try {
            JsonNode root = mapper.readTree(json);

            String summaryLine = root.path("summaryLine").asText(null);
            builder.summaryLine(summaryLine != null ? summaryLine : "Review complete.");
            String narrative = root.path("narrative").asText(null);
            builder.narrative(narrative != null ? narrative : "");
            builder.momentOfDecision(root.path("momentOfDecision").asText(null));

            String toneStr = root.path("narrativeTone").asText(null);
            if (toneStr != null) {
                try {
                    NarrativeTone tone = NarrativeTone.valueOf(toneStr);
                    // Post-AI consistency: ENCOURAGING tone is wrong for NO_FIT/WEAK_FIT
                    if (tone == NarrativeTone.ENCOURAGING
                            && (classification.verdict() == Verdict.NO_FIT
                                || classification.verdict() == Verdict.WEAK_FIT)) {
                        tone = NarrativeTone.CAUTIONARY;
                    }
                    builder.narrativeTone(tone);
                } catch (Exception ignored) {}
            }

            // recruiterGutFeel
            JsonNode gf = root.path("recruiterGutFeel");
            if (!gf.isMissingNode()) {
                FeedbackReport.RecruiterGutFeel gutFeel = new FeedbackReport.RecruiterGutFeel();
                gutFeel.setFirstImpression(gf.path("firstImpression").asText(null));
                gutFeel.setObservation(gf.path("observation").asText(null));
                String trust = gf.path("trustLevel").asText(null);
                if (trust != null) {
                    try { gutFeel.setTrustLevel(TrustLevel.valueOf(trust)); } catch (Exception ignored) {}
                }
                builder.recruiterGutFeel(gutFeel);
            }

            // differentiators
            JsonNode diffs = root.path("differentiators");
            if (diffs.isArray()) {
                List<FeedbackReport.Differentiator> list = new java.util.ArrayList<>();
                for (JsonNode d : diffs) {
                    String obs = d.path("observation").asText(null);
                    String imp = d.path("impact").asText("MEDIUM");
                    if (obs != null) {
                        try {
                            list.add(new FeedbackReport.Differentiator(obs, ImpactLevel.valueOf(imp)));
                        } catch (Exception ignored) {}
                    }
                }
                builder.differentiators(list);
            }

            // fixes — cap at 3 per doc Layer 9
            JsonNode fixes = root.path("fixes");
            if (fixes.isArray()) {
                List<Fix> fixList = new java.util.ArrayList<>();
                int rank = 1;
                for (JsonNode f : fixes) {
                    if (rank > 3) break; // cap at 3
                    Fix fix = new Fix();
                    fix.setRank(rank++);
                    fix.setSignalId(f.path("signalId").asText(null));
                    fix.setAction(f.path("action").asText(null));
                    fix.setReason(f.path("reason").asText(null));
                    String ft = f.path("fixType").asText(null);
                    if (ft != null) { try { fix.setFixType(FixType.valueOf(ft)); } catch (Exception ignored) {} }
                    String fs = f.path("fixScope").asText(null);
                    if (fs != null) { try { fix.setFixScope(FixScope.valueOf(fs)); } catch (Exception ignored) {} }
                    String effort = f.path("effort").asText(null);
                    if (effort != null) { try { fix.setEffort(EffortLevel.valueOf(effort)); } catch (Exception ignored) {} }
                    String impact = f.path("impact").asText("MEDIUM");
                    try { fix.setImpact(ImpactLevel.valueOf(impact)); } catch (Exception ignored) {}
                    JsonNode ba = f.path("beforeAfter");
                    if (!ba.isMissingNode()) {
                        fix.setBeforeAfter(new Fix.BeforeAfter(
                            ba.path("before").asText(null),
                            ba.path("after").asText(null)));
                    }
                    fixList.add(fix);
                }
                builder.fixes(fixList);
            }

            // signals — deterministic structure from FeedbackGenerator, capped at 6
            // AI observation/interpretation text merged in where available
            FeedbackGenerator.FeedbackOutput fb = fallback.generate(signals, classification.verdict());
            JsonNode signalTexts = root.path("signalTexts");
            List<Signal> cappedSignals = fb.signals().stream().limit(6).map(s -> {
                if (!signalTexts.isMissingNode() && s.getId() != null) {
                    JsonNode st = signalTexts.path(s.getId());
                    if (!st.isMissingNode()) {
                        String obs = st.path("observation").asText(null);
                        String interp = st.path("interpretation").asText(null);
                        if (obs != null) s.setObservation(obs);
                        if (interp != null) s.setInterpretation(interp);
                    }
                }
                return s;
            }).toList();
            builder.signals(cappedSignals);

            // Post-AI consistency: tailoringScore must not be 10 for WEAK_FIT/NO_FIT
            // (handled downstream — builder already has tailoringScore from classification)

            return builder;

        } catch (Exception e) {
            log.warn("Failed to parse AI output: {}", e.getMessage());
            return applyFallback(builder, signals, classification);
        }
    }

    private FeedbackReport.Builder applyFallback(FeedbackReport.Builder builder,
                                                   ResumeSignals signals, ClassificationResult classification) {
        FeedbackGenerator.FeedbackOutput fb = fallback.generate(signals, classification.verdict());

        // Generate differentiators from strong signals when AI is unavailable
        List<FeedbackReport.Differentiator> diffs = new java.util.ArrayList<>();
        if (signals.getCurrentCompanyTier() != null
                && (signals.getCurrentCompanyTier().name().equals("FAANG")
                    || signals.getCurrentCompanyTier().name().equals("TIER_1"))) {
            diffs.add(new FeedbackReport.Differentiator(
                "Strong company pedigree — engineering bar and scale are immediately inferred.",
                ImpactLevel.HIGH));
        }
        if (signals.getImpactVerbRatio() >= 0.7 && signals.getMetricDensity() >= 0.4) {
            diffs.add(new FeedbackReport.Differentiator(
                "Bullets show strong impact and quantified results.",
                ImpactLevel.MEDIUM));
        }
        if (signals.isAllMustHavesVisible() && !signals.isHasMissingMustHaves()) {
            diffs.add(new FeedbackReport.Differentiator(
                "All required skills are immediately visible in the skills section.",
                ImpactLevel.MEDIUM));
        }

        return builder
            .summaryLine(fb.summaryLine() != null ? fb.summaryLine() : "Review complete.")
            .signals(fb.signals() != null ? fb.signals() : List.of())
            .fixes(fb.fixes() != null ? fb.fixes() : List.of())
            .narrative("")
            .narrativeTone(NarrativeTone.NEUTRAL)
            .differentiators(diffs)
            .recruiterGutFeel(null); // explicitly null — frontend must handle
    }

    // ── Schema sent to AI ─────────────────────────────────────────────────────

    private static final String SCHEMA = """
        {
          "summaryLine": "string — one sentence shown at top of UI",
          "narrative": "string — 3-5 sentences recruiter perspective",
          "narrativeTone": "ENCOURAGING | NEUTRAL | CAUTIONARY",
          "momentOfDecision": "string — e.g. 'Skills section, ~8 seconds in'",
          "recruiterGutFeel": {
            "firstImpression": "string",
            "trustLevel": "HIGH | MEDIUM | LOW",
            "observation": "string"
          },
          "signalTexts": {
            "title_match": { "observation": "string", "interpretation": "string" },
            "yoe_fit": { "observation": "string", "interpretation": "string" },
            "must_haves_visible": { "observation": "string", "interpretation": "string" },
            "company_context": { "observation": "string", "interpretation": "string" },
            "summary_quality": { "observation": "string", "interpretation": "string" },
            "career_trajectory": { "observation": "string", "interpretation": "string" }
          },
          "differentiators": [
            { "observation": "string", "impact": "HIGH | MEDIUM | LOW" }
          ],
          "fixes": [
            {
              "signalId": "string",
              "fixType": "MISSING_SKILL | REFRAME | DOMAIN_GAP | PRESENTATION",
              "fixScope": "BEFORE_SUBMIT | RESUME_REBUILD | LONG_TERM",
              "action": "string",
              "reason": "string",
              "beforeAfter": { "before": "string", "after": "string" },
              "effort": "LOW | MEDIUM | HIGH",
              "impact": "HIGH | MEDIUM | LOW"
            }
          ]
        }""";

    // ── Groq request payload ──────────────────────────────────────────────────

    private static class GroqRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("model")
        public final String model;
        @com.fasterxml.jackson.annotation.JsonProperty("messages")
        public final java.util.List<java.util.Map<String, String>> messages;
        @com.fasterxml.jackson.annotation.JsonProperty("temperature")
        public final double temperature = 0.3;
        @com.fasterxml.jackson.annotation.JsonProperty("max_tokens")
        public final int maxTokens = 2000;

        GroqRequest(String model, String prompt) {
            this.model = model;
            this.messages = List.of(java.util.Map.of("role", "user", "content", prompt));
        }
    }

    private static void appendSignal(StringBuilder sb, String id, Object value) {
        sb.append(id).append(": ").append(value != null ? value : "UNKNOWN").append("\n");
    }

    private static void appendSignal(StringBuilder sb, String id, Object value, String detail) {
        sb.append(id).append(": ").append(value != null ? value : "UNKNOWN")
          .append(" (").append(detail).append(")\n");
    }
}
