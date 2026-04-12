package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.YoeFit;
import com.resumestudio.reviewer.model.enums.YoeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Computes all YOE-related signals.
 *
 * Handles all edge cases:
 *   EXPLICIT, VAGUE, CALCULABLE, PARTIAL, INCONSISTENT, MISSING,
 *   OVERLAP, GAP_UNEXPLAINED, GAP_EXPLAINED, JOB_HOPPER, UNLABELLED_CONTRACT
 */
@Component
public class YoeSignalCalculator {

    private static final Logger log = LoggerFactory.getLogger(YoeSignalCalculator.class);

    private static final double GAP_THRESHOLD_MONTHS = 9.0;  // 9 months allows for notice period + break
    private static final double JOB_HOPPER_MAX_MONTHS = 12.0;
    private static final int JOB_HOPPER_MIN_COUNT = 3;

    public void compute(List<WorkExperience> experience, Double explicitYoe,
                        boolean yoeExplicitInSummary, Double jdYoeMin, Double jdYoeMax,
                        ResumeSignals signals) {
        compute(experience, explicitYoe, yoeExplicitInSummary, jdYoeMin, jdYoeMax, signals, List.of());
    }

    public void compute(List<WorkExperience> experience, Double explicitYoe,
                        boolean yoeExplicitInSummary, Double jdYoeMin, Double jdYoeMax,
                        ResumeSignals signals, List<com.resumestudio.reviewer.model.Education> education) {

        signals.setJdYoeMin(jdYoeMin);
        signals.setJdYoeMax(jdYoeMax);

        if (experience == null || experience.isEmpty()) {
            if (explicitYoe != null && yoeExplicitInSummary) {
                signals.setCalculatedYoe(explicitYoe);
                signals.setYoeState(YoeState.EXPLICIT);
                signals.setYoeFit(computeFit(explicitYoe, jdYoeMin, jdYoeMax));
            } else {
                signals.setYoeState(YoeState.MISSING);
                signals.setYoeFit(YoeFit.CANNOT_DETERMINE);
            }
            return;
        }

        // ── Calculate total YOE from date ranges ──────────────────────────
        double totalYears = calculateTotalYoe(experience);

        // If dates are missing/zero but explicit YOE stated, use explicit
        double effectiveYoe = (yoeExplicitInSummary && explicitYoe != null) ? explicitYoe : totalYears;
        signals.setCalculatedYoe(effectiveYoe);

        // ── Determine YOE state ───────────────────────────────────────────
        YoeState state = determineState(experience, yoeExplicitInSummary, totalYears);
        signals.setYoeState(state);

        // ── YOE fit vs JD requirement ─────────────────────────────────────
        signals.setYoeFit(computeFit(effectiveYoe, jdYoeMin, jdYoeMax));

        // ── Chronology quality ────────────────────────────────────────────
        ChronologyAssessment chronology = assessChronology(experience, education);
        signals.setHasChronologyIssues(!chronology.descriptions().isEmpty());
        signals.setChronologyUnreliable(chronology.unreliable());
        signals.setChronologyDescriptions(chronology.descriptions());

        // ── Detect gaps ───────────────────────────────────────────────────
        detectGaps(experience, signals, education, chronology);

        // ── Detect job hopping ────────────────────────────────────────────
        detectJobHopping(experience, signals);

        // ── Detect overlapping roles ──────────────────────────────────────
        detectOverlaps(experience, signals);
    }

    // ── YOE calculation ───────────────────────────────────────────────────────

