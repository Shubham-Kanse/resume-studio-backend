package com.resumestudio.reviewer.ats;

import com.resumestudio.reviewer.extraction.ResumeOntologyService;
import com.resumestudio.reviewer.model.Resume;
import com.resumestudio.reviewer.model.WorkExperience;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.resumestudio.reviewer.ats.BulletQualityScorer.*;

/**
 * Style group scorer — covers:
 *   sections, personal-pronouns, active-voice, consistency, date-order, spell-check
 *
 * Uses ResumeOntologyService for section detection.
 */
@Component
public class StyleScorer {

    private static final List<String> REQUIRED_SECTIONS =
        List.of("professionalSummary", "workExperience", "skills", "education");

    private static final List<String> PERSONAL_PRONOUNS =
        List.of("\\bi\\b", "\\bme\\b", "\\bmy\\b", "\\bmine\\b", "\\bwe\\b", "\\bour\\b", "\\bours\\b");

    private static final Pattern PASSIVE_PATTERN =
        Pattern.compile("\\b(was|were|is|are|been|being|be)\\s+\\w+(?:ed|en)\\b", Pattern.CASE_INSENSITIVE);

    // Passive in subordinate clause ("which was improved", "that was built") — not a bullet's main voice
    private static final Pattern SUBORDINATE_PASSIVE = Pattern.compile(
        "(?:which|that|who|where|when|,|;)\\s+(?:was|were|is|are|been|being|be)\\s+\\w+(?:ed|en)\\b",
        Pattern.CASE_INSENSITIVE);

    // Adjective compound ("is well-defined", "are highly regarded") — not passive voice
    private static final Pattern ADJECTIVE_PASSIVE = Pattern.compile(
        "(?:is|are|was|were)\\s+(?:well|highly|fully|deeply|widely|strongly|heavily|easily|clearly|properly|newly|recently)[- ]\\w+\\b",
        Pattern.CASE_INSENSITIVE);

