package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Evaluates the resume filename before the document is even opened.
 * First impression signal — takes ~0.5 seconds in a real recruiter review.
 */
@Component
public class FilenameSignalCalculator {

    private static final int MAX_FILENAME_LENGTH = 40;

    private static final Pattern VERSIONING = Pattern.compile(
        "\\b(v\\d+|v\\.\\d+|final|copy|updated|new|old|revised|draft|temp|backup|\\d{8})\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern GENERIC_NAME = Pattern.compile(
        "^(resume|cv|curriculum[_ ]vitae|my[_ ]resume|my[_ ]cv|document|application)[^a-zA-Z]",
        Pattern.CASE_INSENSITIVE);

    // Capitalised word = likely a name component
    private static final Pattern PROPER_NOUN = Pattern.compile("[A-Z][a-z]{1,20}");

    // Detects names like "John_Smith" or "JohnSmith" or "john-smith"
    private static final Pattern NAME_PATTERN = Pattern.compile(
        "[A-Z][a-z]+[_\\-. ]?[A-Z][a-z]+");

    public void compute(String filename, ResumeSignals signals) {
        if (filename == null || filename.isBlank()) {
            signals.setFilenameProfessional(false);
            signals.setFilenameGeneric(true);
            signals.setFilenameIssueDetail("No filename detected.");
            return;
        }

        // Strip extension
        String name = filename.replaceAll("\\.[a-zA-Z]{2,5}$", "").trim();

        boolean tooLong = name.length() > MAX_FILENAME_LENGTH;
        boolean hasVersioning = VERSIONING.matcher(name).find();
        boolean isGeneric = GENERIC_NAME.matcher(name).find();
        boolean hasName = NAME_PATTERN.matcher(name).find();

        signals.setFilenameTooLong(tooLong);
        signals.setFilenameHasVersioning(hasVersioning);
        signals.setFilenameGeneric(isGeneric);
        signals.setFilenameHasName(hasName);

        boolean isProfessional = !tooLong && !hasVersioning && !isGeneric && hasName;
        signals.setFilenameProfessional(isProfessional);

        if (!isProfessional) {
            signals.setFilenameIssueDetail(buildIssueDetail(name, tooLong, hasVersioning, isGeneric, hasName));
        }
    }

    private String buildIssueDetail(String name, boolean tooLong, boolean hasVersioning,
                                     boolean isGeneric, boolean hasName) {
        if (isGeneric) return "\"" + name + "\" is a generic filename — indistinguishable from hundreds of others.";
        if (hasVersioning) return "\"" + name + "\" — versioning words like 'final' or 'v2' suggest an unpolished submission.";
        if (tooLong) return "\"" + name + "\" is too long (" + name.length() + " chars). Aim for: FirstName_LastName_Role.pdf";
        if (!hasName) return "\"" + name + "\" doesn't include your name — hard to identify in a downloads folder.";
        return null;
    }
}
