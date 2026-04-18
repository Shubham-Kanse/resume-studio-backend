package com.resumestudio.reviewer.api;

import com.resumestudio.auth.SupabaseJwtVerifier;
import com.resumestudio.reviewer.nlp.SoftSkillsService;
import com.resumestudio.reviewer.nlp.VerbQualityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * POST /api/review/language
 *
 * Analyses resume text for:
 *   - Buzzwords / clichés (from soft_skills_ontology)
 *   - Weak / toxic verbs (from verb_quality_ontology)
 *   - Repeated words (same non-trivial word used 3+ times)
 *   - Passive voice patterns
 *
 * Returns flagged items with position, type, and replacement suggestions.
 * Auth-optional — same as JD preview.
 */
@RestController
@RequestMapping("/api/review")
public class LanguageAnalysisController {

    private static final int MAX_TEXT_CHARS = 20_000;

    private final SoftSkillsService softSkills;
    private final VerbQualityService verbQuality;
    private final RateLimiterService rateLimiter;
    private final com.resumestudio.reviewer.skills.MindTechOntology mindTech;

    // Words too common to flag as repeated
    private static final Set<String> STOP_WORDS = Set.of(
        "the","a","an","and","or","but","in","on","at","to","for","of","with","by","from",
        "as","is","was","are","were","be","been","being","have","has","had","do","does","did",
        "will","would","could","should","may","might","shall","can","need","must","that","this",
        "these","those","it","its","i","my","we","our","you","your","he","she","they","their",
        "not","no","nor","so","yet","both","either","neither","each","every","all","any","few",
        "more","most","other","some","such","than","then","there","when","where","which","who",
        "whom","how","what","why","also","just","very","too","only","even","still","already",
        "always","never","often","sometimes","usually","well","back","up","out","about","into",
        "through","during","before","after","above","below","between","among","within","without"
    );

    // Strong action verbs — don't flag as repeated even if used multiple times
    private static final Set<String> ACTION_VERB_ALLOWLIST = Set.of(
        "accelerated","achieved","analyzed","architected","automated","boosted","built",
        "coordinated","created","delivered","designed","developed","drove","enabled","executed",
        "expanded","generated","implemented","improved","increased","launched","led","optimized",
        "organized","owned","reduced","resolved","scaled","simplified","spearheaded","streamlined",
        "strengthened","transformed","upgraded"
    );

    // Buzzwords from the reference implementation
    private static final List<String> HARDCODED_BUZZWORDS = List.of(
        "hardworking","team player","go-getter","self-starter","results-driven","dynamic",
        "synergy","detail-oriented","fast-paced","passionate","motivated","results oriented",
        "results-oriented","proven track record","go getter","self starter","collaborative",
        "cross-functional","cross functional","hit the ground running","hard working",
        "detail oriented","fast paced"
    );

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b([a-zA-Z]{3,})\\b");
    private static final Pattern PASSIVE_PATTERN = Pattern.compile(
        "\\b(was|were|is|are|been|being)\\s+(\\w+ed|\\w+en)\\b", Pattern.CASE_INSENSITIVE);

    public LanguageAnalysisController(SoftSkillsService softSkills, VerbQualityService verbQuality,
                                       RateLimiterService rateLimiter,
                                       com.resumestudio.reviewer.skills.MindTechOntology mindTech) {
        this.softSkills = softSkills;
        this.verbQuality = verbQuality;
        this.rateLimiter = rateLimiter;
        this.mindTech = mindTech;
    }

    /** Tech skills from the MIND ontology (3,333 skills) should not be flagged as repeated. */
    private boolean isTechTerm(String word) {
        return mindTech.isKnownSkill(word);
    }

    public record LanguageRequest(String text) {}

    public record LanguageFlag(
        String type,        // BUZZWORD | WEAK_VERB | REPEATED_WORD | PASSIVE_VOICE | CLICHE
        String phrase,      // the flagged text
        String context,     // surrounding sentence for display
        String suggestion,  // replacement suggestion
        String example,     // strong example if available
        int count,          // how many times it appears (for REPEATED_WORD)
        String severity     // HIGH | MEDIUM | LOW
    ) {}

