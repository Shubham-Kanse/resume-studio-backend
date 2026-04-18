package com.resumestudio.reviewer.model;

import com.resumestudio.reviewer.model.enums.*;

/**
 * A single evaluated signal — what the recruiter would notice,
 * what it means, and how much friction it caused.
 */
public class Signal {

    private String id;
    private String label;
    private SignalStatus status;
    private Confidence confidence;
    private SignalFriction friction;
    private SignalMagnitude magnitude;       // nullable
    private String observation;
    private String interpretation;
    private String evidence;                 // nullable — verbatim resume excerpt that drove this signal
    private String equivalent;              // nullable — e.g. "similar to X"
    private AbsenceReason absenceReason;    // nullable
    private LanguageMismatch languageMismatch;
    private String modifiedBy;              // nullable — rule that overrode this signal
    private ImpactLevel impact;

    public static class LanguageMismatch {
        private String resumeTerm;
        private String jdTerm;

        public LanguageMismatch() {}
        public LanguageMismatch(String resumeTerm, String jdTerm) {
            this.resumeTerm = resumeTerm;
            this.jdTerm = jdTerm;
        }

        public String getResumeTerm() { return resumeTerm; }
        public void setResumeTerm(String resumeTerm) { this.resumeTerm = resumeTerm; }
        public String getJdTerm() { return jdTerm; }
        public void setJdTerm(String jdTerm) { this.jdTerm = jdTerm; }
    }

    public Signal() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public SignalStatus getStatus() { return status; }
    public void setStatus(SignalStatus status) { this.status = status; }

    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence confidence) { this.confidence = confidence; }

    public SignalFriction getFriction() { return friction; }
    public void setFriction(SignalFriction friction) { this.friction = friction; }

    public SignalMagnitude getMagnitude() { return magnitude; }
    public void setMagnitude(SignalMagnitude magnitude) { this.magnitude = magnitude; }

    public String getObservation() { return observation; }
    public void setObservation(String observation) { this.observation = observation; }

    public String getInterpretation() { return interpretation; }
    public void setInterpretation(String interpretation) { this.interpretation = interpretation; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public String getEquivalent() { return equivalent; }
    public void setEquivalent(String equivalent) { this.equivalent = equivalent; }

    public AbsenceReason getAbsenceReason() { return absenceReason; }
    public void setAbsenceReason(AbsenceReason absenceReason) { this.absenceReason = absenceReason; }

    public LanguageMismatch getLanguageMismatch() { return languageMismatch; }
    public void setLanguageMismatch(LanguageMismatch languageMismatch) { this.languageMismatch = languageMismatch; }

    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }

    public ImpactLevel getImpact() { return impact; }
    public void setImpact(ImpactLevel impact) { this.impact = impact; }
}
