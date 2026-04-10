package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.model.Resume;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured identity fields from the header zone of the resume.
 * Header zone = top 25% of page 1.
 *
 * Extraction order mirrors recruiter eye movement:
 *   name → title → company → contacts → LinkedIn/GitHub
 */
@Component
public class HeaderExtractor {

    // ── Contact patterns ──────────────────────────────────────────────────────
    private static final Pattern EMAIL = Pattern.compile(
        "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE = Pattern.compile(
        "(\\+?\\d[\\d\\s\\-().]{7,}\\d)");

    private static final Pattern LINKEDIN = Pattern.compile(
        "(?:https?://)?(?:www\\.)?linkedin\\.com/in/([\\w\\-]+)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern GITHUB = Pattern.compile(
        "(?:https?://)?(?:www\\.)?github\\.com/([\\w\\-]+)",
        Pattern.CASE_INSENSITIVE);

    // ── YOE explicit statement ────────────────────────────────────────────────
    private static final Pattern YOE_EXPLICIT = Pattern.compile(
        "(\\d+(?:\\.5)?)\\s*\\+?\\s*years?\\s+(?:of\\s+)?(?:experience|exp)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern YOE_VAGUE = Pattern.compile(
        "(several|many|extensive|numerous|multiple|over a decade|decade)\\s+years?",
        Pattern.CASE_INSENSITIVE);

    // ── Company descriptor ────────────────────────────────────────────────────
    // Matches things like "(Series B fintech, 200 engineers)" after a company name
    private static final Pattern COMPANY_DESCRIPTOR = Pattern.compile(
        "\\(([^)]{5,80})\\)");

    // ── Known seniority prefixes for title parsing ────────────────────────────
    private static final List<String> SENIORITY_PREFIXES = List.of(
        "junior", "jr", "mid", "mid-level", "senior", "sr", "staff",
        "principal", "lead", "head of", "director of", "vp of", "chief"
    );

    public void extract(RawDocument rawDoc, Resume resume) {
        String headerText = rawDoc.getHeaderZoneText();
        if (headerText == null || headerText.isBlank()) {
            // Fall back to full text top section
            headerText = rawDoc.getFullText() != null
                ? rawDoc.getFullText().substring(0, Math.min(800, rawDoc.getFullText().length()))
                : "";
        }

        extractContacts(headerText, resume);
        extractTitleAndCompany(rawDoc, resume);
        extractYoeStatement(rawDoc.getFullText(), resume);
        detectPhoto(rawDoc, resume);
    }

    // ── Contact extraction ────────────────────────────────────────────────────

    private void extractContacts(String text, Resume resume) {
        // Email
        Matcher emailMatcher = EMAIL.matcher(text);
        if (emailMatcher.find()) {
            resume.setEmail(emailMatcher.group().trim());
        }

        // Phone
        Matcher phoneMatcher = PHONE.matcher(text);
        if (phoneMatcher.find()) {
            resume.setPhone(phoneMatcher.group().trim());
        }

        // LinkedIn
        Matcher linkedInMatcher = LINKEDIN.matcher(text);
        if (linkedInMatcher.find()) {
            resume.setLinkedInUrl("https://linkedin.com/in/" + linkedInMatcher.group(1));
        }

        // GitHub
        Matcher githubMatcher = GITHUB.matcher(text);
        if (githubMatcher.find()) {
            resume.setGitHubUrl("https://github.com/" + githubMatcher.group(1));
        }

        // Name: best heuristic is the largest font block in the header zone,
        // which is typically the first non-email, non-phone line.
        // We extract it from text blocks if available, else first line of header.
        extractName(text, resume);
    }

