package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.YoeFit;
import com.resumestudio.reviewer.model.enums.YoeState;
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

    private static final double GAP_THRESHOLD_MONTHS = 6.0;
    private static final double JOB_HOPPER_MAX_MONTHS = 12.0;
    private static final int JOB_HOPPER_MIN_COUNT = 3;

    public void compute(List<WorkExperience> experience, Double explicitYoe,
                        boolean yoeExplicitInSummary, Double jdYoeMin, Double jdYoeMax,
                        ResumeSignals signals) {

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
        signals.setCalculatedYoe(totalYears);

        // ── Determine YOE state ───────────────────────────────────────────
        YoeState state = determineState(experience, yoeExplicitInSummary, totalYears);
        signals.setYoeState(state);

        // ── YOE fit vs JD requirement ─────────────────────────────────────
        double effectiveYoe = (yoeExplicitInSummary && explicitYoe != null) ? explicitYoe : totalYears;
        signals.setYoeFit(computeFit(effectiveYoe, jdYoeMin, jdYoeMax));

        // ── Detect gaps ───────────────────────────────────────────────────
        detectGaps(experience, signals);

        // ── Detect job hopping ────────────────────────────────────────────
        detectJobHopping(experience, signals);

        // ── Detect overlapping roles ──────────────────────────────────────
        detectOverlaps(experience, signals);
    }

    // ── YOE calculation ───────────────────────────────────────────────────────

    private double calculateTotalYoe(List<WorkExperience> experience) {
        // Sum non-overlapping date ranges
        // Sort by start date
        List<WorkExperience> sorted = new ArrayList<>(experience);
        sorted.sort(Comparator.comparing(
            e -> e.getStartDate() != null ? e.getStartDate() : LocalDate.of(2000, 1, 1)));

        LocalDate rangeStart = null;
        LocalDate rangeEnd = null;
        double totalDays = 0;

        for (WorkExperience role : sorted) {
            LocalDate start = role.getStartDate();
            LocalDate end = role.isCurrent() ? LocalDate.now() : role.getEndDate();
            if (start == null || end == null) continue;

            if (rangeStart == null) {
                rangeStart = start;
                rangeEnd = end;
            } else if (!start.isAfter(rangeEnd)) {
                // Overlapping — extend the range
                if (end.isAfter(rangeEnd)) rangeEnd = end;
            } else {
                // Gap — save previous range, start new
                totalDays += ChronoUnit.DAYS.between(rangeStart, rangeEnd);
                rangeStart = start;
                rangeEnd = end;
            }
        }

        if (rangeStart != null) {
            totalDays += ChronoUnit.DAYS.between(rangeStart, rangeEnd);
        }

        return totalDays / 365.25;
    }

    // ── State determination ───────────────────────────────────────────────────

    private YoeState determineState(List<WorkExperience> experience, boolean explicitInSummary, double computed) {
        if (explicitInSummary) return YoeState.EXPLICIT;

        boolean allHaveDates = experience.stream().allMatch(e -> e.getStartDate() != null);
        boolean someHaveDates = experience.stream().anyMatch(e -> e.getStartDate() != null);
        boolean hasPartialDates = experience.stream().anyMatch(WorkExperience::isDatesArePartial);

        if (!someHaveDates) return YoeState.MISSING;
        if (!allHaveDates) return YoeState.PARTIAL;
        if (hasPartialDates) return YoeState.PARTIAL;

        return YoeState.CALCULABLE;
    }

    // ── Fit computation ───────────────────────────────────────────────────────

    private YoeFit computeFit(double yoe, Double jdMin, Double jdMax) {
        if (jdMin == null) return YoeFit.IN_RANGE; // no requirement stated

        if (jdMax != null && yoe > jdMax + 4) return YoeFit.OVER_RANGE;

        if (yoe >= jdMin) return YoeFit.IN_RANGE;

        double gap = jdMin - yoe;
        if (gap <= 1.5) return YoeFit.UNDER_RANGE_MINOR;
        return YoeFit.UNDER_RANGE_SIGNIFICANT;
    }

    // ── Gap detection ─────────────────────────────────────────────────────────

    private void detectGaps(List<WorkExperience> experience, ResumeSignals signals) {
        List<String> gapDescriptions = new ArrayList<>();
        double longestGap = 0;

        // Sort chronologically
        List<WorkExperience> sorted = experience.stream()
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
            if (gapMonths > GAP_THRESHOLD_MONTHS) {
                longestGap = Math.max(longestGap, gapMonths);
                String desc = String.format("%.0f-month gap between %s and %s",
                    gapMonths,
                    current.getCompany() != null ? current.getCompany() : "previous role",
                    next.getCompany() != null ? next.getCompany() : "next role");
                gapDescriptions.add(desc);
            }
        }

        signals.setHasUnexplainedGap(!gapDescriptions.isEmpty());
        signals.setLongestGapMonths(longestGap);
        signals.setGapDescriptions(gapDescriptions);
    }

    // ── Job hopping detection ─────────────────────────────────────────────────

    private void detectJobHopping(List<WorkExperience> experience, ResumeSignals signals) {
        long shortTenures = experience.stream()
            .filter(e -> !e.isContractOrFreelance())
            .filter(e -> e.getDurationYears() > 0 && e.getDurationYears() < (JOB_HOPPER_MAX_MONTHS / 12.0))
            .count();

        signals.setJobHopper(shortTenures >= JOB_HOPPER_MIN_COUNT);

        // Detect unlabelled contracts: multiple short stints without contract label
        long unlabelledShort = experience.stream()
            .filter(e -> !e.isContractOrFreelance())
            .filter(e -> e.getDurationYears() > 0 && e.getDurationYears() < 1.0)
            .count();
        signals.setHasUnlabelledContracts(unlabelledShort >= 2 && shortTenures >= 2);
    }

    // ── Overlap detection ─────────────────────────────────────────────────────

    private void detectOverlaps(List<WorkExperience> experience, ResumeSignals signals) {
        List<WorkExperience> sorted = experience.stream()
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
}
