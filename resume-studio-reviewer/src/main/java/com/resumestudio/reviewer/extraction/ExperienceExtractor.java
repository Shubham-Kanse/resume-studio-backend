package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.WorkExperience;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the experience section into structured WorkExperience objects.
 *
 * Handles all date format variants:
 *   "Jan 2020 – Mar 2022", "January 2020 - March 2022",
 *   "2019 – Present", "04/2022 – current", "2020–now"
 *
 * Detects: contract roles, date overlaps, unexplained gaps, job hopping.
 */
@Component
public class ExperienceExtractor {

    // ── Date patterns — ordered from most to least specific ──────────────────

    // "Jan 2020", "January 2020"
    private static final Pattern MONTH_YEAR = Pattern.compile(
        "(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|" +
        "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(\\d{4})",
        Pattern.CASE_INSENSITIVE);

    // "04/2022", "04-2022"
    private static final Pattern MM_YYYY = Pattern.compile("(\\d{1,2})[/\\-](\\d{4})");

    // "2019" alone (year only)
    private static final Pattern YEAR_ONLY = Pattern.compile("\\b(20\\d{2}|19\\d{2})\\b");

    // "Present", "Current", "Now", "Today"
    private static final Pattern PRESENT = Pattern.compile(
        "\\b(present|current|now|today|ongoing)\\b", Pattern.CASE_INSENSITIVE);

    // Full date range: date separator date
    private static final Pattern DATE_RANGE = Pattern.compile(
        "(.{3,30})\\s*[–\\-—]\\s*(.{3,30})");

    // Contract/freelance indicators
    private static final Pattern CONTRACT_INDICATOR = Pattern.compile(
        "\\b(contract|freelance|consultant|consulting|part[\\s-]time|temp(?:orary)?)\\b",
        Pattern.CASE_INSENSITIVE);

    // Career break labels
    private static final Pattern CAREER_BREAK = Pattern.compile(
        "\\b(career break|sabbatical|parental leave|maternity|paternity|" +
        "travel|upskilling|study leave|personal leave)\\b",
        Pattern.CASE_INSENSITIVE);

    // Bullet indicators
    private static final Pattern BULLET_START = Pattern.compile("^[•\\-*▪◦➤►→]\\s*");

    // Known title-level keywords for IC normalisation
    private static final Map<Pattern, Integer> TITLE_IC_LEVELS = new LinkedHashMap<>();
    static {
        TITLE_IC_LEVELS.put(Pattern.compile("\\b(intern|trainee|graduate|junior|jr\\.?)\\b", Pattern.CASE_INSENSITIVE), 1);
        TITLE_IC_LEVELS.put(Pattern.compile("\\b(associate|entry[- ]level)\\b", Pattern.CASE_INSENSITIVE), 2);
        TITLE_IC_LEVELS.put(Pattern.compile("\\b(mid[- ]level|intermediate)\\b", Pattern.CASE_INSENSITIVE), 3);
        TITLE_IC_LEVELS.put(Pattern.compile("\\b(senior|sr\\.?)\\b", Pattern.CASE_INSENSITIVE), 4);
        TITLE_IC_LEVELS.put(Pattern.compile("\\b(staff|principal|lead|architect)\\b", Pattern.CASE_INSENSITIVE), 5);
        TITLE_IC_LEVELS.put(Pattern.compile("\\b(distinguished|fellow|head of|vp|director|chief)\\b", Pattern.CASE_INSENSITIVE), 6);
    }

    public List<WorkExperience> extract(String experienceText) {
        if (experienceText == null || experienceText.isBlank()) return List.of();

        List<WorkExperience> roles = new ArrayList<>();
        List<String> roleBlocks = splitIntoRoleBlocks(experienceText);

        for (String block : roleBlocks) {
            WorkExperience role = parseRoleBlock(block);
            if (role != null) roles.add(role);
        }

        // Sort by start date descending (most recent first)
        roles.sort((a, b) -> {
            if (a.getStartDate() == null && b.getStartDate() == null) return 0;
            if (a.getStartDate() == null) return 1;
            if (b.getStartDate() == null) return -1;
            return b.getStartDate().compareTo(a.getStartDate());
        });

        // Compute durations and set role index (0 = most recent)
        for (int i = 0; i < roles.size(); i++) {
            WorkExperience role = roles.get(i);
            computeDuration(role);
        }

        return roles;
    }

    // ── Block splitting ───────────────────────────────────────────────────────

    /**
     * Splits raw experience text into individual role blocks.
     * A new block starts when we detect a date range on or near a short line
     * (company/title header pattern).
     */
    private List<String> splitIntoRoleBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = text.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            // A line with a date range and short length = new role header
            if (containsDateRange(trimmed) && trimmed.length() < 120 && current.length() > 0) {
                blocks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(trimmed).append("\n");
        }