    private void extractName(String headerText, Resume resume) {
        // Try font-size based detection first (largest text block)
        // Fallback: first line of header that looks like a name
        String[] lines = headerText.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (EMAIL.matcher(trimmed).find()) continue;
            if (PHONE.matcher(trimmed).find()) continue;
            if (LINKEDIN.matcher(trimmed).find()) continue;
            if (GITHUB.matcher(trimmed).find()) continue;
            if (trimmed.length() > 60) continue;  // names aren't that long

            // Looks like a name if it's 2–4 words, each capitalised
            String[] words = trimmed.split("\\s+");
            if (words.length >= 2 && words.length <= 5) {
                boolean allCapitalised = true;
                for (String w : words) {
                    if (!w.isEmpty() && !Character.isUpperCase(w.charAt(0))) {
                        allCapitalised = false;
                        break;
                    }
                }
                if (allCapitalised) {
                    resume.setCandidateName(trimmed);
                    return;
                }
            }
        }
    }

    // ── Title and company extraction ──────────────────────────────────────────

    private void extractTitleAndCompany(RawDocument rawDoc, Resume resume) {
        String headerText = rawDoc.getHeaderZoneText();
        String[] lines = headerText.split("\n");

        boolean nameFound = resume.getCandidateName() != null;
        boolean titleFound = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (trimmed.equals(resume.getCandidateName())) { nameFound = true; continue; }
            if (!nameFound) continue;

            // Skip contact lines
            if (EMAIL.matcher(trimmed).find()) continue;
            if (PHONE.matcher(trimmed).find()) continue;
            if (LINKEDIN.matcher(trimmed).find()) continue;
            if (GITHUB.matcher(trimmed).find()) continue;

            // First non-contact line after name = likely title
            if (!titleFound && looksLikeTitle(trimmed)) {
                resume.setCurrentTitle(trimmed);
                titleFound = true;
                continue;
            }

            // Next non-contact line after title = likely company
            if (titleFound && resume.getCurrentCompany() == null && looksLikeCompany(trimmed)) {
                // Check for inline descriptor: "Acme Corp (Series B fintech)"
                Matcher descriptorMatcher = COMPANY_DESCRIPTOR.matcher(trimmed);
                if (descriptorMatcher.find()) {
                    String descriptor = descriptorMatcher.group(1);
                    resume.setCompanyDescriptor(descriptor);
                    resume.setCurrentCompany(trimmed.substring(0, descriptorMatcher.start()).trim());
                } else {
                    resume.setCurrentCompany(trimmed);
                }
            }
        }
    }

    private boolean looksLikeTitle(String line) {
        if (line.length() > 80) return false;
        String lower = line.toLowerCase();
        // Contains engineering/tech role words
        return lower.contains("engineer") || lower.contains("developer") || lower.contains("architect")
            || lower.contains("designer") || lower.contains("analyst") || lower.contains("manager")
            || lower.contains("lead") || lower.contains("director") || lower.contains("scientist")
            || lower.contains("consultant") || lower.contains("specialist") || lower.contains("devops")
            || lower.contains("qa") || lower.contains("sre") || lower.contains("product")
            // OR contains a seniority prefix
            || SENIORITY_PREFIXES.stream().anyMatch(p -> lower.startsWith(p + " "));
    }

    private boolean looksLikeCompany(String line) {
        if (line.length() > 100) return false;
        // Not a date, not an email, reasonable word count
        String[] words = line.split("\\s+");
        return words.length >= 1 && words.length <= 8
            && !line.matches(".*\\d{4}.*–.*\\d{4}.*")   // not a date range
            && !EMAIL.matcher(line).find();
    }

    // ── YOE explicit statement ────────────────────────────────────────────────

    private void extractYoeStatement(String fullText, Resume resume) {
        if (fullText == null) return;

        // Check first 500 chars (summary zone) for explicit YOE statement
        String summaryZone = fullText.substring(0, Math.min(500, fullText.length()));

        Matcher explicitMatcher = YOE_EXPLICIT.matcher(summaryZone);
        if (explicitMatcher.find()) {
            resume.setYoeExplicitInSummary(true);
            resume.setYoeRawStatement(explicitMatcher.group());
            try {
                resume.setTotalYoeYears(Double.parseDouble(explicitMatcher.group(1)));
            } catch (NumberFormatException ignored) {}
            return;
        }

        Matcher vagueMatcher = YOE_VAGUE.matcher(summaryZone);
        if (vagueMatcher.find()) {
            resume.setYoeRawStatement(vagueMatcher.group());
            // Don't set totalYoeYears — it's vague, ExperienceExtractor will calculate from dates
        }
    }

    // ── Photo detection ───────────────────────────────────────────────────────

    private void detectPhoto(RawDocument rawDoc, Resume resume) {
        // PDFBox photo detection was signalled via the bold flag on first block (temp carrier)
        // A cleaner approach: check if first page blocks include image-type objects
        if (rawDoc.getPages() != null && !rawDoc.getPages().isEmpty()) {
            RawDocument.RawPage firstPage = rawDoc.getPages().get(0);
            if (firstPage.getBlocks() != null && !firstPage.getBlocks().isEmpty()) {
                // The PdfExtractor set isBold=true on first block as a photo signal
                boolean photoSignal = firstPage.getBlocks().get(0).isBold()
                    && firstPage.getBlocks().get(0).getText().isBlank();
                resume.setHasPhoto(photoSignal);
            }
        }
    }
}
