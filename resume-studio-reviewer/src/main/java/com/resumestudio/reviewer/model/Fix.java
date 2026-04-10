package com.resumestudio.reviewer.model;


import com.resumestudio.reviewer.model.enums.ImpactLevel;

/**
 * A single actionable fix — ordered by impact in the feedback report.
 * Every fix traces back to a specific signal.
 */
public class Fix {

    private int rank;
    private String signalId;         // references Signal.id
    private String action;           // what to do e.g. "Move Java to your skills section"
    private String reason;           // why it matters in recruiter terms
    private String example;          // concrete before/after or template
    private ImpactLevel impact;

    public Fix() {}

    public Fix(int rank, String signalId, String action, String reason,
               String example, ImpactLevel impact) {
        this.rank = rank;
        this.signalId = signalId;
        this.action = action;
        this.reason = reason;
        this.example = example;
        this.impact = impact;
    }

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public String getSignalId() { return signalId; }
    public void setSignalId(String signalId) { this.signalId = signalId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getExample() { return example; }
    public void setExample(String example) { this.example = example; }

    public ImpactLevel getImpact() { return impact; }
    public void setImpact(ImpactLevel impact) { this.impact = impact; }
}
