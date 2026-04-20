package com.resumestudio.reviewer.signals;

/**
 * Centralized scoring weights for resume evaluation.
 * All magic numbers extracted here for easy tuning.
 */
public final class ScoringWeights {
    
    // Category weights (sum = 71)
    public static final int SKILL_MATCH_WEIGHT = 10;
    public static final int YOE_FIT_WEIGHT = 12;
    public static final int TITLE_MATCH_WEIGHT = 8;
    public static final int SUMMARY_WEIGHT = 6;
    public static final int BULLETS_WEIGHT = 7;
    public static final int SKILLS_FORMAT_WEIGHT = 6;
    public static final int COMPANY_WEIGHT = 4;
    public static final int FORMAT_WEIGHT = 4;
    public static final int CHRONOLOGY_WEIGHT = 5;
    public static final int TAILORING_WEIGHT = 5;
    public static final int PROJECTS_WEIGHT = 4;
    
    public static final int TOTAL_WEIGHT = 
        SKILL_MATCH_WEIGHT + YOE_FIT_WEIGHT + TITLE_MATCH_WEIGHT +
        SUMMARY_WEIGHT + BULLETS_WEIGHT + SKILLS_FORMAT_WEIGHT +
        COMPANY_WEIGHT + FORMAT_WEIGHT + CHRONOLOGY_WEIGHT +
        TAILORING_WEIGHT + PROJECTS_WEIGHT;
    
    // Classification thresholds
    public static final double STRONG_FIT_THRESHOLD = 0.75;
    public static final double POSSIBLE_FIT_THRESHOLD = 0.55;
    public static final double WEAK_FIT_THRESHOLD = 0.35;
    
    // Rate limiting
    public static final int MAX_REQUESTS_PER_MINUTE_IP = 10;
    public static final int MAX_REQUESTS_PER_MINUTE_USER = 20;
    public static final int MAX_PREVIEW_REQUESTS_PER_MINUTE = 30;
    
    // Cache TTLs (seconds)
    public static final int REVIEW_CACHE_TTL = 86400; // 24 hours
    public static final int ASYNC_JOB_TTL = 900; // 15 minutes
    public static final int DOCUMENT_CACHE_TTL = 3600; // 1 hour
    
    private ScoringWeights() {
        throw new UnsupportedOperationException("Utility class");
    }
}
