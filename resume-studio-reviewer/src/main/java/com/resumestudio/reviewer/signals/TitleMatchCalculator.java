package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.extraction.DesignationOntologyService;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.TitleMatch;
import com.resumestudio.reviewer.model.enums.TitleProgression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Computes title match (candidate vs JD) and title progression across roles.
 */
@Component
public class TitleMatchCalculator {

    private static final Logger log = LoggerFactory.getLogger(TitleMatchCalculator.class);

    private final DesignationOntologyService ontology;

    public TitleMatchCalculator(DesignationOntologyService ontology) {
        this.ontology = ontology;
    }

    // Role domain groupings for adjacency detection
    private static final Map<String, Set<String>> ROLE_DOMAINS = new HashMap<>();
    static {
        ROLE_DOMAINS.put("backend", Set.of("backend", "server", "api", "java", "python", "go", "node", "spring", "microservices", "platform"));
        ROLE_DOMAINS.put("frontend", Set.of("frontend", "front-end", "ui", "react", "angular", "vue", "web", "javascript"));
        ROLE_DOMAINS.put("fullstack", Set.of("full stack", "fullstack", "full-stack"));
        ROLE_DOMAINS.put("devops", Set.of("devops", "platform", "sre", "infrastructure", "cloud", "kubernetes", "reliability"));
        ROLE_DOMAINS.put("mobile", Set.of("mobile", "ios", "android", "flutter", "swift", "kotlin"));
        ROLE_DOMAINS.put("data", Set.of("data", "analytics", "ml", "machine learning", "ai", "scientist", "analyst"));
        ROLE_DOMAINS.put("qa", Set.of("qa", "quality", "testing", "test", "automation", "sdet"));
        ROLE_DOMAINS.put("security", Set.of("security", "devsecops", "appsec", "infosec", "cybersecurity"));
        // Adjacent role pairs — career transitions that share domain
        ROLE_DOMAINS.put("data_science_ml", Set.of("data scientist", "ml engineer", "machine learning engineer", "ai engineer", "research engineer"));
        ROLE_DOMAINS.put("devops_sre", Set.of("devops", "sre", "site reliability", "platform engineer", "infrastructure engineer"));
        ROLE_DOMAINS.put("qa_sdet", Set.of("qa engineer", "sdet", "test engineer", "quality engineer", "automation engineer"));
        ROLE_DOMAINS.put("product_management", Set.of("product manager", "product owner", "program manager", "project manager", "technical program manager"));
    }

    // Seniority words to strip before domain comparison
    private static final Pattern SENIORITY_STRIP = Pattern.compile(
        "\\b(junior|jr\\.?|senior|sr\\.?|mid[- ]level|staff|principal|lead|associate|" +
        "head of|director|vp|chief|entry[- ]level|intermediate)\\b",
        Pattern.CASE_INSENSITIVE);

    // IC level map for progression calculation.
    // Order matters: higher seniority patterns are checked first so that
    // "Sr. Associate" resolves to senior (4) rather than associate (2).
    private static final Map<Pattern, Integer> IC_LEVELS = new LinkedHashMap<>();
    static {
        IC_LEVELS.put(Pattern.compile("\\b(distinguished|fellow|vp|director|chief)\\b", Pattern.CASE_INSENSITIVE), 6);
        IC_LEVELS.put(Pattern.compile("\\b(head of|staff|principal|lead|architect)\\b", Pattern.CASE_INSENSITIVE), 5);
        IC_LEVELS.put(Pattern.compile("\\b(senior|sr\\.?)\\b", Pattern.CASE_INSENSITIVE), 4);
        IC_LEVELS.put(Pattern.compile("\\b(mid[- ]level|intermediate)\\b", Pattern.CASE_INSENSITIVE), 3);
        IC_LEVELS.put(Pattern.compile("\\b(associate|entry[- ]level)\\b", Pattern.CASE_INSENSITIVE), 2);
        IC_LEVELS.put(Pattern.compile("\\b(intern|trainee|graduate|junior|jr\\.?)\\b", Pattern.CASE_INSENSITIVE), 1);
    }

    public void compute(String candidateTitle, String jdTitle, List<WorkExperience> experience,
                        ResumeSignals signals) {
        signals.setCandidateTitle(candidateTitle);
        signals.setJdTitle(jdTitle);
        signals.setTitleMatch(matchTitle(candidateTitle, jdTitle));
        signals.setTitleProgression(computeProgression(experience));
    }

