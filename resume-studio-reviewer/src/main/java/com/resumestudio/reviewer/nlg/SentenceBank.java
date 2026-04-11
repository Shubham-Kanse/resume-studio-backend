package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.*;
import org.springframework.stereotype.Component;

/**
 * Rule-based NLG sentence bank.
 *
 * Every feedback item has three layers:
 *   A — Observation:     what the recruiter found (factual)
 *   B — Interpretation:  what it means to them (context)
 *   C — Action:          what the candidate should do (specific, concrete)
 *
 * This generates specific, signal-grounded text — no LLM needed.
 */
@Component
public class SentenceBank {

    // ── Filename sentences ────────────────────────────────────────────────────

    public String filenameObservation(ResumeSignals s) {
        if (s.isFilenameProfessional()) return "Your filename is professional and clear.";
        if (s.isFilenameGeneric()) return "Your file is named with a generic term like 'resume.pdf'.";
        if (s.isFilenameHasVersioning()) return "Your filename contains versioning words like 'final' or 'v2'.";
        if (s.isFilenameTooLong()) return "Your filename is excessively long.";
        if (!s.isFilenameHasName()) return "Your filename doesn't include your name.";
        return "Your filename has minor issues.";
    }

    public String filenameInterpretation(ResumeSignals s) {
        if (s.isFilenameProfessional()) return "It won't raise any flags before the document is opened.";
        if (s.isFilenameGeneric()) return "It's indistinguishable from hundreds of others in a recruiter's downloads folder.";
        if (s.isFilenameHasVersioning()) return "This signals an unpolished submission process before the resume is even read.";
        if (!s.isFilenameHasName()) return "A recruiter can't tell whose application this is without opening it.";
        return "Small detail, but recruiters notice it.";
    }

    public String filenameAction() {
        return "Rename your file to: FirstName_LastName_RoleTitle.pdf — e.g. Jane_Smith_BackendEngineer.pdf";
    }

    // ── Title sentences ───────────────────────────────────────────────────────

    public String titleObservation(ResumeSignals s) {
        String candidate = s.getCandidateTitle() != null ? "\"" + s.getCandidateTitle() + "\"" : "your title";
        String jd = s.getJdTitle() != null ? "\"" + s.getJdTitle() + "\"" : "the role";
        return switch (s.getTitleMatch()) {
            case EXACT -> candidate + " matches " + jd + " exactly.";
            case ADJACENT -> candidate + " is adjacent to " + jd + ".";
            case RELATED -> candidate + " is in a related but different domain to " + jd + ".";
            case MISS -> candidate + " doesn't immediately read as " + jd + ".";
            default -> "No clear title found in the header.";
        };
    }

    public String titleInterpretation(ResumeSignals s) {
        return switch (s.getTitleMatch()) {
            case EXACT -> "This is the first filter a recruiter applies — you pass it without hesitation.";
            case ADJACENT -> "Close enough to keep reading, but the recruiter noted the slight difference.";
            case RELATED -> "A recruiter will pause here — it's unclear from the title alone if the required depth is there.";
            case MISS -> "In 10 seconds, a title mismatch often ends the review. Skills must compensate strongly.";
            default -> "Without a clear title, the recruiter has to read the document to understand what you do.";
        };
    }

    public String titleAction(ResumeSignals s) {
        TitleMatch titleMatch = s.getTitleMatch();
        if (titleMatch != null && (titleMatch == TitleMatch.MISS || titleMatch == TitleMatch.RELATED)) {
            return "If you do this work under a different title, bridge the gap explicitly in your summary. "
                + "E.g. 'Backend Engineer with 5 years of " + (s.getJdTitle() != null ? s.getJdTitle() : "relevant") + " experience.'";
        }
        return null; // No action needed for EXACT or ADJACENT
    }

    // ── Summary sentences ─────────────────────────────────────────────────────

    public String summaryObservation(ResumeSignals s) {
        if (!s.isSummaryPresent()) return "No summary section was found on your resume.";
        if (s.isSummaryIsGeneric()) return "Your summary uses soft skill language with no technical specifics.";
        if (s.isSummaryMentionsYoe() && s.isSummaryMentionsSkills()) return "Your summary states your title, experience, and core skills.";
        return "A summary is present but doesn't surface all key information.";
    }