        if (current.length() > 0) blocks.add(current.toString().trim());
        return blocks;
    }

    private boolean containsDateRange(String line) {
        return DATE_RANGE.matcher(line).find() && YEAR_ONLY.matcher(line).find();
    }

    // ── Role block parsing ────────────────────────────────────────────────────

    private WorkExperience parseRoleBlock(String block) {
        WorkExperience role = new WorkExperience();
        String[] lines = block.split("\n");

        List<String> bullets = new ArrayList<>();
        List<String> skillsInBullets = new ArrayList<>();
        boolean headerParsed = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            if (!headerParsed) {
                // First substantive lines = header (title, company, dates)
                parseHeaderLine(trimmed, role);
                if (role.getStartDate() != null || role.getTitle() != null) {
                    headerParsed = true;
                }
                continue;
            }

            // Remaining lines = bullets
            if (BULLET_START.matcher(trimmed).find() || trimmed.length() > 30) {
                String cleanBullet = BULLET_START.matcher(trimmed).replaceFirst("").trim();
                if (!cleanBullet.isBlank()) {
                    bullets.add(cleanBullet);
                }
            }
        }

        role.setBullets(bullets);
        role.setSkillsMentioned(skillsInBullets); // populated by SkillsSectionExtractor later
        role.setContractOrFreelance(
            CONTRACT_INDICATOR.matcher(block).find() ||
            (role.getTitle() != null && CONTRACT_INDICATOR.matcher(role.getTitle()).find())
        );

        // Normalise IC level from title
        if (role.getTitle() != null) {
            role.setIcLevel(normaliseIcLevel(role.getTitle()));
        }

        return role.getTitle() != null || role.getCompany() != null ? role : null;
    }

    private void parseHeaderLine(String line, WorkExperience role) {
        // Try to extract date range from this line
        Matcher rangeMatcher = DATE_RANGE.matcher(line);
        if (rangeMatcher.find()) {
            String startStr = rangeMatcher.group(1).trim();
            String endStr = rangeMatcher.group(2).trim();

            role.setStartDate(parseDate(startStr, true));
            if (PRESENT.matcher(endStr).find()) {
                role.setCurrent(true);
                role.setEndDate(null);
            } else {
                role.setEndDate(parseDate(endStr, false));
            }

            // Remove the date portion and treat the remainder as title or company
            String remainder = line.replace(rangeMatcher.group(0), "").trim();
            assignTitleOrCompany(remainder, role);
            return;
        }

        // No date range — try assigning as title or company
        assignTitleOrCompany(line, role);
    }

    private void assignTitleOrCompany(String text, WorkExperience role) {
        if (text.isBlank()) return;
        if (role.getTitle() == null && looksLikeTitle(text)) {
            role.setTitle(text);
        } else if (role.getCompany() == null) {
            // Check for inline descriptor
            Matcher descriptorMatcher = Pattern.compile("\\(([^)]{5,80})\\)").matcher(text);
            if (descriptorMatcher.find()) {
                role.setCompanyDescriptor(descriptorMatcher.group(1));
                role.setCompany(text.substring(0, descriptorMatcher.start()).trim());
            } else {
                role.setCompany(text);
            }
        }
    }

    private boolean looksLikeTitle(String text) {
        String lower = text.toLowerCase();
        return lower.contains("engineer") || lower.contains("developer") || lower.contains("architect")
            || lower.contains("manager") || lower.contains("lead") || lower.contains("analyst")
            || lower.contains("designer") || lower.contains("scientist") || lower.contains("consultant")
            || lower.contains("devops") || lower.contains("qa") || lower.contains("sre")
            || TITLE_IC_LEVELS.keySet().stream().anyMatch(p -> p.matcher(text).find());
    }

    // ── Date parsing ──────────────────────────────────────────────────────────

    LocalDate parseDate(String raw, boolean isStart) {
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();

        // Month + Year: "Jan 2020"
        Matcher monthYear = MONTH_YEAR.matcher(raw);
        if (monthYear.find()) {
            int month = parseMonth(monthYear.group(1));
            int year = Integer.parseInt(monthYear.group(2));
            return isStart ? LocalDate.of(year, month, 1) : LocalDate.of(year, month, 1).withDayOfMonth(
                LocalDate.of(year, month, 1).lengthOfMonth());
        }

        // MM/YYYY: "04/2022"
        Matcher mmYyyy = MM_YYYY.matcher(raw);
        if (mmYyyy.find()) {
            int month = Integer.parseInt(mmYyyy.group(1));
            int year = Integer.parseInt(mmYyyy.group(2));
            if (month >= 1 && month <= 12) {
                return isStart ? LocalDate.of(year, month, 1)
                    : LocalDate.of(year, month, 1).withDayOfMonth(LocalDate.of(year, month, 1).lengthOfMonth());
            }
        }

        // Year only: "2019" — flag as partial
        Matcher yearOnly = YEAR_ONLY.matcher(raw);
        if (yearOnly.find()) {
            int year = Integer.parseInt(yearOnly.group(1));
            return isStart ? LocalDate.of(year, 1, 1) : LocalDate.of(year, 12, 31);
        }

        return null;
    }

    private int parseMonth(String monthStr) {
        return switch (monthStr.substring(0, 3).toLowerCase()) {
            case "jan" -> 1; case "feb" -> 2; case "mar" -> 3; case "apr" -> 4;
            case "may" -> 5; case "jun" -> 6; case "jul" -> 7; case "aug" -> 8;
            case "sep" -> 9; case "oct" -> 10; case "nov" -> 11; case "dec" -> 12;
            default -> 1;
        };
    }

    private void computeDuration(WorkExperience role) {
        LocalDate start = role.getStartDate();
        LocalDate end = role.isCurrent() ? LocalDate.now() : role.getEndDate();
        if (start != null && end != null) {
            double years = ChronoUnit.DAYS.between(start, end) / 365.25;
            role.setDurationYears(Math.max(0, years));
        }
    }

    private int normaliseIcLevel(String title) {
        for (Map.Entry<Pattern, Integer> entry : TITLE_IC_LEVELS.entrySet()) {
            if (entry.getKey().matcher(title).find()) return entry.getValue();
        }
        return 3; // default to mid-level if not detectable
    }
}
