package com.resumestudio.reviewer.generate;

import com.resumestudio.reviewer.model.Education;
import com.resumestudio.reviewer.model.Project;
import com.resumestudio.reviewer.model.Resume;
import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.WorkExperience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Merges 1–3 parsed resumes into a single canonical resume data object.
 *
 * Strategy:
 *  - Contact info:  first-non-null across all resumes (most recent wins)
 *  - Experience:    union of all roles, deduped by (title + company), sorted newest-first
 *  - Skills:        union deduped by canonical name, preserving categories
 *  - Education:     union deduped by institution+degree
 *  - Projects:      union deduped by name
 *  - Summary:       longest (most complete) summary text
 */
@Service
public class ResumeMergeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeMergeService.class);

    /**
     * Merge multiple resumes into one consolidated Resume.
     * Input list is ordered by upload priority (index 0 = primary).
     */
    public Resume merge(List<Resume> resumes) {
        if (resumes == null || resumes.isEmpty()) {
            throw new IllegalArgumentException("At least one resume is required");
        }
        if (resumes.size() == 1) {
            return resumes.get(0);
        }

        log.info("Merging {} resumes", resumes.size());
        Resume merged = new Resume();

        // ── Contact / Identity: first-non-null ────────────────────────────────
        merged.setCandidateName(firstNonNull(resumes, Resume::getCandidateName));
        merged.setEmail(firstNonNull(resumes, Resume::getEmail));
        merged.setPhone(firstNonNull(resumes, Resume::getPhone));
        merged.setLocation(firstNonNull(resumes, Resume::getLocation));
        merged.setLinkedInUrl(firstNonNull(resumes, Resume::getLinkedInUrl));
        merged.setGitHubUrl(firstNonNull(resumes, Resume::getGitHubUrl));
        merged.setCurrentTitle(firstNonNull(resumes, Resume::getCurrentTitle));
        merged.setCurrentCompany(firstNonNull(resumes, Resume::getCurrentCompany));
        merged.setCompanyDescriptor(firstNonNull(resumes, Resume::getCompanyDescriptor));

        // ── Summary: pick the most detailed (highest word count) ──────────────
        merged.setSummaryText(bestSummary(resumes));

        // ── Experience: union deduped by company+title, sorted newest-first ───
        merged.setExperience(mergeExperience(resumes));

        // ── Skills: union deduped by canonical name ───────────────────────────
        merged.setSkills(mergeSkills(resumes));

        // ── Education: union deduped by institution+degree ────────────────────
        merged.setEducation(mergeEducation(resumes));

        // ── Projects: union deduped by name ──────────────────────────────────
        merged.setProjects(mergeProjects(resumes));

        // ── YOE: maximum across all resumes ──────────────────────────────────
        OptionalDouble maxYoe = resumes.stream()
            .filter(r -> r.getTotalYoeYears() != null)
            .mapToDouble(Resume::getTotalYoeYears)
            .max();
        maxYoe.ifPresent(merged::setTotalYoeYears);

        log.info("Merged resume: {} roles, {} skills, {} education entries, {} projects",
            merged.getExperience().size(), merged.getSkills().size(),
            merged.getEducation().size(), merged.getProjects().size());

        return merged;
    }

    // ── Merging logic ─────────────────────────────────────────────────────────

    private String bestSummary(List<Resume> resumes) {
        return resumes.stream()
            .map(Resume::getSummaryText)
            .filter(s -> s != null && !s.isBlank())
            .max(Comparator.comparingInt(s -> s.trim().split("\\s+").length))
            .orElse(null);
    }

    private List<WorkExperience> mergeExperience(List<Resume> resumes) {
        Map<String, WorkExperience> seen = new LinkedHashMap<>();

        for (Resume r : resumes) {
            if (r.getExperience() == null) continue;
            for (WorkExperience exp : r.getExperience()) {
                String key = dedupeKey(exp.getCompany(), exp.getTitle());
                // Keep the entry with more bullets / better data
                seen.merge(key, exp, (existing, incoming) -> {
                    int existingBullets = existing.getBullets() != null ? existing.getBullets().size() : 0;
                    int incomingBullets = incoming.getBullets() != null ? incoming.getBullets().size() : 0;
                    return incomingBullets > existingBullets ? incoming : existing;
                });
            }
        }

        // Sort newest-first by start date
        return seen.values().stream()
            .sorted(Comparator.comparing(
                exp -> exp.getStartDate() != null ? exp.getStartDate() : LocalDate.MIN,
                Comparator.reverseOrder()))
            .collect(Collectors.toList());
    }

    private List<Skill> mergeSkills(List<Resume> resumes) {
        Map<String, Skill> seen = new LinkedHashMap<>();
        for (Resume r : resumes) {
            if (r.getSkills() == null) continue;
            for (Skill skill : r.getSkills()) {
                String key = (skill.getCanonicalName() != null ? skill.getCanonicalName() : skill.getRawName())
                    .toLowerCase().trim();
                // Keep skill with category information if available
                seen.merge(key, skill, (existing, incoming) -> {
                    if (existing.getCategory() == null && incoming.getCategory() != null) return incoming;
                    return existing;
                });
            }
        }
        return new ArrayList<>(seen.values());
    }

    private List<Education> mergeEducation(List<Resume> resumes) {
        Map<String, Education> seen = new LinkedHashMap<>();
        for (Resume r : resumes) {
            if (r.getEducation() == null) continue;
            for (Education edu : r.getEducation()) {
                String key = dedupeKey(
                    edu.getInstitution() != null ? edu.getInstitution() : "",
                    edu.getDegree() != null ? edu.getDegree() : "");
                seen.putIfAbsent(key, edu);
            }
        }
        // Sort by graduation year descending
        return seen.values().stream()
            .sorted(Comparator.comparing(
                e -> e.getGraduationYear() != null ? e.getGraduationYear() : 0,
                Comparator.reverseOrder()))
            .collect(Collectors.toList());
    }

    private List<Project> mergeProjects(List<Resume> resumes) {
        Map<String, Project> seen = new LinkedHashMap<>();
        for (Resume r : resumes) {
            if (r.getProjects() == null) continue;
            for (Project proj : r.getProjects()) {
                if (proj.getName() == null) continue;
                String key = proj.getName().toLowerCase().trim();
                // Keep project with description if available
                seen.merge(key, proj, (existing, incoming) -> {
                    if ((existing.getDescription() == null || existing.getDescription().isBlank())
                            && incoming.getDescription() != null) {
                        return incoming;
                    }
                    return existing;
                });
            }
        }
        return new ArrayList<>(seen.values());
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String dedupeKey(String a, String b) {
        String ka = a != null ? a.toLowerCase().trim().replaceAll("\\s+", " ") : "";
        String kb = b != null ? b.toLowerCase().trim().replaceAll("\\s+", " ") : "";
        return ka + "||" + kb;
    }

    @FunctionalInterface
    private interface Extractor<T> {
        T extract(Resume r);
    }

    private String firstNonNull(List<Resume> resumes, Extractor<String> fn) {
        return resumes.stream()
            .map(fn::extract)
            .filter(s -> s != null && !s.isBlank())
            .findFirst()
            .orElse(null);
    }
}