    private double calculateTotalYoe(List<WorkExperience> experience) {
        // Sum non-overlapping date ranges, with part-time counted at 0.5x
        List<WorkExperience> sorted = new ArrayList<>(experience);
        sorted.removeIf(e -> e.isCareerBreak() || e.isSabbatical() || e.isParentalLeave());
        sorted.sort(Comparator.comparing(
            e -> e.getStartDate() != null ? e.getStartDate() : LocalDate.of(2000, 1, 1)));

        LocalDate rangeStart = null;
        LocalDate rangeEnd = null;
        double totalDays = 0;

        for (WorkExperience role : sorted) {
            LocalDate start = role.getStartDate();
            LocalDate end = role.isCurrent() ? LocalDate.now() : role.getEndDate();
            if (start == null || end == null) continue;

            double multiplier = role.isPartTime() ? 0.5 : 1.0;

            if (rangeStart == null) {
                rangeStart = start;
                rangeEnd = end;
                totalDays += ChronoUnit.DAYS.between(start, end) * multiplier;
            } else if (!start.isAfter(rangeEnd)) {
                // Overlapping — extend the range (don't double-count)
                if (end.isAfter(rangeEnd)) {
                    totalDays += ChronoUnit.DAYS.between(rangeEnd, end) * multiplier;
                    rangeEnd = end;
                }
            } else {
                // Gap — start new range
                rangeStart = start;
                rangeEnd = end;
                totalDays += ChronoUnit.DAYS.between(start, end) * multiplier;
            }
        }

        return totalDays / 365.25;
    }

    // ── State determination ───────────────────────────────────────────────────

    private YoeState determineState(List<WorkExperience> experience, boolean explicitInSummary, double computed) {
        if (explicitInSummary) return YoeState.EXPLICIT;

        List<WorkExperience> professionalExperience = experience.stream()
            .filter(e -> !e.isCareerBreak())
            .toList();

        boolean allHaveDates = professionalExperience.stream().allMatch(e -> e.getStartDate() != null);
        boolean someHaveDates = professionalExperience.stream().anyMatch(e -> e.getStartDate() != null);
        boolean hasPartialDates = professionalExperience.stream().anyMatch(WorkExperience::isDatesArePartial);

        if (!someHaveDates) return YoeState.MISSING;
        if (!allHaveDates) return YoeState.PARTIAL;
        if (hasPartialDates) return YoeState.PARTIAL;

        return YoeState.CALCULABLE;
    }

    // ── Fit computation ───────────────────────────────────────────────────────

    private YoeFit computeFit(double yoe, Double jdMin, Double jdMax) {
        if (jdMin == null && jdMax == null) return YoeFit.IN_RANGE; // no requirement stated

        // Overqualified: more than 2 years over the max
        if (jdMax != null && yoe > jdMax + 2) return YoeFit.OVER_RANGE;

        // In range
        double min = jdMin != null ? jdMin : 0.0;
        if (yoe >= min) return YoeFit.IN_RANGE;

        // Under range
        double gap = min - yoe;
        if (gap <= 1.5) return YoeFit.UNDER_RANGE_MINOR;
        return YoeFit.UNDER_RANGE_SIGNIFICANT;
    }

    // ── Gap detection ─────────────────────────────────────────────────────────

    private ChronologyAssessment assessChronology(List<WorkExperience> experience,
                                                   List<com.resumestudio.reviewer.model.Education> education) {
        if (experience == null || experience.isEmpty()) {
            return new ChronologyAssessment(List.of(), false);
        }

        List<String> descriptions = new ArrayList<>();
        int majorIssueCount = 0;

        long professionalRoles = experience.stream().filter(e -> !e.isCareerBreak()).count();
        long missingStartDates = experience.stream()
            .filter(e -> !e.isCareerBreak() && e.getStartDate() == null)
            .count();
        long partialDates = experience.stream()
            .filter(e -> !e.isCareerBreak() && e.isDatesArePartial())
            .count();
        long currentRoles = experience.stream()
            .filter(e -> !e.isCareerBreak() && e.isCurrent() && !e.isContractOrFreelance())
            .count();

        boolean hardChronologyBreak = false;

        if (currentRoles > 1) {
            descriptions.add("Multiple roles are marked as current/present, so the recent chronology is unclear.");
            majorIssueCount++;
            hardChronologyBreak = true;
        }

        for (WorkExperience role : experience) {
            if (role.getStartDate() != null && role.getEndDate() != null && role.getEndDate().isBefore(role.getStartDate())) {
                descriptions.add("At least one role has an end date earlier than its start date.");
                majorIssueCount++;
                hardChronologyBreak = true;
                break;
            }
        }

        if (professionalRoles >= 2 && missingStartDates >= Math.ceil(professionalRoles / 2.0)) {
            descriptions.add("More than half of the work history is missing start dates, so the overall chronology cannot be trusted.");
            majorIssueCount++;
        } else if (missingStartDates > 0) {
            descriptions.add("Some roles are missing start dates, which weakens chronology confidence.");
        }

        if (professionalRoles >= 2 && partialDates >= Math.ceil(professionalRoles / 2.0)) {
            descriptions.add("Several roles use year-only dates, so month-level chronology is uncertain.");
            majorIssueCount++;
        } else if (partialDates > 0) {
            descriptions.add("Year-only dates reduce chronology precision.");
        }

        if (education != null) {
            for (var edu : education) {
                if (edu.getStartYear() != null && edu.getGraduationYear() != null
                    && edu.getStartYear() > edu.getGraduationYear()) {
                    descriptions.add("At least one education entry has years in the wrong order.");
                    majorIssueCount++;
                    break;
                }
            }
        }

        return new ChronologyAssessment(deduplicateDescriptions(descriptions), hardChronologyBreak || majorIssueCount >= 2);
    }

