package com.resumestudio.reviewer.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic section classifier and context-aware YOE extractor.
 */
@Component
public class SemanticExtractor {

    private static final Logger log = LoggerFactory.getLogger(SemanticExtractor.class);

    private static final Set<String> SUMMARY_HEADERS = Set.of(
        "summary", "professional summary", "profile", "about", "about me",
        "objective", "career objective", "professional profile", "overview",
        "personal statement", "executive summary"
    );

    private static final Set<String> EXPERIENCE_HEADERS = Set.of(
        "experience", "work experience", "professional experience", "employment",
        "work history", "career history", "employment history", "positions held",
        "relevant experience", "industry experience"
    );

    private static final Set<String> SKILLS_HEADERS = Set.of(
        "skills", "technical skills", "core competencies", "technologies",
        "tech stack", "tools", "expertise", "key skills", "competencies",
        "technical expertise", "programming languages", "languages & frameworks"
    );

    private static final Set<String> EDUCATION_HEADERS = Set.of(
        "education", "academic background", "qualifications", "degrees",
        "academic qualifications", "educational background"
    );

    private static final Set<String> PROJECTS_HEADERS = Set.of(
        "projects", "personal projects", "side projects", "open source",
        "portfolio", "notable projects", "key projects"
    );

    private static final Set<String> CERTIFICATIONS_HEADERS = Set.of(
        "certifications", "certificates", "accreditations", "licenses",
        "professional certifications", "awards"
    );

    private static final Pattern YOE_NUMBER = Pattern.compile(
        "(\\d{1,2}(?:\\.5)?(?:\\.\\d)?)\\s*\\+?\\s*years?",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern YOE_NEGATIVE_CONTEXT = Pattern.compile(
        "\\b(ago|old|founded|established|since|history|over the last|in the last|" +
        "within the last|past \\d|last \\d|more than \\d+ years ago)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern YOE_POSITIVE_CONTEXT = Pattern.compile(
        "\\b(experience|delivering|building|working|developing|engineering|" +
        "professional|industry|career|background|expertise|specialising|" +
        "specializing|focused|dedicated|proven|hands.on)\\b",
        Pattern.CASE_INSENSITIVE
    );

    public enum SectionType { SUMMARY, EXPERIENCE, SKILLS, EDUCATION, PROJECTS, CERTIFICATIONS, UNKNOWN }

    public static class SectionMap {
        public String summary;
        public String experience;
        public String skills;
        public String education;
        public String projects;
    }

    public SectionMap extractSections(String fullText) {
        SectionMap map = new SectionMap();
        if (fullText == null || fullText.isBlank()) return map;

        String[] lines = fullText.split("\n");
        List<int[]> sectionBoundaries = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isBlank()) continue;
            SectionType type = classifyHeader(trimmed);
            if (type != SectionType.UNKNOWN) {
                sectionBoundaries.add(new int[]{i, type.ordinal()});
            }
        }

        if (sectionBoundaries.isEmpty()) {
            log.warn("No section headers detected — falling back to full text for experience");
            map.experience = fullText;
            return map;
        }

        for (int b = 0; b < sectionBoundaries.size(); b++) {
            int startLine = sectionBoundaries.get(b)[0] + 1;
            int endLine = (b + 1 < sectionBoundaries.size())
                ? sectionBoundaries.get(b + 1)[0]
                : lines.length;

            SectionType type = SectionType.values()[sectionBoundaries.get(b)[1]];
            String content = joinLines(lines, startLine, endLine);

            switch (type) {
                case SUMMARY     -> { if (map.summary == null)    map.summary = content; }
                case EXPERIENCE  -> { if (map.experience == null) map.experience = content; }
                case SKILLS      -> { if (map.skills == null)     map.skills = content; }
                case EDUCATION   -> { if (map.education == null)  map.education = content; }
                case PROJECTS    -> { if (map.projects == null)   map.projects = content; }
                default -> {}
            }
        }

        return map;
    }

    public SectionType classifyHeader(String line) {
        String normalised = line.toLowerCase()
            .replaceAll("[:\\-–—|]", "")
            .replaceAll("\\s+", " ")
            .trim();

        if (normalised.split("\\s+").length > 6) return SectionType.UNKNOWN;

        if (SUMMARY_HEADERS.contains(normalised))        return SectionType.SUMMARY;
        if (EXPERIENCE_HEADERS.contains(normalised))     return SectionType.EXPERIENCE;
        if (SKILLS_HEADERS.contains(normalised))         return SectionType.SKILLS;
        if (EDUCATION_HEADERS.contains(normalised))      return SectionType.EDUCATION;
        if (PROJECTS_HEADERS.contains(normalised))       return SectionType.PROJECTS;
        if (CERTIFICATIONS_HEADERS.contains(normalised)) return SectionType.CERTIFICATIONS;

        return SectionType.UNKNOWN;
    }

    public Double extractYoe(String text) {
        if (text == null || text.isBlank()) return null;

        Matcher m = YOE_NUMBER.matcher(text);
        Double bestYoe = null;
        int bestScore = -1;

        while (m.find()) {
            double candidate;
            try {
                candidate = Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }

            if (candidate <= 0 || candidate > 50) continue;

            // Narrow window for negative context — prevents "2 years ago" from
            // poisoning a "5 years of experience" clause 30+ chars away.
            int negStart = Math.max(0, m.start() - 20);
            int negEnd = Math.min(text.length(), m.end() + 20);
            String negContext = text.substring(negStart, negEnd);
            if (YOE_NEGATIVE_CONTEXT.matcher(negContext).find()) continue;

            int start = Math.max(0, m.start() - 100);
            int end = Math.min(text.length(), m.end() + 100);
            String context = text.substring(start, end);

            int score = 0;
            Matcher pos = YOE_POSITIVE_CONTEXT.matcher(context);
            while (pos.find()) score++;

            if (score > bestScore) {
                bestScore = score;
                bestYoe = candidate;
            }
        }

        return bestYoe;
    }

    private String joinLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!lines[i].isBlank()) sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }
}
