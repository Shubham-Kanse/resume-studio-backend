package com.resumestudio.reviewer.model.enums;

public enum YoeFit {
    IN_RANGE,
    UNDER_RANGE_MINOR,       // Within 1 year under minimum
    UNDER_RANGE_SIGNIFICANT, // More than 2 years under minimum
    OVER_RANGE,              // Overqualified signal
    CANNOT_DETERMINE         // Dates missing or unparseable
}
