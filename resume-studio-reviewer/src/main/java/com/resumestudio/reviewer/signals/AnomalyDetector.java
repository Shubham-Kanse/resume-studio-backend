package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.nlp.NlpService;
import com.resumestudio.reviewer.skills.EscoSkillGraph;
import com.resumestudio.reviewer.skills.SkillRecencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    private static final Pattern SKILL_YOE_CLAIM = Pattern.compile(
        "(\\d+)\\s*\\+?\\s*years?\\s+(?:of\\s+)?([A-Za-z][A-Za-z0-9.+#\\s]{1,30})",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SKILL_YOE_CLAIM_ALT = Pattern.compile(
        "([A-Za-z][A-Za-z0-9.+#\\s]{1,30})\\s+(?:for\\s+)?(\\d+)\\s*\\+?\\s*years?",
        Pattern.CASE_INSENSITIVE);

    private static final List<String> JUNIOR_BULLET_VERBS = List.of(
        "assisted", "helped", "supported", "shadowed", "learned", "was responsible for",
        "participated in", "contributed to", "worked on", "involved in"
    );

    private static final List<String> SENIOR_BULLET_VERBS = List.of(
        "architected", "designed", "led", "owned", "drove", "established", "defined",
        "mentored", "spearheaded", "pioneered", "transformed", "built from scratch"
    );

    private final EscoSkillGraph escoGraph;
    private final NlpService nlp;
    private final SkillRecencyService skillRecency;

    public AnomalyDetector(EscoSkillGraph escoGraph, NlpService nlp, SkillRecencyService skillRecency) {
        this.escoGraph = escoGraph;
        this.nlp = nlp;
        this.skillRecency = skillRecency;
    }

    public void detect(List<Skill> skills, List<WorkExperience> experience,
                       String fullText, ResumeSignals signals) {
        detectSkillAgeMismatch(fullText, signals);
        detectTitleInflation(experience, signals);
        // Note: bullet quality (impactVerbRatio, metricDensity) is computed in
        // ReviewerPipeline.computeSignals via NlpService over ALL bullets — not duplicated here.
    }

    private void detectSkillAgeMismatch(String text, ResumeSignals signals) {
        if (text == null) return;
        int currentYear = LocalDate.now().getYear();
        Matcher m1 = SKILL_YOE_CLAIM.matcher(text);
        while (m1.find()) checkMismatch(m1.group(2).trim(), Integer.parseInt(m1.group(1)), currentYear, signals);
        Matcher m2 = SKILL_YOE_CLAIM_ALT.matcher(text);
        while (m2.find()) checkMismatch(m2.group(1).trim(), Integer.parseInt(m2.group(2)), currentYear, signals);
    }

    private void checkMismatch(String skillName, int claimedYears, int currentYear, ResumeSignals signals) {
        // Use SkillRecencyService — replaces the broken escoGraph.releaseYearOf() stub
        if (skillRecency.isYoeClaimSuspicious(skillName, claimedYears)) {
            Integer born = skillRecency.bornYear(skillName);
            int maxPossible = born != null ? currentYear - born : claimedYears;
            signals.setHasSkillAgeMismatch(true);
            signals.setSkillAgeMismatchDetail(String.format(
                "Claims %d years of %s, but it was released ~%d (max possible: ~%d years).",
                claimedYears, skillName, born != null ? born : currentYear - maxPossible, maxPossible));
        }
    }

    private void detectTitleInflation(List<WorkExperience> experience, ResumeSignals signals) {
        if (experience == null || experience.isEmpty()) return;
        WorkExperience recent = experience.get(0);
        if (recent.getTitle() == null || recent.getBullets() == null) return;
        if (recent.getIcLevel() < 4) return;

        int juniorCount = 0, seniorCount = 0;
        int total = recent.getBullets().size();
        for (String bullet : recent.getBullets()) {
            String lower = bullet.toLowerCase();
            if (JUNIOR_BULLET_VERBS.stream().anyMatch(lower::contains)) juniorCount++;
            if (SENIOR_BULLET_VERBS.stream().anyMatch(lower::contains)) seniorCount++;
        }
        if (total >= 3 && juniorCount > seniorCount && juniorCount > total / 2) {
            signals.setHasTitleInflation(true);
        }
    }

    /**
     * Uses NlpService POS tagger to compute bullet quality signals:
     * - impactVerbRatio: fraction of bullets starting with strong action verbs
     * - metricDensity: fraction of bullets containing quantified claims
     */
    private void computeBulletQuality(List<WorkExperience> experience, ResumeSignals signals) {
        if (experience == null || experience.isEmpty()) return;

        // Collect bullets from the 2 most recent roles
        List<String> bullets = experience.stream()
            .limit(2)
            .filter(e -> e.getBullets() != null)
            .flatMap(e -> e.getBullets().stream())
            .toList();

        if (bullets.isEmpty()) return;

        signals.setImpactVerbRatio(nlp.impactVerbRatio(bullets));
        signals.setMetricDensity(nlp.metricDensity(bullets));
    }
}
