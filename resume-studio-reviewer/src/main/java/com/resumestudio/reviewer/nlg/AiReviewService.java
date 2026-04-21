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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calls Groq to fill the text fields of FeedbackReport.
 *
 * The rule engine owns all structural decisions (verdict, status, impact).
 * AI only fills: narrative, summaryLine, narrativeTone,
 * signal observation/interpretation, fix action/reason/beforeAfter,
 * recruiterGutFeel, differentiators.
 *
 * Retries once on invalid JSON. Falls back to SentenceBank on second failure.
 */
@Service
public class AiReviewService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);

    private static final java.util.Set<String> VALID_SIGNAL_IDS = java.util.Set.of(
        "skill_match",
        "skills_format",
        "yoe_fit",
        "title_match",
        "summary",
        "bullets",
        "format",
        "format_quality",
        "tailoring",
        "chronology",
        "company_context",
        "filename",
        "skill_age_mismatch",
        "candidate_fit",
        "skill_coverage",
        "presentation",
        "trust"
    );

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
        return enrich(builder, signals, classification, jd, resume, coherence, classification.tailoringScore());
    }

    public FeedbackReport.Builder enrich(
            FeedbackReport.Builder builder,
            ResumeSignals signals,
            ClassificationResult classification,
            JobDescription jd,
            Resume resume,
            CoherenceEngine.CoherenceResult coherence,
            int tailoringScore) {

        // Issue 2: tailoringScore passed in from ResumeScore — single source of truth
        String prompt = buildPrompt(signals, classification, jd, resume, coherence, tailoringScore);
        prompt = smartTruncate(prompt, 8000); // Kimi K2 has 262k context — raise limit for richer prompts

        // Build ground-truth before-text map from actual resume data
        // so AI-generated "before" is overridden with real resume text
        Map<String, String> signalToRealText = buildSignalTextMap(signals, resume);

        try {
            String raw = callGroq(prompt);
            FeedbackReport.Builder result = mergeAiOutput(builder, raw, signals, classification, resume);
            injectRealBeforeText(result, signalToRealText);
            return result;
        } catch (Exception first) {
            log.warn("AI call failed ({}), retrying once", first.getMessage());
            try {
                String retryPrompt = prompt + "\n\nPrevious attempt failed: " + first.getMessage()
                    + "\nEnsure output is valid JSON matching the schema exactly.";
                String raw = callGroq(retryPrompt);
                FeedbackReport.Builder result = mergeAiOutput(builder, raw, signals, classification, resume);
                injectRealBeforeText(result, signalToRealText);
                return result;
            } catch (Exception second) {
                log.warn("AI retry failed ({}), using fallback", second.getMessage());
                FeedbackReport.Builder fallbackResult = applyFallback(builder, signals, classification, resume);
                injectRealBeforeText(fallbackResult, signalToRealText);
                return fallbackResult;
            }
        }
    }

    // ── Real before-text injection ────────────────────────────────────────────

    /**
     * Builds a map of signalId → actual resume text for use as ground-truth "before" in fixes.
     * Keyed by the signal IDs the AI uses in its fix output.
     */
    private Map<String, String> buildSignalTextMap(ResumeSignals signals, Resume resume) {
        Map<String, String> map = new java.util.HashMap<>();

        List<com.resumestudio.reviewer.nlp.BulletEnricher.EnrichedBullet> enriched =
            resume.getEnrichedBullets();

        if (enriched != null && !enriched.isEmpty()) {
            // Weakest bullet → used for impact_evidence / bullets fixes
            enriched.stream()
                .filter(b -> !b.duplicateFlag())
                .min(java.util.Comparator.comparingDouble(b -> {
                    double v = b.metricDetected() ? 1.0 : 0.0;
                    double verb = switch (b.actionVerbQuality()) {
                        case "STRONG" -> 1.0; case "MEDIUM" -> 0.6; default -> 0.1;
                    };
                    return v * 0.3 + verb * 0.2 + b.specificityScore() / 10.0 * 0.5;
                }))
                .ifPresent(b -> {
                    map.put("bullets", b.text());
                    map.put("impact_evidence", b.text());
                });

            // Duplicate bullet → used for duplicate fixes
            enriched.stream()
                .filter(com.resumestudio.reviewer.nlp.BulletEnricher.EnrichedBullet::duplicateFlag)
                .findFirst()
                .ifPresent(b -> map.put("duplicate_bullets", b.text()));
        }

        // Summary text → used for summary fixes
        if (resume.getSummaryText() != null && !resume.getSummaryText().isBlank()) {
            map.put("summary", resume.getSummaryText());
        }

        // Missing must-have skill → used for skill_match fixes
        if (signals.getMustHaveResults() != null) {
            signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING)
                .findFirst()
                .ifPresent(r -> map.put("skill_match", "\"" + r.getJdSkill() + "\" not found on resume"));
        }

        return map;
    }

    /**
     * Overrides AI-fabricated "before" text with actual resume text.
     * The AI's "after" rewrite is kept — only "before" is replaced.
     */
    // Signal IDs that have extractable resume text — all others get beforeAfter suppressed
    private static final java.util.Set<String> TEXT_INJECTABLE_SIGNALS = java.util.Set.of(
        "bullets", "impact_evidence", "summary", "skill_match", "duplicate_bullets"
    );

    private void injectRealBeforeText(FeedbackReport.Builder builder, Map<String, String> signalTextMap) {
        FeedbackReport report = builder.build();
        if (report.getFixes() == null) return;
        for (Fix fix : report.getFixes()) {
            String signalId = fix.getSignalId();
            if (signalId == null) continue;
            if (TEXT_INJECTABLE_SIGNALS.contains(signalId)) {
                String realText = signalTextMap.get(signalId);
                // Only inject if it's actual resume text — absence reason strings start with '"'
                if (realText != null && !realText.startsWith("\"")) {
                    if (fix.getBeforeAfter() == null) fix.setBeforeAfter(new Fix.BeforeAfter(realText, null));
                    else fix.getBeforeAfter().setBefore(realText);
                } else {
                    // No real text available (skill is missing) — keep AI's beforeAfter or clear it
                    if (fix.getBeforeAfter() != null && fix.getBeforeAfter().getBefore() != null
                            && fix.getBeforeAfter().getBefore().contains("not found on resume")) {
                        fix.setBeforeAfter(null);
                    }
                }
            } else {
                fix.setBeforeAfter(null);
            }
        }
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private String buildPrompt(ResumeSignals signals, ClassificationResult classification,
                                JobDescription jd, Resume resume,
                                CoherenceEngine.CoherenceResult coherence) {
        // Always use the tailoringScore passed from ResumeScore (single source of truth)
        // This overload should not be called directly — use the one with explicit tailoringScore
        return buildPrompt(signals, classification, jd, resume, coherence, classification.tailoringScore());
    }

    private String buildPrompt(ResumeSignals signals, ClassificationResult classification,
                                JobDescription jd, Resume resume,
                                CoherenceEngine.CoherenceResult coherence, int tailoringScore) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are simulating an experienced technical recruiter reading a resume.\n");
        sb.append("You receive pre-computed signals. Your job is to interpret them — not recompute them.\n");
        sb.append("Output ONLY valid JSON matching this schema exactly:\n");
        sb.append(SCHEMA).append("\n\n");
        sb.append("Rules:\n");
        sb.append("- narrative: 3-5 sentences, warm but honest, past tense\n");
        sb.append("- never mention scores or confidence numbers\n");
        sb.append("- fixes: maximum 3, ranked by impact\n");
        sb.append("- CRITICAL: the verdict is ").append(classification.verdict())
          .append(" — your narrative MUST reflect this. Do not describe the candidate as a strong fit if the verdict is WEAK_FIT or NO_FIT.\n");
        if (classification.jdClarity() == JdClarity.LOW)
            sb.append("- jdClarity is LOW: hedge language in narrative, acknowledge uncertainty\n\n");

        sb.append("=== JD ===\n");
        if (jd.getTrimmedText() != null) {
            sb.append(sanitizeForPrompt(jd.getTrimmedText())).append("\n");
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
            topBullets.forEach(b -> sb.append("- ").append(sanitizeForPrompt(b)).append("\n"));
        }

        // Weakest bullet — give the AI the specific text to reference in fixes
        if (resume.getEnrichedBullets() != null && !resume.getEnrichedBullets().isEmpty()) {
            resume.getEnrichedBullets().stream()
                .filter(b -> !b.duplicateFlag())
                .min(java.util.Comparator.comparingDouble(b -> {
                    double v = b.metricDetected() ? 1.0 : 0.0;
                    double verb = switch (b.actionVerbQuality()) { case "STRONG" -> 1.0; case "MEDIUM" -> 0.6; default -> 0.1; };
                    return v * 0.4 + verb * 0.3 + b.specificityScore() / 10.0 * 0.3;
                }))
                .ifPresent(b -> sb.append("Weakest bullet (use this as the 'before' in your bullets fix): ")
                    .append(sanitizeForPrompt(b.text())).append("\n"));
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

        // NLU signals — skill credibility and task alignment
        if (signals.getSkillCredibilityScore() < 0.5) {
            sb.append("skill_credibility: LOW (").append(String.format("%.0f%%", signals.getSkillCredibilityScore() * 100))
              .append(" — claimed skills are not well-evidenced in bullets)\n");
        } else if (signals.getSkillCredibilityScore() > 0.75) {
            sb.append("skill_credibility: HIGH (skills are well-evidenced with outcomes and metrics)\n");
        }
        if (signals.isHasUnevidencedSkills()) {
            sb.append("unevidenced_skills: true — some skills appear only in the skills section with no bullet evidence\n");
        }
        if (!signals.getImpliedSkillsFound().isEmpty()) {
            sb.append("implied_skills_found: ").append(String.join(", ", signals.getImpliedSkillsFound()))
              .append(" (inferred from ontology — candidate likely knows these even if not listed)\n");
        }
        if (signals.getDemonstratedSeniorityLevel() > 0) {
            String[] levelNames = {"", "Intern", "Junior", "Mid", "Senior", "Staff", "Principal"};
            int level = Math.min(signals.getDemonstratedSeniorityLevel(), 6);
            sb.append("demonstrated_seniority: ").append(levelNames[level])
              .append(" (inferred from bullet verb language — independent of title)\n");
        }

        // Semantic alignment signals
        if (signals.getIntentAlignmentScore() > 0.5) {
            sb.append("intent_alignment: ").append(String.format("%.0f%%", signals.getIntentAlignmentScore() * 100))
              .append(" — bullets semantically match JD responsibilities\n");
            if (signals.getTopAlignedBullet() != null) {
                sb.append("best_aligned_bullet: \"").append(signals.getTopAlignedBullet()).append("\"\n");
            }
        } else if (signals.getIntentAlignmentScore() > 0) {
            sb.append("intent_alignment: LOW — bullet language doesn't closely match what the JD asks candidates to do\n");
        }
        if (!signals.getShallowSkills().isEmpty()) {
            sb.append("shallow_skills: ").append(String.join(", ", signals.getShallowSkills()))
              .append(" (required skills mentioned only once — depth of expertise unclear)\n");
        }
        if (signals.getKeywordDensityScore() < 0.3) {
            sb.append("keyword_density: LOW — most required skills appear only once; ATS expects primary keywords 3-5x\n");
        } else if (signals.getKeywordDensityScore() > 0.7) {
            sb.append("keyword_density: GOOD — required skills appear multiple times across the resume\n");
        }

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
        sb.append("tailoringScore: ").append(tailoringScore).append("/10\n");
        sb.append("jdClarity: ").append(classification.jdClarity()).append("\n");
        sb.append("interviewLikelihood: ").append(classification.interviewLikelihood()).append("\n");

        return sb.toString();
    }

    // ── Groq HTTP call ────────────────────────────────────────────────────────

    /**
     * Sanitizes user-provided text before injecting into AI prompt.
     * Strips common prompt injection patterns.
     */
    private static String sanitizeForPrompt(String text) {
        if (text == null) return "";
        return text
            // Strip role injection attempts
            .replaceAll("(?i)(ignore|disregard|forget).{0,30}(previous|above|instructions|system|prompt)", "[REDACTED]")
            .replaceAll("(?i)you are (now|a|an) ", "")
            // Strip markdown that could confuse the JSON schema boundary
            .replaceAll("```json", "")
            .replaceAll("```", "")
            // Limit to printable ASCII + common Unicode — strip control chars
            .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
            .trim();
    }

    /**
     * Truncates prompt to maxChars by dropping low-priority sections first,
     * then truncating at the last sentence boundary.
     *
     * Drop order (least → most important):
     *   1. Top bullets section
     *   2. Coherence flags section
     *   3. JD trimmed text → replaced with role+skills summary
     * If still over limit, truncate at last '.' before the limit.
     */
    private String smartTruncate(String prompt, int maxChars) {
        if (prompt.length() <= maxChars) return prompt;

        // Drop top bullets section
        String trimmed = prompt.replaceAll("(?s)Top bullets:.*?(?=\\n===|\\Z)", "");
        if (trimmed.length() <= maxChars) return trimmed;

        // Drop coherence flags section
        trimmed = trimmed.replaceAll("(?s)=== Coherence flags ===.*?(?=\\n===|\\Z)", "");
        if (trimmed.length() <= maxChars) return trimmed;

        // Drop JD trimmed text block (keep only role title + required skills line)
        trimmed = trimmed.replaceAll("(?s)(=== JD ===\n).*?(?=\n=== Candidate)", "$1[JD text omitted — see skill matching section]\n");
        if (trimmed.length() <= maxChars) return trimmed;

        // Last resort: truncate at last sentence boundary before limit
        String cut = trimmed.substring(0, maxChars);
        int lastDot = cut.lastIndexOf('.');
        return lastDot > maxChars / 2 ? cut.substring(0, lastDot + 1) : cut;
    }

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
        return mergeAiOutput(builder, json, signals, classification, null);
    }

    private FeedbackReport.Builder mergeAiOutput(FeedbackReport.Builder builder, String json,
                                                   ResumeSignals signals, ClassificationResult classification, Resume resume) {
        try {
            JsonNode root = mapper.readTree(json);

            String summaryLine = root.path("summaryLine").asText(null);
            builder.summaryLine(summaryLine != null ? summaryLine : "Review complete.");
            String narrative = root.path("narrative").asText(null);
            builder.narrative(narrative != null ? narrative : "");
            // momentOfDecision is set deterministically in ReviewerPipeline — do NOT let AI overwrite it

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
                    // Validate signalId — reject invented IDs, fall back to skill_match
                    String rawSignalId = f.path("signalId").asText(null);
                    fix.setSignalId(VALID_SIGNAL_IDS.contains(rawSignalId) ? rawSignalId : "skill_match");
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

            // signals — deterministic structure from FeedbackGenerator only
            FeedbackGenerator.FeedbackOutput fb = resume != null
                ? fallback.generate(signals, classification.verdict(), resume)
                : fallback.generate(signals, classification.verdict());
            List<Signal> cappedSignals = fb.signals().stream().limit(6).toList();
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
        return applyFallback(builder, signals, classification, null);
    }

    private FeedbackReport.Builder applyFallback(FeedbackReport.Builder builder,
                                                   ResumeSignals signals, ClassificationResult classification, Resume resume) {
        FeedbackGenerator.FeedbackOutput fb = resume != null
            ? fallback.generate(signals, classification.verdict(), resume)
            : fallback.generate(signals, classification.verdict());

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
            // Issue 4: cap fallback fixes to 3, same as AI path
            .fixes(fb.fixes() != null ? fb.fixes().stream().limit(3).collect(java.util.stream.Collectors.toList()) : List.of())
            .narrative("")
            .narrativeTone(NarrativeTone.NEUTRAL)
            .differentiators(diffs)
            .recruiterGutFeel(null);
    }

    // ── Schema sent to AI ─────────────────────────────────────────────────────

    private static final String SCHEMA = """
        {
          "summaryLine": "string — one sentence shown at top of UI",
          "narrative": "string — 3-5 sentences recruiter perspective",
          "narrativeTone": "ENCOURAGING | NEUTRAL | CAUTIONARY",
          "recruiterGutFeel": {
            "firstImpression": "string",
            "trustLevel": "HIGH | MEDIUM | LOW",
            "observation": "string"
          },
          "differentiators": [
            { "observation": "string", "impact": "HIGH | MEDIUM | LOW" }
          ],
          "fixes": [
            {
              "signalId": "skill_match | skills_format | yoe_fit | title_match | summary | bullets | format | format_quality | tailoring | chronology | company_context | filename | skill_age_mismatch",
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
        public final double temperature = 0.1; // Lower temperature = more deterministic output
        @com.fasterxml.jackson.annotation.JsonProperty("max_tokens")
        public final int maxTokens = 4000;

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

    /**
     * One AI call to enrich the top FAIL/WARN deep dive items with specific, contextual actions.
     *
     * Strategy: collect up to 8 FAIL/WARN items across all sections, send their quoted text
     * and current generic action to the AI, get back specific rewrites.
     * All structural data (verdict, score, section) stays deterministic — AI only improves `action`.
     */
    public void enrichDeepDive(com.resumestudio.reviewer.model.DeepDiveReport report,
                                com.resumestudio.reviewer.model.JobDescription jd) {
        // Collect top items needing AI enrichment (FAIL first, then WARN, max 8)
        List<com.resumestudio.reviewer.model.DeepDiveReport.ReviewItem> targets = new java.util.ArrayList<>();
        for (com.resumestudio.reviewer.model.DeepDiveReport.Section section : report.getSections()) {
            for (com.resumestudio.reviewer.model.DeepDiveReport.ReviewItem item : section.getItems()) {
                if (("FAIL".equals(item.getVerdict()) || "WARN".equals(item.getVerdict()))
                        && item.getQuote() != null && !item.getQuote().isBlank()
                        && item.getAction() != null) {
                    targets.add(item);
                }
            }
        }
        // Sort: FAIL first, then by score ascending (worst first)
        targets.sort(java.util.Comparator
            .comparing((com.resumestudio.reviewer.model.DeepDiveReport.ReviewItem i) -> "FAIL".equals(i.getVerdict()) ? 0 : 1)
            .thenComparingInt(com.resumestudio.reviewer.model.DeepDiveReport.ReviewItem::getScore));
        if (targets.size() > 8) targets = targets.subList(0, 8);
        if (targets.isEmpty()) return;

        String prompt = buildDeepDivePrompt(targets, jd);
        try {
            String raw = callGroq(prompt);
            mergeDeepDiveOutput(raw, targets);
        } catch (Exception first) {
            log.warn("Deep dive AI enrichment failed ({}), retrying", first.getMessage());
            try {
                String raw = callGroq(prompt);
                mergeDeepDiveOutput(raw, targets);
            } catch (Exception second) {
                log.warn("Deep dive AI enrichment retry failed — keeping rule-based actions");
            }
        }
    }

    private String buildDeepDivePrompt(
            List<com.resumestudio.reviewer.model.DeepDiveReport.ReviewItem> items,
            com.resumestudio.reviewer.model.JobDescription jd) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a professional resume reviewer. For each item, write a specific, contextual 'action' ");
        sb.append("that references the exact quoted text and tells the candidate precisely what to write or change. ");
        sb.append("Be concrete — include example rewrites where possible. Max 2 sentences. ");
        sb.append("Do NOT give generic advice. Do NOT repeat the observation. Reference the quote directly.\n\n");
        sb.append("Role: ").append(jd.getRoleTitle() != null ? jd.getRoleTitle() : "Software Engineer").append("\n");
        if (!jd.getMustHaveSkills().isEmpty()) {
            sb.append("Required skills: ").append(String.join(", ", jd.getMustHaveSkills().stream().limit(8).toList())).append("\n");
        }
        sb.append("\nOutput ONLY valid JSON:\n");
        sb.append("{ \"items\": [ { \"index\": 0, \"action\": \"string\" }, ... ] }\n\n");
        sb.append("Items:\n");
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            sb.append(i).append(". [").append(item.getType()).append("] verdict=").append(item.getVerdict())
              .append(" score=").append(item.getScore())
              .append("\n   quote: \"").append(item.getQuote().replace("\"", "'")).append("\"")
              .append("\n   observation: ").append(item.getObservation()).append("\n");
        }
        return sb.toString();
    }

    private void mergeDeepDiveOutput(String json,
            List<com.resumestudio.reviewer.model.DeepDiveReport.ReviewItem> targets) {
        try {
            JsonNode root = mapper.readTree(json);
            for (JsonNode node : root.path("items")) {
                int idx = node.path("index").asInt(-1);
                String aiAction = node.path("action").asText(null);
                if (idx < 0 || idx >= targets.size() || aiAction == null || aiAction.isBlank()) continue;

                String ruleAction = targets.get(idx).getAction();

                // Only use AI action if it is:
                // 1. Longer than the rule-based action (more specific)
                // 2. References the actual quote text (contextual, not generic)
                // 3. Not just a rephrasing of the rule action (different enough)
                boolean aiIsLonger = aiAction.length() > (ruleAction != null ? ruleAction.length() : 0);
                String quote = targets.get(idx).getQuote();
                boolean aiReferencesQuote = quote != null && !quote.isBlank()
                    && aiAction.toLowerCase().contains(quote.substring(0, Math.min(10, quote.length())).toLowerCase().replaceAll("[^a-z0-9 ]", "").trim());
                boolean aiIsDifferent = ruleAction == null
                    || !aiAction.substring(0, Math.min(30, aiAction.length()))
                        .equalsIgnoreCase(ruleAction.substring(0, Math.min(30, ruleAction.length())));

                // Use AI if it's longer AND (references the quote OR is clearly different from rule)
                if (aiIsLonger && (aiReferencesQuote || aiIsDifferent)) {
                    targets.get(idx).setAction(aiAction);
                }
                // Otherwise keep the rule-based action — it's more reliable for structured items
            }
        } catch (Exception e) {
            log.debug("Failed to merge deep dive AI output: {}", e.getMessage());
        }
    }
}
