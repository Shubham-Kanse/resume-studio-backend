package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.JobDescription;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw job description text into a structured JobDescription.
 *
 * Key responsibilities:
 *  - Extract role title
 *  - Separate must-have from nice-to-have skills
 *  - Extract YOE requirement
 *  - Estimate IC level
 *  - Infer implied skills (e.g. Spring Boot → implies Java)
 */
@Service
public class JdParserService {

    // ── YOE patterns ──────────────────────────────────────────────────────────
    private static final Pattern YOE_MIN_MAX = Pattern.compile(
        "(\\d+)\\s*[–\\-]\\s*(\\d+)\\s*\\+?\\s*years?", Pattern.CASE_INSENSITIVE);

    private static final Pattern YOE_MIN_PLUS = Pattern.compile(
        "(\\d+)\\s*\\+\\s*years?", Pattern.CASE_INSENSITIVE);

    private static final Pattern YOE_AT_LEAST = Pattern.compile(
        "(?:at least|minimum|min\\.?)\\s+(\\d+)\\s*years?", Pattern.CASE_INSENSITIVE);

    // ── Section boundary patterns ─────────────────────────────────────────────
    private static final Pattern MUST_HAVE_SECTION = Pattern.compile(
        "^(requirements?|required|must[- ]have|essential|what you.ll need|" +
        "what we.re looking for|qualifications?|minimum qualifications?)\\s*:?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern NICE_TO_HAVE_SECTION = Pattern.compile(
        "^(nice[- ]to[- ]have|preferred|bonus|plus|desirable|" +
        "what would be great|what.s a plus|advantageous)\\s*:?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // ── Bullet/list item ─────────────────────────────────────────────────────
    private static final Pattern BULLET_ITEM = Pattern.compile(
        "^[•\\-*▪◦➤►→]\\s*(.+)$");

    // ── Title extraction ──────────────────────────────────────────────────────
    private static final Pattern ROLE_TITLE_INDICATORS = Pattern.compile(
        "(?:position|role|title|job title|we.re hiring|hiring a|looking for a?)\\s*:?\\s*([\\w\\s]{3,50})",
        Pattern.CASE_INSENSITIVE);

    // ── Seniority detection ───────────────────────────────────────────────────
    private static final Map<Pattern, Integer> SENIORITY_IC = new LinkedHashMap<>();
    static {
        SENIORITY_IC.put(Pattern.compile("\\b(junior|jr\\.?|entry[- ]level|graduate|intern)\\b", Pattern.CASE_INSENSITIVE), 1);
        SENIORITY_IC.put(Pattern.compile("\\b(associate)\\b", Pattern.CASE_INSENSITIVE), 2);
        SENIORITY_IC.put(Pattern.compile("\\b(mid[- ]level|intermediate)\\b", Pattern.CASE_INSENSITIVE), 3);
        SENIORITY_IC.put(Pattern.compile("\\b(senior|sr\\.?)\\b", Pattern.CASE_INSENSITIVE), 4);
        SENIORITY_IC.put(Pattern.compile("\\b(staff|principal|lead|architect)\\b", Pattern.CASE_INSENSITIVE), 5);
        SENIORITY_IC.put(Pattern.compile("\\b(distinguished|fellow|vp|director|head of|chief)\\b", Pattern.CASE_INSENSITIVE), 6);
    }

    // ── Implied skill map — if A is required, B is implied ───────────────────
    private static final Map<String, List<String>> IMPLIED_SKILLS = new HashMap<>();
    static {
        IMPLIED_SKILLS.put("spring boot", List.of("Java", "Maven/Gradle"));
        IMPLIED_SKILLS.put("react", List.of("JavaScript", "HTML", "CSS"));
        IMPLIED_SKILLS.put("angular", List.of("TypeScript", "JavaScript"));
        IMPLIED_SKILLS.put("django", List.of("Python"));
        IMPLIED_SKILLS.put("rails", List.of("Ruby"));
        IMPLIED_SKILLS.put("kubernetes", List.of("Docker", "Linux"));
        IMPLIED_SKILLS.put("terraform", List.of("Cloud infrastructure"));
        IMPLIED_SKILLS.put("next.js", List.of("React", "JavaScript"));
    }

    public JobDescription parse(String rawText) {
        JobDescription jd = new JobDescription();
        jd.setRawText(rawText);

        if (rawText == null || rawText.isBlank()) {
            jd.setParseConfidence(0.0);
            return jd;
        }

        extractTitle(rawText, jd);
        extractYoeRequirement(rawText, jd);
        extractSkills(rawText, jd);
        inferImpliedSkills(jd);
        estimateIcLevel(rawText, jd);
        detectContext(rawText, jd);

        jd.setNormalisedTitle(normalise(jd.getRoleTitle()));
        jd.setParseConfidence(computeConfidence(jd));
        jd.setWellStructured(
            MUST_HAVE_SECTION.matcher(rawText).find() || NICE_TO_HAVE_SECTION.matcher(rawText).find()
        );

        return jd;
    }

    // ── Title extraction ──────────────────────────────────────────────────────

    private void extractTitle(String text, JobDescription jd) {
        // Try explicit label
        Matcher labelMatcher = ROLE_TITLE_INDICATORS.matcher(text);
        if (labelMatcher.find()) {
            jd.setRoleTitle(labelMatcher.group(1).trim());
            return;
        }

        // Fallback: first line that looks like a job title
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.length() > 80) continue;
            String lower = trimmed.toLowerCase();
            if (lower.contains("engineer") || lower.contains("developer") || lower.contains("architect")
                || lower.contains("manager") || lower.contains("analyst") || lower.contains("designer")
                || lower.contains("scientist") || lower.contains("devops") || lower.contains("sre")) {
                jd.setRoleTitle(trimmed);
                return;
            }
        }

