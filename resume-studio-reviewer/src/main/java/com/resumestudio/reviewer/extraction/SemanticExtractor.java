package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.nlp.TextNormalizer;
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

    private final TextNormalizer normalizer;
    private final ResumeOntologyService resumeOntology;

    public SemanticExtractor(TextNormalizer normalizer, ResumeOntologyService resumeOntology) {
        this.normalizer = normalizer;
        this.resumeOntology = resumeOntology;
    }

    /** Check if a normalised header line matches any ontology synonym for the given fieldId. */
    private boolean matchesOntologySynonym(String normalised, String fieldId) {
        return resumeOntology.getSectionSynonyms(fieldId).contains(normalised);
    }

    private static final Set<String> SUMMARY_HEADERS = Set.of(
        "summary", "professional summary", "profile", "about", "about me",
        "objective", "career objective", "professional profile", "overview",
        "personal statement", "executive summary", "introduction", "bio",
        "professional bio", "career summary", "career profile", "background",
        "professional background", "who i am", "highlights", "career highlights",
        "professional highlights", "value proposition", "professional overview",
        "personal profile", "candidate profile", "brief", "snapshot"
    );

    private static final Set<String> EXPERIENCE_HEADERS = Set.of(
        "experience", "work experience", "professional experience", "employment",
        "work history", "career history", "employment history", "positions held",
        "relevant experience", "industry experience", "professional history",
        "career experience", "job history", "work background", "roles",
        "professional roles", "previous experience", "past experience",
        "recent experience", "internship experience", "internships",
        "volunteer experience", "volunteering", "freelance experience",
        "consulting experience", "contract experience", "research experience"
    );

    private static final Set<String> SKILLS_HEADERS = Set.of(
        "skills", "technical skills", "core competencies", "technologies",
        "tech stack", "tools", "expertise", "key skills", "competencies",
        "technical expertise", "programming languages", "languages & frameworks",
        "languages and frameworks", "technical competencies", "core skills",
        "skill set", "skillset", "technology stack", "tools & technologies",
        "tools and technologies", "technical tools", "software skills",
        "technical proficiencies", "proficiencies", "capabilities",
        "areas of expertise", "areas of competency", "knowledge",
        "technical knowledge", "languages & tools", "languages and tools",
        "frameworks & libraries", "frameworks and libraries",
        "programming skills", "it skills", "computer skills",
        "software proficiency", "technical stack", "stack"
    );

    private static final Set<String> EDUCATION_HEADERS = Set.of(
        "education", "academic background", "qualifications", "degrees",
        "academic qualifications", "educational background", "academics",
        "academic history", "educational history", "schooling",
        "academic credentials", "degrees & certifications",
        "degrees and certifications", "university", "college",
        "academic experience", "formal education", "training & education",
        "training and education"
    );

    private static final Set<String> PROJECTS_HEADERS = Set.of(
        "projects", "personal projects", "side projects", "open source",
        "portfolio", "notable projects", "key projects", "project work",
        "selected projects", "featured projects", "independent projects",
        "academic projects", "university projects", "college projects",
        "hobby projects", "pet projects", "github projects",
        "open source contributions", "contributions", "work samples",
        "sample work", "project portfolio", "technical projects",
        "software projects", "engineering projects", "research projects"
    );

    private static final Set<String> CERTIFICATIONS_HEADERS = Set.of(
        "certifications", "certificates", "accreditations", "licenses",
        "professional certifications", "awards", "achievements",
        "awards & achievements", "awards and achievements",
        "honors", "honours", "honors & awards", "honours & awards",
        "recognition", "professional development", "courses",
        "online courses", "training", "professional training",
        "continuing education", "credentials", "badges",
        "certifications & licenses", "certifications and licenses"
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
                case SUMMARY     -> {
                    if (map.summary == null) {
                        // Reject content that looks like a job entry or skill list
                        // rather than prose — prevents experience/skills leaking into summary slot
                        if (looksLikeSummaryProse(content)) map.summary = content;
                    }
                }
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
            .replace("&", "and")
            .replaceAll("[:\\-–—|]", "")
            .replaceAll("\\s+", " ")
            .trim();

        if (normalised.split("\\s+").length > 7) return SectionType.UNKNOWN;

        // Pass 1 — exact match against known header sets
        if (SUMMARY_HEADERS.contains(normalised))        return SectionType.SUMMARY;
        if (EXPERIENCE_HEADERS.contains(normalised))     return SectionType.EXPERIENCE;
        if (SKILLS_HEADERS.contains(normalised))         return SectionType.SKILLS;
        if (EDUCATION_HEADERS.contains(normalised))      return SectionType.EDUCATION;
        if (PROJECTS_HEADERS.contains(normalised))       return SectionType.PROJECTS;
        if (CERTIFICATIONS_HEADERS.contains(normalised)) return SectionType.CERTIFICATIONS;

        // Pass 2 — ontology synonyms (resume_ontology.json sectionSynonyms)
        if (matchesOntologySynonym(normalised, "PROFESSIONAL_SUMMARY"))  return SectionType.SUMMARY;
        if (matchesOntologySynonym(normalised, "WORK_EXPERIENCE"))        return SectionType.EXPERIENCE;
        if (matchesOntologySynonym(normalised, "SKILLS"))                 return SectionType.SKILLS;
        if (matchesOntologySynonym(normalised, "EDUCATION"))              return SectionType.EDUCATION;
        if (matchesOntologySynonym(normalised, "PROJECTS"))               return SectionType.PROJECTS;
        if (matchesOntologySynonym(normalised, "CERTIFICATIONS"))         return SectionType.CERTIFICATIONS;

        // Pass 2 — lemmatized bag-of-words fallback
        // Only apply to very short lines (≤ 2 words) that don't start with bullet characters
        // and don't look like proper nouns (institution names, company names, etc.)
        // This prevents "University College Dublin" from being classified as EDUCATION header
        String trimmed = line.trim();
        boolean isBullet = trimmed.startsWith("•") || trimmed.startsWith("-") || trimmed.startsWith("*")
            || trimmed.startsWith("▪") || trimmed.startsWith("◦") || trimmed.startsWith("➤")
            || trimmed.startsWith("►") || trimmed.startsWith("→");
        if (!isBullet && normalised.split("\\s+").length <= 2) {
            String fallback = normalizer.classifyHeaderFallback(normalised);
            if (fallback != null) {
                return switch (fallback) {
                    case "SUMMARY"          -> SectionType.SUMMARY;
                    case "EXPERIENCE"       -> SectionType.EXPERIENCE;
                    case "SKILLS"           -> SectionType.SKILLS;
                    case "EDUCATION"        -> SectionType.EDUCATION;
                    case "PROJECTS"         -> SectionType.PROJECTS;
                    case "CERTIFICATIONS"   -> SectionType.CERTIFICATIONS;
                    default                 -> SectionType.UNKNOWN;
                };
            }
        }

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

    /**
     * Returns true if the content looks like prose (a summary/objective paragraph)
     * rather than a job entry, skill list, or education block.
     *
     * Rejects content that:
     * - Has date ranges (job entries)
     * - Has more than 40% bullet lines (skill lists / experience bullets)
     * - Is fewer than 20 words (too short to be a real summary)
     */
    private boolean looksLikeSummaryProse(String content) {
        if (content == null || content.isBlank()) return false;
        String[] lines = content.split("\n");
        int totalLines = 0, bulletLines = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isBlank()) continue;
            totalLines++;
            if (t.matches("^[•\\-*▪◦➤►→].*")) bulletLines++;
            // Date range pattern — strong signal this is a job entry
            if (t.matches(".*\\b(19|20)\\d{2}\\b.*[–\\-—].*\\b((19|20)\\d{2}|present|current|now)\\b.*")
                    || t.matches(".*\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(19|20)\\d{2}\\b.*")) {
                return false;
            }
        }
        if (totalLines == 0) return false;
        // Reject if mostly bullets
        if (totalLines > 2 && (double) bulletLines / totalLines > 0.4) return false;
        return true;
    }

    private String joinLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!lines[i].isBlank()) sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }
}
