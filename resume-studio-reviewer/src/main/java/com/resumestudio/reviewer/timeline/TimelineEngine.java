package com.resumestudio.reviewer.timeline;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.TimelineEvent;
import com.resumestudio.reviewer.model.enums.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Recruiter simulation timeline — ordered to mirror actual 10-second eye movement:
 *
 *  1. Title match          — first filter, always
 *  2. Summary              — only if present; absent = subtle feedback note, not timeline event
 *  3. Skills               — must-haves visible?
 *  4. Experience / YOE     — years + recent role tenure
 *  5. Company              — credibility signal (only if notable)
 *  6. Bullet quality       — impact, numbers, scale (only if strong or weak)
 *  7. Layout clarity       — only if genuinely hard to scan
 *  8. Verdict              — always last
 *
 * Filename removed from timeline — only surfaced in fixes if vague/generic.
 * Location/visa only shown if JD specifies a strict location requirement.
 */
@Component
public class TimelineEngine {

    private static final Logger log = LoggerFactory.getLogger(TimelineEngine.class);

    public List<TimelineEvent> build(ResumeSignals signals, Verdict verdict) {
        List<TimelineEvent> events = new ArrayList<>();
        boolean continueReading = true;

        // ── 1. Title ──────────────────────────────────────────────────────
        TimelineEvent titleEvent = buildTitleEvent(signals);
        events.add(titleEvent);
        if (titleEvent.getOutcome() == TimelineOutcome.HARD_STOP) continueReading = false;

        // ── 2. Summary (only if present — absence handled in fixes) ───────
        if (continueReading && signals.isSummaryPresent()) {
            events.add(buildSummaryEvent(signals));
        }

        // ── 3. Skills ─────────────────────────────────────────────────────
        if (continueReading) {
            TimelineEvent skillsEvent = buildSkillsEvent(signals);
            events.add(skillsEvent);
            if (skillsEvent.getOutcome() == TimelineOutcome.HARD_STOP) continueReading = false;
        }

        // ── 4. Experience / YOE ───────────────────────────────────────────
        if (continueReading) {
            TimelineEvent yoeEvent = buildYoeEvent(signals);
            events.add(yoeEvent);
            // Stop reading if experience gap is significant — can't be compensated
            if (yoeEvent.getOutcome() == TimelineOutcome.NEAR_EXIT) {
                continueReading = false;
            }
        }

        // ── 5. Company (only if notable) ──────────────────────────────────
        if (continueReading && isCompanyNotable(signals)) {
            events.add(buildCompanyEvent(signals));
        }

        // ── 6. Bullet quality (only if notably strong or weak) ────────────
        if (continueReading && hasBulletQualitySignal(signals)) {
            events.add(buildBulletQualityEvent(signals));
        }

        // ── 7. Location/Visa (only if JD has strict location requirement) ──
        if (continueReading && signals.isJdLocationStrict() && signals.getJdLocation() != null) {
            events.add(buildLocationEvent(signals));
        }

        // ── 8. Layout clarity (only if genuinely hard to scan) ────────────
        if (continueReading && hasHighFormatFriction(signals)) {
            events.add(buildFormatEvent(signals));
        }

        // ── 8. Verdict ────────────────────────────────────────────────────
        events.add(buildVerdictEvent(verdict, continueReading));

        assignTimestamps(events);
        return events;
    }

    // ── Event builders ────────────────────────────────────────────────────────

