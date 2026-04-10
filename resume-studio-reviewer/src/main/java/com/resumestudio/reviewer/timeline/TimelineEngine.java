package com.resumestudio.reviewer.timeline;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.TimelineEvent;
import com.resumestudio.reviewer.model.enums.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the recruiter simulation timeline.
 * Every event is conditional — the timeline is reconstructed from actual signals,
 * not filled from a template.
 *
 * Rules:
 *  - Always starts with filename
 *  - Always ends with verdict
 *  - Format issues only included if HIGH friction
 *  - Company only included if notable (FAANG, DESCRIBED, or trajectory signal)
 *  - 5–8 events total for readability
 *  - Timeline collapses early if a HARD_STOP signal fires
 */
@Component
public class TimelineEngine {

    public List<TimelineEvent> build(ResumeSignals signals, Verdict verdict) {
        List<TimelineEvent> events = new ArrayList<>();
        boolean continueReading = true;

        // ── 1. Filename (always) ──────────────────────────────────────────
        events.add(buildFilenameEvent(signals));

        // ── 2. Format (only if high friction) ────────────────────────────
        if (hasHighFormatFriction(signals)) {
            events.add(buildFormatEvent(signals));
            if (signals.isFormatWallOfText() && signals.isFormatFontTooSmall()) {
                continueReading = false; // catastrophic format = early exit
            }
        }

        // ── 3. Title (always if still reading) ───────────────────────────
        if (continueReading) {
            TimelineEvent titleEvent = buildTitleEvent(signals);
            events.add(titleEvent);
            if (titleEvent.getOutcome() == TimelineOutcome.HARD_STOP) {
                continueReading = false;
            }
        }

        // ── 4. Summary (if present or notably absent) ─────────────────────
        if (continueReading) {
            events.add(buildSummaryEvent(signals));
        }

        // ── 5. YOE ────────────────────────────────────────────────────────
        if (continueReading) {
            TimelineEvent yoeEvent = buildYoeEvent(signals);
            events.add(yoeEvent);
            if (yoeEvent.getOutcome() == TimelineOutcome.NEAR_EXIT
                && signals.isHasMissingMustHaves()) {
                continueReading = false;
            }
        }

        // ── 6. Company (only if notable) ──────────────────────────────────
        if (continueReading && isCompanyNotable(signals)) {
            events.add(buildCompanyEvent(signals));
        }

        // ── 7. Skills (always if still reading) ───────────────────────────
        if (continueReading) {
            events.add(buildSkillsEvent(signals));
        }

        // ── 8. Verdict (always last) ───────────────────────────────────────
        events.add(buildVerdictEvent(verdict, continueReading, events));

        // Assign timestamps
        assignTimestamps(events);

        return events;
    }

    // ── Event builders ────────────────────────────────────────────────────────

    private TimelineEvent buildFilenameEvent(ResumeSignals signals) {
        if (signals.isFilenameProfessional()) {
            return event(TimelineEventType.FILENAME,
                "Filename — clean first impression",
                "Professional naming. Findable and unambiguous.",
                TimelineOutcome.POSITIVE, "POSITIVE");
        }
        if (signals.isFilenameHasVersioning()) {
            return event(TimelineEventType.FILENAME,
                "Filename raised a small flag",
                signals.getFilenameIssueDetail() != null
                    ? signals.getFilenameIssueDetail()
                    : "Versioning words like 'final' or 'v2' signal an unpolished submission process.",
                TimelineOutcome.MINOR_FLAG, "NEGATIVE");
        }
        if (signals.isFilenameGeneric()) {
            return event(TimelineEventType.FILENAME,
                "Filename — completely generic",
                "A generic filename is indistinguishable from hundreds of others before the document is even opened.",
                TimelineOutcome.MINOR_FLAG, "NEGATIVE");
        }
        return event(TimelineEventType.FILENAME,
            "Filename — minor issue",
            signals.getFilenameIssueDetail() != null ? signals.getFilenameIssueDetail() : "Filename could be clearer.",
            TimelineOutcome.MINOR_FLAG, "NEUTRAL");
    }

