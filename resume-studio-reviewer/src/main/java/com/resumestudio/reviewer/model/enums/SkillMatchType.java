package com.resumestudio.reviewer.model.enums;

public enum SkillMatchType {
    EXACT,           // Direct string match after normalisation
    SYNONYM,         // Resolved via ESCO synonym graph (Postgres → PostgreSQL)
    VERSION_STRIPPED, // Matched after removing version number (Java 17 → Java)
    ABBREVIATION,    // K8s → Kubernetes, JS → JavaScript
    IMPLICIT,        // Inferred from related skill (has Spring Boot → likely has Java)
    PARENT_FRAMEWORK, // JD: Spring Boot, CV: Spring (parent framework)
    MISSING          // Not found by any method
}
