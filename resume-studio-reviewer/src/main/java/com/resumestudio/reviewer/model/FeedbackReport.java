package com.resumestudio.reviewer.model;

import com.resumestudio.reviewer.model.enums.Confidence;
import com.resumestudio.reviewer.model.enums.Verdict;

import java.util.List;

/**
 * The complete output of the 10-second pass pipeline.
 * This is serialised to JSON and returned to the client.
 */
public class FeedbackReport {

    private Verdict verdict;
    private Confidence confidence;

    // Role context — for UI display
    private String roleTitle;
    private List<String> roleStack;

    // The narrative paragraph that stitches signals together
    private String summaryParagraph;

    // Chronological simulation of what the recruiter experienced
    private List<TimelineEvent> recruiterTimeline;

    // Structured signal breakdown (6 signals, maps to 2x3 grid in UI)
    private List<Signal> signals;

    // Ordered action items
    private List<Fix> fixes;

    // Raw resume info echoed back for reference
    private String candidateName;
    private String resumeFilename;

    public FeedbackReport() {}

    // ── Getters & Setters ────────────────────────────────────

    public Verdict getVerdict() { return verdict; }
    public void setVerdict(Verdict verdict) { this.verdict = verdict; }

    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence confidence) { this.confidence = confidence; }

    public String getRoleTitle() { return roleTitle; }
    public void setRoleTitle(String roleTitle) { this.roleTitle = roleTitle; }

    public List<String> getRoleStack() { return roleStack; }
    public void setRoleStack(List<String> roleStack) { this.roleStack = roleStack; }

    public String getSummaryParagraph() { return summaryParagraph; }
    public void setSummaryParagraph(String summaryParagraph) { this.summaryParagraph = summaryParagraph; }

    public List<TimelineEvent> getRecruiterTimeline() { return recruiterTimeline; }
    public void setRecruiterTimeline(List<TimelineEvent> recruiterTimeline) { this.recruiterTimeline = recruiterTimeline; }

    public List<Signal> getSignals() { return signals; }
    public void setSignals(List<Signal> signals) { this.signals = signals; }

    public List<Fix> getFixes() { return fixes; }
    public void setFixes(List<Fix> fixes) { this.fixes = fixes; }

    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }

    public String getResumeFilename() { return resumeFilename; }
    public void setResumeFilename(String resumeFilename) { this.resumeFilename = resumeFilename; }
}
