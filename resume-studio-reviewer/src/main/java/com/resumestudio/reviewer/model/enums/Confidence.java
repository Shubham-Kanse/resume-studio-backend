package com.resumestudio.reviewer.model.enums;

public enum Confidence {
    HIGH,   // Clean PDF, all sections found, dates parsed cleanly
    MEDIUM, // Some sections ambiguous, minor parse issues
    LOW     // OCR'd document, missing sections, significant parse failures
}
