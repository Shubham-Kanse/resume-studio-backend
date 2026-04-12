package com.resumestudio.reviewer.model;

import com.resumestudio.reviewer.model.enums.EffortLevel;
import com.resumestudio.reviewer.model.enums.FixScope;
import com.resumestudio.reviewer.model.enums.FixType;
import com.resumestudio.reviewer.model.enums.ImpactLevel;

/**
 * A single actionable fix — ordered by impact in the feedback report.
 * Every fix traces back to a specific signal.
 */
public class Fix {

    private int rank;
    private String signalId;
    private FixType fixType;
    private FixScope fixScope;
    private String action;
    private String reason;
    private BeforeAfter beforeAfter;
    private EffortLevel effort;
    private ImpactLevel impact;

    public static class BeforeAfter {
        private String before;
        private String after;

        public BeforeAfter() {}
        public BeforeAfter(String before, String after) {
            this.before = before;
            this.after = after;
        }

        public String getBefore() { return before; }
        public void setBefore(String before) { this.before = before; }
        public String getAfter() { return after; }
        public void setAfter(String after) { this.after = after; }
    }

    public Fix() {}

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public String getSignalId() { return signalId; }
    public void setSignalId(String signalId) { this.signalId = signalId; }

    public FixType getFixType() { return fixType; }
    public void setFixType(FixType fixType) { this.fixType = fixType; }

    public FixScope getFixScope() { return fixScope; }
    public void setFixScope(FixScope fixScope) { this.fixScope = fixScope; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public BeforeAfter getBeforeAfter() { return beforeAfter; }
    public void setBeforeAfter(BeforeAfter beforeAfter) { this.beforeAfter = beforeAfter; }

    public EffortLevel getEffort() { return effort; }
    public void setEffort(EffortLevel effort) { this.effort = effort; }

    public ImpactLevel getImpact() { return impact; }
    public void setImpact(ImpactLevel impact) { this.impact = impact; }
}
