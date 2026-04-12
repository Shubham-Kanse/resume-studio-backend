package com.resumestudio.reviewer.model;

import com.resumestudio.reviewer.model.enums.*;

import java.util.List;

/**
 * The complete output of the recruiter simulation pipeline.
 * Serialised to JSON and returned to the client.
 */
public class FeedbackReport {

    // --- Top-level verdict ---
    private Verdict verdict;
    private Confidence confidence;
    private InterviewLikelihood interviewLikelihood;
    private int scanDuration;               // seconds the recruiter spent before deciding

    // --- Narrative ---
    private String summaryLine;             // one-liner shown at the top of the UI
    private String narrative;              // AI-generated paragraph, scoped to signals
    private NarrativeTone narrativeTone;
    private String momentOfDecision;       // e.g. "Skills section — 8 seconds in"

    // --- Calibration ---
    private SeniorityCalibration seniorityCalibration;
    private int tailoringScore;            // 0–10
    private JdClarity jdClarity;
    private RecruiterType recruiterType;
    private CompetitiveContext competitiveContext;

    // --- Role context ---
    private RoleContext roleContext;

    // --- Core sections ---
    private List<Signal> signals;
    private List<Differentiator> differentiators;
    private List<RedFlag> redFlags;
    private RecruiterGutFeel recruiterGutFeel;
    private List<Fix> fixes;

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    public static class RoleContext {
        private String title;              // job title from JD
        private List<String> required;
        private List<String> preferred;
        private List<String> inferred;
        private String domain;
        private List<String> implicitExpectations;

        public RoleContext() {}

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<String> getRequired() { return required; }
        public void setRequired(List<String> required) { this.required = required; }
        public List<String> getPreferred() { return preferred; }
        public void setPreferred(List<String> preferred) { this.preferred = preferred; }
        public List<String> getInferred() { return inferred; }
        public void setInferred(List<String> inferred) { this.inferred = inferred; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public List<String> getImplicitExpectations() { return implicitExpectations; }
        public void setImplicitExpectations(List<String> implicitExpectations) { this.implicitExpectations = implicitExpectations; }
    }

    public static class Differentiator {
        private String observation;
        private ImpactLevel impact;

        public Differentiator() {}
        public Differentiator(String observation, ImpactLevel impact) {
            this.observation = observation;
            this.impact = impact;
        }

        public String getObservation() { return observation; }
        public void setObservation(String observation) { this.observation = observation; }
        public ImpactLevel getImpact() { return impact; }
        public void setImpact(ImpactLevel impact) { this.impact = impact; }
    }

    public static class RedFlag {
        private String flag;
        private ImpactLevel severity;
        private String detail;

        public RedFlag() {}
        public RedFlag(String flag, ImpactLevel severity, String detail) {
            this.flag = flag;
            this.severity = severity;
            this.detail = detail;
        }

        public String getFlag() { return flag; }
        public void setFlag(String flag) { this.flag = flag; }
        public ImpactLevel getSeverity() { return severity; }
        public void setSeverity(ImpactLevel severity) { this.severity = severity; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
    }

    public static class RecruiterGutFeel {
        private String firstImpression;
        private TrustLevel trustLevel;
        private String observation;

        public RecruiterGutFeel() {}

        public String getFirstImpression() { return firstImpression; }
        public void setFirstImpression(String firstImpression) { this.firstImpression = firstImpression; }
        public TrustLevel getTrustLevel() { return trustLevel; }
        public void setTrustLevel(TrustLevel trustLevel) { this.trustLevel = trustLevel; }
        public String getObservation() { return observation; }
        public void setObservation(String observation) { this.observation = observation; }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final FeedbackReport r = new FeedbackReport();

        public Builder verdict(Verdict v) { r.verdict = v; return this; }
        public Builder confidence(Confidence c) { r.confidence = c; return this; }
        public Builder interviewLikelihood(InterviewLikelihood il) { r.interviewLikelihood = il; return this; }
        public Builder scanDuration(int s) { r.scanDuration = s; return this; }
        public Builder summaryLine(String s) { r.summaryLine = s; return this; }
        public Builder narrative(String n) { r.narrative = n; return this; }
        public Builder narrativeTone(NarrativeTone t) { r.narrativeTone = t; return this; }
        public Builder momentOfDecision(String m) { r.momentOfDecision = m; return this; }
        public Builder seniorityCalibration(SeniorityCalibration sc) { r.seniorityCalibration = sc; return this; }
        public Builder tailoringScore(int ts) { r.tailoringScore = ts; return this; }
        public Builder jdClarity(JdClarity jc) { r.jdClarity = jc; return this; }
        public Builder recruiterType(RecruiterType rt) { r.recruiterType = rt; return this; }
        public Builder competitiveContext(CompetitiveContext cc) { r.competitiveContext = cc; return this; }
        public Builder roleContext(RoleContext rc) { r.roleContext = rc; return this; }
        public Builder signals(List<Signal> s) { r.signals = s; return this; }
        public Builder differentiators(List<Differentiator> d) { r.differentiators = d; return this; }
        public Builder redFlags(List<RedFlag> rf) { r.redFlags = rf; return this; }
        public Builder recruiterGutFeel(RecruiterGutFeel gf) { r.recruiterGutFeel = gf; return this; }
        public Builder fixes(List<Fix> f) { r.fixes = f; return this; }

        public FeedbackReport build() { return r; }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public Verdict getVerdict() { return verdict; }
    public Confidence getConfidence() { return confidence; }
    public InterviewLikelihood getInterviewLikelihood() { return interviewLikelihood; }
    public int getScanDuration() { return scanDuration; }
    public String getSummaryLine() { return summaryLine; }
    public String getNarrative() { return narrative; }
    public NarrativeTone getNarrativeTone() { return narrativeTone; }
    public String getMomentOfDecision() { return momentOfDecision; }
    public SeniorityCalibration getSeniorityCalibration() { return seniorityCalibration; }
    public int getTailoringScore() { return tailoringScore; }
    public JdClarity getJdClarity() { return jdClarity; }
    public RecruiterType getRecruiterType() { return recruiterType; }
    public CompetitiveContext getCompetitiveContext() { return competitiveContext; }
    public RoleContext getRoleContext() { return roleContext; }
    public List<Signal> getSignals() { return signals; }
    public List<Differentiator> getDifferentiators() { return differentiators; }
    public List<RedFlag> getRedFlags() { return redFlags; }
    public RecruiterGutFeel getRecruiterGutFeel() { return recruiterGutFeel; }
    public List<Fix> getFixes() { return fixes; }
}
