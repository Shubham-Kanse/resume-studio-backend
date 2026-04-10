package com.resumestudio.reviewer.model.enums;

public enum SkillVisibility {
    SURFACE,   // In skills section or summary — immediately visible
    MID,       // In a recent role bullet (last 2 roles)
    BURIED,    // Only in older role bullets
    MISSING    // Not found anywhere on resume
}
