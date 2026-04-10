package com.resumestudio.reviewer.model.enums;

public enum TitleProgression {
    GROWING,    // IC level increases across roles
    FLAT,       // Same level throughout
    REGRESSION, // IC level decreases (may indicate lateral move or restart)
    UNKNOWN     // Cannot determine from titles
}