    private void detectGaps(List<WorkExperience> experience, ResumeSignals signals,
                             List<com.resumestudio.reviewer.model.Education> education,
                             ChronologyAssessment chronology) {
        List<String> gapDescriptions = new ArrayList<>();
        double longestGap = 0;
        List<String> chronologyDescriptions = new ArrayList<>(chronology.descriptions());

        List<WorkExperience> sorted = experience.stream()
            .filter(e -> !e.isCareerBreak())
            .filter(e -> e.getStartDate() != null)
            .sorted(Comparator.comparing(WorkExperience::getStartDate))
            .toList();

        for (int i = 0; i < sorted.size() - 1; i++) {
            WorkExperience current = sorted.get(i);
            WorkExperience next = sorted.get(i + 1);

            LocalDate currentEnd = current.isCurrent() ? LocalDate.now() : current.getEndDate();
            LocalDate nextStart = next.getStartDate();

            if (currentEnd == null || nextStart == null) continue;

            double gapMonths = ChronoUnit.DAYS.between(currentEnd, nextStart) / 30.44;
            if (gapMonths <= GAP_THRESHOLD_MONTHS) continue;

            // Check if gap is covered by an education period or an explicit career break
            if (isCoveredByEducation(currentEnd, nextStart, education)
                || isCoveredByCareerBreak(currentEnd, nextStart, experience)) {
                continue;
            }

            if (current.isDatesArePartial() || next.isDatesArePartial()) {
                chronologyDescriptions.add(String.format(
                    "Possible gap between %s and %s, but one or both roles use year-only dates.",
                    displayName(current),
                    displayName(next)));
                continue;
            }

            longestGap = Math.max(longestGap, gapMonths);
            gapDescriptions.add(String.format("%.0f-month gap between %s and %s",
                gapMonths,
                displayName(current),
                displayName(next)));
        }

        signals.setHasUnexplainedGap(!gapDescriptions.isEmpty());
        signals.setLongestGapMonths(longestGap);
        signals.setGapDescriptions(gapDescriptions);
        signals.setChronologyDescriptions(deduplicateDescriptions(chronologyDescriptions));
        signals.setHasChronologyIssues(!signals.getChronologyDescriptions().isEmpty());
        signals.setChronologyUnreliable(signals.isChronologyUnreliable() || signals.getChronologyDescriptions().size() >= 2);
    }

    /**
     * Returns true if the gap period overlaps with any education entry.
     * A gap during full-time study is expected and should not be flagged.
     * Also allows 9 months post-graduation for job search (especially for fresh grads).
     */
    private boolean isCoveredByEducation(LocalDate gapStart, LocalDate gapEnd,
                                          List<com.resumestudio.reviewer.model.Education> education) {
        if (education == null || education.isEmpty()) return false;
        for (var edu : education) {
            if (edu.getGraduationYear() == null) continue;
            LocalDate eduEnd = LocalDate.of(edu.getGraduationYear(), 12, 31);
            LocalDate eduStart = edu.getStartYear() != null
                ? LocalDate.of(edu.getStartYear(), 1, 1)
                : eduEnd.minusYears(4); // assume 4-year degree if no start year
            
            // Extend education coverage to 9 months post-graduation for job search
            LocalDate extendedEduEnd = eduEnd.plusMonths(9);
            
            // Gap is covered if education (+ post-grad buffer) overlaps with it
            if (!extendedEduEnd.isBefore(gapStart) && !eduStart.isAfter(gapEnd)) return true;
        }
        return false;
    }

