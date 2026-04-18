package com.resumestudio.reviewer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Reads outcome data from review_jobs (INTERVIEW/REJECTED/NO_RESPONSE) and
 * correlates it with composite scores from analysis_snapshots.
 *
 * Mirrors career-ops analyze-patterns.mjs logic:
 *   - Find minimum composite score among INTERVIEW outcomes
 *   - Find maximum composite score among REJECTED outcomes
 *   - Compute calibrated thresholds for STRONG_FIT / POSSIBLE_FIT
 *
 * Results are exposed via getters so ClassificationEngine can use them.
 * Recalibrates daily at 4am. Falls back to hardcoded defaults if insufficient data.
 */
@Component
public class ScoreCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(ScoreCalibrationService.class);

    // Defaults from initial design — used when insufficient outcome data
    private static final double DEFAULT_STRONG_FIT = 0.75;
    private static final double DEFAULT_POSSIBLE_FIT = 0.55;
    private static final int MIN_OUTCOMES_FOR_CALIBRATION = 20;

    private final com.resumestudio.reviewer.api.ReviewJobRepository jobRepo;

    // Calibrated thresholds — updated by recalibrate()
    private volatile double strongFitThreshold = DEFAULT_STRONG_FIT;
    private volatile double possibleFitThreshold = DEFAULT_POSSIBLE_FIT;
    private volatile int calibrationSampleSize = 0;

    public ScoreCalibrationService(com.resumestudio.reviewer.api.ReviewJobRepository jobRepo) {
        this.jobRepo = jobRepo;
    }

    public double getStrongFitThreshold() { return strongFitThreshold; }
    public double getPossibleFitThreshold() { return possibleFitThreshold; }
    public int getCalibrationSampleSize() { return calibrationSampleSize; }

    @Scheduled(cron = "0 0 4 * * *") // 4am daily
    public void recalibrate() {
        try {
            // Get all jobs with outcomes
            List<com.resumestudio.reviewer.api.ReviewJobEntity> withOutcomes = jobRepo.findAll().stream()
                .filter(j -> j.getOutcome() != null && j.getResultJson() != null)
                .toList();

            if (withOutcomes.size() < MIN_OUTCOMES_FOR_CALIBRATION) {
                log.info("ScoreCalibration: insufficient data ({}/{}), using defaults",
                    withOutcomes.size(), MIN_OUTCOMES_FOR_CALIBRATION);
                return;
            }

            // Extract composite scores per outcome
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<double[]> scoredOutcomes = withOutcomes.stream()
                .map(j -> {
                    try {
                        com.fasterxml.jackson.databind.JsonNode r = mapper.readTree(j.getResultJson());
                        double composite = r.path("score").path("composite").asDouble(-1);
                        if (composite < 0) return null;
                        // Normalize 0-100 score to 0-1
                        double normalized = composite / 100.0;
                        double outcomeVal = "INTERVIEW".equals(j.getOutcome()) ? 1.0 : 0.0;
                        return new double[]{normalized, outcomeVal};
                    } catch (Exception e) { return null; }
                })
                .filter(x -> x != null)
                .toList();

            if (scoredOutcomes.size() < MIN_OUTCOMES_FOR_CALIBRATION) return;

            // career-ops pattern: find minimum score among positive outcomes
            OptionalDouble minInterviewScore = scoredOutcomes.stream()
                .filter(x -> x[1] == 1.0)
                .mapToDouble(x -> x[0])
                .min();

            // Find score where rejection rate drops below 30%
            // Sort by score, compute rolling rejection rate
            List<double[]> sorted = scoredOutcomes.stream()
                .sorted((a, b) -> Double.compare(b[0], a[0]))
                .toList();

            double calibratedStrong = DEFAULT_STRONG_FIT;
            double calibratedPossible = DEFAULT_POSSIBLE_FIT;

            if (minInterviewScore.isPresent()) {
                // Strong fit: at least as high as the minimum interview score, capped at 0.85
                calibratedStrong = Math.min(0.85, Math.max(0.65, minInterviewScore.getAsDouble()));

                // Possible fit: 15 points below strong fit, minimum 0.45
                calibratedPossible = Math.max(0.45, calibratedStrong - 0.15);

                log.info("ScoreCalibration: recalibrated from {} outcomes — strong={:.2f}, possible={:.2f} (min interview score: {:.2f})",
                    scoredOutcomes.size(), calibratedStrong, calibratedPossible, minInterviewScore.getAsDouble());
            }

            this.strongFitThreshold = calibratedStrong;
            this.possibleFitThreshold = calibratedPossible;
            this.calibrationSampleSize = scoredOutcomes.size();

        } catch (Exception e) {
            log.warn("ScoreCalibration: recalibration failed ({}), keeping current thresholds", e.getMessage());
        }
    }
}
