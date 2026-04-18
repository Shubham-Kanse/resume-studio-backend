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
        if (signals == null || verdict == null) {
            log.warn("Null signals or verdict provided to build()");
            return List.of();
        }
        
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

        // ── 9. Verdict ────────────────────────────────────────────────────
        events.add(buildVerdictEvent(verdict, continueReading));

        assignTimestamps(events);
        return events;
    }

    // ── Event builders ────────────────────────────────────────────────────────

    private TimelineEvent buildTitleEvent(ResumeSignals signals) {
        String cTitle = signals.getCandidateTitle() != null ? "\"" + signals.getCandidateTitle() + "\"" : "No title found";
        String jTitle = signals.getJdTitle() != null ? "\"" + signals.getJdTitle() + "\"" : "the role";
        
        TitleMatch titleMatch = signals.getTitleMatch();
        if (titleMatch == null) {
            return event(TimelineEventType.TITLE,
                "No title visible",
                "Recruiter can't tell what this person does without reading the full document.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        }
        
        return switch (titleMatch) {
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
            // Name the missing skills if available
            List<String> missing = signals.getMissingMustHavesList();
            String detail = (missing != null && !missing.isEmpty())
                ? "Missing: " + String.join(", ", missing.subList(0, Math.min(3, missing.size())))
                  + (missing.size() > 3 ? " and " + (missing.size() - 3) + " more." : ".")
                  + " These were listed as requirements. Most recruiters stop here."
                : "The primary technical requirements for this role don't appear on the resume. Most recruiters stop here.";
            return event(TimelineEventType.SKILLS,
                "Required skills not found",
                detail,
                TimelineOutcome.HARD_STOP, "NEGATIVE");
        }
        if (signals.isHasBuriedMustHaves() && !signals.isAllMustHavesVisible()) {
            return event(TimelineEventType.SKILLS,
                "Key skills buried — not visible at a glance",
                "Required skills exist but only appear inside job bullets. In a 7-second scan, a recruiter's eye doesn't reach there.",
                TimelineOutcome.NEAR_EXIT, "NEGATIVE");
        }
        if (signals.getSkillsFormat() != null && signals.getSkillsFormat() == SkillsFormat.PROSE) {
            return event(TimelineEventType.SKILLS,
                "Skills written as prose — hard to scan",
                "Skills in paragraph form require reading rather than scanning. A dedicated skills section would clear this in under a second.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        }
        if (signals.isAllMustHavesVisible()) {
            return event(TimelineEventType.SKILLS,
                "Required skills visible immediately",
                "Must-have skills found in the skills section. No hunting required — this is what a recruiter wants.",
                TimelineOutcome.POSITIVE, "POSITIVE");
        }
        return event(TimelineEventType.SKILLS,
            "Skills found — minor friction",
            "Required skills are present but took a moment to locate. Moving them higher would help.",
            TimelineOutcome.CAUTION, "NEUTRAL");
    }

    private TimelineEvent buildYoeEvent(ResumeSignals signals) {
        if (signals.isChronologyUnreliable()) {
            String detail = !signals.getChronologyDescriptions().isEmpty()
                ? signals.getChronologyDescriptions().get(0)
                : "The chronology is too inconsistent to trust.";
            return event(TimelineEventType.YOE,
                "Chronology is hard to trust",
                detail,
                TimelineOutcome.NEAR_EXIT, "NEGATIVE");
        }

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

        YoeFit yoeFit = signals.getYoeFit();
        if (yoeFit == null) {
            return event(TimelineEventType.YOE,
                "Experience — could not determine",
                "No clear experience information available to assess fit.",
                TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
        }

        return switch (yoeFit) {
            case IN_RANGE -> {
                String clarity = signals.getYoeState() != null && signals.getYoeState() == YoeState.EXPLICIT
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
            case CANNOT_DETERMINE -> {
                // For fresher roles (0-2 YOE required), no experience is expected — neutral
                boolean isFresherRole = signals.getJdYoeMin() == null
                    || (signals.getJdYoeMin() <= 0 && (signals.getJdYoeMax() == null || signals.getJdYoeMax() <= 2));
                if (isFresherRole) {
                    yield event(TimelineEventType.YOE,
                        "Entry-level role — experience not required",
                        "This role targets early-career candidates. Experience level is not a barrier.",
                        TimelineOutcome.NEUTRAL, "NEUTRAL");
                }
                yield event(TimelineEventType.YOE,
                    "Experience — hard to verify",
                    signals.getYoeState() != null && signals.getYoeState() == YoeState.VAGUE
                        ? "Vague phrasing forces the recruiter to calculate manually."
                        : "Could not determine total experience from the dates provided.",
                    TimelineOutcome.FRICTION_FLAG, "NEGATIVE");
            }
        };
    }

    private TimelineEvent buildLocationEvent(ResumeSignals signals) {
        String jdLoc = signals.getJdLocation();
        String candidateLoc = signals.getCandidateLocation();

        if (candidateLoc != null && !candidateLoc.isBlank()) {
            boolean likelyMatch = candidateLoc.toLowerCase().contains(jdLoc.toLowerCase())
                || jdLoc.toLowerCase().contains(candidateLoc.toLowerCase());
            if (likelyMatch) {
                return event(TimelineEventType.LOCATION,
                    "Location — matches requirement",
                    "Candidate is based in " + candidateLoc + ". Aligns with the " + jdLoc + " requirement.",
                    TimelineOutcome.POSITIVE, "POSITIVE");
            }
            return event(TimelineEventType.LOCATION,
                "Location — potential mismatch",
                "Role requires " + jdLoc + " but candidate is listed as " + candidateLoc + ". May require relocation or visa.",
                TimelineOutcome.CAUTION, "NEUTRAL");
        }
        return event(TimelineEventType.LOCATION,
            "Location — not stated on resume",
            "Role requires " + jdLoc + " but no location is visible on the resume. Recruiter will need to verify.",
            TimelineOutcome.MINOR_FLAG, "NEUTRAL");
    }

    private TimelineEvent buildCompanyEvent(ResumeSignals signals) {
        String company = signals.getCurrentCompanyName() != null ? signals.getCurrentCompanyName() : "Current company";
        
        CompanyTier tier = signals.getCurrentCompanyTier();
        if (tier == null) {
            tier = CompanyTier.UNKNOWN;
        }
        
        return switch (tier) {
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
            return event(TimelineEventType.BULLET,
                "Bullets show impact and scale",
                "Strong action verbs and quantified results. Recruiter can see the scope of work at a glance.",
                TimelineOutcome.POSITIVE, "POSITIVE");
        }
        if (ivr < 0.3 || md < 0.1) {
            return event(TimelineEventType.BULLET,
                "Bullets lack impact and numbers",
                "Passive language and no quantified results make it hard to gauge the scale of work.",
                TimelineOutcome.MINOR_FLAG, "NEGATIVE");
        }
        return event(TimelineEventType.BULLET,
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
                "Added to interview shortlist",
                "Passed all first-pass filters. This resume earns a full read and a call.",
                TimelineOutcome.POSITIVE, "POSITIVE");
            case POSSIBLE_FIT -> event(TimelineEventType.VERDICT,
                "Held in the maybe pile",
                "Not rejected outright. Will be revisited if the strong pile is thin — but not prioritised.",
                TimelineOutcome.NEUTRAL, "NEUTRAL");
            case NO_FIT -> event(TimelineEventType.VERDICT,
                "Rejected — did not pass first filter",
                "A hard stop was hit before the recruiter reached the experience section.",
                TimelineOutcome.HARD_STOP, "NEGATIVE");
            case WEAK_FIT -> fullRead
                ? event(TimelineEventType.VERDICT,
                    "Rejected after full scan",
                    "Resume was read in full but no strong signal emerged. Passed on.",
                    TimelineOutcome.HARD_STOP, "NEGATIVE")
                : event(TimelineEventType.VERDICT,
                    "Rejected — scan ended early",
                    "A decisive negative signal caused the recruiter to stop before finishing the page.",
                    TimelineOutcome.HARD_STOP, "NEGATIVE");
        };
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void assignTimestamps(List<TimelineEvent> events) {
        // Based on TheLadders 2018 eye-tracking study: avg 7.4s, F-pattern scan.
        // Title/name: ~1s, current role+company: ~2s, skills: ~2s, rest: ~1s each.
        int elapsed = 0;
        for (TimelineEvent event : events) {
            event.setTimeLabel(elapsed + "s");
            elapsed += switch (event.getType()) {
                case TITLE   -> 1;
                case SUMMARY -> 1;
                case SKILLS  -> 2;
                case YOE     -> 2;
                case COMPANY -> 1;
                case BULLET  -> 1;
                case LOCATION -> 1;
                case FORMAT  -> 1;
                case VERDICT -> 0;
                default      -> 1;
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
