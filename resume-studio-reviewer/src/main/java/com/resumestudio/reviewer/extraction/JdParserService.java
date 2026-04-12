package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.JobDescription;
import com.resumestudio.reviewer.skills.EscoSkillGraph;
import com.resumestudio.reviewer.skills.MindTechOntology;
import com.resumestudio.reviewer.nlp.SentenceEncoder;
import com.resumestudio.reviewer.nlp.TfIdfVectorizer;
import com.resumestudio.reviewer.nlp.PosTagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses raw job description text into a structured JobDescription.
 *
 * Key responsibilities:
 *  - Extract role title
 *  - Separate must-have from nice-to-have skills
 *  - Extract YOE requirement
 *  - Estimate IC level
 *  - Infer implied skills (e.g. Spring Boot → implies Java)
 *
 * Skill extraction uses ESCO + MIND-tech taxonomies with n-gram matching.
 */
@Service
public class JdParserService {

    private static final Logger log = LoggerFactory.getLogger(JdParserService.class);

    private final EscoSkillGraph escoGraph;
    private final MindTechOntology mindTech;
    private final SentenceEncoder sentenceEncoder;
    private final TfIdfVectorizer tfidfVectorizer;
    private final PosTagService posTagService;
    
    private double unstructuredMustHaveRatio = 0.7;
    private int unstructuredSplitMinSkills = 8;
    