    public String summaryInterpretation(ResumeSignals s) {
        if (!s.isSummaryPresent()) return "The recruiter had no narrative hook — they went straight to experience with no context about who you are.";
        if (s.isSummaryIsGeneric()) return "Generic phrases like 'passionate team player' don't tell a technical recruiter anything about fit.";
        if (s.isSummaryMentionsYoe() && s.isSummaryMentionsSkills()) return "This is exactly what a summary should do — it oriented the recruiter in under 2 seconds.";
        return "A partial summary leaves the recruiter to fill in gaps themselves.";
    }

    public String summaryAction(ResumeSignals s) {
        if (!s.isSummaryPresent() || s.isSummaryIsGeneric()) {
            return "Add a 2-line summary at the top. Formula: [Title] with [X] years of experience in [core skills]. "
                + "E.g. 'Backend Engineer with 5 years building Java/Spring Boot APIs at scale across fintech and SaaS.'";
        }
        return null;
    }

    // ── YOE sentences ─────────────────────────────────────────────────────────

    public String yoeObservation(ResumeSignals s) {
        if (s.isChronologyUnreliable()) {
            if (!s.getChronologyDescriptions().isEmpty()) {
                return s.getChronologyDescriptions().get(0);
            }
            return "The work/education chronology is too inconsistent to trust.";
        }
        String yoe = s.getCalculatedYoe() != null && s.getCalculatedYoe() > 0
            ? String.format("%.1f", s.getCalculatedYoe()).replaceAll("\\.0$", "")
            : "Unknown";
        String range = buildJdYoeRange(s);
        
        YoeFit yoeFit = s.getYoeFit();
        if (yoeFit == null) {
            return "Experience level could not be determined.";
        }
        
        return switch (yoeFit) {
            case IN_RANGE -> yoe + " years of experience — within the " + range + " requirement.";
            case UNDER_RANGE_MINOR -> yoe + " years of experience — the role asks for " + range + ".";
            case UNDER_RANGE_SIGNIFICANT -> yoe + " years of experience against a " + range + " year requirement.";
            case OVER_RANGE -> yoe + " years of experience for a role asking " + range + " years.";
            case CANNOT_DETERMINE -> {
                YoeState yoeState = s.getYoeState();
                if (yoeState == YoeState.VAGUE) yield "Your experience is described vaguely, not as a specific number.";
                if (yoeState == YoeState.MISSING) yield "No experience dates were found on your resume.";
                yield "Total experience could not be determined from the dates provided.";
            }
        };
    }

    public String yoeInterpretation(ResumeSignals s) {
        if (s.isChronologyUnreliable()) {
            return "If the chronology can't be trusted, recruiters also won't trust the years-of-experience calculation.";
        }
        YoeFit yoeFit = s.getYoeFit();
        if (yoeFit == null) {
            return "Experience level could not be assessed.";
        }
        
        return switch (yoeFit) {
            case IN_RANGE -> {
                YoeState yoeState = s.getYoeState();
                yield (yoeState == YoeState.EXPLICIT)
                    ? "Stated clearly — no calculation needed. Passes without friction."
                    : "The dates were clean enough to calculate quickly.";
            }
            case UNDER_RANGE_MINOR -> "Close enough that strong skills visibility can compensate. Recruiters treat YOE as a guideline, not a hard rule.";
            case UNDER_RANGE_SIGNIFICANT -> "This is a significant gap. Most recruiters will stop here unless something else stands out strongly.";
            case OVER_RANGE -> "Recruiters may question salary expectations or interest level in a more junior scope.";
            case CANNOT_DETERMINE -> {
                YoeState yoeState = s.getYoeState();
                if (yoeState == YoeState.VAGUE) yield "A recruiter can't validate 'several years' — they have to calculate manually, adding friction.";
                yield "Missing dates mean the recruiter cannot verify experience level. A significant credibility gap.";
            }
        };
    }

    public String yoeAction(ResumeSignals s) {
        if (s.isChronologyUnreliable()) {
            return "Standardise all dates to Month Year – Month Year and ensure work, education, and career breaks appear in a coherent timeline.";
        }
        YoeState yoeState = s.getYoeState();
        if (yoeState != null && (yoeState == YoeState.VAGUE || yoeState == YoeState.MISSING)) {
            return "Add explicit dates to every role (Month Year – Month Year). State your total YOE clearly in your summary.";
        }
        
        YoeFit yoeFit = s.getYoeFit();
        if (yoeFit == YoeFit.UNDER_RANGE_MINOR) {
            return "Lead with quality over duration in your summary. Frame your experience around impact and skills, not years.";
        }
        if (yoeFit == YoeFit.UNDER_RANGE_SIGNIFICANT) {
            return "Target roles requiring " + (s.getCalculatedYoe() != null ? Math.round(s.getCalculatedYoe()) : "your level") + " years of experience where you are competitive.";
        }
        return null;
    }