    // Word-boundary patterns prevent false positives inside compound words
    private static final Map<Pattern, String> COMMON_MISSPELLINGS;
    static {
        Map<Pattern, String> m = new LinkedHashMap<>();
        // Original 8
        m.put(Pattern.compile("\\bseperate\\b", Pattern.CASE_INSENSITIVE), "separate");
        m.put(Pattern.compile("\\brecieve\\b", Pattern.CASE_INSENSITIVE), "receive");
        m.put(Pattern.compile("\\bacheived\\b", Pattern.CASE_INSENSITIVE), "achieved");
        m.put(Pattern.compile("\\bmanagment\\b", Pattern.CASE_INSENSITIVE), "management");
        m.put(Pattern.compile("\\benviroment\\b", Pattern.CASE_INSENSITIVE), "environment");
        m.put(Pattern.compile("\\bresponsibilites\\b", Pattern.CASE_INSENSITIVE), "responsibilities");
        m.put(Pattern.compile("\\bexperiance\\b", Pattern.CASE_INSENSITIVE), "experience");
        m.put(Pattern.compile("\\bteh\\b", Pattern.CASE_INSENSITIVE), "the");
        // Common resume misspellings
        m.put(Pattern.compile("\\bprograming\\b", Pattern.CASE_INSENSITIVE), "programming");
        m.put(Pattern.compile("\\btechincal\\b", Pattern.CASE_INSENSITIVE), "technical");
        m.put(Pattern.compile("\\breccomend\\b", Pattern.CASE_INSENSITIVE), "recommend");
        m.put(Pattern.compile("\\brecommandation\\b", Pattern.CASE_INSENSITIVE), "recommendation");
        m.put(Pattern.compile("\\bbuisness\\b", Pattern.CASE_INSENSITIVE), "business");
        m.put(Pattern.compile("\\bdefinately\\b", Pattern.CASE_INSENSITIVE), "definitely");
        m.put(Pattern.compile("\\bdefenitely\\b", Pattern.CASE_INSENSITIVE), "definitely");
        m.put(Pattern.compile("\\bsuccesful\\b", Pattern.CASE_INSENSITIVE), "successful");
        m.put(Pattern.compile("\\bsucessful\\b", Pattern.CASE_INSENSITIVE), "successful");
        m.put(Pattern.compile("\\bacheive\\b", Pattern.CASE_INSENSITIVE), "achieve");
        m.put(Pattern.compile("\\bachivment\\b", Pattern.CASE_INSENSITIVE), "achievement");
        m.put(Pattern.compile("\\bacheivement\\b", Pattern.CASE_INSENSITIVE), "achievement");
        m.put(Pattern.compile("\\bknowlegde\\b", Pattern.CASE_INSENSITIVE), "knowledge");
        m.put(Pattern.compile("\\banalitical\\b", Pattern.CASE_INSENSITIVE), "analytical");
        m.put(Pattern.compile("\\bleanring\\b", Pattern.CASE_INSENSITIVE), "learning");
        m.put(Pattern.compile("\\bcompetance\\b", Pattern.CASE_INSENSITIVE), "competence");
        m.put(Pattern.compile("\\bintergrate\\b", Pattern.CASE_INSENSITIVE), "integrate");
        m.put(Pattern.compile("\\barchitecutre\\b", Pattern.CASE_INSENSITIVE), "architecture");
        m.put(Pattern.compile("\\barchitechure\\b", Pattern.CASE_INSENSITIVE), "architecture");
        m.put(Pattern.compile("\\bcolaboration\\b", Pattern.CASE_INSENSITIVE), "collaboration");
        m.put(Pattern.compile("\\bcollaberation\\b", Pattern.CASE_INSENSITIVE), "collaboration");
        m.put(Pattern.compile("\\bmaintainence\\b", Pattern.CASE_INSENSITIVE), "maintenance");
        m.put(Pattern.compile("\\boptmization\\b", Pattern.CASE_INSENSITIVE), "optimization");
        m.put(Pattern.compile("\\boptimizaiton\\b", Pattern.CASE_INSENSITIVE), "optimization");
        m.put(Pattern.compile("\\bimplementaion\\b", Pattern.CASE_INSENSITIVE), "implementation");
        m.put(Pattern.compile("\\bimplemantion\\b", Pattern.CASE_INSENSITIVE), "implementation");
        m.put(Pattern.compile("\\bcomunication\\b", Pattern.CASE_INSENSITIVE), "communication");
        m.put(Pattern.compile("\\bcommunicaiton\\b", Pattern.CASE_INSENSITIVE), "communication");
        m.put(Pattern.compile("\\bpreformance\\b", Pattern.CASE_INSENSITIVE), "performance");
        m.put(Pattern.compile("\\bacessibility\\b", Pattern.CASE_INSENSITIVE), "accessibility");
        m.put(Pattern.compile("\\baccesibility\\b", Pattern.CASE_INSENSITIVE), "accessibility");
        m.put(Pattern.compile("\\bneccessary\\b", Pattern.CASE_INSENSITIVE), "necessary");
        m.put(Pattern.compile("\\bnecessairy\\b", Pattern.CASE_INSENSITIVE), "necessary");
        m.put(Pattern.compile("\\baccomodate\\b", Pattern.CASE_INSENSITIVE), "accommodate");
        m.put(Pattern.compile("\\boccured\\b", Pattern.CASE_INSENSITIVE), "occurred");
        // Tech-specific
        m.put(Pattern.compile("\\bJavascirpt\\b", Pattern.CASE_INSENSITIVE), "JavaScript");
        m.put(Pattern.compile("\\bJavascript\\b", Pattern.CASE_INSENSITIVE), "JavaScript");
        m.put(Pattern.compile("\\bPytohn\\b", Pattern.CASE_INSENSITIVE), "Python");
        m.put(Pattern.compile("\\bTypescirpt\\b", Pattern.CASE_INSENSITIVE), "TypeScript");
        m.put(Pattern.compile("\\bKubernetees\\b", Pattern.CASE_INSENSITIVE), "Kubernetes");
        m.put(Pattern.compile("\\bPostgress\\b", Pattern.CASE_INSENSITIVE), "PostgreSQL");
        COMMON_MISSPELLINGS = Collections.unmodifiableMap(m);
    }