    public record LanguageResult(
        List<LanguageFlag> flags,
        int buzzwordCount,
        int weakVerbCount,
        int repeatedWordCount,
        int passiveCount,
        int totalFlags,
        String grade  // A | B | C | D
    ) {}

    @PostMapping("/language")
    public ResponseEntity<?> analyse(
        @RequestBody LanguageRequest body,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        if (rateLimiter.isPreviewLimited(request))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Too many requests."));

        if (body == null || body.text() == null || body.text().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "text is required."));

        String text = body.text().length() > MAX_TEXT_CHARS
            ? body.text().substring(0, MAX_TEXT_CHARS) : body.text();

        List<LanguageFlag> flags = new ArrayList<>();

        // ── 1. Buzzwords & clichés (hardcoded list first) ────────────────────
        Set<String> seenPhrases = new HashSet<>();
        String textLower = text.toLowerCase();
        for (String buzzword : HARDCODED_BUZZWORDS) {
            if (textLower.contains(buzzword) && seenPhrases.add(buzzword)) {
                String ctx = extractContext(text, buzzword);
                flags.add(new LanguageFlag(
                    "BUZZWORD", buzzword, ctx,
                    "Replace with a specific, evidence-backed claim",
                    null, countOccurrences(text, buzzword), "MEDIUM"
                ));
            }
        }

        // ── 2. Buzzwords & clichés from ontology ─────────────────────────────
        for (SoftSkillsService.PhraseEntry entry : softSkills.findAll(text)) {
            if (seenPhrases.contains(entry.key)) continue;
            seenPhrases.add(entry.key);
            String matchedForm = entry.matchForms.stream()
                .filter(f -> textLower.contains(f.toLowerCase()))
                .findFirst().orElse(entry.key);
            String ctx = extractContext(text, matchedForm);
            String severity = entry.redFlag ? "HIGH" : "WEAK_SIGNAL".equals(entry.type) ? "MEDIUM" : "LOW";
            flags.add(new LanguageFlag(
                entry.redFlag ? "CLICHE" : "BUZZWORD",
                matchedForm, ctx, entry.suggestion, entry.exampleStrong,
                countOccurrences(text, matchedForm), severity
            ));
        }

        // ── 3. Weak / toxic verbs ─────────────────────────────────────────────
        Set<String> seenVerbs = new HashSet<>();
        Matcher wm = WORD_PATTERN.matcher(text);
        while (wm.find()) {
            String word = wm.group(1);
            if (seenVerbs.contains(word.toLowerCase())) continue;
            if (!verbQuality.isWeakVerb(word)) continue;
            seenVerbs.add(word.toLowerCase());
            VerbQualityService.VerbEntry ve = verbQuality.lookup(word);
            String ctx = extractContext(text, word);
            String severity = "TOXIC".equals(ve != null ? ve.quality : "") ? "HIGH" : "MEDIUM";
            flags.add(new LanguageFlag(
                "WEAK_VERB", word, ctx,
                ve != null ? ve.suggestion : "Replace with a strong action verb (Built, Led, Reduced, Delivered)",
                null, countOccurrences(text, word), severity
            ));
        }

        // ── 4. Personal pronouns ──────────────────────────────────────────────
        for (String pronoun : List.of("\\bi\\b","\\bme\\b","\\bmy\\b","\\bmine\\b","\\bwe\\b","\\bour\\b","\\bours\\b")) {
            Pattern pp = Pattern.compile(pronoun, Pattern.CASE_INSENSITIVE);
            Matcher pm2 = pp.matcher(text);
            if (pm2.find()) {
                String matched = pm2.group(0);
                String ctx = extractContext(text, matched);
                flags.add(new LanguageFlag(
                    "BUZZWORD", matched.toLowerCase(), ctx,
                    "Remove personal pronouns — resumes use implied first person",
                    null, countOccurrences(text, matched), "MEDIUM"
                ));
            }
        }

