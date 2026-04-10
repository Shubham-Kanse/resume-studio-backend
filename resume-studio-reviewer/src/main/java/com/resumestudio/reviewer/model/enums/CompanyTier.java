package com.resumestudio.reviewer.model.enums;

public enum CompanyTier {
    FAANG,        // Google, Meta, Apple, Amazon, Netflix, Microsoft
    TIER_1,       // Stripe, Airbnb, Uber, LinkedIn, Salesforce, etc.
    SCALE_UP,     // Well-funded, known in industry (Series B+)
    STARTUP,      // Early stage, less known
    UNKNOWN,      // Not in taxonomy
    DESCRIBED     // Unknown name but candidate provided descriptor
}
