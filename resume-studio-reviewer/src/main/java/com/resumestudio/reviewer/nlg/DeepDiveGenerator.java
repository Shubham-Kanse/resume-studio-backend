package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.DeepDiveReport.*;
import com.resumestudio.reviewer.model.enums.*;
import com.resumestudio.reviewer.nlp.BulletEnricher.EnrichedBullet;
import com.resumestudio.reviewer.signals.ResumeScoreCalculator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a section-by-section deep dive review.
 * Every bullet, skill, and section element gets a score, verdict, observation, and action.
 */
@Component
public class DeepDiveGenerator {

    private final ResumeScoreCalculator scoreCalculator;
    private final SentenceBankOntologyService ontologyBank;

    public DeepDiveGenerator(ResumeScoreCalculator scoreCalculator, SentenceBankOntologyService ontologyBank) {
        this.scoreCalculator = scoreCalculator;
        this.ontologyBank = ontologyBank;
    }

    public DeepDiveReport generate(Resume resume, ResumeSignals signals, JobDescription jd) {
        ResumeScore score = scoreCalculator.calculate(signals);
        List<Section> sections = new ArrayList<>();

        sections.add(buildHeaderSection(resume, signals));
        if (resume.getSummaryText() != null) sections.add(buildSummarySection(resume, signals));
        sections.addAll(buildExperienceSections(resume, signals, jd));
        sections.add(buildSkillsSection(resume, signals, jd));
        if (!resume.getEducation().isEmpty()) sections.add(buildEducationSection(resume));
        sections.add(buildFormatSection(resume, signals));

        DeepDiveReport report = new DeepDiveReport();
        report.setScore(score);
        report.setSections(sections);
        return report;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private Section buildHeaderSection(Resume resume, ResumeSignals signals) {
        List<ReviewItem> items = new ArrayList<>();

        // Name
        if (resume.getCandidateName() != null) {
            items.add(pass("HEADER_FIELD", resume.getCandidateName(), 100,
                "Name is present and clearly formatted.", null));
        } else {
            items.add(fail("HEADER_FIELD", "[No name detected]", 0,
                "No candidate name was found in the header.",
                "Place your full name as the first line of your resume in a larger font."));
        }

        // Title
        if (resume.getCurrentTitle() != null) {
            boolean titleMatch = signals.getTitleMatch() == TitleMatch.EXACT || signals.getTitleMatch() == TitleMatch.ADJACENT;
            int score = titleMatch ? 90 : signals.getTitleMatch() == TitleMatch.RELATED ? 60 : 25;
            String verdict = score >= 70 ? "PASS" : score >= 40 ? "WARN" : "FAIL";
            String obs = titleMatch
                ? "\"" + resume.getCurrentTitle() + "\" aligns with the target role."
                : "\"" + resume.getCurrentTitle() + "\" doesn't immediately read as \"" + (jdTitle(signals)) + "\".";
            String action = score < 70 ? "Consider updating your title to match the role more closely, or bridge the gap in your summary." : null;
            items.add(new ReviewItem("HEADER_FIELD", resume.getCurrentTitle(), verdict, score, obs, action));
        } else {
            items.add(fail("HEADER_FIELD", "[No title]", 0,
                "No professional title found below your name.",
                "Add your title directly below your name: e.g. 'Senior Backend Engineer'."));
        }

        // Contact completeness
        boolean hasEmail = resume.getEmail() != null;
        boolean hasPhone = resume.getPhone() != null;
        boolean hasLinkedIn = resume.getLinkedInUrl() != null;
        int contactScore = (hasEmail ? 40 : 0) + (hasPhone ? 30 : 0) + (hasLinkedIn ? 30 : 0);
        String contactObs = "Email: " + (hasEmail ? "✓" : "✗") + "  Phone: " + (hasPhone ? "✓" : "✗") + "  LinkedIn: " + (hasLinkedIn ? "✓" : "✗");
        String contactAction = contactScore < 100 ? "Add missing contact details. LinkedIn is expected for tech roles." : null;
        items.add(new ReviewItem("HEADER_FIELD", resume.getEmail() != null ? resume.getEmail() : "[contact info]",
            contactScore >= 70 ? "PASS" : "WARN", contactScore, contactObs, contactAction));

        // Filename
        if (!signals.isFilenameProfessional()) {
            String detail = signals.getFilenameIssueDetail() != null ? signals.getFilenameIssueDetail() : "Filename is not professional.";
            items.add(warn("FORMAT_ISSUE", resume.getRawFilename(), 30, detail,
                "Rename to FirstName_LastName_Role.pdf — e.g. Shubham_Kanse_BackendEngineer.pdf"));
        }

        int sectionScore = items.stream().mapToInt(ReviewItem::getScore).sum() / items.size();
        return new Section("HEADER", "Header & Contact", sectionScore, items);
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private Section buildSummarySection(Resume resume, ResumeSignals signals) {
        List<ReviewItem> items = new ArrayList<>();
        String text = resume.getSummaryText();

        // Presence & length
        int wordCount = text.split("\\s+").length;
        if (wordCount < 15) {
            items.add(warn("SUMMARY_TEXT", text, 20,
                "Summary is too short (" + wordCount + " words). A recruiter gets no useful context.",
                "Expand to 2–3 sentences: title + YOE + core skills + what you're targeting."));
        } else if (wordCount > 100) {
            items.add(warn("SUMMARY_TEXT", text.substring(0, 80) + "…", 50,
                "Summary is too long (" + wordCount + " words). Recruiters read the first 2 lines.",
                "Trim to 40–60 words. Every word must earn its place."));
        }

        // Generic language
        if (signals.isSummaryIsGeneric()) {
            items.add(warn("SUMMARY_TEXT", text, 25,
                "Summary uses generic phrases ('passionate', 'team player') that add no signal.",
                "Replace with specifics: your actual title, years, and 3 core technologies."));
        }

        // Mentions required skills
        if (!signals.isSummaryMentionsSkills()) {
            items.add(warn("SUMMARY_TEXT", text, 35,
                "Summary doesn't mention the core skills this role requires.",
                "Add the 2–3 most important required skills from the JD into your summary."));
        }

        // Mentions YOE
        if (!signals.isSummaryMentionsYoe()) {
            items.add(warn("SUMMARY_TEXT", text, 40,
                "Summary doesn't state your years of experience.",
                "Add your YOE explicitly: 'Backend engineer with 3.5 years of experience…'"));
        }

        // If everything is good
        if (items.isEmpty()) {
            items.add(pass("SUMMARY_TEXT", text, 90,
                "Summary is specific, mentions YOE and core skills. Strong opening.", null));
        }

        int sectionScore = items.stream().mapToInt(ReviewItem::getScore).sum() / items.size();
        return new Section("SUMMARY", "Professional Summary", sectionScore, items);
    }

    // ── Experience ────────────────────────────────────────────────────────────

    private List<Section> buildExperienceSections(Resume resume, ResumeSignals signals, JobDescription jd) {
        List<Section> sections = new ArrayList<>();
        List<WorkExperience> experience = resume.getExperience();
        if (experience == null || experience.isEmpty()) return sections;

        // Build a map of bullet text → enriched bullet for fast lookup
        Map<String, EnrichedBullet> bulletMap = new java.util.HashMap<>();
        if (resume.getEnrichedBullets() != null) {
            for (EnrichedBullet eb : resume.getEnrichedBullets()) {
                bulletMap.put(eb.text(), eb);
            }
        }

        for (int i = 0; i < experience.size(); i++) {
            WorkExperience role = experience.get(i);
            List<ReviewItem> items = new ArrayList<>();

            // Role header — title + company + dates
            String roleHeader = buildRoleHeader(role);
            boolean hasDates = role.getStartDate() != null;
            if (!hasDates) {
                items.add(warn("HEADER_FIELD", roleHeader, 30,
                    "This role is missing start/end dates.",
                    "Add Month Year – Month Year dates to every role."));
            } else if (role.isDatesArePartial()) {
                items.add(warn("HEADER_FIELD", roleHeader, 55,
                    "Year-only dates reduce chronology precision.",
                    "Use Month Year format: 'May 2021 – Jul 2024' instead of '2021 – 2024'."));
            }

            // Bullet review
            if (role.getBullets() != null) {
                for (String bullet : role.getBullets()) {
                    EnrichedBullet eb = bulletMap.get(bullet);
                    items.add(reviewBullet(bullet, eb, jd));
                }
            }

            if (role.getBullets() == null || role.getBullets().isEmpty()) {
                items.add(fail("BULLET", roleHeader, 0,
                    "No bullet points found for this role.",
                    "Add 3–5 achievement bullets per role. Use: Verb + What + Result + Scale."));
            }

            int sectionScore = items.isEmpty() ? 50 : items.stream().mapToInt(ReviewItem::getScore).sum() / items.size();
            String title = (role.getTitle() != null ? role.getTitle() : "Role") +
                (role.getCompany() != null ? " · " + role.getCompany() : "");
            sections.add(new Section("EXPERIENCE_" + i, title, sectionScore, items));
        }
        return sections;
    }

    private ReviewItem reviewBullet(String bullet, EnrichedBullet eb, JobDescription jd) {
        if (eb == null) {
            // No enrichment data — basic heuristic
            boolean hasNumber = bullet.matches(".*\\d+.*");
            int score = hasNumber ? 60 : 35;
            return new ReviewItem("BULLET", bullet, score >= 50 ? "WARN" : "FAIL", score,
                hasNumber ? "Has a number but verb quality unknown." : "No quantified result detected.",
                hasNumber ? null : "Add a metric: how much, how many, how fast, how often.");
        }

        int score = 0;
        List<String> issues = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        // Verb quality (0–40 pts)
        score += switch (eb.actionVerbQuality()) {
            case "STRONG" -> 40;
            case "MEDIUM" -> 28;
            case "WEAK" -> 10;
            default -> 0;
        };
        if ("WEAK".equals(eb.actionVerbQuality()) || "MISSING".equals(eb.actionVerbQuality())) {
            issues.add("weak opening verb");
            actions.add("Start with a strong action verb: Built, Reduced, Led, Automated, Scaled.");
        }

        // Metric (0–35 pts)
        score += eb.metricDetected() ? 35 : 0;
        if (!eb.metricDetected()) {
            issues.add("no quantified result");
            actions.add("Add a number: %, $, time saved, users served, team size.");
        }

        // Specificity (0–25 pts)
        score += (int)(eb.specificityScore() / 10.0 * 25);

        // Penalties
        if (eb.duplicateFlag()) { score = Math.max(0, score - 20); issues.add("similar to another bullet"); }
        if (eb.credibilityFlag()) { score = Math.max(0, score - 15); issues.add("credibility concern"); }

        score = Math.min(100, score);
        String verdict = score >= 70 ? "PASS" : score >= 40 ? "WARN" : "FAIL";
        String obs = issues.isEmpty() ? "Strong bullet — specific, quantified, action-led." : String.join("; ", issues) + ".";
        String action = actions.isEmpty() ? null : actions.get(0);

        return new ReviewItem("BULLET", bullet, verdict, score, obs, action);
    }

    // ── Skills ────────────────────────────────────────────────────────────────

    private Section buildSkillsSection(Resume resume, ResumeSignals signals, JobDescription jd) {
        List<ReviewItem> items = new ArrayList<>();

        // Format
        SkillsFormat fmt = signals.getSkillsFormat();
        if (fmt == SkillsFormat.NO_SECTION) {
            items.add(fail("SECTION_STRUCTURE", "[No skills section]", 0,
                "No dedicated skills section found.",
                "Add a 'Technical Skills' section near the top. Group by category."));
        } else if (fmt == SkillsFormat.GENERIC_ONLY) {
            items.add(fail("SECTION_STRUCTURE", "[Skills section]", 10,
                "Skills section contains only soft skills — no technical skills.",
                "Replace with your actual tech stack. Soft skills belong in bullets, not a skills section."));
        } else if (fmt == SkillsFormat.PROSE) {
            items.add(warn("SECTION_STRUCTURE", "[Skills section]", 30,
                "Skills written in paragraph form — hard to scan in 10 seconds.",
                "Reformat as a comma-separated list grouped by category."));
        }

        // Per required skill — visibility
        if (signals.getMustHaveResults() != null) {
            for (var result : signals.getMustHaveResults()) {
                String skillName = result.getJdSkill();
                SkillVisibility vis = result.getVisibility();
                int score = switch (vis) {
                    case SURFACE -> 100;
                    case MID -> 65;
                    case BURIED -> 35;
                    case MISSING -> 0;
                };
                String verdict = score >= 70 ? "PASS" : score >= 40 ? "WARN" : "FAIL";
                String obs = switch (vis) {
                    case SURFACE -> "\"" + skillName + "\" is visible in your skills section.";
                    case MID -> "\"" + skillName + "\" appears in a recent bullet but not your skills section.";
                    case BURIED -> "\"" + skillName + "\" only appears in an older role — effectively invisible.";
                    case MISSING -> "\"" + skillName + "\" does not appear anywhere on your resume.";
                };
                String action = switch (vis) {
                    case SURFACE -> null;
                    case MID -> "Move \"" + skillName + "\" into your skills section.";
                    case BURIED -> "Add \"" + skillName + "\" to your skills section. If you've used it recently, mention it in your latest role.";
                    case MISSING -> "If you have any exposure to \"" + skillName + "\", add it. If not, this is a genuine gap.";
                };
                items.add(new ReviewItem("SKILL", skillName, verdict, score, obs, action));
            }
        }

        int sectionScore = items.isEmpty() ? 50 : items.stream().mapToInt(ReviewItem::getScore).sum() / items.size();
        return new Section("SKILLS", "Technical Skills", sectionScore, items);
    }

    // ── Education ─────────────────────────────────────────────────────────────

    private Section buildEducationSection(Resume resume) {
        List<ReviewItem> items = new ArrayList<>();
        for (Education edu : resume.getEducation()) {
            String label = (edu.getDegree() != null ? edu.getDegree() : "Degree") +
                (edu.getInstitution() != null ? " · " + edu.getInstitution() : "");
            boolean hasDates = edu.getStartYear() != null || edu.getGraduationYear() != null;
            int score = 70;
            if (!hasDates) { score = 40; }
            String tier = edu.getInstitutionTier() != null ? edu.getInstitutionTier() : "UNKNOWN";
            double boost = edu.getInstitutionBoost();
            score = (int) Math.min(100, score + boost * 30);
            String obs = tier.equals("ELITE") || tier.equals("PRESTIGE")
                ? "Prestigious institution — strong credibility signal."
                : hasDates ? "Education entry is complete." : "Missing dates on education entry.";
            String action = !hasDates ? "Add graduation year to this education entry." : null;
            items.add(new ReviewItem("HEADER_FIELD", label, score >= 60 ? "PASS" : "WARN", (int)score, obs, action));
        }
        int sectionScore = items.isEmpty() ? 50 : items.stream().mapToInt(ReviewItem::getScore).sum() / items.size();
        return new Section("EDUCATION", "Education", sectionScore, items);
    }

    // ── Format ────────────────────────────────────────────────────────────────

    private Section buildFormatSection(Resume resume, ResumeSignals signals) {
        List<ReviewItem> items = new ArrayList<>();

        // Page count
        if (signals.isFormatTooManyPages()) {
            items.add(warn("FORMAT_ISSUE", resume.getPageCount() + " pages", 30,
                "Resume is " + resume.getPageCount() + " pages. Recruiters expect 1–2 pages for < 10 years experience.",
                "Cut to 2 pages maximum. Remove old roles, trim bullets, reduce white space."));
        } else {
            items.add(pass("FORMAT_ISSUE", resume.getPageCount() + " page(s)", 90, "Page count is appropriate.", null));
        }

        // Font size
        if (signals.isFormatFontTooSmall()) {
            items.add(warn("FORMAT_ISSUE", "Font size: " + String.format("%.1f", resume.getMinFontSize()) + "pt min", 25,
                "Minimum font size is below 9pt — hard to read and may fail ATS parsing.",
                "Keep body text at 10–11pt minimum. Section headers at 12–14pt."));
        }

        // Wall of text
        if (signals.isFormatWallOfText()) {
            items.add(warn("FORMAT_ISSUE", "Whitespace ratio: " + String.format("%.0f%%", resume.getWhitespaceRatio() * 100), 20,
                "Very low whitespace — the document reads as a wall of text.",
                "Add more line breaks, increase margins, or reduce content density."));
        }

        // Photo
        if (signals.isFormatHasPhoto()) {
            items.add(warn("FORMAT_ISSUE", "[Photo detected]", 40,
                "Photo present. Non-standard for tech roles in most markets.",
                "Remove the photo. It introduces bias risk and most ATS systems strip it anyway."));
        }

        // Multi-column
        if (signals.isFormatIsMultiColumn()) {
            items.add(warn("FORMAT_ISSUE", "[Multi-column layout]", 35,
                "Multi-column layouts often break ATS parsing.",
                "Switch to a single-column layout for maximum ATS compatibility."));
        }

        if (items.stream().allMatch(i -> "PASS".equals(i.getVerdict()))) {
            items.add(pass("FORMAT_ISSUE", "Overall layout", 95, "Clean, ATS-friendly single-column layout.", null));
        }

        int sectionScore = items.stream().mapToInt(ReviewItem::getScore).sum() / items.size();
        return new Section("FORMAT", "Format & Layout", sectionScore, items);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ReviewItem pass(String type, String quote, int score, String obs, String action) {
        return new ReviewItem(type, quote, "PASS", score, obs, action);
    }
    private ReviewItem warn(String type, String quote, int score, String obs, String action) {
        return new ReviewItem(type, quote, "WARN", score, obs, action);
    }
    private ReviewItem fail(String type, String quote, int score, String obs, String action) {
        return new ReviewItem(type, quote, "FAIL", score, obs, action);
    }

    private String jdTitle(ResumeSignals signals) {
        return signals.getJdTitle() != null ? signals.getJdTitle() : "this role";
    }

    private String buildRoleHeader(WorkExperience role) {
        StringBuilder sb = new StringBuilder();
        if (role.getTitle() != null) sb.append(role.getTitle());
        if (role.getCompany() != null) sb.append(" · ").append(role.getCompany());
        if (role.getStartDate() != null) sb.append(" (").append(role.getStartDate().getYear()).append(")");
        return sb.toString();
    }
}