    // Cache for parsed JDs (SHA-256 hash -> JobDescription)
    // Thread-safe with synchronized access
    private final Map<String, JobDescription> jdCache = Collections.synchronizedMap(
        new LinkedHashMap<>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JobDescription> eldest) {
                return size() > 100;
            }
        }
    );

    public JdParserService(EscoSkillGraph escoGraph, 
                          MindTechOntology mindTech,
                          SentenceEncoder sentenceEncoder,
                          TfIdfVectorizer tfidfVectorizer,
                          PosTagService posTagService) {
        this.escoGraph = escoGraph;
        this.mindTech = mindTech;
        this.sentenceEncoder = sentenceEncoder;
        this.tfidfVectorizer = tfidfVectorizer;
        this.posTagService = posTagService;
    }

    @Value("${reviewer.jd.unstructured.must-have-ratio:0.7}")
    public void setUnstructuredMustHaveRatio(double ratio) {
        if (ratio <= 0.0 || ratio >= 1.0) {
            log.warn("Invalid reviewer.jd.unstructured.must-have-ratio {}. Using default 0.7", ratio);
            this.unstructuredMustHaveRatio = 0.7;
            return;
        }
        this.unstructuredMustHaveRatio = ratio;
    }

    @Value("${reviewer.jd.unstructured.min-skills-for-split:8}")
    public void setUnstructuredSplitMinSkills(int minSkills) {
        if (minSkills < 2) {
            log.warn("Invalid reviewer.jd.unstructured.min-skills-for-split {}. Using default 8", minSkills);
            this.unstructuredSplitMinSkills = 8;
            return;
        }
        this.unstructuredSplitMinSkills = minSkills;
    }

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
        "^(nice[- ]to[- ]have|preferred|preferred qualifications?|bonus|plus|desirable|" +
        "what would be great|what.s a plus|advantageous)\\s*:?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // ── Bullet/list item ─────────────────────────────────────────────────────
    private static final Pattern BULLET_ITEM = Pattern.compile(
        "^[•\\-*▪◦➤►→]\\s*(.+)$");

    // ── Title extraction ──────────────────────────────────────────────────────

    // Pattern 1: explicit label  "Position: Senior Backend Engineer"
    private static final Pattern ROLE_TITLE_EXPLICIT = Pattern.compile(
        "(?:position|role|title|job title)[ \\t]*:?[ \\t]*([^\\r\\n]{3,80})",
        Pattern.CASE_INSENSITIVE);

    // Pattern 2: "we're looking for / hiring a … [title]" in narrative sentences
    private static final Pattern ROLE_TITLE_SEEKING = Pattern.compile(
        "(?:we(?:'re| are)\\s+(?:seeking|looking for|hiring|searching for)|" +
        "(?:seeking|looking for|hiring)\\s+(?:a|an))\\s+" +
        "(?:(?:senior|jr\\.?|junior|staff|lead|principal|mid[- ]?level|associate|" +
        "experienced|talented|skilled|passionate)\\s+)*" +
        "([\\w][\\w\\s.+#/-]{2,50}?" +
        "(?:engineer|developer|architect|manager|analyst|designer|scientist|devops|sre|" +
        "consultant|specialist|lead|programmer|director|officer))",
        Pattern.CASE_INSENSITIVE);

    // Legacy — kept for backward compat with existing tests
    private static final Pattern ROLE_TITLE_INDICATORS = Pattern.compile(
        "(?:position|role|title|job title|we.re hiring|hiring a|looking for a?)[ \\t]*:?[ \\t]*([^\\r\\n]{3,80})",
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

    public JobDescription parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            JobDescription jd = new JobDescription();
            jd.setRawText(rawText);
            jd.setParseConfidence(0.0);
            return jd;
        }
        
        // Check cache
        String hash = computeSha256(rawText);
        JobDescription cached = jdCache.get(hash);
        if (cached != null) {
            log.debug("JD cache hit: {}", hash.substring(0, 8));
            return cached;
        }
        
        JobDescription jd = parseInternal(rawText);
        jdCache.put(hash, jd);
        return jd;
    }
    
    private JobDescription parseInternal(String rawText) {
        JobDescription jd = new JobDescription();
        jd.setRawText(rawText);

        extractTitle(rawText, jd);
        extractYoeRequirement(rawText, jd);
        extractSkillSpecificYoe(rawText, jd);
        extractSkills(rawText, jd);
        computeSkillWeights(rawText, jd);
        inferImpliedSkills(jd);
        estimateIcLevel(rawText, jd);
        detectContext(rawText, jd);
        
        // Compute initial confidence before validation
        jd.setParseConfidence(computeConfidence(jd));
        
        // Validate and apply penalties
        validateParse(jd);

        jd.setNormalisedTitle(normalise(jd.getRoleTitle() != null ? jd.getRoleTitle() : ""));
        jd.setWellStructured(
            MUST_HAVE_SECTION.matcher(rawText).find() || NICE_TO_HAVE_SECTION.matcher(rawText).find()
        );
        jd.setJdClarity(computeJdClarity(rawText, jd));
        jd.setTrimmedText(buildTrimmedText(rawText, jd));
        return jd;
    }
    
    private String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    // ── Title extraction ──────────────────────────────────────────────────────

    private static final java.util.Set<String> TITLE_KEYWORDS = java.util.Set.of(
        "engineer", "developer", "architect", "manager", "analyst", "designer",
        "scientist", "devops", "sre", "consultant", "specialist", "programmer",
        "director", "lead", "officer", "recruiter", "qa", "tester"
    );

    private static final Set<String> TITLE_DISALLOWED_PHRASES = Set.of(
        "experience with", "experience in", "experience of", "proficient in", "familiarity with",
        "knowledge of", "understanding of", "ability to", "responsible for", "responsibilities",
        "requirements", "qualifications", "must have", "nice to have", "what you'll do",
        "what you will do", "what we're looking for", "what we are looking for", "preferred"
    );

    private static final Set<String> GENERIC_SKILL_NOISE = Set.of(
        "team", "teams", "tool", "tools", "thing", "things", "meet", "meeting", "meetings",
        "developer", "developers", "development", "platform", "platforms", "practice", "practices",
        "experience", "knowledge", "understanding", "ability", "requirement", "requirements",
        "qualification", "qualifications", "responsibility", "responsibilities", "stakeholder",
        "stakeholders", "customer", "customers", "business", "software", "engineering"
    );

    private static final Set<String> AMBIGUOUS_SINGLE_WORD_SKILLS = Set.of("go", "r", "solid", "crypto");

    private void extractTitle(String text, JobDescription jd) {
        // Work on markdown-stripped text for pattern matching
        String strippedText = text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                                  .replaceAll("\\*([^*]+)\\*", "$1")
                                  .replaceAll("`([^`]+)`", "$1");

        // Pass 1: explicit label ("Position: Senior Backend Engineer")
        Matcher explicitMatcher = ROLE_TITLE_EXPLICIT.matcher(strippedText);
        if (explicitMatcher.find()) {
            String title = cleanTitle(explicitMatcher.group(1));
            if (validateTitle(title)) {
                jd.setRoleTitle(title);
                return;
            }
        }

        // Pass 2: narrative seeking phrase ("We're looking for a Senior Backend Engineer")
        Matcher seekingMatcher = ROLE_TITLE_SEEKING.matcher(strippedText);
        if (seekingMatcher.find()) {
            String title = cleanTitle(seekingMatcher.group(1));
            if (validateTitle(title)) {
                jd.setRoleTitle(title);
                return;
            }
        }
        
        // Pass 3: Semantic similarity - first 5 lines vs "job title" query
        String[] lines = strippedText.split("\n");
        String titleQuery = "job title position role";
        double maxSim = 0.0;
        String bestMatch = null;
        
        for (int i = 0; i < Math.min(5, lines.length); i++) {
            String line = lines[i].trim();
            if (line.isBlank() || line.length() > 100) continue;
            if (!isPlausibleTitleCandidate(line)) continue;
            
            double sim = sentenceEncoder.similarity(line, titleQuery);
            if (sim > maxSim && sim > 0.6) {
                maxSim = sim;
                bestMatch = line;
            }
        }
        
        if (bestMatch != null) {
            String title = cleanTitle(bestMatch);
            if (validateTitle(title)) {
                jd.setRoleTitle(title);
                return;
            }
        }

        // Pass 4: first short standalone line that IS a job title (≤ 80 chars, no verb)
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.length() > 80) continue;
            if (!isPlausibleTitleCandidate(trimmed)) continue;
            String lower = trimmed.toLowerCase();
            // Must contain a title keyword but not look like a sentence (no common verbs)
            boolean hasKeyword = containsTitleKeyword(lower);
            boolean looksSentence = lower.matches(".*\\b(we|our|you|your|will|are|is|the|a |an ).*");
            if (hasKeyword && !looksSentence) {
                String title = cleanTitle(trimmed);
                if (validateTitle(title)) {
                    jd.setRoleTitle(title);
                    return;
                }
            }
        }

        // Pass 5: long line — extract just the job-title noun phrase
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            String lower = trimmed.toLowerCase();
            for (String kw : TITLE_KEYWORDS) {
                int idx = lower.indexOf(kw);
                if (idx == -1) continue;
                // Walk back up to 6 words to capture full seniority prefix
                int words = 0;
                int s = idx - 1;
                while (s > 0 && words < 6) {
                    if (trimmed.charAt(s) == ' ') words++;
                    s--;
                }
                // Walk forward to capture full title
                int end = Math.min(trimmed.length(), idx + kw.length() + 30);
                String candidate = trimmed.substring(Math.max(0, s + 1), end).trim();
                // Strip trailing punctuation / filler
                candidate = candidate.replaceAll("[,;.!?].*$", "").trim();
                if (!isPlausibleTitleCandidate(candidate)) {
                    continue;
                }
                if (candidate.length() >= 3 && validateTitle(candidate)) {
                    jd.setRoleTitle(candidate);
                    return;
                }
            }
        }

        jd.setRoleTitle("Unknown Role");
    }

    private String cleanTitle(String raw) {
        if (raw == null) return "Unknown Role";
        String cleaned = raw.trim()
            .replaceAll("^[#>*•\\-*▪◦➤►→\\d.)\\s]+", "")
            .replaceAll("\\s{2,}", " ")
            .replaceAll("[,;.!?]+$", "")
            .trim();

        for (String separator : List.of(" | ", " @ ", " – ", " — ", " - ")) {
            int idx = cleaned.indexOf(separator);
            if (idx > 0) {
                cleaned = cleaned.substring(0, idx).trim();
                break;
            }
        }

        cleaned = cleaned.replaceAll("(?i)\\s+(to join our team|to join us|based in .+|remote|hybrid)$", "").trim();
        return cleaned.isBlank() ? "Unknown Role" : cleaned;
    }
    
    private boolean validateTitle(String title) {
        if (title == null || title.isBlank() || title.length() < 3) {
            return false;
        }
        
        String cleaned = cleanTitle(title);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        int wordCount = cleaned.split("\\s+").length;
        if (wordCount > 8) {
            return false;
        }
        if (lower.startsWith("experience ") || lower.startsWith("must ") || lower.startsWith("required ")) {
            return false;
        }
        if (TITLE_DISALLOWED_PHRASES.stream().anyMatch(lower::contains)) {
            return false;
        }
        
        // Must contain at least one title keyword
        boolean hasKeyword = containsTitleKeyword(lower);
        
        // Should not be too generic
        boolean tooGeneric = lower.matches("^(role|position|job|title)$");
        
        return hasKeyword && !tooGeneric;
    }

    private boolean containsTitleKeyword(String lower) {
        return TITLE_KEYWORDS.stream()
            .anyMatch(keyword -> lower.matches(".*\\b" + Pattern.quote(keyword) + "\\b.*"));
    }

    private boolean isPlausibleTitleCandidate(String line) {
        String cleaned = cleanTitle(line);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (cleaned.equals("Unknown Role")) {
            return false;
        }
        if (cleaned.contains(",") && cleaned.split("\\s+").length > 6) {
            return false;
        }
        return TITLE_DISALLOWED_PHRASES.stream().noneMatch(lower::contains);
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
    
    /**
     * Extract skill-specific YOE requirements.
     * E.g., "5+ years of Java experience", "3 years with React"
     */
    private void extractSkillSpecificYoe(String text, JobDescription jd) {
        Map<String, Double> skillYoe = new HashMap<>();
        
        // Pattern: "X years of/in/with <skill>"
        Pattern skillYoePattern = Pattern.compile(
            "(\\d+)\\+?\\s*years?\\s*(?:of|in|with|experience in|experience with)?\\s+([\\w\\s.+#/-]{2,30})",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher m = skillYoePattern.matcher(text);
        while (m.find()) {
            double years = Double.parseDouble(m.group(1));
            String skillCandidate = m.group(2).trim();
            
            // Validate against ESCO or MIND-tech
            String canonical = null;
            if (escoGraph.isKnownSkill(skillCandidate.toLowerCase())) {
                canonical = escoGraph.resolve(skillCandidate.toLowerCase());
            } else if (mindTech.isKnownSkill(skillCandidate)) {
                canonical = mindTech.resolve(skillCandidate);
            }
            
            if (canonical != null) {
                skillYoe.put(canonical, years);
                log.debug("Extracted skill-specific YOE: {} -> {} years", canonical, years);
            }
        }
        
        jd.setSkillYoeRequirements(skillYoe);
    }

    // ── Skills extraction ─────────────────────────────────────────────────────

    /**
     * Strip markdown formatting from a line so skill extraction works on clean text.
     * Handles bold (**text**), italic (*text*), inline code (`text`), and header prefixes (### ).
     */
    private String stripMarkdown(String line) {
        return line
            .replaceAll("^#{1,6}\\s+", "")       // ### Header → Header
            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1") // **bold** → bold
            .replaceAll("\\*([^*]+)\\*", "$1")    // *italic* → italic
            .replaceAll("`([^`]+)`", "$1")         // `code` → code
            .trim();
    }

    private void extractSkills(String text, JobDescription jd) {
        String[] lines = text.split("\n");
        SectionContext context = SectionContext.UNKNOWN;

        List<SkillWithContext> skillsWithContext = new ArrayList<>();

        for (String line : lines) {
            String trimmed = stripMarkdown(line.trim());
            if (trimmed.isBlank()) continue;

            // Detect section transitions with semantic similarity
            SectionContext detectedContext = detectSection(trimmed);
            if (detectedContext != SectionContext.UNKNOWN) {
                context = detectedContext;
                log.debug("JD section detected: {} at line: {}", context, trimmed);
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
            boolean isBullet = bulletMatcher.matches();
            String content = isBullet ? bulletMatcher.group(1) : trimmed;

            if (!shouldProcessLineForSkills(content, context, isBullet)) {
                continue;
            }

            List<String> extracted = extractTechTokens(content, line);
            
            // Detect intensity modifiers for each skill
            for (String skill : extracted) {
                IntensityLevel intensity = detectIntensity(content, skill);
                skillsWithContext.add(new SkillWithContext(skill, context, intensity, content));
            }
        }
        
        // Classify skills using multiple signals
        List<String> mustHaves = new ArrayList<>();
        List<String> niceToHaves = new ArrayList<>();
        
        for (SkillWithContext swc : skillsWithContext) {
            // Priority 1: Explicit section context
            if (swc.context == SectionContext.MUST_HAVE) {
                mustHaves.add(swc.skill);
                continue;
            }
            if (swc.context == SectionContext.NICE_TO_HAVE) {
                niceToHaves.add(swc.skill);
                continue;
            }
            
            // Priority 2: Intensity modifiers
            if (swc.intensity == IntensityLevel.HIGH) {
                mustHaves.add(swc.skill);
                continue;
            }
            if (swc.intensity == IntensityLevel.LOW) {
                niceToHaves.add(swc.skill);
                continue;
            }
            
            // Priority 3: Default to must-have for unstructured
            mustHaves.add(swc.skill);
        }

        // Deduplicate
        List<String> dedupedMustHaves = deduplicate(mustHaves);
        List<String> dedupedNiceToHaves = deduplicate(niceToHaves);
        
        // Remove nice-to-haves that are also must-haves (must-haves take precedence)
        dedupedNiceToHaves.removeAll(dedupedMustHaves);
        
        log.info("JD skills extracted: {} must-haves, {} nice-to-haves", dedupedMustHaves.size(), dedupedNiceToHaves.size());
        log.info("Must-haves: {}", dedupedMustHaves);
        log.info("Nice-to-haves: {}", dedupedNiceToHaves);
        
        // If still unstructured and many skills, use TF-IDF for split
        if (dedupedNiceToHaves.isEmpty() && dedupedMustHaves.size() >= unstructuredSplitMinSkills) {
            splitByImportance(text, dedupedMustHaves, jd);
        } else {
            jd.setMustHaveSkills(dedupedMustHaves);
            jd.setNiceToHaveSkills(dedupedNiceToHaves);
        }
    }
    
    /**
     * Detect section type using regex + semantic similarity.
     */
    private SectionContext detectSection(String line) {
        // Try regex first (fast path)
        if (MUST_HAVE_SECTION.matcher(line).matches()) {
            return SectionContext.MUST_HAVE;
        }
        if (NICE_TO_HAVE_SECTION.matcher(line).matches()) {
            return SectionContext.NICE_TO_HAVE;
        }
        
        // Check for structural markers (markdown headers, all caps, numbered)
        if (line.matches("^#{1,3}\\s+.+") || 
            line.matches("^[A-Z\\s]{10,}:?$") ||
            line.matches("^\\d+\\.\\s+[A-Z].+")) {
            
            // Use semantic similarity
            double mustHaveSim = sentenceEncoder.mustHaveSimilarity(line);
            double niceToHaveSim = sentenceEncoder.niceToHaveSimilarity(line);
            
            if (mustHaveSim > 0.7 && mustHaveSim > niceToHaveSim) {
                return SectionContext.MUST_HAVE;
            }
            if (niceToHaveSim > 0.7 && niceToHaveSim > mustHaveSim) {
                return SectionContext.NICE_TO_HAVE;
            }
        }
        
        return SectionContext.UNKNOWN;
    }
    
    /**
     * Detect intensity modifiers around a skill.
     */
    private IntensityLevel detectIntensity(String text, String skill) {
        String lower = text.toLowerCase();

        // "or" between skills → preferred (either/or, not both required)
        if (lower.matches(".*\\b" + java.util.regex.Pattern.quote(skill.toLowerCase()) + "\\b.*\\bor\\b.*")
            || lower.matches(".*\\bor\\b.*\\b" + java.util.regex.Pattern.quote(skill.toLowerCase()) + "\\b.*")) {
            return IntensityLevel.LOW;
        }

        // High intensity (must-have indicators)
        if (lower.matches(".*\\b(strong|expert|proficient|solid|deep|extensive|proven|required|must|essential)\\b.*")) {
            return IntensityLevel.HIGH;
        }

        // Low intensity (nice-to-have indicators)
        if (lower.matches(".*\\b(familiarity|exposure|knowledge|understanding|bonus|plus|preferred|nice|optional)\\b.*")) {
            return IntensityLevel.LOW;
        }

        return IntensityLevel.MEDIUM;
    }
    
    /**
     * Split skills by importance using TF-IDF.
     */
    private void splitByImportance(String text, List<String> allSkills, JobDescription jd) {
        Map<String, Double> tfidf = tfidfVectorizer.computeTfIdf(text, allSkills);
        
        // Sort by TF-IDF score
        List<Map.Entry<String, Double>> sorted = tfidf.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toList());
        
        // Top 70% are must-haves
        int splitPoint = (int) Math.ceil(sorted.size() * unstructuredMustHaveRatio);
        
        List<String> mustHaves = sorted.subList(0, splitPoint).stream()
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        List<String> niceToHaves = sorted.subList(splitPoint, sorted.size()).stream()
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        jd.setMustHaveSkills(mustHaves);
        jd.setNiceToHaveSkills(niceToHaves);
        
        log.info("Split skills by TF-IDF: {} must-haves, {} nice-to-haves", mustHaves.size(), niceToHaves.size());
    }

    /**
     * Extracts tech skills from a line of JD text using the skill taxonomy.
     *
     * Strategy — n-gram sliding window (3→2→1) over whitespace-split tokens:
     *  - Try 3-word phrases first ("Spring Boot", "New Relic", "GitHub Actions")
     *  - Then 2-word phrases ("SQL Server", "Power BI")
     *  - Then single words ("Python", "Docker", "Kafka")
     * 
     * Filters out verb phrases and action words using POS tagging.
     */
    private List<String> extractTechTokens(String line, String fullContext) {
        // Pre-process: handle "or" between skills and slash-separated skills
        // "React or Angular" → "React, Angular"
        // "Python/Java/Go" → "Python, Java, Go"
        String preprocessed = line
            .replaceAll("(?i)\\s+or\\s+", ", ")   // "React or Angular" → "React, Angular"
            .replaceAll("/", ", ");                 // "Python/Java/Go" → "Python, Java, Go"

        // N-gram matching against ESCO + MIND-tech taxonomies
        String[] raw = preprocessed.split("[\\s,;|()\\[\\]]+");
        List<String> words = new ArrayList<>();
        for (String w : raw) {
            String cleaned = w.replaceAll("^[^\\w.#+\\-]+", "")
                               .replaceAll("[.,;!?:*]+$", "")
                               .trim();
            if (!cleaned.isEmpty()) words.add(cleaned);
        }

        List<String> found = new ArrayList<>();
        boolean[] consumed = new boolean[words.size()];

        for (int i = 0; i < words.size(); i++) {
            if (consumed[i]) continue;

            String w0 = words.get(i).toLowerCase();
            
            // Try 3-gram
            if (i + 2 < words.size() && !consumed[i + 1] && !consumed[i + 2]) {
                String w1 = words.get(i + 1).toLowerCase();
                String w2 = words.get(i + 2).toLowerCase();
                String trigram = w0 + " " + w1 + " " + w2;
                
                if (escoGraph.isKnownSkill(trigram) || mindTech.isKnownSkill(trigram)) {
                    String resolved = resolveSkill(trigram);
                    if (shouldKeepSkillCandidate(trigram, resolved, fullContext)) {
                        found.add(resolved);
                        consumed[i] = consumed[i + 1] = consumed[i + 2] = true;
                        i += 2;
                        continue;
                    }
                }
            }
            
            // Try 2-gram
            if (i + 1 < words.size() && !consumed[i + 1]) {
                String w1 = words.get(i + 1).toLowerCase();
                String bigram = w0 + " " + w1;
                
                if (escoGraph.isKnownSkill(bigram) || mindTech.isKnownSkill(bigram)) {
                    String resolved = resolveSkill(bigram);
                    if (shouldKeepSkillCandidate(bigram, resolved, fullContext)) {
                        found.add(resolved);
                        consumed[i] = consumed[i + 1] = true;
                        i += 1;
                        continue;
                    }
                }
            }
            
            // Try 1-gram
            if (shouldSkipSingleWordToken(words.get(i), fullContext)) {
                continue;
            }
            if (escoGraph.isKnownSkill(w0) || mindTech.isKnownSkill(w0)) {
                String resolved = resolveSkill(w0);
                if (shouldKeepSkillCandidate(words.get(i), resolved, fullContext)) {
                    found.add(resolved);
                }
                consumed[i] = true;
            }
        }

        return found;
    }

    private boolean shouldProcessLineForSkills(String line, SectionContext context, boolean isBullet) {
        if (context != SectionContext.UNKNOWN || isBullet) {
            return true;
        }

        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("experience with") || lower.contains("experience in") ||
            lower.contains("proficient in") || lower.contains("knowledge of") ||
            lower.contains("familiarity with") || lower.contains("hands-on") ||
            lower.contains("required") || lower.contains("must have") ||
            lower.contains("nice to have") || lower.contains("preferred") ||
            lower.contains("skills in");
    }

    private boolean shouldSkipSingleWordToken(String rawToken, String fullContext) {
        String lower = rawToken.toLowerCase(Locale.ROOT);
        if (GENERIC_SKILL_NOISE.contains(lower)) {
            return true;
        }
        if (AMBIGUOUS_SINGLE_WORD_SKILLS.contains(lower) && !rawToken.equals("Go") && !rawToken.equals("R")) {
            return true;
        }
        return rawToken.matches("[A-Za-z]+") && posTagService.isVerb(lower, fullContext);
    }

    private boolean shouldKeepSkillCandidate(String rawCandidate, String resolvedSkill, String fullContext) {
        if (resolvedSkill == null || resolvedSkill.isBlank()) {
            return false;
        }

        String rawLower = rawCandidate.toLowerCase(Locale.ROOT).trim();
        String resolvedLower = resolvedSkill.toLowerCase(Locale.ROOT).trim();

        if (GENERIC_SKILL_NOISE.contains(rawLower) || GENERIC_SKILL_NOISE.contains(resolvedLower)) {
            return false;
        }
        if ((escoGraph.isKnownSkill(rawLower) || escoGraph.isKnownSkill(resolvedLower))
            && !escoGraph.isTechnicalSkill(resolvedSkill)) {
            return false;
        }
        if (resolvedLower.split("\\s+").length == 1 && shouldSkipSingleWordToken(resolvedSkill, fullContext)) {
            return false;
        }

        return true;
    }
    
    /**
     * Resolve skill name to canonical form (try ESCO first, then MIND-tech).
     */
    private String resolveSkill(String skill) {
        String resolved = escoGraph.resolve(skill.toLowerCase());
        if (resolved != null) return resolved;
        
        resolved = mindTech.resolve(skill);
        if (resolved != null) return resolved;
        
        return skill;
    }
    
    /**
     * Check if a token should be filtered out.
     * Uses POS tagging to distinguish verbs from nouns.
     */
    private List<String> deduplicate(List<String> list) {
        // Case-insensitive deduplication - keep first occurrence
        Map<String, String> seen = new LinkedHashMap<>();
        for (String s : list) {
            String trimmed = s.trim();
            String key = trimmed.toLowerCase();
            if (!seen.containsKey(key)) {
                seen.put(key, trimmed);
            }
        }
        return new ArrayList<>(seen.values());
    }

    // ── Skill weighting ───────────────────────────────────────────────────────
    
    /**
     * Compute importance weights for all skills using multiple signals:
     * - TF-IDF (frequency in JD)
     * - Role relevance (from MIND-tech ontology)
     * - Positional weight (earlier in JD = more important)
     */
    private void computeSkillWeights(String text, JobDescription jd) {
        List<String> allSkills = new ArrayList<>();
        allSkills.addAll(jd.getMustHaveSkills());
        allSkills.addAll(jd.getNiceToHaveSkills());
        
        if (allSkills.isEmpty()) {
            return;
        }
        
        Map<String, Double> weights = new HashMap<>();
        
        // Compute TF-IDF scores
        Map<String, Double> tfidf = tfidfVectorizer.computeTfIdf(text, allSkills);
        
        // Compute role relevance scores
        String roleTitle = jd.getRoleTitle() != null ? jd.getRoleTitle() : "";
        
        for (String skill : allSkills) {
            double tfidfScore = tfidf.getOrDefault(skill, 0.5);
            double roleRelevance = mindTech.getRoleSkillRelevance(roleTitle, skill);
            double positionalWeight = tfidfVectorizer.computePositionalWeight(text, skill);
            
            // Weighted combination
            double finalWeight = 0.4 * tfidfScore + 0.4 * roleRelevance + 0.2 * positionalWeight;
            
            weights.put(skill, finalWeight);
        }
        
        jd.setSkillWeights(weights);
        log.debug("Computed skill weights: {}", weights);
    }

    // ── Implied skills ────────────────────────────────────────────────────────

    private void inferImpliedSkills(JobDescription jd) {
        Set<String> implied = new HashSet<>();
        
        for (String skill : jd.getMustHaveSkills()) {
            // Get implications from MIND-tech ontology
            List<String> mindTechImplied = mindTech.getImpliedSkills(skill);
            implied.addAll(mindTechImplied);
            
            // Get related skills from ESCO
            if (escoGraph.isKnownSkill(skill.toLowerCase())) {
                String canonical = escoGraph.resolve(skill.toLowerCase());
                // ESCO doesn't have explicit relationships in current implementation
                // but we keep this for future enhancement
            }
        }
        
        // Remove skills that are already explicitly listed (case-insensitive)
        Set<String> existingLower = new HashSet<>();
        jd.getMustHaveSkills().forEach(s -> existingLower.add(s.toLowerCase()));
        jd.getNiceToHaveSkills().forEach(s -> existingLower.add(s.toLowerCase()));
        
        implied.removeIf(skill -> existingLower.contains(skill.toLowerCase()));
        
        jd.setImpliedSkills(new ArrayList<>(implied));
        log.debug("Inferred implied skills: {}", implied);
    }

    // ── IC level estimation ───────────────────────────────────────────────────

    private void estimateIcLevel(String text, JobDescription jd) {
        // Check title + full text (not just first 300 chars)
        String titleAndText = (jd.getRoleTitle() != null ? jd.getRoleTitle() : "") + " " + text;
        
        for (Map.Entry<Pattern, Integer> entry : SENIORITY_IC.entrySet()) {
            if (entry.getKey().matcher(titleAndText).find()) {
                jd.setIcLevel(entry.getValue());
                return;
            }
        }
        
        // Cross-reference with YOE if available
        if (jd.getYoeMin() != null) {
            double yoe = jd.getYoeMin();
            if (yoe <= 2) {
                jd.setIcLevel(2); // Junior/Associate
            } else if (yoe <= 5) {
                jd.setIcLevel(3); // Mid-level
            } else if (yoe <= 8) {
                jd.setIcLevel(4); // Senior
            } else {
                jd.setIcLevel(5); // Staff/Principal
            }
            return;
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
    
    // ── Validation ────────────────────────────────────────────────────────────
    
    /**
     * Validate parse quality and add warnings.
     */
    private void validateParse(JobDescription jd) {
        List<String> warnings = new ArrayList<>();
        
        // 1. Skill count check
        if (jd.getMustHaveSkills().size() < 3) {
            warnings.add("Low skill count: " + jd.getMustHaveSkills().size());
            jd.setParseConfidence(jd.getParseConfidence() * 0.7);
        }
        
        // 2. Role-skill alignment
        if (jd.getRoleTitle() != null && !jd.getRoleTitle().equals("Unknown Role")) {
            double alignmentScore = computeSkillAlignment(jd);
            if (alignmentScore < 0.5) {
                warnings.add("Skills may not match role category (alignment: " + 
                    String.format("%.2f", alignmentScore) + ")");
                jd.setParseConfidence(jd.getParseConfidence() * 0.8);
            }
        }
        
        // 3. Truncation detection
        String rawText = jd.getRawText();
        if (rawText != null && (rawText.length() < 200 || !rawText.matches(".*[.!?]\\s*$"))) {
            warnings.add("Possibly truncated JD");
        }
        
        // 4. Title validation
        if (jd.getRoleTitle() == null || jd.getRoleTitle().equals("Unknown Role")) {
            warnings.add("Could not extract role title");
        }
        
        jd.setParseWarnings(warnings);
        
        if (!warnings.isEmpty()) {
            log.info("Parse warnings: {}", warnings);
        }
    }
    
    /**
     * Compute alignment score between role and extracted skills.
     */
    private double computeSkillAlignment(JobDescription jd) {
        if (jd.getMustHaveSkills().isEmpty()) {
            return 0.0;
        }
        
        String roleTitle = jd.getRoleTitle();
        double totalRelevance = 0.0;
        
        for (String skill : jd.getMustHaveSkills()) {
            double relevance = mindTech.getRoleSkillRelevance(roleTitle, skill);
            totalRelevance += relevance;
        }
        
        return totalRelevance / jd.getMustHaveSkills().size();
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    private double computeConfidence(JobDescription jd) {
        double score = 0.0;
        if (jd.getRoleTitle() != null && !jd.getRoleTitle().equals("Unknown Role")) score += 0.25;
        if (!jd.getMustHaveSkills().isEmpty()) score += 0.35;
        if (jd.getYoeMin() != null) score += 0.15;
        if (jd.isWellStructured()) score += 0.15;
        if (!jd.getSkillWeights().isEmpty()) score += 0.10; // Bonus for computed weights
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
    
    private enum IntensityLevel { HIGH, MEDIUM, LOW }
    
    private static class SkillWithContext {
        final String skill;
        final SectionContext context;
        final IntensityLevel intensity;
        final String sourceText;
        
        SkillWithContext(String skill, SectionContext context, IntensityLevel intensity, String sourceText) {
            this.skill = skill;
            this.context = context;
            this.intensity = intensity;
            this.sourceText = sourceText;
        }
    }

    // ── jdClarity scoring (Layer 0b Step 10) ─────────────────────────────────

    private com.resumestudio.reviewer.model.enums.JdClarity computeJdClarity(String rawText, JobDescription jd) {
        int score = 0;
        if (!jd.getMustHaveSkills().isEmpty()) score += 2;
        if (jd.getYoeMin() != null) score += 1;
        if (jd.isWellStructured()) score += 2;
        if (MUST_HAVE_SECTION.matcher(rawText).find()) score += 2;
        if (!jd.getNiceToHaveSkills().isEmpty()) score += 1;
        if (jd.getIcLevel() > 0 && jd.getIcLevel() < 6) score += 1;
        jd.setJdClarityScore(score);
        if (score >= 7) return com.resumestudio.reviewer.model.enums.JdClarity.HIGH;
        if (score >= 4) return com.resumestudio.reviewer.model.enums.JdClarity.MEDIUM;
        return com.resumestudio.reviewer.model.enums.JdClarity.LOW;
    }

    /** Strips boilerplate, keeps requirements + tech stack. ~150 tokens for AI prompt. */
    private String buildTrimmedText(String rawText, JobDescription jd) {
        StringBuilder sb = new StringBuilder();
        if (jd.getRoleTitle() != null) sb.append("Role: ").append(jd.getRoleTitle()).append("\n");
        if (!jd.getMustHaveSkills().isEmpty())
            sb.append("Required: ").append(String.join(", ", jd.getMustHaveSkills())).append("\n");
        if (!jd.getNiceToHaveSkills().isEmpty())
            sb.append("Preferred: ").append(String.join(", ", jd.getNiceToHaveSkills())).append("\n");
        if (jd.getYoeRawStatement() != null)
            sb.append("Experience: ").append(jd.getYoeRawStatement()).append("\n");
        return sb.toString().trim();
    }
}
