package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.extraction.ResumeExtractionService;
import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import com.resumestudio.reviewer.model.enums.SkillsFormat;
import com.resumestudio.reviewer.model.enums.YoeFit;
import com.resumestudio.reviewer.model.enums.YoeState;
import com.resumestudio.reviewer.skills.SkillMatchEngine;
import com.resumestudio.reviewer.skills.SkillVisibilityAnalyser;
import com.resumestudio.reviewer.skills.SkillsFormatAnalyser;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Aggregates all signal computation logic.
 * Owns: filename, format, title, yoe, company, skills, anomaly, bullet signals.
 */
@Service
public class SignalComputationService {

    private final FilenameSignalCalculator filenameCalculator;
    private final FormatSignalCalculator formatCalculator;
    private final TitleMatchCalculator titleCalculator;
    private final YoeSignalCalculator yoeCalculator;
    private final CompanySignalCalculator companyCalculator;
    private final SkillMatchEngine skillMatchEngine;
    private final SkillVisibilityAnalyser visibilityAnalyser;
    private final SkillsFormatAnalyser formatAnalyser;
    private final AnomalyDetector anomalyDetector;
    private final ResumeExtractionService extractionService;
    private final com.resumestudio.reviewer.nlp.NlpService nlpService;
    private final com.resumestudio.reviewer.nlp.BulletEnricher bulletEnricher;

    public SignalComputationService(FilenameSignalCalculator filenameCalculator,
                                    FormatSignalCalculator formatCalculator,
                                    TitleMatchCalculator titleCalculator,
                                    YoeSignalCalculator yoeCalculator,
                                    CompanySignalCalculator companyCalculator,
                                    SkillMatchEngine skillMatchEngine,
                                    SkillVisibilityAnalyser visibilityAnalyser,
                                    SkillsFormatAnalyser formatAnalyser,
                                    AnomalyDetector anomalyDetector,
                                    ResumeExtractionService extractionService,
                                    com.resumestudio.reviewer.nlp.NlpService nlpService,
                                    com.resumestudio.reviewer.nlp.BulletEnricher bulletEnricher) {
        this.filenameCalculator = filenameCalculator;
        this.formatCalculator = formatCalculator;
        this.titleCalculator = titleCalculator;
        this.yoeCalculator = yoeCalculator;
        this.companyCalculator = companyCalculator;
        this.skillMatchEngine = skillMatchEngine;
        this.visibilityAnalyser = visibilityAnalyser;
        this.formatAnalyser = formatAnalyser;
        this.anomalyDetector = anomalyDetector;
        this.extractionService = extractionService;
        this.nlpService = nlpService;
        this.bulletEnricher = bulletEnricher;
    }

    public ResumeSignals compute(Resume resume, JobDescription jd, RawDocument raw) {
        ResumeSignals signals = new ResumeSignals();

        filenameCalculator.compute(raw.getFilename(), signals);
        formatCalculator.compute(raw, resume.getTotalYoeYears() != null ? resume.getTotalYoeYears() : 0.0, signals);
        titleCalculator.compute(resume.getCurrentTitle(), jd.getRoleTitle(), resume.getExperience(), signals);

        var summaryAnalysis = extractionService.analyseSummary(resume.getSummaryText(), jd.getRoleTitle());
        signals.setSummaryPresent(summaryAnalysis.isPresent());
        signals.setSummaryMentionsTitle(summaryAnalysis.isMentionsTitle());
        signals.setSummaryMentionsYoe(summaryAnalysis.isMentionsYoe());
        signals.setSummaryMentionsSkills(summaryAnalysis.isMentionsSkills());
        signals.setSummaryIsGeneric(summaryAnalysis.isGeneric());

        yoeCalculator.compute(resume.getExperience(), resume.getTotalYoeYears(),
            resume.isYoeExplicitInSummary(), jd.getYoeMin(), jd.getYoeMax(), signals, resume.getEducation());
        companyCalculator.compute(resume.getExperience(), resume.getCurrentCompany(),
            resume.getCompanyDescriptor(), signals);

        visibilityAnalyser.analyse(resume.getSkills(), resume.getExperience(), resume.getSummaryText());

        SkillsFormat initialFormat = extractionService.detectInitialFormat(resume.getSkills());
        signals.setSkillsFormat(formatAnalyser.refine(initialFormat, resume.getSkills(), jd.getMustHaveSkills()));

        List<SkillMatchResult> mustHaveResults = skillMatchEngine.matchAll(jd.getMustHaveSkills(), resume.getSkills(), true);
        List<SkillMatchResult> niceToHaveResults = skillMatchEngine.matchAll(jd.getNiceToHaveSkills(), resume.getSkills(), false);
        signals.setMustHaveResults(mustHaveResults);
        signals.setNiceToHaveResults(niceToHaveResults);

        boolean hasJdSkills = !mustHaveResults.isEmpty();
        signals.setAllMustHavesFound(hasJdSkills && mustHaveResults.stream().allMatch(r -> r.getVisibility() != SkillVisibility.MISSING));
        signals.setAllMustHavesVisible(hasJdSkills && mustHaveResults.stream().allMatch(r ->
            r.getVisibility() == SkillVisibility.SURFACE || r.getVisibility() == SkillVisibility.MID));
        signals.setHasBuriedMustHaves(mustHaveResults.stream().anyMatch(r -> r.getVisibility() == SkillVisibility.BURIED));
        signals.setHasMissingMustHaves(mustHaveResults.stream().anyMatch(r -> r.getVisibility() == SkillVisibility.MISSING));

        anomalyDetector.detect(resume.getSkills(), resume.getExperience(), resume.getSummaryText(), signals);

        List<String> allBullets = resume.getExperience().stream()
            .flatMap(exp -> exp.getBullets().stream()).toList();
        signals.setImpactVerbRatio(nlpService.impactVerbRatio(allBullets));
        signals.setMetricDensity(nlpService.metricDensity(allBullets));

        var enrichment = bulletEnricher.enrich(resume.getExperience(), jd.getMustHaveSkills());
        resume.setTopBullets(enrichment.topBullets());
        resume.setEnrichedBullets(enrichment.bullets());

        if (jd.getJdClarity() != null) signals.setJdClarity(jd.getJdClarity());

        boolean hasProjects = resume.getProjects() != null && !resume.getProjects().isEmpty();
        signals.setHasProjectsSection(hasProjects);

        boolean isFresherRole = jd.getYoeMin() == null || jd.getYoeMin() <= 1.0;
        if (isFresherRole && hasProjects && signals.getYoeFit() == YoeFit.CANNOT_DETERMINE) {
            signals.setYoeState(YoeState.CALCULABLE);
        }

        return signals;
    }
}