    // Matches "I" used as a Roman numeral or level indicator — not a personal pronoun
    private static final Pattern ROMAN_NUMERAL_I = Pattern.compile(
        "\\b(?:Phase|Level|Type|Tier|Grade|Stage|Version|Track|Section|Part|Module|Band|" +
        "Engineer|Analyst|Developer|Manager|Specialist|Associate|Consultant|" +
        "Architect|Designer|Scientist|Officer|Supervisor|Director|Coordinator|" +
        "Track|Release|Sprint|Objective|Milestone)\\s+I\\b",
        Pattern.CASE_INSENSITIVE);

    // Month-year pattern for date consistency
    private static final Pattern DATE_PATTERN = Pattern.compile(
        "\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|" +
        "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+\\d{4}\\b|" +
        "\\b\\d{1,2}/\\d{4}\\b|\\b\\d{4}\\b",
        Pattern.CASE_INSENSITIVE);

    private final ResumeOntologyService ontology;

    public StyleScorer(ResumeOntologyService ontology) {
        this.ontology = ontology;
    }

    // ── Sections ──────────────────────────────────────────────────────────────

    public AtsReport.AtsSection scoreSections(Resume resume) {
        List<String> missing = new ArrayList<>();
        if (resume.getSummaryText() == null || resume.getSummaryText().isBlank())
            missing.add("Professional Summary");
        if (resume.getExperience() == null || resume.getExperience().isEmpty())
            missing.add("Work Experience");
        if (resume.getSkills() == null || resume.getSkills().isEmpty())
            missing.add("Skills");
        if (resume.getEducation() == null || resume.getEducation().isEmpty())
            missing.add("Education");

        int score = clamp(100 - missing.size() * 24);
        List<String> issues = missing.stream()
            .map(s -> "Missing section: " + s)
            .toList();
        List<String> suggestions = missing.isEmpty() ? List.of()
            : List.of("Add the missing sections with ATS-safe headers (e.g. 'Work Experience', not 'Where I've Been').");
        return section("sections", "Sections", score, issues, suggestions);
    }

    // ── Personal pronouns ─────────────────────────────────────────────────────

    public AtsReport.AtsSection scorePersonalPronouns(String resumeText) {
        // Strip known false-positive "I" contexts (Roman numerals, level indicators)
        // before running pronoun detection, so "Phase I", "Engineer I" etc. aren't counted.
        String text = ROMAN_NUMERAL_I.matcher(resumeText).replaceAll(m -> {
            // Replace just the trailing " I" with " X" to break the word-boundary match
            return m.group(0).replaceFirst("\\bI$", "X");
        });

        int count = 0;
        for (String pronoun : PERSONAL_PRONOUNS) {
            Matcher m = Pattern.compile(pronoun, Pattern.CASE_INSENSITIVE).matcher(text);
            while (m.find()) count++;
        }
        int score = clamp(100 - count * 12);
        List<String> issues = count > 0
            ? List.of("Found " + count + " personal pronoun(s). Resumes should use implied first person.")
            : List.of();
        List<String> suggestions = count > 0
            ? List.of("Remove 'I', 'my', 'we' — start bullets directly with the action verb.")
            : List.of();
        return section("personal-pronouns", "Personal Pronouns", score, issues, suggestions);
    }

    // ── Active voice ──────────────────────────────────────────────────────────