    // ── Skills visibility sentences ───────────────────────────────────────────

    public String skillVisibilityObservation(SkillMatchResult match) {
        String skill = match.getJdSkill();
        return switch (match.getVisibility()) {
            case SURFACE -> skill + " is visible in your skills section.";
            case MID -> skill + " appears in a recent job bullet but not in your skills section.";
            case BURIED -> skill + " only appears in an older role — not surfaced in your skills section.";
            case MISSING -> skill + " does not appear anywhere on your resume.";
        };
    }

    public String skillVisibilityInterpretation(SkillMatchResult match) {
        return switch (match.getVisibility()) {
            case SURFACE -> "A recruiter scanning for this role's core requirement will find it immediately.";
            case MID -> "A recruiter's eye doesn't consistently reach bullet points in 10 seconds — this skill is at risk of being missed.";
            case BURIED -> "This skill is effectively invisible on a first pass. A recruiter scanning your skills section won't see it.";
            case MISSING -> "This is a hard filter for most recruiters. Missing must-haves typically end the review immediately.";
        };
    }

    public String skillVisibilityAction(SkillMatchResult match) {
        String skill = match.getJdSkill();
        return switch (match.getVisibility()) {
            case SURFACE -> null;
            case MID -> "Move " + skill + " into your skills section so it's visible at a glance.";
            case BURIED -> "Move " + skill + " to your skills section. If you've used it recently, also mention it in your most recent role summary.";
            case MISSING -> "If you have experience with " + skill + ", add it explicitly. If not, this role may not be the right match yet.";
        };
    }

    // ── Skills format sentences ───────────────────────────────────────────────

    public String skillsFormatObservation(ResumeSignals s) {
        return switch (s.getSkillsFormat()) {
            case OPTIMAL -> "Your skills are grouped by category with the most relevant skills listed first.";
            case CATEGORISED_UNORDERED -> "Your skills are grouped by category but the order doesn't prioritise the most relevant skills.";
            case FLAT_ORDERED -> "Your skills are listed in a flat format with the key skills near the top.";
            case FLAT_UNORDERED -> "Your skills are listed in a flat format with key skills buried mid-list.";
            case PROSE -> "Your skills are written in paragraph form rather than a scannable list.";
            case BULLET_LIST -> "Your skills are formatted as individual bullet points — one per line.";
            case NO_SECTION -> "No dedicated skills section was found on your resume.";
            case GENERIC_ONLY -> "Your skills section contains only soft skills — no technical skills listed.";
            case MIXED_SOFT_HARD -> "Technical and soft skills are mixed together in the same section.";
            case SELF_RATED -> "Your skills section includes self-assessed proficiency ratings.";
            case OVER_VERSIONED -> "Your skills include version numbers for most or all technologies.";
        };
    }

    public String skillsFormatInterpretation(ResumeSignals s) {
        return switch (s.getSkillsFormat()) {
            case OPTIMAL -> "The recruiter found the key skill in under 1 second. This is best practice.";
            case CATEGORISED_UNORDERED -> "The structure is good but reordering to put the most relevant skill first would make it faster to scan.";
            case FLAT_ORDERED -> "Scannable with low friction. Minor improvement possible by grouping into categories.";
            case FLAT_UNORDERED -> "A recruiter scanning fast could miss the key skill buried mid-list.";
            case PROSE -> "Skills in paragraph form require reading rather than scanning — high friction in a 10-second pass.";
            case BULLET_LIST -> "One skill per line is scannable but space-inefficient and can look junior.";
            case NO_SECTION -> "Without a skills section, a recruiter has to search bullet points for technical keywords. Most won't bother.";
            case GENERIC_ONLY -> "For a technical role, the absence of technical skills in the skills section is a critical miss.";
            case MIXED_SOFT_HARD -> "Soft skills dilute the technical signal. A recruiter scanning for Java won't stop at 'Communication'.";
            case SELF_RATED -> "Self-assessed ratings carry zero weight with technical recruiters and make the section harder to scan.";
            case OVER_VERSIONED -> "Version numbers belong in interviews, not skills sections. They add visual noise without adding information.";
        };
    }

