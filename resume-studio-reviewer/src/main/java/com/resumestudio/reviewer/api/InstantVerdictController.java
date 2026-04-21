package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.extraction.JdParserService;
import com.resumestudio.reviewer.model.JobDescription;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instant verdict - fast signal without login or file upload.
 * Delivers directional fit only; full review endpoint remains the source of truth.
 */
@RestController
@RequestMapping("/api/check")
public class InstantVerdictController {

    private static final Pattern YEAR_RANGE_PATTERN = Pattern.compile(
        "\\b(19\\d{2}|20\\d{2})\\s*[-–—]\\s*(19\\d{2}|20\\d{2}|present|current|now)\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern EXPLICIT_YEARS_PATTERN = Pattern.compile(
        "\\b(\\d{1,2})(?:\\+)?\\s+years?\\b",
        Pattern.CASE_INSENSITIVE);

    private final JdParserService jdParser;

    public InstantVerdictController(JdParserService jdParser) {
        this.jdParser = jdParser;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> instantCheck(@RequestBody Map<String, String> request) {
        String jdText = request.get("jobDescription");
        String resumeText = request.get("resumeText");

        if (jdText == null || jdText.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Job description required"));
        }

        JobDescription jd = jdParser.parse(jdText);
        String verdict = determineQuickVerdict(resumeText, jd);
        List<String> keyPoints = extractKeyPoints(resumeText, jd);
        String recommendation = getRecommendation(verdict);

        String roleTitle = jd.getRoleTitle() != null && !jd.getRoleTitle().isBlank()
            ? jd.getRoleTitle()
            : "Unknown Role";
        int requiredYoe = jd.getYoeMin() != null ? (int) Math.round(jd.getYoeMin()) : 0;

        return ResponseEntity.ok(Map.of(
            "verdict", verdict,
            "keyPoints", keyPoints,
            "recommendation", recommendation,
            "roleTitle", roleTitle,
            "requiredYoe", Math.max(0, requiredYoe)
        ));
    }

    private String determineQuickVerdict(String resumeText, JobDescription jd) {
        if (resumeText == null || resumeText.isBlank()) {
            return "UNKNOWN";
        }

        List<String> mustHaveSkills = jd.getMustHaveSkills() != null ? jd.getMustHaveSkills() : List.of();
        long mustHaveTotal = mustHaveSkills.size();
        long matchedSkills = mustHaveSkills.stream().filter(skill -> matchesSkill(resumeText, skill)).count();

        double matchRatio = mustHaveTotal > 0 ? (double) matchedSkills / mustHaveTotal : 0.5;
        int estimatedYoe = estimateYoeFromText(resumeText);
        double requiredYoe = jd.getYoeMin() != null ? jd.getYoeMin() : 0.0;
        double yoeGap = requiredYoe - estimatedYoe;

        if (mustHaveTotal == 0) {
            return yoeGap <= 0.5 ? "POSSIBLE_FIT" : "REACH";
        }

        if (matchRatio >= 0.75 && yoeGap <= 0.5) {
            return "STRONG_FIT";
        }
        if (matchRatio >= 0.55 && yoeGap <= 1.5) {
            return "POSSIBLE_FIT";
        }
        if (matchRatio >= 0.35 || yoeGap <= 2.5) {
            return "REACH";
        }
        return "SKIP";
    }

    private List<String> extractKeyPoints(String resumeText, JobDescription jd) {
        List<String> points = new ArrayList<>();

        if (resumeText == null || resumeText.isBlank()) {
            points.add("Add your resume text to unlock personalized fit signals.");
            points.add("Without resume evidence, this is only a JD-level directional check.");
            return points;
        }

        List<String> mustHaveSkills = jd.getMustHaveSkills() != null ? jd.getMustHaveSkills() : List.of();
        List<String> missingSkills = mustHaveSkills.stream()
            .filter(skill -> !matchesSkill(resumeText, skill))
            .toList();

        if (!mustHaveSkills.isEmpty()) {
            int matched = mustHaveSkills.size() - missingSkills.size();
            points.add("Matched " + matched + " of " + mustHaveSkills.size() + " required skills.");
        }

        if (!missingSkills.isEmpty()) {
            int limit = Math.min(3, missingSkills.size());
            String topMissing = String.join(", ", missingSkills.subList(0, limit));
            if (missingSkills.size() > limit) {
                topMissing += ", and " + (missingSkills.size() - limit) + " more";
            }
            points.add("Missing core skills: " + topMissing + ".");
        }

        int estimatedYoe = estimateYoeFromText(resumeText);
        double requiredYoe = jd.getYoeMin() != null ? jd.getYoeMin() : 0.0;
        double yoeGap = requiredYoe - estimatedYoe;

        if (requiredYoe > 0) {
            if (yoeGap > 0.5) {
                int roundedGap = (int) Math.ceil(yoeGap);
                points.add("Experience gap: about " + roundedGap + " year" + (roundedGap == 1 ? "" : "s") + " short versus the JD minimum.");
            } else {
                points.add("Experience level is broadly aligned with the JD minimum.");
            }
        }

        String roleTitle = jd.getRoleTitle();
        if (roleTitle != null && !roleTitle.isBlank() && !matchesSkill(resumeText, roleTitle)) {
            points.add("Your resume does not explicitly mirror the target role title.");
        }

        if (points.isEmpty()) {
            points.add("Strong alignment detected on skills and experience.");
        }

        return points;
    }

    private String getRecommendation(String verdict) {
        return switch (verdict) {
            case "STRONG_FIT" -> "Definitely apply. Your profile aligns strongly with this role.";
            case "POSSIBLE_FIT" -> "Worth applying. Improve skills visibility and role-specific wording before submitting.";
            case "REACH" -> "Apply selectively. Tailor heavily toward missing core requirements first.";
            case "SKIP" -> "Likely low-conversion as-is. Target closer-fit roles or close the key gaps first.";
            default -> "Add resume text for a personalized verdict.";
        };
    }

    private int estimateYoeFromText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int explicitYears = 0;
        Matcher explicitMatcher = EXPLICIT_YEARS_PATTERN.matcher(text);
        while (explicitMatcher.find()) {
            int years = Integer.parseInt(explicitMatcher.group(1));
            explicitYears = Math.max(explicitYears, years);
        }

        int currentYear = Year.now().getValue();
        Integer minStart = null;
        Integer maxEnd = null;

        Matcher rangeMatcher = YEAR_RANGE_PATTERN.matcher(text);
        while (rangeMatcher.find()) {
            int start = Integer.parseInt(rangeMatcher.group(1));
            String endRaw = rangeMatcher.group(2).toLowerCase(Locale.ROOT);
            int end = ("present".equals(endRaw) || "current".equals(endRaw) || "now".equals(endRaw))
                ? currentYear
                : Integer.parseInt(endRaw);

            if (end < start) {
                continue;
            }
            minStart = minStart == null ? start : Math.min(minStart, start);
            maxEnd = maxEnd == null ? end : Math.max(maxEnd, end);
        }

        int spanYears = 0;
        if (minStart != null && maxEnd != null) {
            spanYears = Math.max(0, Math.min(25, maxEnd - minStart));
        }

        return Math.max(explicitYears, spanYears);
    }

    private boolean matchesSkill(String text, String skill) {
        if (text == null || skill == null || skill.isBlank()) {
            return false;
        }

        String resumeLower = text.toLowerCase(Locale.ROOT);
        String skillLower = skill.toLowerCase(Locale.ROOT).trim();

        String escapedSkill = Pattern.quote(skillLower);
        Pattern strictBoundary = Pattern.compile("(^|[^a-z0-9+#])" + escapedSkill + "($|[^a-z0-9+#])");
        if (strictBoundary.matcher(resumeLower).find()) {
            return true;
        }

        // Fallback for cases like punctuation/spacing variants (e.g. "nodejs" vs "node.js").
        String normalizedResume = normalizeForMatch(resumeLower);
        String normalizedSkill = normalizeForMatch(skillLower);
        return !normalizedSkill.isBlank() && normalizedResume.contains(normalizedSkill);
    }

    private String normalizeForMatch(String text) {
        return text
            .replaceAll("[^a-z0-9+#]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
