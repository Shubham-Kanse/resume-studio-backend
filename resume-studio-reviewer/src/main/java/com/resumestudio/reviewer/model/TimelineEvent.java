package com.resumestudio.reviewer.model;

import com.resumestudio.reviewer.model.enums.TimelineEventType;
import com.resumestudio.reviewer.model.enums.TimelineOutcome;

/**
 * A single event in the recruiter's 10-second simulation timeline.
 * Reconstructs what the recruiter saw, at what point, and what they concluded.
 */
public class TimelineEvent {

    private String timeLabel;          // e.g. "0s", "2s", "7s"
    private TimelineEventType type;
    private String heading;            // e.g. "Filename raised a small flag"
    private String detail;             // the specific observation
    private TimelineOutcome outcome;   // KEPT_READING, HARD_STOP, etc.
    private String sentiment;          // "POSITIVE", "NEGATIVE", "NEUTRAL" — drives UI colour

    public TimelineEvent() {}

    public TimelineEvent(String timeLabel, TimelineEventType type, String heading,
                         String detail, TimelineOutcome outcome, String sentiment) {
        this.timeLabel = timeLabel;
        this.type = type;
        this.heading = heading;
        this.detail = detail;
        this.outcome = outcome;
        this.sentiment = sentiment;
    }

    public String getTimeLabel() { return timeLabel; }
    public void setTimeLabel(String timeLabel) { this.timeLabel = timeLabel; }

    public TimelineEventType getType() { return type; }
    public void setType(TimelineEventType type) { this.type = type; }

    public String getHeading() { return heading; }
    public void setHeading(String heading) { this.heading = heading; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public TimelineOutcome getOutcome() { return outcome; }
    public void setOutcome(TimelineOutcome outcome) { this.outcome = outcome; }

    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }
}
