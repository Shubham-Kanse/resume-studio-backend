package com.resumestudio.reviewer.ats;

/**
 * JSON body for POST /api/ats/score-text and POST /api/ats/nlp-analysis.
 * Matches the frontend's AtsScoreRequest shape exactly.
 */
public class AtsTextRequest {
    public String resumeContent = "";
    public String jobDescription = "";
    public String resumeProfileMode = "auto";
}