        jd.setRoleTitle("Unknown Role");
    }

    // ── YOE extraction ────────────────────────────────────────────────────────

    private void extractYoeRequirement(String text, JobDescription jd) {
        // Try range first: "3-5 years"
        Matcher rangeMatcher = YOE_MIN_MAX.matcher(text);
        if (rangeMatcher.find()) {
            jd.setYoeMin(Double.parseDouble(rangeMatcher.group(1)));
            jd.setYoeMax(Double.parseDouble(rangeMatcher.group(2)));
            jd.setYoeRawStatement(rangeMatcher.group());
            return;
        }

        // Try "5+ years"
        Matcher plusMatcher = YOE_MIN_PLUS.matcher(text);
        if (plusMatcher.find()) {
            jd.setYoeMin(Double.parseDouble(plusMatcher.group(1)));
            jd.setYoeMax(null); // open-ended
            jd.setYoeRawStatement(plusMatcher.group());
            return;
        }

        // Try "at least 3 years"
        Matcher atLeastMatcher = YOE_AT_LEAST.matcher(text);
        if (atLeastMatcher.find()) {
            jd.setYoeMin(Double.parseDouble(atLeastMatcher.group(1)));
            jd.setYoeRawStatement(atLeastMatcher.group());
        }
    }

    // ── Skills extraction ─────────────────────────────────────────────────────

    private void extractSkills(String text, JobDescription jd) {
        String[] lines = text.split("\n");
        SectionContext context = SectionContext.UNKNOWN;

        List<String> mustHaves = new ArrayList<>();
        List<String> niceToHaves = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            // Detect section transitions
            if (MUST_HAVE_SECTION.matcher(trimmed).matches()) {
                context = SectionContext.MUST_HAVE;
                continue;
            }
            if (NICE_TO_HAVE_SECTION.matcher(trimmed).matches()) {
                context = SectionContext.NICE_TO_HAVE;
                continue;
            }

            // Check inline signals: "Required: Java" or "Preferred: Kotlin"
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("required:") || lower.startsWith("must have:") || lower.startsWith("essential:")) {
                context = SectionContext.MUST_HAVE;
            } else if (lower.startsWith("preferred:") || lower.startsWith("nice to have:") || lower.startsWith("bonus:")) {
                context = SectionContext.NICE_TO_HAVE;
            }

            // Extract tech tokens from bullet items or inline lists
            Matcher bulletMatcher = BULLET_ITEM.matcher(trimmed);
            String content = bulletMatcher.matches() ? bulletMatcher.group(1) : trimmed;

            List<String> extracted = extractTechTokens(content);
            if (context == SectionContext.NICE_TO_HAVE) {
                niceToHaves.addAll(extracted);
            } else if (context == SectionContext.MUST_HAVE || !extracted.isEmpty()) {
                mustHaves.addAll(extracted);
            }
        }

        // Deduplicate
        jd.setMustHaveSkills(deduplicate(mustHaves));
        jd.setNiceToHaveSkills(deduplicate(niceToHaves));
    }

    private List<String> extractTechTokens(String line) {
        List<String> tokens = new ArrayList<>();
        // Known tech keyword pattern — extract by matching known tech terms
        Pattern techPattern = Pattern.compile(
            "\\b(Java|Python|Go|Golang|Rust|Kotlin|TypeScript|JavaScript|C\\+\\+|C#|Scala|" +
            "Spring Boot|Spring|Hibernate|JPA|Quarkus|Micronaut|" +
            "React|Angular|Vue|Next\\.js|Node\\.js|Express|" +
            "AWS|GCP|Azure|Kubernetes|Docker|Terraform|Ansible|Helm|" +
            "PostgreSQL|MySQL|MongoDB|Redis|Cassandra|DynamoDB|Elasticsearch|" +
            "Kafka|RabbitMQ|gRPC|REST|GraphQL|" +
            "Git|GitHub|GitLab|CI/CD|Jenkins|GitHub Actions|" +
            "Linux|Unix|Bash|Shell|" +
            "Microservices|Distributed Systems|System Design|" +
            "Machine Learning|ML|TensorFlow|PyTorch|" +
            "Swift|Objective-C|Flutter|Dart|Android)\\b",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m = techPattern.matcher(line);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    private List<String> deduplicate(List<String> list) {
        Set<String> seen = new LinkedHashSet<>();
        for (String s : list) seen.add(s.trim());
        return new ArrayList<>(seen);
    }

    // ── Implied skills ────────────────────────────────────────────────────────

    private void inferImpliedSkills(JobDescription jd) {
        List<String> implied = new ArrayList<>();
        for (String skill : jd.getMustHaveSkills()) {
            List<String> implication = IMPLIED_SKILLS.get(skill.toLowerCase());
            if (implication != null) implied.addAll(implication);
        }
        jd.setImpliedSkills(deduplicate(implied));
    }

    // ── IC level estimation ───────────────────────────────────────────────────

    private void estimateIcLevel(String text, JobDescription jd) {
        // Check title first
        String titleAndText = (jd.getRoleTitle() != null ? jd.getRoleTitle() : "") + " " + text.substring(0, Math.min(300, text.length()));
        for (Map.Entry<Pattern, Integer> entry : SENIORITY_IC.entrySet()) {
            if (entry.getKey().matcher(titleAndText).find()) {
                jd.setIcLevel(entry.getValue());
                return;
            }
        }
        // Default to mid-level if undetectable
        jd.setIcLevel(3);
    }

    // ── Context detection ─────────────────────────────────────────────────────

    private void detectContext(String text, JobDescription jd) {
        String lower = text.toLowerCase();
        jd.setRemote(lower.contains("remote") || lower.contains("work from home") || lower.contains("fully remote"));

        if (lower.contains("fast-paced") || lower.contains("startup") || lower.contains("early stage")) {
            jd.setCompanyCulture("fast-paced startup");
        } else if (lower.contains("enterprise") || lower.contains("fortune 500") || lower.contains("large scale")) {
            jd.setCompanyCulture("enterprise");
        }
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    private double computeConfidence(JobDescription jd) {
        double score = 0.0;
        if (jd.getRoleTitle() != null && !jd.getRoleTitle().equals("Unknown Role")) score += 0.3;
        if (!jd.getMustHaveSkills().isEmpty()) score += 0.4;
        if (jd.getYoeMin() != null) score += 0.2;
        if (jd.isWellStructured()) score += 0.1;
        return Math.min(1.0, score);
    }

    private String normalise(String title) {
        if (title == null) return "";
        return title.toLowerCase()
            .replaceAll("\\b(sr\\.?|senior)\\b", "senior")
            .replaceAll("\\b(jr\\.?|junior)\\b", "junior")
            .trim();
    }

    private enum SectionContext { MUST_HAVE, NICE_TO_HAVE, UNKNOWN }
}
