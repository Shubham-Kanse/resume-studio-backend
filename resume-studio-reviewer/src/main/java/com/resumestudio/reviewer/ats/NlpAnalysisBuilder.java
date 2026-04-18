package com.resumestudio.reviewer.ats;

import com.resumestudio.reviewer.model.Resume;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import com.resumestudio.reviewer.nlp.TfIdfVectorizer;
import com.resumestudio.reviewer.nlp.VerbQualityService;
import com.resumestudio.reviewer.skills.EscoSkillGraph;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.resumestudio.reviewer.ats.BulletQualityScorer.clamp;

/**
 * Builds NlpAnalysisResponse (ATSNLPAnalysis shape).
 *
 * Uses:
 *  - VerbQualityService (verb_quality_ontology.json) for verb scoring
 *  - TfIdfVectorizer for JD term importance weighting
 *  - SkillMatchResult list (from SignalComputationService) for per-section coverage
 */
@Service
public class NlpAnalysisBuilder {

    private static final Pattern METRIC_PATTERN =
        Pattern.compile("\\d[\\d.,]*|%|\\$|£|€|\\bkpi\\b|\\bokr\\b|\\bmrr\\b|\\barr\\b|\\broi\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTCOME_PATTERN =
        Pattern.compile("\\b(increas|reduc|improv|generat|cut|boost|grew|saved|accelerat|shorten|stabiliz|expand|rais|deliver)\\w*\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESULT_CONNECTOR =
        Pattern.compile("\\b(by|through|resulting in|resulted in|leading to|led to|which|so that)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSIVE_PATTERN =
        Pattern.compile("\\b(was|were|is|are|been|being|be)\\s+\\w+(?:ed|en)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCOPE_PATTERN =
        Pattern.compile("\\b(team|users|customers|clients|revenue|cost|pipeline|platform|system|accounts|markets|product|workflow|process|sales|profit)\\b",
            Pattern.CASE_INSENSITIVE);

    // Bidirectional abbreviation expansion delegated to EscoSkillGraph — no duplicate map here.

    private static final List<String> RESPONSIBILITY_PHRASES = List.of(
        "responsible for", "duties included", "tasked with", "participated in",
        "involved in", "helped with", "worked on", "was part of", "assisted with", "assisted", "supported"
    );

    private static final Set<String> STOPWORDS = Set.of(
        "the","a","an","and","or","but","in","on","at","to","for","of","with","by","from","is","are",
        "was","were","be","been","being","have","has","had","do","does","did","will","would","could",
        "should","may","might","shall","can","need","must","that","this","these","those","it","its",
        "we","our","you","your","they","their","he","she","his","her","i","my","me","us","them","who",
        "which","what","when","where","how","if","as","so","than","then","also","not","no","nor","yet",
        "both","either","neither","each","every","all","any","few","more","most","other","some","such",
        "only","own","same","too","very","just","because","while","although","though","since","until",
        "unless","whether","after","before","during","between","among","through","into","onto","upon",
        "about","above","below","over","under","again","further","once"
    );

    private static final List<BuzzwordFamily> BUZZWORD_FAMILIES = List.of(
        new BuzzwordFamily("results",   List.of("results driven","result driven","results-oriented","results oriented","proven track record")),
        new BuzzwordFamily("ownership", List.of("go-getter","go getter","self-starter","self starter","hit the ground running")),
        new BuzzwordFamily("teamwork",  List.of("team player","cross-functional","cross functional","collaborative")),
        new BuzzwordFamily("energy",    List.of("dynamic","fast-paced","fast paced","passionate","motivated")),
        new BuzzwordFamily("quality",   List.of("detail-oriented","detail oriented","hardworking","hard working"))
    );

    private final VerbQualityService verbQuality;
    private final BuzzwordsScorer buzzwordsScorer;
    private final TfIdfVectorizer tfidf;
    private final EscoSkillGraph escoSkillGraph;

    public NlpAnalysisBuilder(VerbQualityService verbQuality,
                               BuzzwordsScorer buzzwordsScorer,
                               TfIdfVectorizer tfidf,
                               EscoSkillGraph escoSkillGraph) {
        this.verbQuality = verbQuality;
        this.buzzwordsScorer = buzzwordsScorer;
        this.tfidf = tfidf;
        this.escoSkillGraph = escoSkillGraph;
    }

    /** Called from AtsController — no pre-computed signals. */
    public NlpAnalysisResponse build(Resume resume, String rawText, String jobDescription) {
        return build(resume, rawText, jobDescription, null);
    }

    /** Called from AtsResponseBuilder when signals are already computed (JD mode). */
    public NlpAnalysisResponse build(Resume resume, String rawText, String jobDescription,
                                      List<SkillMatchResult> mustHaveResults) {
        List<String> bullets = BulletQualityScorer.extractBullets(
            resume.getExperience() != null ? resume.getExperience() : List.of());

        // Extract JD terms once for per-bullet enrichment
        List<String> jdTerms = extractJdTerms(jobDescription);

        NlpAnalysisResponse response = new NlpAnalysisResponse();
        response.actionVerbs       = buildActionVerbs(bullets);
        response.repetition        = buildRepetition(bullets);
        response.quantifyingImpact = buildImpact(bullets, jdTerms);
        response.bulletLength      = buildBulletLength(bullets, jdTerms);
        response.buzzwords         = buildBuzzwords(rawText);
        response.jobMatch          = buildJobMatch(resume, rawText, jobDescription, mustHaveResults);
        return response;
    }

    private List<String> extractJdTerms(String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) return List.of();
        return Arrays.stream(jobDescription.toLowerCase().split("[^a-z0-9+#.]+"))
            .filter(t -> t.length() > 2 && !STOPWORDS.contains(t))
            .distinct().limit(60).collect(Collectors.toList());
    }

    // ── Action verbs ──────────────────────────────────────────────────────────

    private NlpAnalysisResponse.ActionVerbAnalysis buildActionVerbs(List<String> bullets) {
        int strong = 0, weak = 0;
        Map<String, Integer> verbCounts = new LinkedHashMap<>();
        List<NlpAnalysisResponse.WeakVerbMatch> weakMatches = new ArrayList<>();

        for (String b : bullets) {
            String first = firstWord(b);
            if (verbQuality.isImpactVerb(first)) {
                strong++;
                verbCounts.merge(first, 1, Integer::sum);
            } else if (verbQuality.isWeakVerb(first)) {
                weak++;
                VerbQualityService.VerbEntry entry = verbQuality.lookup(first);
                String suggestion = entry != null ? entry.getSuggestion() : null;
                weakMatches.add(new NlpAnalysisResponse.WeakVerbMatch(
                    first, 1,
                    suggestion != null ? List.of(suggestion) : List.of("Use a stronger action verb")));
            }
        }

        List<NlpAnalysisResponse.RepeatedVerb> repeated = verbCounts.entrySet().stream()
            .filter(e -> e.getValue() > 2)
            .map(e -> new NlpAnalysisResponse.RepeatedVerb(e.getKey(), e.getValue()))
            .collect(Collectors.toList());

        double strongRatio = bullets.isEmpty() ? 0 : (double) strong / bullets.size();
        int score = clamp((int)(strongRatio * 100) - weak * 8);

        return new NlpAnalysisResponse.ActionVerbAnalysis(
            score, bullets.size(), strong, weak, repeated, weakMatches);
    }

    // ── Repetition ────────────────────────────────────────────────────────────

    private NlpAnalysisResponse.RepetitionAnalysis buildRepetition(List<String> bullets) {
        Map<String, Integer> verbCounts = new LinkedHashMap<>();
        Map<String, Integer> phraseCounts = new LinkedHashMap<>();

        for (String b : bullets) {
            String v = firstWord(b);
            if (verbQuality.isImpactVerb(v)) verbCounts.merge(v, 1, Integer::sum);
            String[] words = b.toLowerCase().replaceAll("[^a-z\\s]", "").split("\\s+");
            for (int i = 0; i < words.length - 1; i++) {
                String bigram = words[i] + " " + words[i + 1];
                if (bigram.length() > 5 && !STOPWORDS.contains(words[i]) && !STOPWORDS.contains(words[i+1]))
                    phraseCounts.merge(bigram, 1, Integer::sum);
            }
        }

        List<NlpAnalysisResponse.RepeatedVerb> repeatedVerbs = verbCounts.entrySet().stream()
            .filter(e -> e.getValue() > 2)
            .map(e -> new NlpAnalysisResponse.RepeatedVerb(e.getKey(), e.getValue()))
            .collect(Collectors.toList());

        List<NlpAnalysisResponse.RepeatedPhrase> repeatedPhrases = phraseCounts.entrySet().stream()
            .filter(e -> e.getValue() > 2)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(5)
            .map(e -> new NlpAnalysisResponse.RepeatedPhrase(e.getKey(), e.getValue()))
            .collect(Collectors.toList());

        int totalActionVerbCount = verbCounts.values().stream().mapToInt(Integer::intValue).sum();
        int repeatedCount = repeatedVerbs.stream().mapToInt(NlpAnalysisResponse.RepeatedVerb::count).sum();
        double ratio = totalActionVerbCount > 0 ? (double) repeatedCount / totalActionVerbCount : 0;
        int score = repeatedCount == 0 ? 100 : clamp((int)((1 - ratio) * 100));

        return new NlpAnalysisResponse.RepetitionAnalysis(
            score, totalActionVerbCount, repeatedCount, repeatedVerbs, repeatedPhrases);
    }

    // ── Quantifying impact ────────────────────────────────────────────────────

    private NlpAnalysisResponse.ImpactAnalysis buildImpact(List<String> bullets, List<String> jdTerms) {
        int quantified = 0, achievement = 0, responsibility = 0;
        List<NlpAnalysisResponse.BulletImpactItem> analyses = new ArrayList<>();
        Set<String> jdTermSet = new HashSet<>(jdTerms.stream().map(String::toLowerCase).toList());

        for (String b : bullets) {
            boolean hasMetric    = METRIC_PATTERN.matcher(b).find();
            boolean hasOutcome   = OUTCOME_PATTERN.matcher(b).find();
            boolean hasConnector = RESULT_CONNECTOR.matcher(b).find();
            boolean hasScope     = SCOPE_PATTERN.matcher(b).find();
            boolean isResp       = RESPONSIBILITY_PHRASES.stream().anyMatch(p -> b.toLowerCase().contains(p));
            boolean isPassive    = PASSIVE_PATTERN.matcher(b).find();
            String first         = firstWord(b);
            boolean strongVerb   = verbQuality.isImpactVerb(first);
            boolean weakPhrase   = verbQuality.isWeakVerb(first) || isResp;
            boolean resultPat    = hasOutcome && hasConnector;
            boolean starLike     = hasMetric && hasOutcome && strongVerb;
            int wordCount        = b.split("\\s+").length;

            // Per-bullet JD keyword matches
            String bLower = b.toLowerCase();
            List<String> kwMatches = jdTermSet.stream().filter(bLower::contains).collect(Collectors.toList());
            double relevanceRatio = jdTermSet.isEmpty() ? 0.0 : (double) kwMatches.size() / jdTermSet.size();

            // Technical entities: tokens that look like tech terms (uppercase acronyms, version numbers, known patterns)
            List<String> techEntities = Arrays.stream(b.split("\\s+"))
                .map(w -> w.replaceAll("[^A-Za-z0-9.#+]", ""))
                .filter(w -> w.length() >= 2 && (w.matches("[A-Z][A-Za-z0-9.#+]{1,}") || w.matches("[A-Z]{2,}")))
                .distinct().limit(5).collect(Collectors.toList());

            if (hasMetric || resultPat) quantified++;
            if (!isResp && (hasMetric || resultPat)) achievement++;
            if (isResp) responsibility++;

            int bulletScore = 3;
            if (strongVerb) bulletScore += 2;
            if (hasMetric)  bulletScore += 2;
            if (resultPat)  bulletScore += 2;
            if (!isPassive) bulletScore += 1;
            bulletScore = Math.max(1, Math.min(10, bulletScore));

            List<String> feedback = new ArrayList<>();
            if (!hasMetric) feedback.add("Add a number, percentage, or scale.");
            if (!strongVerb) feedback.add("Open with a stronger action verb.");
            if (isResp) feedback.add("Rewrite as an accomplishment, not a responsibility.");
            if (isPassive) feedback.add("Use active voice.");

            List<String> reasons = new ArrayList<>();
            if (hasMetric) reasons.add("has metric");
            if (strongVerb) reasons.add("strong verb");
            if (resultPat) reasons.add("result pattern");
            if (isResp) reasons.add("responsibility language");
            if (isPassive) reasons.add("passive voice");

            analyses.add(new NlpAnalysisResponse.BulletImpactItem(
                b, !isResp && (hasMetric || hasOutcome), hasMetric,
                bulletScore, bulletScore,
                buildBulletAnalysisProse(hasMetric, strongVerb, resultPat, isPassive, isResp, hasScope),
                feedback, reasons,
                new NlpAnalysisResponse.BulletSignals(
                    first.isEmpty() ? null : first, wordCount,
                    hasMetric ? 1 : 0, hasOutcome ? 1 : 0, hasScope ? 1 : 0,
                    strongVerb, weakPhrase, isPassive, resultPat, starLike, isResp,
                    kwMatches, relevanceRatio, techEntities, List.of()
                )
            ));
        }

        double ratio = bullets.isEmpty() ? 0 : (double) quantified / bullets.size();
        int score = clamp((int)(ratio * 100));
        int allowance = Math.max(0, (int)(bullets.size() * 0.4) - (bullets.size() - quantified));

        return new NlpAnalysisResponse.ImpactAnalysis(
            score, bullets.size(), quantified, allowance, achievement, responsibility, analyses);
    }

    private List<String> buildBulletAnalysisProse(boolean hasMetric, boolean strongVerb,
                                                    boolean resultPat, boolean isPassive,
                                                    boolean isResp, boolean hasScope) {
        List<String> prose = new ArrayList<>();
        if (hasMetric && strongVerb && resultPat)
            prose.add("Strong bullet: quantified outcome with impact verb and result pattern.");
        else if (hasMetric && strongVerb)
            prose.add("Good bullet: has a metric and a strong action verb.");
        else if (hasMetric)
            prose.add("Partially strong: has a metric but the verb opening could be stronger.");
        else if (strongVerb && resultPat)
            prose.add("Good structure: strong verb and result pattern, but missing a specific number.");
        else if (isResp)
            prose.add("Responsibility statement: describes a duty rather than an achievement.");
        else
            prose.add("Weak bullet: lacks both a metric and a strong action verb.");
        if (isPassive) prose.add("Passive voice detected — rewrite to start with the action you took.");
        if (hasScope) prose.add("Scope signal present (team, users, revenue, etc.) — good context.");
        return prose;
    }

    // ── Bullet length ─────────────────────────────────────────────────────────

    private NlpAnalysisResponse.BulletLengthAnalysis buildBulletLength(List<String> bullets, List<String> jdTerms) {
        int tooShort = 0, tooLong = 0, good = 0, totalWords = 0;
        Set<String> jdTermSet = new HashSet<>(jdTerms.stream().map(String::toLowerCase).toList());
        List<NlpAnalysisResponse.BulletLengthItem> items = new ArrayList<>();

        for (String b : bullets) {
            String[] tokens = b.split("\\s+");
            int words = tokens.length;
            totalWords += words;

            // Informative tokens: non-stopword, length > 1
            long informative = Arrays.stream(tokens)
                .map(w -> w.toLowerCase().replaceAll("[^a-z]", ""))
                .filter(w -> w.length() > 1 && !STOPWORDS.contains(w))
                .count();
            double density = words > 0 ? (double) informative / words : 0;

            // Technical entities
            List<String> techEntities = Arrays.stream(tokens)
                .map(w -> w.replaceAll("[^A-Za-z0-9.#+]", ""))
                .filter(w -> w.length() >= 2 && (w.matches("[A-Z][A-Za-z0-9.#+]{1,}") || w.matches("[A-Z]{2,}")))
                .distinct().limit(5).collect(Collectors.toList());

            // JD keyword matches in this bullet
            String bLower = b.toLowerCase();
            List<String> kwMatches = jdTermSet.stream().filter(bLower::contains).collect(Collectors.toList());

            String classification;
            List<String> reasons = new ArrayList<>();
            if (words < 8) {
                tooShort++; classification = "short";
                reasons.add("Too short — add context, tool, or outcome.");
            } else if (words > 34) {
                tooLong++; classification = "long";
                reasons.add("Too long — split or remove filler.");
            } else {
                good++; classification = "good";
            }
            if (density < 0.4) reasons.add("Low content density — remove filler words.");

            items.add(new NlpAnalysisResponse.BulletLengthItem(
                b, words, (int) informative, density, techEntities, kwMatches, classification, reasons));
        }

        double avg = bullets.isEmpty() ? 0 : (double) totalWords / bullets.size();
        double goodRatio = bullets.isEmpty() ? 0 : (double) good / bullets.size();
        int score = clamp((int)(goodRatio * 100) - tooShort * 5 - tooLong * 5);

        return new NlpAnalysisResponse.BulletLengthAnalysis(score, avg, tooShort, tooLong, good, items);
    }

    // ── Buzzwords ─────────────────────────────────────────────────────────────

    private NlpAnalysisResponse.BuzzwordAnalysis buildBuzzwords(String rawText) {
        String lower = rawText.toLowerCase();
        List<Map<String, Object>> matchedFamilies = buzzwordsScorer.getMatchedFamilies(rawText);

        List<NlpAnalysisResponse.RepeatedPhrase> repeated = new ArrayList<>();
        for (BuzzwordFamily family : BUZZWORD_FAMILIES) {
            for (String term : family.terms()) {
                int count = countOccurrences(lower, term);
                if (count > 1) repeated.add(new NlpAnalysisResponse.RepeatedPhrase(term, count));
            }
        }

        int totalHits = matchedFamilies.stream()
            .mapToInt(m -> ((Number) m.get("count")).intValue()).sum();
        int score = clamp(100 - totalHits * 14);

        return new NlpAnalysisResponse.BuzzwordAnalysis(score, repeated, matchedFamilies);
    }

    // ── Job match — TF-IDF weighted, per-section coverage ────────────────────

    private NlpAnalysisResponse.JobMatchAnalysis buildJobMatch(Resume resume, String rawText,
                                                                 String jobDescription,
                                                                 List<SkillMatchResult> mustHaveResults) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return new NlpAnalysisResponse.JobMatchAnalysis(0, false, List.of(), List.of(), List.of(), Map.of());
        }

        // Extract JD terms (stopword-filtered)
        List<String> jdTerms = Arrays.stream(jobDescription.toLowerCase().split("[^a-z0-9+#.]+"))
            .filter(t -> t.length() > 2 && !STOPWORDS.contains(t))
            .distinct()
            .limit(60)
            .collect(Collectors.toList());

        // TF-IDF weighting — rank terms by importance
        Map<String, Double> termWeights = tfidf.computeTfIdf(rawText, jdTerms);
        List<String> topJDTerms = termWeights.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(20)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        // If we have pre-computed SkillMatchResults, use them for higher-quality matching
        if (mustHaveResults != null && !mustHaveResults.isEmpty()) {
            List<String> matched = mustHaveResults.stream()
                .filter(r -> r.getVisibility() != SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill)
                .collect(Collectors.toList());
            List<String> missing = mustHaveResults.stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill)
                .collect(Collectors.toList());
            int score = mustHaveResults.isEmpty() ? 0
                : clamp((int)((double) matched.size() / mustHaveResults.size() * 100));

            return new NlpAnalysisResponse.JobMatchAnalysis(
                score, true, topJDTerms, matched, missing,
                buildSectionCoverage(resume, matched));
        }

        // Fallback: abbreviation-aware contains matching
        String resumeLower = rawText.toLowerCase();
        List<String> matched = jdTerms.stream()
            .filter(t -> resumeContainsWithAbbrev(resumeLower, t))
            .collect(Collectors.toList());
        List<String> missing = jdTerms.stream()
            .filter(t -> !resumeContainsWithAbbrev(resumeLower, t))
            .limit(20)
            .collect(Collectors.toList());

        // Weight score by TF-IDF importance of matched terms
        double weightedMatched = matched.stream()
            .mapToDouble(t -> termWeights.getOrDefault(t, 0.5))
            .sum();
        double weightedTotal = jdTerms.stream()
            .mapToDouble(t -> termWeights.getOrDefault(t, 0.5))
            .sum();
        int score = weightedTotal > 0 ? clamp((int)(weightedMatched / weightedTotal * 100)) : 0;

        return new NlpAnalysisResponse.JobMatchAnalysis(
            score, true, topJDTerms, matched, missing,
            buildSectionCoverage(resume, matched));
    }