    private TimelineEvent buildTitleEvent(ResumeSignals signals) {
        String cTitle = signals.getCandidateTitle() != null ? "\"" + signals.getCandidateTitle() + "\"" : "No title found";
        String jTitle = signals.getJdTitle() != null ? "\"" + signals.getJdTitle() + "\"" : "the role";
        return switch (signals.getTitleMatch()) {
            case EXACT -> event(TimelineEventType.TITLE,
                "Title matched exactly — kept reading",
                cTitle + " directly matches the role. First filter cleared instantly.",
                TimelineOutcome.KEPT_READING, "POSITIVE");
            case ADJACENT -> event(TimelineEventType.TITLE,
                "Title close enough — kept reading",
                cTitle + " is adjacent to " + jTitle + ". Close enough to pass, not close enough to be obvious.",
                TimelineOutcome.KEPT_READING_WITH_HESITATION, "POSITIVE");
            case RELATED -> event(TimelineEventType.TITLE,
                "Title — related but different domain",
                cTitle + " applying for " + jTitle + ". Recruiter paused — unclear if the required depth is there.",
                TimelineOutcome.CAUTION, "NEUTRAL");
            case MISS -> event(TimelineEventType.TITLE,
                "Title mismatch — nearly stopped here",
                cTitle + " doesn't immediately read as " + jTitle + ". Skills section must compensate strongly.",
                TimelineOutcome.HARD_STOP, "NEGATIVE");
            default -> event(TimelineEventType.TITLE,
                "No title visible",
                "Recruiter can't tell what this person does without reading the full document.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        };
    }

    private TimelineEvent buildSummaryEvent(ResumeSignals signals) {
        if (signals.isSummaryMentionsYoe() && signals.isSummaryMentionsSkills() && !signals.isSummaryIsGeneric()) {
            return event(TimelineEventType.SUMMARY,
                "Summary gave instant context",
                "Title, experience, and core skills stated upfront. Recruiter oriented in under 2 seconds.",
                TimelineOutcome.POSITIVE, "POSITIVE");
        }
        if (signals.isSummaryIsGeneric()) {
            return event(TimelineEventType.SUMMARY,
                "Summary present — but generic",
                "Soft skill language doesn't tell a technical recruiter anything about fit.",
                TimelineOutcome.MINOR_FLAG, "NEGATIVE");
        }
        return event(TimelineEventType.SUMMARY,
            "Summary present — partial context",
            "A summary exists but doesn't surface experience or core skills clearly.",
            TimelineOutcome.NEUTRAL, "NEUTRAL");
    }

    private TimelineEvent buildSkillsEvent(ResumeSignals signals) {
        if (signals.isHasMissingMustHaves()) {
            return event(TimelineEventType.SKILLS,
                "Must-have skills not found anywhere",
                "The primary technical requirements for this role don't appear on the resume. Typically a decisive filter.",
                TimelineOutcome.HARD_STOP, "NEGATIVE");
        }
        if (signals.isHasBuriedMustHaves() && !signals.isAllMustHavesVisible()) {
            return event(TimelineEventType.SKILLS,
                "Key skills buried — not visible at a glance",
                "Required skills exist but only in old job bullets. A recruiter's eye doesn't reach there in 10 seconds.",
                TimelineOutcome.NEAR_EXIT, "NEGATIVE");
        }
        if (signals.getSkillsFormat() == SkillsFormat.PROSE) {
            return event(TimelineEventType.SKILLS,
                "Skills written as prose — hard to scan",
                "Skills in paragraph form require reading rather than scanning. High friction.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        }
        if (signals.isAllMustHavesVisible()) {
            return event(TimelineEventType.SKILLS,
                "Required skills found immediately",
                "Must-have skills visible in under 1 second. Skills section is doing its job.",
                TimelineOutcome.POSITIVE, "POSITIVE");
        }
        return event(TimelineEventType.SKILLS,
            "Skills found — minor friction",
            "Required skills are present but took a moment to locate.",
            TimelineOutcome.CAUTION, "NEUTRAL");
    }

    private TimelineEvent buildYoeEvent(ResumeSignals signals) {
        String yoeStr = signals.getCalculatedYoe() != null
            ? String.format("%.1f", signals.getCalculatedYoe()).replaceAll("\\.0$", "") : "?";
        String jdRange = buildJdYoeRange(signals);

        // Add recent role context if multiple recent roles
        String roleNote = "";
        if (signals.getRecentRoleCount() > 1) {
            roleNote = " " + signals.getRecentRoleCount() + " roles in the last 3 years.";
        } else if (signals.getMostRecentRoleTitle() != null && signals.getMostRecentCompany() != null) {
            roleNote = " Most recent: " + signals.getMostRecentRoleTitle() + " at " + signals.getMostRecentCompany() + ".";
        }
        String hopNote = signals.isJobHopper() ? " Multiple short tenures noted." : "";

        return switch (signals.getYoeFit()) {
            case IN_RANGE -> {
                String clarity = signals.getYoeState() == YoeState.EXPLICIT
                    ? "Stated clearly — no calculation needed." : "Dates were clean enough to calculate.";
                yield event(TimelineEventType.YOE,
                    yoeStr + " years experience — in range",
                    "Role asks for " + jdRange + ". " + clarity + roleNote + hopNote,
                    TimelineOutcome.KEPT_READING, "POSITIVE");
            }
            case UNDER_RANGE_MINOR -> event(TimelineEventType.YOE,
                "Experience slightly under requirement",
                yoeStr + " years against " + jdRange + " requirement. Close enough that strong skills could compensate." + roleNote + hopNote,
                TimelineOutcome.CAUTION, "NEUTRAL");
            case UNDER_RANGE_SIGNIFICANT -> event(TimelineEventType.YOE,
                "Experience gap — significant",
                yoeStr + " years against " + jdRange + " requirement. A recruiter paused here." + roleNote + hopNote,
                TimelineOutcome.NEAR_EXIT, "NEGATIVE");
            case OVER_RANGE -> event(TimelineEventType.YOE,
                "Overqualified signal",
                yoeStr + " years for a role asking " + jdRange + ". Recruiter may question fit.",
                TimelineOutcome.CAUTION, "NEUTRAL");
            case CANNOT_DETERMINE -> event(TimelineEventType.YOE,
                "Experience — hard to verify",
                signals.getYoeState() == YoeState.VAGUE
                    ? "Vague phrasing forces the recruiter to calculate manually."
                    : "Could not determine total experience from the dates provided.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        };
    }

    private TimelineEvent buildLocationEvent(ResumeSignals signals) {
        String jdLoc = signals.getJdLocation();
        String candidateLoc = signals.getCandidateLocation();

        if (candidateLoc != null && !candidateLoc.isBlank()) {
            boolean likelyMatch = candidateLoc.toLowerCase().contains(jdLoc.toLowerCase())
                || jdLoc.toLowerCase().contains(candidateLoc.toLowerCase());
            if (likelyMatch) {
                return event(TimelineEventType.FORMAT,
                    "Location — matches requirement",
                    "Candidate is based in " + candidateLoc + ". Aligns with the " + jdLoc + " requirement.",
                    TimelineOutcome.POSITIVE, "POSITIVE");
            }
            return event(TimelineEventType.FORMAT,
                "Location — potential mismatch",
                "Role requires " + jdLoc + " but candidate is listed as " + candidateLoc + ". May require relocation or visa.",
                TimelineOutcome.CAUTION, "NEUTRAL");
        }
        return event(TimelineEventType.FORMAT,
            "Location — not stated on resume",
            "Role requires " + jdLoc + " but no location is visible on the resume. Recruiter will need to verify.",
            TimelineOutcome.MINOR_FLAG, "NEUTRAL");
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
                company + " is known in the industry. Clear context.",
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

    private TimelineEvent buildBulletQualityEvent(ResumeSignals signals) {
        double ivr = signals.getImpactVerbRatio();
        double md = signals.getMetricDensity();

        if (ivr >= 0.7 && md >= 0.4) {
            return event(TimelineEventType.SKILLS, // reuse SKILLS type for bullet quality
                "Bullets show impact and scale",
                "Strong action verbs and quantified results. Recruiter can see the scope of work at a glance.",
                TimelineOutcome.POSITIVE, "POSITIVE");
        }
        if (ivr < 0.3 || md < 0.1) {
            return event(TimelineEventType.SKILLS,
                "Bullets lack impact and numbers",
                "Passive language and no quantified results make it hard to gauge the scale of work.",
                TimelineOutcome.MINOR_FLAG, "NEGATIVE");
        }
        return event(TimelineEventType.SKILLS,
            "Bullets — some impact shown",
            "Some strong results visible but more quantification would strengthen the case.",
            TimelineOutcome.NEUTRAL, "NEUTRAL");
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
            "Layout — minor friction",
            "Small formatting issues that add friction to a quick scan.",
            TimelineOutcome.MINOR_FLAG, "NEGATIVE");
    }

    private TimelineEvent buildVerdictEvent(Verdict verdict, boolean fullRead) {
        return switch (verdict) {
            case STRONG_FIT -> event(TimelineEventType.VERDICT,
                "Moved to interview pile",
                "Strong enough signal on first pass. This resume earns a full read.",
                TimelineOutcome.POSITIVE, "POSITIVE");
            case POSSIBLE_FIT -> event(TimelineEventType.VERDICT,
                "Moved to the maybe pile",
                "Not rejected. Not prioritised. Will be revisited if the strong pile runs thin.",
                TimelineOutcome.NEUTRAL, "NEUTRAL");
            case WEAK_FIT -> fullRead
                ? event(TimelineEventType.VERDICT,
                    "Passed on after full scan",
                    "Resume was reviewed but no strong signal emerged. Moved to the reject pile.",
                    TimelineOutcome.HARD_STOP, "NEGATIVE")
                : event(TimelineEventType.VERDICT,
                    "Review stopped early",
                    "A decisive negative signal ended the review before a full scan.",
                    TimelineOutcome.HARD_STOP, "NEGATIVE");
        };
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void assignTimestamps(List<TimelineEvent> events) {
        // Weights reflect cognitive time per event type
        int elapsed = 0;
        for (int i = 0; i < events.size(); i++) {
            events.get(i).setTimeLabel(elapsed + "s");
            TimelineEventType type = events.get(i).getType();
            elapsed += switch (type) {
                case TITLE -> 1;
                case SUMMARY -> 1;
                case SKILLS -> 2;
                case YOE -> 2;
                case COMPANY -> 1;
                case FORMAT -> 1;
                case VERDICT -> 1;
                default -> 1;
            };
        }
    }

    private boolean hasHighFormatFriction(ResumeSignals signals) {
        return signals.isFormatWallOfText() || signals.isFormatTooManyPages() || signals.isFormatHasPhoto();
    }

    private boolean hasBulletQualitySignal(ResumeSignals signals) {
        double ivr = signals.getImpactVerbRatio();
        double md = signals.getMetricDensity();
        // Only show if we actually have bullet data (ivr > 0 means bullets were analysed)
        // and the signal is notably strong or notably weak
        if (ivr == 0 && md == 0) return false; // no bullet data
        return (ivr >= 0.7 && md >= 0.4) || ivr < 0.3 || md < 0.1;
    }

    private boolean isCompanyNotable(ResumeSignals signals) {
        CompanyTier tier = signals.getCurrentCompanyTier();
        return tier == CompanyTier.FAANG || tier == CompanyTier.TIER_1
            || tier == CompanyTier.SCALE_UP || tier == CompanyTier.DESCRIBED
            || signals.isCompanyTierImproving();
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
