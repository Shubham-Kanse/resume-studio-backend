package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.SkillsFormat;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregates all resume extraction logic.
 * Owns: header, summary, experience, education, skills extraction + section splitting.
 */
@Service
public class ResumeExtractionService {

    private final HeaderExtractor headerExtractor;
    private final SummaryExtractor summaryExtractor;
    private final ExperienceExtractor experienceExtractor;
    private final EducationExtractor educationExtractor;
    private final SkillsSectionExtractor skillsExtractor;
    private final SemanticExtractor semanticExtractor;
    private final DesignationOntologyService designationOntology;

    public ResumeExtractionService(HeaderExtractor headerExtractor,
                                   SummaryExtractor summaryExtractor,
                                   ExperienceExtractor experienceExtractor,
                                   EducationExtractor educationExtractor,
                                   SkillsSectionExtractor skillsExtractor,
                                   SemanticExtractor semanticExtractor,
                                   DesignationOntologyService designationOntology) {
        this.headerExtractor = headerExtractor;
        this.summaryExtractor = summaryExtractor;
        this.experienceExtractor = experienceExtractor;
        this.educationExtractor = educationExtractor;
        this.skillsExtractor = skillsExtractor;
        this.semanticExtractor = semanticExtractor;
        this.designationOntology = designationOntology;
    }

    public Resume extract(RawDocument raw) {
        Resume resume = new Resume();
        resume.setRawFilename(raw.getFilename());

        headerExtractor.extract(raw, resume);

        String fullText = raw.getFullText();
        SemanticExtractor.SectionMap sections = semanticExtractor.extractSections(fullText);

        if (sections.summary != null) {
            summaryExtractor.extract(sections.summary, resume);
        }
        if (sections.experience != null) {
            resume.setExperience(experienceExtractor.extract(sections.experience));
        }
        if (sections.education != null) {
            resume.setEducation(educationExtractor.extract(sections.education));
        }
        if (sections.skills != null) {
            SkillsSectionExtractor.ExtractionResult skillResult =
                skillsExtractor.extract(sections.skills, extractAllBullets(fullText));
            List<Skill> deduped = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Skill s : skillResult.getSkills()) {
                String key = (s.getCanonicalName() != null ? s.getCanonicalName() : s.getRawName())
                    .toLowerCase().trim();
                if (seen.add(key)) deduped.add(s);
            }
            resume.setSkills(deduped);
        }

        return resume;
    }

    public SummaryExtractor.SummaryAnalysis analyseSummary(String summaryText, String roleTitle) {
        return summaryExtractor.analyse(summaryText, roleTitle);
    }

    public SkillsFormat detectInitialFormat(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) return SkillsFormat.NO_SECTION;
        boolean hasCategories = skills.stream().anyMatch(s -> s.getCategory() != null && !s.getCategory().isBlank());
        return hasCategories ? SkillsFormat.CATEGORISED_UNORDERED : SkillsFormat.FLAT_UNORDERED;
    }

    public String inferDomain(String roleTitle) {
        return designationOntology.inferDomain(roleTitle);
    }

    private List<String> extractAllBullets(String text) {
        Pattern bulletPattern = Pattern.compile("^\\s*[•\\-*▪◦➤►→]\\s*(.+)$", Pattern.MULTILINE);
        Matcher matcher = bulletPattern.matcher(text);
        List<String> bullets = new ArrayList<>();
        while (matcher.find()) bullets.add(matcher.group(1).trim());
        return bullets;
    }
}
