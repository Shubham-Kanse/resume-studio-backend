package com.resumestudio.reviewer.nlp;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Lightweight NLP utilities: tokenization, lemmatization, stopword removal.
 *
 * Used for:
 *   1. Header classification fallback — lemmatize header tokens, match against
 *      section concept words (catches "WHERE I'VE WORKED" → "work" → EXPERIENCE)
 *   2. Impact verb matching — lemmatize bullet first word so "architected",
 *      "architecting", "architects" all match the "architect" impact verb
 *   3. Summary BoW overlap — bag-of-words similarity between summary and JD
 */
@Component
public class TextNormalizer {

    // ── Stopwords ─────────────────────────────────────────────────────────────
    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
        "been", "being", "have", "has", "had", "do", "does", "did", "will",
        "would", "could", "should", "may", "might", "shall", "can", "i",
        "my", "me", "we", "our", "you", "your", "it", "its", "this", "that",
        "these", "those", "what", "which", "who", "whom", "how", "when",
        "where", "why", "all", "any", "both", "each", "few", "more", "most",
        "other", "some", "such", "no", "not", "only", "own", "same", "so",
        "than", "too", "very", "just", "about", "above", "after", "before",
        "between", "during", "into", "through", "under", "while", "also",
        "across", "along", "around", "behind", "below", "beyond", "within"
    );

    // ── Lemma map — irregular + common verb/noun forms ────────────────────────
    // Covers the forms most likely to appear in resume headers and bullets
    private static final Map<String, String> LEMMAS = new HashMap<>();
    static {
        // Section concept words
        LEMMAS.put("worked", "work");       LEMMAS.put("working", "work");
        LEMMAS.put("works", "work");
        LEMMAS.put("built", "build");       LEMMAS.put("building", "build");
        LEMMAS.put("builds", "build");
        LEMMAS.put("developed", "develop"); LEMMAS.put("developing", "develop");
        LEMMAS.put("developments", "develop");
        LEMMAS.put("skills", "skill");      LEMMAS.put("skilled", "skill");
        LEMMAS.put("experiences", "experience"); LEMMAS.put("experienced", "experience");
        LEMMAS.put("projects", "project");
        LEMMAS.put("certifications", "certification"); LEMMAS.put("certified", "certification");
        LEMMAS.put("certificates", "certificate");
        LEMMAS.put("achievements", "achievement"); LEMMAS.put("achieved", "achieve");
        LEMMAS.put("awards", "award");      LEMMAS.put("awarded", "award");
        LEMMAS.put("educations", "education"); LEMMAS.put("educated", "educate");
        LEMMAS.put("qualifications", "qualification"); LEMMAS.put("qualified", "qualify");
        LEMMAS.put("technologies", "technology"); LEMMAS.put("technological", "technology");
        LEMMAS.put("tools", "tool");
        LEMMAS.put("languages", "language");
        LEMMAS.put("roles", "role");
        LEMMAS.put("positions", "position");
        LEMMAS.put("companies", "company");
        LEMMAS.put("employers", "employer");
        LEMMAS.put("contributions", "contribution"); LEMMAS.put("contributed", "contribute");
        LEMMAS.put("volunteering", "volunteer"); LEMMAS.put("volunteered", "volunteer");
        LEMMAS.put("internships", "internship");
        LEMMAS.put("publications", "publication"); LEMMAS.put("published", "publish");
        LEMMAS.put("interests", "interest");
        LEMMAS.put("hobbies", "hobby");
        LEMMAS.put("references", "reference");
        LEMMAS.put("highlights", "highlight");
        LEMMAS.put("summaries", "summary");
        LEMMAS.put("profiles", "profile");
        LEMMAS.put("backgrounds", "background");
        LEMMAS.put("objectives", "objective");
        LEMMAS.put("overviews", "overview");

        // Impact verbs (for bullet scoring)
        LEMMAS.put("architected", "architect"); LEMMAS.put("architecting", "architect");
        LEMMAS.put("led", "lead");              LEMMAS.put("leading", "lead");
        LEMMAS.put("launched", "launch");       LEMMAS.put("launching", "launch");
        LEMMAS.put("delivered", "deliver");     LEMMAS.put("delivering", "deliver");
        LEMMAS.put("reduced", "reduce");        LEMMAS.put("reducing", "reduce");
        LEMMAS.put("increased", "increase");    LEMMAS.put("increasing", "increase");
        LEMMAS.put("improved", "improve");      LEMMAS.put("improving", "improve");
        LEMMAS.put("optimised", "optimise");    LEMMAS.put("optimising", "optimise");
        LEMMAS.put("optimized", "optimize");    LEMMAS.put("optimizing", "optimize");
        LEMMAS.put("automated", "automate");    LEMMAS.put("automating", "automate");
        LEMMAS.put("migrated", "migrate");      LEMMAS.put("migrating", "migrate");
        LEMMAS.put("refactored", "refactor");   LEMMAS.put("refactoring", "refactor");
        LEMMAS.put("implemented", "implement"); LEMMAS.put("implementing", "implement");
        LEMMAS.put("deployed", "deploy");       LEMMAS.put("deploying", "deploy");
        LEMMAS.put("scaled", "scale");          LEMMAS.put("scaling", "scale");
        LEMMAS.put("secured", "secure");        LEMMAS.put("securing", "secure");
        LEMMAS.put("integrated", "integrate");  LEMMAS.put("integrating", "integrate");
        LEMMAS.put("engineered", "engineer");   LEMMAS.put("engineering", "engineer");
        LEMMAS.put("established", "establish"); LEMMAS.put("establishing", "establish");
        LEMMAS.put("drove", "drive");           LEMMAS.put("driving", "drive");
        LEMMAS.put("accelerated", "accelerate"); LEMMAS.put("accelerating", "accelerate");
        LEMMAS.put("streamlined", "streamline"); LEMMAS.put("streamlining", "streamline");
        LEMMAS.put("eliminated", "eliminate");  LEMMAS.put("eliminating", "eliminate");
        LEMMAS.put("introduced", "introduce");  LEMMAS.put("introducing", "introduce");
        LEMMAS.put("shipped", "ship");          LEMMAS.put("shipping", "ship");
        LEMMAS.put("mentored", "mentor");       LEMMAS.put("mentoring", "mentor");
        LEMMAS.put("managed", "manage");        LEMMAS.put("managing", "manage");
        LEMMAS.put("diagnosed", "diagnose");    LEMMAS.put("diagnosing", "diagnose");
        LEMMAS.put("resolved", "resolve");      LEMMAS.put("resolving", "resolve");
        LEMMAS.put("designed", "design");       LEMMAS.put("designing", "design");
        LEMMAS.put("created", "create");        LEMMAS.put("creating", "create");
        LEMMAS.put("owned", "own");             LEMMAS.put("owning", "own");
    }

    // ── Section concept words — lemmatized forms that map to section types ────
    public static final Map<String, String> HEADER_CONCEPTS = new HashMap<>();
    static {
        // SUMMARY
        for (String w : List.of("summary", "profile", "objective", "overview", "background",
                "introduction", "bio", "highlight", "snapshot", "about", "brief")) {
            HEADER_CONCEPTS.put(w, "SUMMARY");
        }
        // EXPERIENCE
        for (String w : List.of("experience", "work", "employment", "career", "history",
                "role", "position", "job", "internship", "volunteer", "freelance")) {
            HEADER_CONCEPTS.put(w, "EXPERIENCE");
        }
        // SKILLS
        for (String w : List.of("skill", "technology", "tool", "language", "competency",
                "expertise", "proficiency", "capability", "stack", "framework")) {
            HEADER_CONCEPTS.put(w, "SKILLS");
        }
        // EDUCATION
        for (String w : List.of("education", "degree", "qualification", "academic",
                "university", "college", "school", "study", "course", "training")) {
            HEADER_CONCEPTS.put(w, "EDUCATION");
        }
        // PROJECTS
        for (String w : List.of("project", "portfolio", "contribution", "build",
                "sample", "work")) {
            HEADER_CONCEPTS.put(w, "PROJECTS");
        }
        // CERTIFICATIONS
        for (String w : List.of("certification", "certificate", "award", "achievement",
                "honor", "honour", "recognition", "license", "credential", "badge")) {
            HEADER_CONCEPTS.put(w, "CERTIFICATIONS");
        }
    }

    /**
     * Tokenize text into lowercase word tokens, stripping punctuation.
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] raw = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String t : raw) {
            if (!t.isBlank()) tokens.add(t);
        }
        return tokens;
    }

    /**
     * Lemmatize a single token — returns the root form.
     * Falls back to simple suffix stripping for unknown words.
     */
    public String lemmatize(String token) {
        if (token == null) return "";
        String lower = token.toLowerCase();
        if (LEMMAS.containsKey(lower)) return LEMMAS.get(lower);
        // Simple suffix stripping fallback
        if (lower.endsWith("ing") && lower.length() > 5) return lower.substring(0, lower.length() - 3);
        if (lower.endsWith("tion") && lower.length() > 6) return lower.substring(0, lower.length() - 4);
        if (lower.endsWith("tions") && lower.length() > 7) return lower.substring(0, lower.length() - 5);
        if (lower.endsWith("ed") && lower.length() > 4) return lower.substring(0, lower.length() - 2);
        if (lower.endsWith("es") && lower.length() > 4) return lower.substring(0, lower.length() - 2);
        if (lower.endsWith("s") && lower.length() > 3) return lower.substring(0, lower.length() - 1);
        return lower;
    }

    /**
     * Tokenize, remove stopwords, and lemmatize.
     * Returns a bag of meaningful root-form tokens.
     */
    public List<String> bagOfWords(String text) {
        return tokenize(text).stream()
            .filter(t -> !STOPWORDS.contains(t) && t.length() > 2)
            .map(this::lemmatize)
            .distinct()
            .toList();
    }

    /**
     * Jaccard similarity between two texts using bag-of-words.
     * Used for summary-JD overlap scoring.
     */
    public double jaccardSimilarity(String text1, String text2) {
        Set<String> bow1 = new HashSet<>(bagOfWords(text1));
        Set<String> bow2 = new HashSet<>(bagOfWords(text2));
        if (bow1.isEmpty() && bow2.isEmpty()) return 1.0;
        if (bow1.isEmpty() || bow2.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(bow1);
        intersection.retainAll(bow2);
        Set<String> union = new HashSet<>(bow1);
        union.addAll(bow2);
        return (double) intersection.size() / union.size();
    }

    /**
     * Classify a header line using lemmatized token matching.
     * Returns the section type string or null if unrecognised.
     * Used as fallback when exact header matching fails.
     */
    public String classifyHeaderFallback(String headerLine) {
        List<String> tokens = bagOfWords(headerLine);
        Map<String, Integer> votes = new HashMap<>();
        for (String token : tokens) {
            String section = HEADER_CONCEPTS.get(token);
            if (section != null) votes.merge(section, 1, Integer::sum);
        }
        return votes.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }
}
