package com.resumestudio.reviewer.model.enums;

public enum SkillsFormat {
    OPTIMAL,               // Categorised + JD skills first  e.g. "Programming: Java, Python"
    CATEGORISED_UNORDERED, // Grouped by category but JD skills not first
    FLAT_ORDERED,          // Flat comma list, JD skills near top
    FLAT_UNORDERED,        // Flat comma list, JD skills buried
    PROSE,                 // Written as paragraph — hardest to scan
    BULLET_LIST,           // One bullet per skill — scannable but space-inefficient
    NO_SECTION,            // No skills section — skills only in job bullets
    GENERIC_ONLY,          // Only soft skills listed
    MIXED_SOFT_HARD,       // Technical and soft skills mixed together
    SELF_RATED,            // Proficiency bars / star ratings
    OVER_VERSIONED         // Every skill has version numbers
}