    private TimelineEvent buildFormatEvent(ResumeSignals signals) {
        if (signals.isFormatWallOfText()) {
            return event(TimelineEventType.FORMAT,
                "Layout — dense, hard to scan",
                "Almost no white space. A recruiter's eye has nowhere to land quickly.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        }
        if (signals.isFormatTooManyPages()) {
            return event(TimelineEventType.FORMAT,
                "Resume length — too long for experience level",
                "Too many pages for the years of experience stated. Signals poor editing judgment.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        }
        if (signals.isFormatHasPhoto()) {
            return event(TimelineEventType.FORMAT,
                "Photo detected",
                "Photos are non-standard for tech roles and introduce unconscious bias risk.",
                TimelineOutcome.MINOR_FLAG, "NEGATIVE");
        }
        return event(TimelineEventType.FORMAT,
            "Formatting issues noted",
            "Minor layout issues that add friction to a quick scan.",
            TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
    }

    private TimelineEvent buildTitleEvent(ResumeSignals signals) {
        String cTitle = signals.getCandidateTitle() != null ? signals.getCandidateTitle() : "No title found";
        String jTitle = signals.getJdTitle() != null ? signals.getJdTitle() : "role";

        return switch (signals.getTitleMatch()) {
            case EXACT -> event(TimelineEventType.TITLE,
                "Title matched exactly — kept reading",
                "\"" + cTitle + "\" directly matches the role. First filter cleared instantly.",
                TimelineOutcome.KEPT_READING, "POSITIVE");
            case ADJACENT -> event(TimelineEventType.TITLE,
                "Title close enough — kept reading",
                "\"" + cTitle + "\" is adjacent to \"" + jTitle + ".\" Close enough to pass, not close enough to be obvious.",
                TimelineOutcome.KEPT_READING_WITH_HESITATION, "POSITIVE");
            case RELATED -> event(TimelineEventType.TITLE,
                "Title — related but different domain",
                "\"" + cTitle + "\" applying for \"" + jTitle + ".\" Recruiter paused — unclear if the required depth is there.",
                TimelineOutcome.CAUTION, "NEUTRAL");
            case MISS -> event(TimelineEventType.TITLE,
                "Title mismatch — nearly stopped here",
                "\"" + cTitle + "\" doesn't immediately read as \"" + jTitle + ".\" Skills section must compensate.",
                TimelineOutcome.HARD_STOP, "NEGATIVE");
            default -> event(TimelineEventType.TITLE,
                "No title visible",
                "Recruiter can't tell what this person does without reading the full document.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        };
    }

    private TimelineEvent buildSummaryEvent(ResumeSignals signals) {
        if (signals.isSummaryPresent()) {
            if (signals.isSummaryMentionsYoe() && signals.isSummaryMentionsSkills()) {
                return event(TimelineEventType.SUMMARY,
                    "Summary gave instant context",
                    "The summary stated title, experience, and core skills. Exactly what it should do.",
                    TimelineOutcome.POSITIVE, "POSITIVE");
            }
            if (signals.isSummaryIsGeneric()) {
                return event(TimelineEventType.SUMMARY,
                    "Summary present — but generic",
                    "Soft skill language doesn't tell a technical recruiter anything about fit. A wasted opportunity.",
                    TimelineOutcome.MINOR_FLAG, "NEGATIVE");
            }
            return event(TimelineEventType.SUMMARY,
                "Summary present — partial context",
                "A summary exists but doesn't surface YOE or core skills clearly.",
                TimelineOutcome.NEUTRAL, "NEUTRAL");
        }
        return event(TimelineEventType.SUMMARY,
            "No summary — no narrative hook",
            "Recruiter went straight to experience with no context. A missed opportunity to frame the first impression.",
            TimelineOutcome.MINOR_FLAG, "NEGATIVE");
    }

    private TimelineEvent buildYoeEvent(ResumeSignals signals) {
        String yoeStr = signals.getCalculatedYoe() != null
            ? String.format("%.0f", signals.getCalculatedYoe()) : "?";
        String jdRange = buildJdYoeRange(signals);

        return switch (signals.getYoeFit()) {
            case IN_RANGE -> {
                String clarity = signals.getYoeState() == YoeState.EXPLICIT
                    ? "Stated clearly — no calculation needed." : "Dates were clean enough to calculate.";
                yield event(TimelineEventType.YOE,
                    yoeStr + " years experience — in range",
                    "Role asks for " + jdRange + ". " + clarity,
                    TimelineOutcome.KEPT_READING, "POSITIVE");
            }
            case UNDER_RANGE_MINOR -> event(TimelineEventType.YOE,
                "Experience slightly under requirement",
                yoeStr + " years against " + jdRange + " requirement. Close enough that strong skills could compensate.",
                TimelineOutcome.CAUTION, "NEUTRAL");
            case UNDER_RANGE_SIGNIFICANT -> event(TimelineEventType.YOE,
                "Experience gap — significant",
                yoeStr + " years against " + jdRange + " requirement. A recruiter paused here.",
                TimelineOutcome.NEAR_EXIT, "NEGATIVE");
            case OVER_RANGE -> event(TimelineEventType.YOE,
                "Overqualified signal",
                yoeStr + " years for a role asking " + jdRange + ". Recruiter may question fit.",
                TimelineOutcome.CAUTION, "NEUTRAL");
            case CANNOT_DETERMINE -> {
                if (signals.getYoeState() == YoeState.VAGUE) {
                    yield event(TimelineEventType.YOE,
                        "Experience — stated but not a number",
                        "Vague phrasing like 'several years' forces the recruiter to calculate manually.",
                        TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
                }
                yield event(TimelineEventType.YOE,
                    "Experience dates — unclear",
                    "Could not determine total experience from the information provided.",
                    TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
            }
        };
    }

    private TimelineEvent buildCompanyEvent(ResumeSignals signals) {
        String company = signals.getCurrentCompanyName() != null ? signals.getCurrentCompanyName() : "Current company";

        return switch (signals.getCurrentCompanyTier()) {
            case FAANG, TIER_1 -> event(TimelineEventType.COMPANY,
                "Current company — strong signal",
                company + " is immediately recognised. Engineering bar and scale are inferred without reading a word.",
                TimelineOutcome.POSITIVE, "POSITIVE");
            case SCALE_UP -> event(TimelineEventType.COMPANY,
                "Current company — solid signal",
                company + " is known in the industry. Context is clear.",
                TimelineOutcome.POSITIVE, "POSITIVE");
            case DESCRIBED -> event(TimelineEventType.COMPANY,
                "Current company — context given",
                "Unknown name but the descriptor fills the gap. Well handled.",
                TimelineOutcome.NEUTRAL, "NEUTRAL");
            default -> event(TimelineEventType.COMPANY,
                "Current company — no context",
                company + " doesn't signal anything without a descriptor. A missed trust signal.",
                TimelineOutcome.MINOR_FLAG, "NEUTRAL");
        };
    }

    private TimelineEvent buildSkillsEvent(ResumeSignals signals) {
        if (signals.isHasMissingMustHaves()) {
            return event(TimelineEventType.SKILLS,
                "Must-have skills not found anywhere",
                "The primary technical requirement for this role doesn't appear on the resume. Typically a decisive filter.",
                TimelineOutcome.HARD_STOP, "NEGATIVE");
        }
        if (signals.isHasBuriedMustHaves() && !signals.isAllMustHavesVisible()) {
            return event(TimelineEventType.SKILLS,
                "Key skills not in skills section — only in old bullets",
                "The required skills exist on the resume but a recruiter's eye doesn't reach job bullets in 10 seconds.",
                TimelineOutcome.NEAR_EXIT, "NEGATIVE");
        }
        if (signals.getSkillsFormat() == SkillsFormat.OPTIMAL || signals.getSkillsFormat() == SkillsFormat.FLAT_ORDERED) {
            return event(TimelineEventType.SKILLS,
                "Required skills found immediately",
                "The must-have skills were visible in under 1 second. Skills section is doing its job.",
                TimelineOutcome.POSITIVE, "POSITIVE");
        }
        if (signals.getSkillsFormat() == SkillsFormat.PROSE) {
            return event(TimelineEventType.SKILLS,
                "Skills written as prose — hard to scan",
                "Skills in paragraph form require reading rather than scanning. High friction.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        }
        return event(TimelineEventType.SKILLS,
            "Skills found — minor friction",
            "Required skills are present but took a moment to locate in the list.",
            TimelineOutcome.CAUTION, "NEUTRAL");
    }

    private TimelineEvent buildVerdictEvent(Verdict verdict, boolean fullRead, List<TimelineEvent> priorEvents) {
        return switch (verdict) {
            case STRONG_FIT -> event(TimelineEventType.VERDICT,
                "Moved to interview pile",
                "Strong enough signal on first pass. This resume earns a full read.",
                TimelineOutcome.POSITIVE, "POSITIVE");
            case POSSIBLE_FIT -> event(TimelineEventType.VERDICT,
                "Moved to the maybe pile",
                "Not rejected. Not prioritised. Will be revisited if the strong pile runs thin.",
                TimelineOutcome.NEUTRAL, "NEUTRAL");
            case WEAK_FIT -> {
                if (!fullRead) {
                    yield event(TimelineEventType.VERDICT,
                        "Review stopped early",
                        "A decisive negative signal ended the review before a full scan.",
                        TimelineOutcome.HARD_STOP, "NEGATIVE");
                }
                yield event(TimelineEventType.VERDICT,
                    "Passed on after full scan",
                    "Resume was reviewed but no strong signal emerged. Moved to the reject pile.",
                    TimelineOutcome.HARD_STOP, "NEGATIVE");
            }
        };
    }

    // ── Timeline utilities ────────────────────────────────────────────────────

    private void assignTimestamps(List<TimelineEvent> events) {
        int[] weights = {1, 1, 1, 1, 2, 1, 2, 1}; // per event type weight in seconds
        int elapsed = 0;
        for (int i = 0; i < events.size(); i++) {
            events.get(i).setTimeLabel(elapsed + "s");
            elapsed += (i < weights.length ? weights[i] : 1);
        }
    }

    private boolean hasHighFormatFriction(ResumeSignals signals) {
        return signals.isFormatWallOfText() || signals.isFormatTooManyPages()
            || signals.isFormatFontTooSmall() || signals.isFormatHasPhoto()
            || signals.isFormatIsMultiColumn();
    }

    private boolean isCompanyNotable(ResumeSignals signals) {
        CompanyTier tier = signals.getCurrentCompanyTier();
        return tier == CompanyTier.FAANG || tier == CompanyTier.TIER_1
            || tier == CompanyTier.SCALE_UP || tier == CompanyTier.DESCRIBED
            || signals.isCompanyTierImproving() || signals.isCompanyTierDeclining();
    }

    private String buildJdYoeRange(ResumeSignals signals) {
        if (signals.getJdYoeMin() == null) return "unspecified";
        if (signals.getJdYoeMax() == null) return signals.getJdYoeMin().intValue() + "+";
        return signals.getJdYoeMin().intValue() + "–" + signals.getJdYoeMax().intValue();
    }

    private TimelineEvent event(TimelineEventType type, String heading, String detail,
                                 TimelineOutcome outcome, String sentiment) {
        return new TimelineEvent(null, type, heading, detail, outcome, sentiment);
    }
}