    private boolean isCoveredByCareerBreak(LocalDate gapStart, LocalDate gapEnd, List<WorkExperience> experience) {
        if (experience == null || experience.isEmpty()) return false;
        for (WorkExperience role : experience) {
            // Career breaks, sabbaticals, and parental leave all explain gaps
            boolean isExplainedBreak = role.isCareerBreak() || role.isSabbatical() || role.isParentalLeave();
            if (!isExplainedBreak || role.getStartDate() == null) continue;
            LocalDate breakEnd = role.isCurrent() ? LocalDate.now() : role.getEndDate();
            if (breakEnd == null) continue;
            if (!breakEnd.isBefore(gapStart) && !role.getStartDate().isAfter(gapEnd)) return true;
        }
        return false;
    }

    // ── Job hopping detection ─────────────────────────────────────────────────

    private void detectJobHopping(List<WorkExperience> experience, ResumeSignals signals) {
        long shortTenures = experience.stream()
            .filter(e -> !e.isCareerBreak())
            .filter(e -> !e.isContractOrFreelance())
            .filter(e -> {
                double dur = effectiveDuration(e);
                return dur > 0 && dur < (JOB_HOPPER_MAX_MONTHS / 12.0);
            })
            .count();

        signals.setJobHopper(shortTenures >= JOB_HOPPER_MIN_COUNT);

        // Detect unlabelled contracts: multiple short stints without contract label
        long unlabelledShort = experience.stream()
            .filter(e -> !e.isCareerBreak())
            .filter(e -> !e.isContractOrFreelance())
            .filter(e -> {
                double dur = effectiveDuration(e);
                return dur > 0 && dur < 1.0;
            })
            .count();
        signals.setHasUnlabelledContracts(unlabelledShort >= 2 && shortTenures >= 2);
    }

    /** Returns durationYears if pre-computed, otherwise calculates from start/end dates. */
    private double effectiveDuration(WorkExperience e) {
        if (e.getDurationYears() > 0) return e.getDurationYears();
        LocalDate start = e.getStartDate();
        LocalDate end = e.isCurrent() ? LocalDate.now() : e.getEndDate();
        if (start == null || end == null) return 0;
        return ChronoUnit.DAYS.between(start, end) / 365.25;
    }

    private String displayName(WorkExperience role) {
        if (role.getCompany() != null && !role.getCompany().isBlank()) {
            return role.getCompany();
        }
        if (role.getTitle() != null && !role.getTitle().isBlank()) {
            return role.getTitle();
        }
        return "role";
    }

    private List<String> deduplicateDescriptions(List<String> descriptions) {
        return new ArrayList<>(new LinkedHashSet<>(descriptions));
    }

    // ── Overlap detection ─────────────────────────────────────────────────────

    private void detectOverlaps(List<WorkExperience> experience, ResumeSignals signals) {
        List<WorkExperience> sorted = experience.stream()
            .filter(e -> !e.isCareerBreak())
            .filter(e -> e.getStartDate() != null && (e.getEndDate() != null || e.isCurrent()))
            .sorted(Comparator.comparing(WorkExperience::getStartDate))
            .toList();

        for (int i = 0; i < sorted.size() - 1; i++) {
            WorkExperience a = sorted.get(i);
            WorkExperience b = sorted.get(i + 1);
            LocalDate aEnd = a.isCurrent() ? LocalDate.now() : a.getEndDate();

            if (aEnd != null && b.getStartDate().isBefore(aEnd)) {
                // Both were full-time (neither labelled as contract) = suspicious overlap
                if (!a.isContractOrFreelance() && !b.isContractOrFreelance()) {
                    signals.setHasConcurrentRoles(true);
                    return;
                }
            }
        }
    }

    private record ChronologyAssessment(List<String> descriptions, boolean unreliable) {}
}
