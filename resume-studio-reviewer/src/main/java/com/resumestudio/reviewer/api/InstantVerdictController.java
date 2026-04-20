package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.extraction.JdParserService;
import com.resumestudio.reviewer.model.JobDescription;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Instant verdict - 5-second signal without login or file upload.
 * The product's front door.
 */
@RestController
@RequestMapping("/api/check")
public class InstantVerdictController {

    private final JdParserService jdParser;

    public InstantVerdictController(JdParserService jdParser) {
        this.jdParser = jdParser;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> instantCheck(
        @RequestBody Map<String, String> request
    ) {
        String jdText = request.get("jobDescription");
        String resumeText = request.get("resumeText"); // Optional - can be LinkedIn URL or pasted text
        
        if (jdText == null || jdText.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Job description required"));
        }

        // Parse JD quickly
        JobDescription jd = jdParser.parse(jdText);
        
        // Quick analysis without full pipeline
        String verdict = determineQuickVerdict(resumeText, jd);
        List<String> keyPoints = extractKeyPoints(resumeText, jd);
        String recommendation = getRecommendation(verdict);
        
        return ResponseEntity.ok(Map.of(
            "verdict", verdict,
            "keyPoints", keyPoints,
            "recommendation", recommendation,
            "roleTitle", jd.getTitle() != null ? jd.getTitle() : "Unknown Role",
            "requiredYoe", jd.getYoeMin() != null ? jd.getYoeMin() : 0
        ));
    }

    private String determineQuickVerdict(String resumeText, JobDescription jd) {
        if (resumeText == null || resumeText.isBlank()) {
            return "UNKNOWN";
        }
        
        // Quick skill overlap check
        int mustHaveCount = 0;
        int mustHaveTotal = jd.getMustHaveSkills().size();
        
        String resumeLower = resumeText.toLowerCase();
        for (var skill : jd.getMustHaveSkills()) {
            if (resumeLower.contains(skill.getName().toLowerCase())) {
                mustHaveCount++;
            }
        }
        
        double matchRatio = mustHaveTotal > 0 ? (double) mustHaveCount / mustHaveTotal : 0;
        
        // Quick YOE check (simple heuristic)
        int estimatedYoe = estimateYoeFromText(resumeText);
        int requiredYoe = jd.getYoeMin() != null ? jd.getYoeMin() : 0;
        int yoeGap = requiredYoe - estimatedYoe;
        
        // Determine verdict
        if (matchRatio >= 0.8 && yoeGap <= 0) {
            return "STRONG_FIT";
        } else if (matchRatio >= 0.6 && yoeGap <= 1) {
            return "POSSIBLE_FIT";
        } else if (matchRatio >= 0.4 || yoeGap <= 2) {
            return "REACH";
        } else {
            return "SKIP";
        }
    }

    private List<String> extractKeyPoints(String resumeText, JobDescription jd) {
        List<String> points = new ArrayList<>();
        
        if (resumeText == null || resumeText.isBlank()) {
            points.add("Upload your resume for detailed analysis");
            return points;
        }
        
        // YOE gap
        int estimatedYoe = estimateYoeFromText(resumeText);
        int requiredYoe = jd.getYoeMin() != null ? jd.getYoeMin() : 0;
        int yoeGap = requiredYoe - estimatedYoe;
        
        if (yoeGap > 0) {
            points.add(String.format("You're %d year%s short on experience. Reviewers may skim past.", 
                yoeGap, yoeGap == 1 ? "" : "s"));
        } else if (yoeGap == 0) {
            points.add("Your experience level matches perfectly.");
        }
        
        // Skill gaps
        List<String> missingSkills = new ArrayList<>();
        String resumeLower = resumeText.toLowerCase();
        for (var skill : jd.getMustHaveSkills()) {
            if (!resumeLower.contains(skill.getName().toLowerCase())) {
                missingSkills.add(skill.getName());
            }
        }
        
        if (!missingSkills.isEmpty() && missingSkills.size() <= 3) {
            points.add("Missing key skills: " + String.join(", ", missingSkills));
        } else if (missingSkills.size() > 3) {
            points.add(String.format("Missing %d key skills - this is a significant gap.", missingSkills.size()));
        }
        
        // Domain check (simple keyword matching)
        if (jd.getTitle() != null && !resumeLower.contains(jd.getTitle().toLowerCase())) {
            points.add("Your resume doesn't mention this exact role title.");
        }
        
        if (points.isEmpty()) {
            points.add("Strong alignment with job requirements.");
        }
        
        return points;
    }

    private String getRecommendation(String verdict) {
        return switch (verdict) {
            case "STRONG_FIT" -> "Definitely apply. You're a strong match.";
            case "POSSIBLE_FIT" -> "Worth applying if you're interested in the role.";
            case "REACH" -> "Worth applying if you'd take this 3x out of 10. Skip if you have 10+ other roles to apply to today.";
            case "SKIP" -> "Consider focusing on roles that better match your profile.";
            default -> "Upload your resume for a detailed verdict.";
        };
    }

    private int estimateYoeFromText(String text) {
        // Simple heuristic: count year ranges
        // This is a rough estimate - full pipeline does better
        int maxYears = 0;
        String[] lines = text.split("\n");
        
        for (String line : lines) {
            // Look for patterns like "2020-2023" or "2020-Present"
            if (line.matches(".*\\d{4}\\s*[-–—]\\s*(\\d{4}|Present|Current).*")) {
                maxYears += 2; // Rough estimate
            }
        }
        
        return Math.min(maxYears, 15); // Cap at 15 years
    }
}
