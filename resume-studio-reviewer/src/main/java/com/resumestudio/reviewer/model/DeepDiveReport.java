package com.resumestudio.reviewer.model;

import java.util.List;

/**
 * Section-by-section deep dive report.
 * Each section contains a list of ReviewItems — each with a quoted text,
 * verdict, score, observation, and action.
 */
public class DeepDiveReport {

    private ResumeScore score;
    private List<Section> sections;

    public static class Section {
        private String id;          // HEADER, SUMMARY, EXPERIENCE_0, SKILLS, EDUCATION, FORMAT
        private String title;       // "Professional Summary", "Fiserv (May 2021 – Jul 2024)", etc.
        private int sectionScore;   // 0–100
        private List<ReviewItem> items;

        public Section() {}
        public Section(String id, String title, int sectionScore, List<ReviewItem> items) {
            this.id = id; this.title = title; this.sectionScore = sectionScore; this.items = items;
        }
        public String getId() { return id; }
        public String getTitle() { return title; }
        public int getSectionScore() { return sectionScore; }
        public List<ReviewItem> getItems() { return items; }
    }

    public static class ReviewItem {
        private String type;        // BULLET, SKILL, HEADER_FIELD, SUMMARY_TEXT, FORMAT_ISSUE, SECTION_STRUCTURE
        private String quote;       // exact text from resume being reviewed (for highlighting)
        private String verdict;     // PASS / WARN / FAIL
        private int score;          // 0–100
        private String observation; // what the reviewer found
        private String action;      // what to do about it (null if PASS)

        public ReviewItem() {}
        public ReviewItem(String type, String quote, String verdict, int score, String observation, String action) {
            this.type = type; this.quote = quote; this.verdict = verdict;
            this.score = score; this.observation = observation; this.action = action;
        }
        public String getType() { return type; }
        public String getQuote() { return quote; }
        public String getVerdict() { return verdict; }
        public int getScore() { return score; }
        public String getObservation() { return observation; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }

    public ResumeScore getScore() { return score; }
    public void setScore(ResumeScore score) { this.score = score; }
    public List<Section> getSections() { return sections; }
    public void setSections(List<Section> sections) { this.sections = sections; }
}
