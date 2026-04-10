package com.resumestudio.reviewer.model.enums;

public enum YoeState {
    EXPLICIT,              // "5 years of backend experience" stated in summary
    VAGUE,                 // "Several years", "extensive experience"
    CALCULABLE,            // Not stated but clean date ranges present
    PARTIAL,               // Some dates present but incomplete/ambiguous
    INCONSISTENT_FORMAT,   // Mixed date formats (Jan 2020 vs 04/2022 vs 2023)
    MISSING,               // No dates found at all
    HAS_OVERLAP,           // Two concurrent full-time roles
    HAS_GAP_UNEXPLAINED,   // Gap > 6 months, no label
    HAS_GAP_EXPLAINED,     // Gap present but labelled (career break, etc.)
    JOB_HOPPER,            // 3+ roles under 12 months each
    UNLABELLED_CONTRACT    // Short stints that look like hopping, no label
}