        // ── 5. Repeated words ─────────────────────────────────────────────────
        Map<String, Integer> wordFreq = new LinkedHashMap<>();
        Matcher fm = WORD_PATTERN.matcher(text.toLowerCase());
        while (fm.find()) {
            String w = fm.group(1);
            if (!STOP_WORDS.contains(w) && !ACTION_VERB_ALLOWLIST.contains(w) && !isTechTerm(w))
                wordFreq.merge(w, 1, Integer::sum);
        }
        wordFreq.entrySet().stream()
            .filter(e -> e.getValue() >= 3)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(8)
            .forEach(e -> {
                boolean alreadyFlagged = flags.stream()
                    .anyMatch(f -> f.phrase().equalsIgnoreCase(e.getKey()));
                if (alreadyFlagged) return;
                String ctx = extractContext(text, e.getKey());
                flags.add(new LanguageFlag(
                    "REPEATED_WORD", e.getKey(), ctx,
                    "Vary your language — use synonyms to avoid repetition",
                    null, e.getValue(), e.getValue() >= 5 ? "MEDIUM" : "LOW"
                ));
            });

        // ── 6. Passive voice ──────────────────────────────────────────────────
        Set<String> seenPassive = new HashSet<>();
        Matcher pm = PASSIVE_PATTERN.matcher(text);
        while (pm.find()) {
            String phrase = pm.group(0);
            String key = phrase.toLowerCase();
            if (seenPassive.contains(key)) continue;
            seenPassive.add(key);
            String ctx = extractContext(text, phrase);
            flags.add(new LanguageFlag(
                "PASSIVE_VOICE", phrase, ctx,
                "Rewrite in active voice: start with a strong action verb",
                null, countOccurrences(text, phrase), "MEDIUM"
            ));
        }

        // ── Summary stats ─────────────────────────────────────────────────────
        long buzzwords = flags.stream().filter(f -> "BUZZWORD".equals(f.type()) || "CLICHE".equals(f.type())).count();
        long weakVerbs = flags.stream().filter(f -> "WEAK_VERB".equals(f.type())).count();
        long repeated = flags.stream().filter(f -> "REPEATED_WORD".equals(f.type())).count();
        long passive = flags.stream().filter(f -> "PASSIVE_VOICE".equals(f.type())).count();

        int total = flags.size();
        String grade = total == 0 ? "A" : total <= 3 ? "B" : total <= 7 ? "C" : "D";

        // Sort: HIGH severity first, then by type priority
        flags.sort(Comparator
            .comparing((LanguageFlag f) -> severityOrder(f.severity()))
            .thenComparing(f -> typeOrder(f.type())));

        return ResponseEntity.ok(new LanguageResult(
            flags, (int) buzzwords, (int) weakVerbs, (int) repeated, (int) passive, total, grade
        ));
    }

    private String extractContext(String text, String phrase) {
        int idx = text.toLowerCase().indexOf(phrase.toLowerCase());
        if (idx < 0) return phrase;
        int start = Math.max(0, text.lastIndexOf('\n', idx) + 1);
        int end = Math.min(text.length(), text.indexOf('\n', idx + phrase.length()));
        if (end < 0) end = Math.min(text.length(), idx + phrase.length() + 80);
        String ctx = text.substring(start, end).trim();
        return ctx.length() > 120 ? ctx.substring(0, 120) + "…" : ctx;
    }

    private int countOccurrences(String text, String phrase) {
        int count = 0, idx = 0;
        String lower = text.toLowerCase(), phraseLower = phrase.toLowerCase();
        while ((idx = lower.indexOf(phraseLower, idx)) >= 0) { count++; idx += phraseLower.length(); }
        return count;
    }

    private int severityOrder(String s) {
        return switch (s) { case "HIGH" -> 0; case "MEDIUM" -> 1; default -> 2; };
    }

    private int typeOrder(String t) {
        return switch (t) { case "CLICHE" -> 0; case "WEAK_VERB" -> 1; case "BUZZWORD" -> 2;
                            case "PASSIVE_VOICE" -> 3; default -> 4; };
    }
}