    /** Builds per-section coverage map: which matched terms appear in which section. */
    private Map<String, List<String>> buildSectionCoverage(Resume resume, List<String> matchedTerms) {
        Map<String, List<String>> coverage = new LinkedHashMap<>();

        String summary = resume.getSummaryText() != null ? resume.getSummaryText().toLowerCase() : "";
        String skills  = resume.getSkills() != null
            ? resume.getSkills().stream().map(s -> s.getRawName() != null ? s.getRawName().toLowerCase() : "").collect(Collectors.joining(" "))
            : "";
        String exp = resume.getExperience() != null
            ? resume.getExperience().stream()
                .flatMap(e -> e.getBullets() != null ? e.getBullets().stream() : java.util.stream.Stream.of())
                .collect(Collectors.joining(" ")).toLowerCase()
            : "";
        String edu = resume.getEducation() != null
            ? resume.getEducation().stream().map(e -> (e.getDegree() != null ? e.getDegree() : "") + " " + (e.getInstitution() != null ? e.getInstitution() : "")).collect(Collectors.joining(" ")).toLowerCase()
            : "";

        coverage.put("professionalSummary", matchedTerms.stream().filter(t -> summary.contains(t)).collect(Collectors.toList()));
        coverage.put("skills",              matchedTerms.stream().filter(t -> skills.contains(t)).collect(Collectors.toList()));
        coverage.put("workExperience",      matchedTerms.stream().filter(t -> exp.contains(t)).collect(Collectors.toList()));
        coverage.put("education",           matchedTerms.stream().filter(t -> edu.contains(t)).collect(Collectors.toList()));
        coverage.put("projects",            List.of());
        coverage.put("certifications",      List.of());

        return coverage;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Contains-check that also tries the abbreviation/full-form alternative via EscoSkillGraph. */
    private boolean resumeContainsWithAbbrev(String resumeLower, String term) {
        if (resumeLower.contains(term)) return true;
        String expanded = escoSkillGraph.expandAbbreviation(term).toLowerCase();
        if (!expanded.equals(term) && resumeLower.contains(expanded)) return true;
        // also try expanding the resume term as an abbreviation of the search term
        String resolved = escoSkillGraph.resolve(term);
        return resolved != null && !resolved.equalsIgnoreCase(term) && resumeLower.contains(resolved.toLowerCase());
    }

    private String firstWord(String bullet) {
        String clean = bullet.replaceAll("^[-*•]\\s*", "").trim();
        String[] parts = clean.split("\\s+");
        return parts.length > 0 ? parts[0].toLowerCase().replaceAll("[^a-z]", "") : "";
    }

    private int countOccurrences(String text, String phrase) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(phrase, idx)) != -1) { count++; idx += phrase.length(); }
        return count;
    }

    private record BuzzwordFamily(String name, List<String> terms) {}
}
