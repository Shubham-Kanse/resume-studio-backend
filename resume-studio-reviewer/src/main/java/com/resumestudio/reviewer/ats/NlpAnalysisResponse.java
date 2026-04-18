package com.resumestudio.reviewer.ats;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Response shape for POST /api/ats/nlp-analysis.
 * Matches ATSNLPAnalysis in lib/ats-nlp-analysis-types.ts exactly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NlpAnalysisResponse {

    public ActionVerbAnalysis actionVerbs;
    public RepetitionAnalysis repetition;
    public ImpactAnalysis quantifyingImpact;
    public BulletLengthAnalysis bulletLength;
    public BuzzwordAnalysis buzzwords;
    public JobMatchAnalysis jobMatch;

    public record ActionVerbAnalysis(
        int score,
        int totalLeadVerbs,
        int strongLeadVerbs,
        int weakLeadVerbs,
        List<RepeatedVerb> repeatedLeadVerbs,
        List<WeakVerbMatch> weakMatches
    ) {}

    public record RepetitionAnalysis(
        int score,
        int totalActionVerbCount,
        int repeatedActionVerbCount,
        List<RepeatedVerb> repeatedLeadVerbs,
        List<RepeatedPhrase> repeatedPhrases
    ) {}

    public record ImpactAnalysis(
        int score,
        int totalBullets,
        int quantifiedBullets,
        int missingQuantificationAllowance,
        int achievementLikeBullets,
        int responsibilityLikeBullets,
        List<BulletImpactItem> bulletAnalyses
    ) {}

    public record BulletImpactItem(
        String bullet,
        boolean achievementLike,
        boolean quantified,
        int score,
        int scoreOutOfTen,
        List<String> analysis,
        List<String> feedback,
        List<String> reasons,
        BulletSignals signals
    ) {}

    public record BulletSignals(
        String leadVerb,
        int wordCount,
        int metricMentions,
        int outcomeMentions,
        int scopeMentions,
        boolean strongLeadVerb,
        boolean weakLeadPhrase,
        boolean passiveVoice,
        boolean resultPattern,
        boolean starLike,
        boolean taskHeavy,
        List<String> keywordMatches,
        double relevanceRatio,
        List<String> technicalEntities,
        List<String> roleAlignmentTerms
    ) {}

    public record BulletLengthAnalysis(
        int score,
        double averageWords,
        int tooShortCount,
        int tooLongCount,
        int goodLengthCount,
        List<BulletLengthItem> bullets
    ) {}

    public record BulletLengthItem(
        String bullet,
        int wordCount,
        int informativeTokenCount,
        double contentDensity,
        List<String> technicalEntities,
        List<String> keywordMatches,
        String classification,
        List<String> reasons
    ) {}

    public record BuzzwordAnalysis(
        int score,
        List<RepeatedPhrase> repeatedBuzzwords,
        List<Map<String, Object>> matchedFamilies
    ) {}

    public record JobMatchAnalysis(
        int score,
        boolean hasJobDescription,
        List<String> topJDTerms,
        List<String> matchedTerms,
        List<String> missingTerms,
        Map<String, List<String>> sectionCoverage
    ) {}

    public record RepeatedVerb(String verb, int count) {}
    public record RepeatedPhrase(String phrase, int count) {}
    public record WeakVerbMatch(String phrase, int count, List<String> replacements) {}
}
