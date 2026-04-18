package com.resumestudio.reviewer.ats;

/**
 * Response from POST /api/ats/extract — raw resume text for client-side local scorer.
 */
public record AtsExtractResponse(String text, String filename) {}
