package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.extraction.SemanticExtractor;
import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.model.Resume;
import com.resumestudio.reviewer.nlp.NlpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(HeaderExtractor.class);

    private final SemanticExtractor semanticExtractor;
    private final NlpService nlp;

    public HeaderExtractor(SemanticExtractor semanticExtractor, NlpService nlp) {
        this.semanticExtractor = semanticExtractor;
        this.nlp = nlp;
    }

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

    // Location: "City, Country" or "City, State" pattern in header
    private static final Pattern LOCATION = Pattern.compile(
        "\\b([A-Z][a-zA-Z\\s]+,\\s*(?:[A-Z][a-zA-Z\\s]+|[A-Z]{2}))\\b");

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
            String fullText = rawDoc.getFullText();
            headerText = fullText != null
                ? fullText.substring(0, Math.min(600, fullText.length()))
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

        // Location (e.g. "Galway, Ireland" or "San Francisco, CA")
        Matcher locationMatcher = LOCATION.matcher(text);
        if (locationMatcher.find()) {
            resume.setLocation(locationMatcher.group(1).trim());
        }

        // Name: best heuristic is the largest font block in the header zone,
        // which is typically the first non-email, non-phone line.
        // We extract it from text blocks if available, else first line of header.
        extractName(text, resume);
    }

    private void extractName(String headerText, Resume resume) {
        String[] lines = headerText.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (EMAIL.matcher(trimmed).find()) continue;
            if (PHONE.matcher(trimmed).find()) continue;
            if (LINKEDIN.matcher(trimmed).find()) continue;
            if (GITHUB.matcher(trimmed).find()) continue;
            if (trimmed.length() > 60) continue;

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

        // NER fallback — use OpenNLP person finder on the header text
        if (resume.getCandidateName() == null) {
            List<String> persons = nlp.findPersons(headerText.substring(0, Math.min(300, headerText.length())));
            if (!persons.isEmpty()) {
                resume.setCandidateName(persons.get(0));
            }
        }
    }

    // ── Title and company extraction ──────────────────────────────────────────

    private void extractTitleAndCompany(RawDocument rawDoc, Resume resume) {
        // Use only the first 600 chars of text — strictly the header zone
        String fullText = rawDoc.getFullText() != null ? rawDoc.getFullText() : "";
        String headerText = fullText.substring(0, Math.min(600, fullText.length()));
        String[] lines = headerText.split("\n");

        boolean nameFound = resume.getCandidateName() != null;
        boolean titleFound = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (trimmed.equals(resume.getCandidateName())) { nameFound = true; continue; }
            if (!nameFound) continue;

            // Stop if we hit a section header (but only after we've found title AND company, or exhausted attempts)
            if (semanticExtractor.classifyHeader(trimmed) != com.resumestudio.reviewer.extraction.SemanticExtractor.SectionType.UNKNOWN) {
                // Only stop if we've already extracted both title and company
                if (titleFound && resume.getCurrentCompany() != null) break;
            }

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

            // Next non-contact line after title = company (only if it looks like one)
            if (titleFound && resume.getCurrentCompany() == null && looksLikeCompany(trimmed)) {
                Matcher descriptorMatcher = COMPANY_DESCRIPTOR.matcher(trimmed);
                if (descriptorMatcher.find()) {
                    resume.setCompanyDescriptor(descriptorMatcher.group(1));
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
        String[] words = line.split("\\s+");
        // Reject section headers (all-caps short lines like "PROFESSIONAL SUMMARY")
        boolean isAllCaps = line.equals(line.toUpperCase()) && line.matches("[A-Z\\s]+");
        return !isAllCaps && words.length >= 1 && words.length <= 8
            && !line.matches(".*\\d{4}.*[–\\-].*\\d{4}.*")
            && !EMAIL.matcher(line).find();
    }

    // ── YOE explicit statement ────────────────────────────────────────────────

    private void extractYoeStatement(String fullText, Resume resume) {
        if (fullText == null) return;
        Double yoe = semanticExtractor.extractYoe(fullText);
        if (yoe != null) {
            resume.setYoeExplicitInSummary(true);
            resume.setTotalYoeYears(yoe);
            resume.setYoeRawStatement(yoe + " years");
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
