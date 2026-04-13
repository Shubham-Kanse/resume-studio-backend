package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.Education;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the education section into coarse-grained Education entries.
 *
 * For chronology and gap analysis we mainly need start/end years, so this
 * extractor prefers robust year capture over aggressive semantic parsing.
 */
@Component
public class EducationExtractor {

    private final EducationPrestigeService prestigeService;

    public EducationExtractor(EducationPrestigeService prestigeService) {
        this.prestigeService = prestigeService;
    }

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
    private static final Pattern INSTITUTION_HINT = Pattern.compile(
        "\\b(university|college|institute|school|academy|polytechnic)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DEGREE_HINT = Pattern.compile(
        "\\b(b\\.?tech|b\\.?e\\.?|bachelor|master|m\\.?tech|m\\.?s\\.?|mba|phd|doctorate|diploma|certificate|bsc|msc|bs|ba|ma)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPECTED_HINT = Pattern.compile(
        "\\b(expected|present|current|ongoing|pursuing)\\b",
        Pattern.CASE_INSENSITIVE);

    public List<Education> extract(String educationText) {
        if (educationText == null || educationText.isBlank()) {
            return List.of();
        }

        List<String> blocks = splitIntoEducationBlocks(educationText);
        List<Education> entries = new ArrayList<>();

        for (String block : blocks) {
            Education parsed = parseBlock(block);
            if (parsed != null) {
                entries.add(parsed);
            }
        }

        return entries;
    }

    private List<String> splitIntoEducationBlocks(String text) {
        String[] rawLines = text.split("\n");
        List<String> lines = new ArrayList<>();
        for (String raw : rawLines) {
            String trimmed = raw.trim();
            if (!trimmed.isBlank()) {
                lines.add(trimmed);
            }
        }

        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean currentHasYear = false;

        for (String line : lines) {
            boolean lineHasYear = YEAR_PATTERN.matcher(line).find();
            if (current.length() > 0 && currentHasYear && lineHasYear) {
                blocks.add(current.toString().trim());
                current.setLength(0);
                currentHasYear = false;
            }

            if (current.length() > 0) {
                current.append("\n");
            }
            current.append(line);
            currentHasYear = currentHasYear || lineHasYear;
        }

        if (current.length() > 0) {
            blocks.add(current.toString().trim());
        }

        return blocks;
    }

    private Education parseBlock(String block) {
        Set<Integer> years = extractYears(block);
        String[] lines = block.split("\n");

        String institution = null;
        String degree = null;
        for (String line : lines) {
            if (institution == null && INSTITUTION_HINT.matcher(line).find()) {
                institution = line.trim();
            }
            if (degree == null && DEGREE_HINT.matcher(line).find()) {
                degree = line.trim();
            }
        }

        if (institution == null && lines.length > 0) {
            institution = lines[0].trim();
        }

        if (years.isEmpty() && institution == null && degree == null) {
            return null;
        }

        Education education = new Education();
        education.setInstitution(institution);
        education.setDegree(degree);

        List<Integer> sortedYears = years.stream().sorted().toList();
        if (!sortedYears.isEmpty()) {
            if (sortedYears.size() >= 2) {
                education.setStartYear(sortedYears.get(0));
                education.setGraduationYear(sortedYears.get(sortedYears.size() - 1));
            } else {
                int year = sortedYears.get(0);
                education.setGraduationYear(year);
            }
        }

        // Detect ongoing education — "present", "current", "ongoing", "pursuing"
        if (EXPECTED_HINT.matcher(block).find()) {
            education.setCurrentlyStudying(true);
            // If only one year found, it's the start year not graduation
            if (sortedYears.size() == 1) {
                education.setStartYear(sortedYears.get(0));
                education.setGraduationYear(null);
            }
        }

        if (education.getStartYear() != null && education.getGraduationYear() != null
            && education.getStartYear() > education.getGraduationYear()) {
            Integer tmp = education.getStartYear();
            education.setStartYear(education.getGraduationYear());
            education.setGraduationYear(tmp);
        }

        // Enrich with prestige data
        if (institution != null) {
            education.setInstitutionTier(prestigeService.institutionTier(institution));
            education.setInstitutionBoost(prestigeService.institutionBoost(institution));
        }
        if (degree != null) {
            education.setDegreeRelevance(prestigeService.degreeRelevance(degree));
        }

        return education;
    }

    private Set<Integer> extractYears(String text) {
        Matcher matcher = YEAR_PATTERN.matcher(text);
        Set<Integer> years = new LinkedHashSet<>();
        while (matcher.find()) {
            years.add(Integer.parseInt(matcher.group(1)));
        }
        return years;
    }
}