    public AtsReport.AtsSection scoreActiveVoice(List<String> bullets) {
        List<String> issues = new ArrayList<>();
        for (String b : bullets) {
            if (PASSIVE_PATTERN.matcher(b).find()
                    && !SUBORDINATE_PASSIVE.matcher(b).find()
                    && !ADJECTIVE_PASSIVE.matcher(b).find()
                    && issues.size() < 3) {
                issues.add("Passive voice: " + truncate(b));
            }
        }
        int score = clamp(100 - issues.size() * 18);
        List<String> suggestions = issues.isEmpty() ? List.of()
            : List.of("Rewrite passive bullets to start with the action you personally took.");
        return section("active-voice", "Active Voice", score, issues, suggestions);
    }

    // ── Consistency ───────────────────────────────────────────────────────────

    public AtsReport.AtsSection scoreConsistency(List<String> bullets, String resumeText) {
        List<String> issues = new ArrayList<>();

        // Bullet marker consistency
        Map<String, Integer> markers = new LinkedHashMap<>();
        for (String b : bullets) {
            String marker = b.startsWith("-") ? "-" : b.startsWith("•") ? "•" : b.startsWith("*") ? "*" : "none";
            markers.merge(marker, 1, Integer::sum);
        }
        if (markers.size() > 1) {
            issues.add("Mixed bullet markers: " + markers.keySet());
        }

        // Punctuation consistency
        long withPeriod = bullets.stream().filter(b -> b.trim().endsWith(".")).count();
        long withoutPeriod = bullets.size() - withPeriod;
        if (withPeriod > 2 && withoutPeriod > 2) {
            issues.add("Inconsistent bullet endings: " + withPeriod + " end with period, " + withoutPeriod + " don't.");
        }

        // Date format consistency
        Set<String> dateFormats = new HashSet<>();
        Matcher m = DATE_PATTERN.matcher(resumeText);
        while (m.find()) {
            String match = m.group();
            if (match.matches("\\d{4}")) dateFormats.add("year-only");
            else if (match.matches("\\d{1,2}/\\d{4}")) dateFormats.add("MM/YYYY");
            else dateFormats.add("Month YYYY");
        }
        if (dateFormats.size() > 1) {
            issues.add("Mixed date formats: " + dateFormats + ". Use one format throughout.");
        }

        int score = clamp(100 - issues.size() * 16);
        List<String> suggestions = issues.isEmpty() ? List.of()
            : List.of("Pick one bullet style, one date format, and one punctuation convention and apply it everywhere.");
        return section("consistency", "Consistency", score, issues, suggestions);
    }

    // ── Date order ────────────────────────────────────────────────────────────

    public AtsReport.AtsSection scoreDateOrder(List<WorkExperience> experience) {
        List<String> issues = new ArrayList<>();
        for (int i = 1; i < experience.size(); i++) {
            WorkExperience prev = experience.get(i - 1);
            WorkExperience curr = experience.get(i);
            if (prev.getStartDate() != null && curr.getStartDate() != null
                && curr.getStartDate().isAfter(prev.getStartDate())) {
                issues.add(curr.getTitle() + " at " + curr.getCompany()
                    + " appears after an older role — check reverse-chronological order.");
                if (issues.size() >= 2) break;
            }
        }
        int score = clamp(100 - issues.size() * 22);
        List<String> suggestions = issues.isEmpty() ? List.of()
            : List.of("List roles in reverse-chronological order (most recent first).");
        return section("date-order", "Date Order", score, issues, suggestions);
    }

    // ── Spell check ───────────────────────────────────────────────────────────

    public AtsReport.AtsSection scoreSpellCheck(String resumeText) {
        List<String> issues = new ArrayList<>();
        for (Map.Entry<Pattern, String> e : COMMON_MISSPELLINGS.entrySet()) {
            Matcher matcher = e.getKey().matcher(resumeText);
            if (matcher.find()) {
                issues.add(matcher.group() + " → should be " + e.getValue());
            }
        }
        int score = issues.isEmpty() ? 100 : clamp(100 - issues.size() * 18);
        List<String> suggestions = issues.isEmpty() ? List.of()
            : List.of("Fix the spelling errors above — ATS parsers may misclassify misspelled skills.");
        return section("spell-check", "Spell Check", score, issues, suggestions);
    }

    private String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "…" : s;
    }
}