    public String skillsFormatAction(ResumeSignals s) {
        return switch (s.getSkillsFormat()) {
            case OPTIMAL, FLAT_ORDERED -> null;
            case CATEGORISED_UNORDERED -> "Reorder your skills so the most JD-relevant skill is first in its category.";
            case FLAT_UNORDERED -> "Move your most relevant skills to the start of the list. Better yet, group them: 'Programming: Java, Python, Go'";
            case PROSE -> "Reformat your skills as a comma-separated list or grouped categories. Remove all sentences.";
            case BULLET_LIST -> "Switch from bullet-per-skill to a comma-separated list grouped by category.";
            case NO_SECTION -> "Add a dedicated 'Skills' or 'Technical Skills' section near the top of your resume.";
            case GENERIC_ONLY -> "Replace soft skills with your actual technical stack. Move any soft skills to a separate section or remove them.";
            case MIXED_SOFT_HARD -> "Separate technical skills from soft skills. Create two distinct sections or remove soft skills entirely.";
            case SELF_RATED -> "Remove proficiency ratings. List the skills cleanly — your experience bullets demonstrate proficiency.";
            case OVER_VERSIONED -> "Remove version numbers from your skills section. List 'Java' not 'Java 8, Java 11, Java 17'.";
        };
    }

    // ── Company sentences ─────────────────────────────────────────────────────

    public String companyObservation(ResumeSignals s) {
        String company = s.getCurrentCompanyName() != null ? s.getCurrentCompanyName() : "Your current company";
        return switch (s.getCurrentCompanyTier()) {
            case FAANG, TIER_1 -> company + " is a well-recognised top-tier company.";
            case SCALE_UP -> company + " is known in the industry.";
            case DESCRIBED -> company + " is less known but you've provided context.";
            case STARTUP -> company + " is an early-stage company.";
            case UNKNOWN -> company + " is not immediately recognisable to a recruiter.";
        };
    }

    public String companyInterpretation(ResumeSignals s) {
        return switch (s.getCurrentCompanyTier()) {
            case FAANG, TIER_1 -> "Engineering bar, scale, and culture are all inferred without reading a word. Strong trust signal.";
            case SCALE_UP -> "Clear context — mild positive signal.";
            case DESCRIBED -> "Unknown name, but the descriptor fills the gap effectively.";
            case STARTUP -> "No tier signal. Recruiter has no frame of reference for the engineering context.";
            case UNKNOWN -> "Without a descriptor, no context is signalled. A missed trust opportunity.";
        };
    }

    public String companyAction(ResumeSignals s) {
        CompanyTier tier = s.getCurrentCompanyTier();
        if (tier != null && (tier == CompanyTier.UNKNOWN || tier == CompanyTier.STARTUP)) {
            return "Add a brief descriptor after your company name in brackets: e.g. Acme Corp (Series B fintech, 150 engineers). "
                + "This gives the recruiter instant context even for an unknown company.";
        }
        return null;
    }

    // ── Gap sentences ─────────────────────────────────────────────────────────

    public String gapObservation(ResumeSignals s) {
        if (!s.getGapDescriptions().isEmpty()) return s.getGapDescriptions().get(0) + " with no explanation.";
        return "An unexplained gap was detected in your work history.";
    }

    public String gapAction() {
        return "Label career gaps explicitly: 'Career break — upskilling / personal / parental leave'. "
            + "A labelled gap raises no flags. An unexplained gap does.";
    }

    public String chronologyObservation(ResumeSignals s) {
        if (!s.getChronologyDescriptions().isEmpty()) {
            return s.getChronologyDescriptions().get(0);
        }
        return "The overall chronology is hard to verify from the dates provided.";
    }

    public String chronologyInterpretation(ResumeSignals s) {
        return s.isChronologyUnreliable()
            ? "The work and education timeline does not form a trustworthy chronology. Recruiters will question all downstream experience claims."
            : "The chronology is partially ambiguous, which adds friction to experience verification.";
    }

    public String chronologyAction() {
        return "Align work, education, and career breaks into one clear timeline. Use Month Year – Month Year consistently and label non-work periods explicitly.";
    }

    // ── Anomaly sentences ─────────────────────────────────────────────────────

    public String skillAgeMismatchAction(ResumeSignals s) {
        return "Correct the years of experience stated for this skill — "
            + (s.getSkillAgeMismatchDetail() != null ? s.getSkillAgeMismatchDetail() : "the claim exceeds the technology's age.")
            + " This type of error undermines credibility.";
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private String buildJdYoeRange(ResumeSignals s) {
        if (s.getJdYoeMin() == null) return "unspecified";
        if (s.getJdYoeMax() == null) return s.getJdYoeMin().intValue() + "+";
        return s.getJdYoeMin().intValue() + "–" + s.getJdYoeMax().intValue();
    }
}
