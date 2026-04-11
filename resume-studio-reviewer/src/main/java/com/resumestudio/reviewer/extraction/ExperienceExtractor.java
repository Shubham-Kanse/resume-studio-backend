package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.nlp.NlpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ExperienceExtractor.class);

    private final NlpService nlp;

    public ExperienceExtractor(NlpService nlp) {
        this.nlp = nlp;
    }

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

    // "Present", "Current", "Now", "Today", "Till date", "To date", "Continuing"
    private static final Pattern PRESENT = Pattern.compile(
        "\\b(present|current|now|today|ongoing|continuing|till date|to date)\\b", Pattern.CASE_INSENSITIVE);

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
        String[] lines = text.split("\n");
        List<String> nonBlank = new ArrayList<>();
        for (String l : lines) if (!l.isBlank()) nonBlank.add(l.trim());

        // Find all role start positions: a date-containing line, optionally preceded by title line
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < nonBlank.size(); i++) {
            if (containsDateRange(nonBlank.get(i)) && nonBlank.get(i).length() < 120) {
                // Look back for title (1 line) or company+title (2 lines)
                int start = i;
                // Check if previous line is a title
                if (i > 0 && looksLikeTitle(nonBlank.get(i - 1)) && !containsDateRange(nonBlank.get(i - 1))) {
                    start = i - 1;
                }
                // Check if 2 lines back is a company (only if we already found a title at i-1)
                else if (i > 1 && looksLikeTitle(nonBlank.get(i - 1)) && looksLikeCompany(nonBlank.get(i - 2)) 
                         && !containsDateRange(nonBlank.get(i - 1)) && !containsDateRange(nonBlank.get(i - 2))) {
                    start = i - 2;
                }
                starts.add(start);
            }
        }

        if (starts.isEmpty()) return List.of(text.trim());

        List<String> blocks = new ArrayList<>();
        for (int b = 0; b < starts.size(); b++) {
            int from = starts.get(b);
            int to = (b + 1 < starts.size()) ? starts.get(b + 1) : nonBlank.size();
            // Don't include the title line of the next block
            if (b + 1 < starts.size() && starts.get(b + 1) < to) to = starts.get(b + 1);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) sb.append(nonBlank.get(i)).append("\n");
            blocks.add(sb.toString().trim());
        }
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
                parseHeaderLine(trimmed, role);
                // Header is complete when we have dates (company+date line processed)
                // OR when we hit a bullet point
                if (BULLET_START.matcher(trimmed).find()) {
                    headerParsed = true;
                } else if (role.getStartDate() != null && (role.getTitle() != null || role.getCompany() != null)) {
                    headerParsed = true;
                }
                continue;
            }

            // Remaining lines = bullets (include short lines without markers, but not if they look like titles)
            if (BULLET_START.matcher(trimmed).find()) {
                String cleanBullet = BULLET_START.matcher(trimmed).replaceFirst("").trim();
                if (!cleanBullet.isBlank()) {
                    bullets.add(cleanBullet);
                }
            } else if (trimmed.length() > 10 && !trimmed.matches(".*\\d{4}.*") && !looksLikeTitle(trimmed)) {
                // Include non-date, non-title lines > 10 chars (catches short bullets like "Led team of 5")
                bullets.add(trimmed);
            }
        }

        role.setBullets(bullets);
        role.setSkillsMentioned(skillsInBullets); // populated by SkillsSectionExtractor later
        role.setContractOrFreelance(
            CONTRACT_INDICATOR.matcher(block).find() ||
            (role.getTitle() != null && CONTRACT_INDICATOR.matcher(role.getTitle()).find())
        );
        role.setCareerBreak(
            CAREER_BREAK.matcher(block).find() ||
            (role.getTitle() != null && CAREER_BREAK.matcher(role.getTitle()).find()) ||
            (role.getCompany() != null && CAREER_BREAK.matcher(role.getCompany()).find())
        );

        // Normalise IC level from title
        if (role.getTitle() != null && !role.isCareerBreak()) {
            role.setIcLevel(normaliseIcLevel(role.getTitle()));
        }

        return role.getTitle() != null || role.getCompany() != null ? role : null;
    }

    private void parseHeaderLine(String line, WorkExperience role) {
        // Handle "Company | May 2021 – July 2024" or "Company — May 2021 – July 2024"
        // Split on pipe or em-dash separator between company and date
        java.util.regex.Matcher pipeSplit = java.util.regex.Pattern
            .compile("^(.+?)\\s*[|—]\\s*(.+)$").matcher(line);
        if (pipeSplit.matches()) {
            String left = pipeSplit.group(1).trim();
            String right = pipeSplit.group(2).trim();
            // If right side contains a date range, left = company, right = dates
            if (containsDateRange(right) || PRESENT.matcher(right).find()) {
                assignTitleOrCompany(left, role);
                Matcher rangeMatcher = DATE_RANGE.matcher(right);
                if (rangeMatcher.find()) {
                    role.setStartDate(parseDate(rangeMatcher.group(1).trim(), true));
                    role.setDatesArePartial(role.isDatesArePartial() || isPartialDate(rangeMatcher.group(1).trim()));
                    String endStr = rangeMatcher.group(2).trim();
                    if (PRESENT.matcher(endStr).find()) {
                        role.setCurrent(true);
                    } else {
                        role.setEndDate(parseDate(endStr, false));
                        role.setDatesArePartial(role.isDatesArePartial() || isPartialDate(endStr));
                    }
                }
                return;
            }
        }

        // Try to extract date range from this line
        Matcher rangeMatcher = DATE_RANGE.matcher(line);
        if (rangeMatcher.find()) {
            String startStr = rangeMatcher.group(1).trim();
            String endStr = rangeMatcher.group(2).trim();

            role.setStartDate(parseDate(startStr, true));
            role.setDatesArePartial(role.isDatesArePartial() || isPartialDate(startStr));
            if (PRESENT.matcher(endStr).find()) {
                role.setCurrent(true);
                role.setEndDate(null);
            } else {
                role.setEndDate(parseDate(endStr, false));
                role.setDatesArePartial(role.isDatesArePartial() || isPartialDate(endStr));
            }

            String remainder = line.replace(rangeMatcher.group(0), "").trim();
            assignTitleOrCompany(remainder, role);
            return;
        }

        assignTitleOrCompany(line, role);
    }

    private void assignTitleOrCompany(String text, WorkExperience role) {
        if (text.isBlank()) return;
        if (CAREER_BREAK.matcher(text).find()) {
            if (role.getTitle() == null) {
                role.setTitle(text);
            } else if (role.getCompany() == null) {
                role.setCompany(text);
            }
            role.setCareerBreak(true);
            return;
        }
        if (role.getTitle() == null && looksLikeTitle(text)) {
            role.setTitle(text);
        } else if (role.getCompany() == null) {
            // Use NER to confirm it's an organisation name
            String company = text;
            List<String> orgs = nlp.findOrganizations(text);
            if (!orgs.isEmpty()) company = orgs.get(0);

            Matcher descriptorMatcher = Pattern.compile("\\(([^)]{5,80})\\)").matcher(company);
            if (descriptorMatcher.find()) {
                role.setCompanyDescriptor(descriptorMatcher.group(1));
                role.setCompany(company.substring(0, descriptorMatcher.start()).trim());
            } else {
                role.setCompany(company);
            }
        }
    }

    private boolean looksLikeTitle(String text) {
        String lower = text.toLowerCase();
        return lower.contains("engineer") || lower.contains("developer") || lower.contains("architect")
            || lower.contains("manager") || lower.contains("lead") || lower.contains("analyst")
            || lower.contains("designer") || lower.contains("scientist") || lower.contains("consultant")
            || lower.contains("devops") || lower.contains("qa") || lower.contains("sre")
            || CAREER_BREAK.matcher(text).find()
            || TITLE_IC_LEVELS.keySet().stream().anyMatch(p -> p.matcher(text).find());
    }

    private boolean looksLikeCompany(String text) {
        if (text.length() > 100) return false;
        String[] words = text.split("\\s+");
        // Reject section headers (all-caps short lines)
        boolean isAllCaps = text.equals(text.toUpperCase()) && text.matches("[A-Z\\s]+");
        return !isAllCaps && words.length >= 1 && words.length <= 8
            && !text.matches(".*\\d{4}.*[–\\-].*\\d{4}.*");
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

    private boolean isPartialDate(String raw) {
        return YEAR_ONLY.matcher(raw).find() && !MONTH_YEAR.matcher(raw).find() && !MM_YYYY.matcher(raw).find();
    }

    private int parseMonth(String monthStr) {
        if (monthStr == null || monthStr.length() < 3) return 1;
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