    private TitleMatch matchTitle(String candidateTitle, String jdTitle) {
        if (candidateTitle == null || jdTitle == null) return TitleMatch.MISS;

        String cNorm = normalise(candidateTitle);
        String jNorm = normalise(jdTitle);

        // EXACT: normalised strings match
        if (cNorm.equals(jNorm)) return TitleMatch.EXACT;

        // EXACT: both resolve to the same canonical designation
        String cCanon = ontology.canonicalise(candidateTitle);
        String jCanon = ontology.canonicalise(jdTitle);
        if (cCanon != null && cCanon.equalsIgnoreCase(jCanon)) return TitleMatch.EXACT;

        // EXACT: strip seniority and compare core role (legacy fallback)
        String cCore = stripSeniority(cNorm);
        String jCore = stripSeniority(jNorm);
        if (!cCore.isBlank() && cCore.equals(jCore)) return TitleMatch.EXACT;

        // ADJACENT: same seniority band (±1), overlapping domains from ontology
        List<String> cDomains = ontology.domains(candidateTitle);
        List<String> jDomains = ontology.domains(jdTitle);
        boolean domainsOverlap = !cDomains.isEmpty() && !jDomains.isEmpty()
            && !Collections.disjoint(cDomains, jDomains);

        if (domainsOverlap) {
            int cLevel = ontology.seniorityLevel(candidateTitle);
            int jLevel = ontology.seniorityLevel(jdTitle);
            if (Math.abs(cLevel - jLevel) <= 1) return TitleMatch.ADJACENT;
        }

        // ADJACENT: candidate is in jd title's relatedDesignations
        List<String> related = ontology.relatedDesignations(jdTitle);
        if (cCanon != null && related.stream().anyMatch(r -> r.equalsIgnoreCase(cCanon)))
            return TitleMatch.ADJACENT;

        // RELATED: overlapping domains but seniority gap > 1
        if (domainsOverlap) return TitleMatch.RELATED;

        // RELATED: legacy keyword domain overlap
        if (hasOverlappingDomain(cNorm, jNorm)) return TitleMatch.RELATED;

        return TitleMatch.MISS;
    }

    private TitleProgression computeProgression(List<WorkExperience> experience) {
        if (experience == null || experience.size() < 2) return TitleProgression.UNKNOWN;

        List<WorkExperience> chronological = new ArrayList<>(experience);
        Collections.reverse(chronological); // most-recent-first → chronological

        List<Integer> levels = chronological.stream()
            .filter(e -> e.getTitle() != null)
            .map(e -> {
                int ontologyLevel = ontology.seniorityLevel(e.getTitle());
                // If ontology returned default (3), try IC_LEVELS regex as fallback
                return ontologyLevel != 3 ? ontologyLevel : extractIcLevel(e.getTitle());
            })
            .filter(l -> l > 0)
            .toList();

        if (levels.size() < 2) return TitleProgression.UNKNOWN;

        int first = levels.get(0);
        int last = levels.get(levels.size() - 1);

        if (last > first) return TitleProgression.GROWING;
        if (last < first) return TitleProgression.REGRESSION;
        return TitleProgression.FLAT;
    }

    private boolean sameDomain(String title1, String title2) {
        for (Set<String> domainKeywords : ROLE_DOMAINS.values()) {
            boolean t1InDomain = domainKeywords.stream().anyMatch(title1::contains);
            boolean t2InDomain = domainKeywords.stream().anyMatch(title2::contains);
            if (t1InDomain && t2InDomain) return true;
        }
        return false;
    }

    private boolean hasOverlappingDomain(String title1, String title2) {
        String[] words1 = title1.split("\\s+");
        String[] words2 = title2.split("\\s+");
        for (String w1 : words1) {
            if (w1.length() <= 3) continue;
            for (String w2 : words2) {
                if (w1.equals(w2)) return true;
            }
        }
        return false;
    }

    private int extractIcLevel(String title) {
        if (title == null) return 0;
        for (Map.Entry<Pattern, Integer> entry : IC_LEVELS.entrySet()) {
            if (entry.getKey().matcher(title).find()) return entry.getValue();
        }
        return 3; // default mid-level
    }

    private String normalise(String s) {
        return s.toLowerCase().trim().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ");
    }

    private String stripSeniority(String s) {
        return SENIORITY_STRIP.matcher(s).replaceAll("").trim().replaceAll("\\s+", " ");
    }
}
