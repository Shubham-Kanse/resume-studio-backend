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
    private RoleContext roleContext;
    private String summaryParagraph;
    private List<TimelineEvent> timeline;
    private List<Signal> signals;
    private List<Fix> fixes;

    public static class RoleContext {
        private String title;
        private List<String> stack;

        public RoleContext(String title, List<String> stack) {
            this.title = title;
            this.stack = stack;
        }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<String> getStack() { return stack; }
        public void setStack(List<String> stack) { this.stack = stack; }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Verdict verdict;
        private Confidence confidence;
        private RoleContext roleContext;
        private String summaryParagraph;
        private List<TimelineEvent> timeline;
        private List<Signal> signals;
        private List<Fix> fixes;

        public Builder verdict(Verdict verdict) {
            this.verdict = verdict;
            return this;
        }

        public Builder confidence(Confidence confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder roleContext(RoleContext roleContext) {
            this.roleContext = roleContext;
            return this;
        }

        public Builder summaryParagraph(String summaryParagraph) {
            this.summaryParagraph = summaryParagraph;
            return this;
        }

        public Builder timeline(List<TimelineEvent> timeline) {
            this.timeline = timeline;
            return this;
        }

        public Builder signals(List<Signal> signals) {
            this.signals = signals;
            return this;
        }

        public Builder fixes(List<Fix> fixes) {
            this.fixes = fixes;
            return this;
        }

        public FeedbackReport build() {
            FeedbackReport report = new FeedbackReport();
            report.verdict = this.verdict;
            report.confidence = this.confidence;
            report.roleContext = this.roleContext;
            report.summaryParagraph = this.summaryParagraph;
            report.timeline = this.timeline;
            report.signals = this.signals;
            report.fixes = this.fixes;
            return report;
        }
    }

    // Getters
    public Verdict getVerdict() { return verdict; }
    public Confidence getConfidence() { return confidence; }
    public RoleContext getRoleContext() { return roleContext; }
    public String getSummaryParagraph() { return summaryParagraph; }
    public List<TimelineEvent> getTimeline() { return timeline; }
    public List<Signal> getSignals() { return signals; }
    public List<Fix> getFixes() { return fixes; }
}
