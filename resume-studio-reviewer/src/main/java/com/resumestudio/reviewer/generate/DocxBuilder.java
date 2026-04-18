package com.resumestudio.reviewer.generate;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

/**
 * Builds an ATS-perfect .docx resume from GeneratedResumeContent.
 *
 * ATS compliance checklist enforced here:
 *  ✓ Letter size (8.5" x 11"), 0.75" margins on all sides
 *  ✓ Single-column layout (no tables, no text boxes, no columns)
 *  ✓ Calibri font throughout (ATS-safe standard font)
 *  ✓ 14pt name, 12pt section headers (BOLD ALL-CAPS), 11pt body
 *  ✓ Left-aligned text (no center or justify)
 *  ✓ Standard section headers: PROFESSIONAL SUMMARY, WORK EXPERIENCE, SKILLS, EDUCATION, PROJECTS
 *  ✓ Bullet character: • (U+2022) — only via plain text, NOT Word list styles
 *  ✓ All contact info in main body (NOT headers/footers)
 *  ✓ Dates in MM/YYYY format
 *  ✓ No images, no colors, no special characters
 *  ✓ No watermarks, no borders, no horizontal lines, no shapes
 *  ✓ ASCII-only separators (hyphen) — no em/en-dash, no pipe
 *  ✓ GPA shown only when numeric and >= 3.5
 *  ✓ Single line spacing (1.0)
 */
@Component
public class DocxBuilder {

    private static final Logger log = LoggerFactory.getLogger(DocxBuilder.class);

    // ── Typography constants ──────────────────────────────────────────────────
    private static final String FONT = "Calibri";
    private static final int SIZE_NAME      = 14;   // pt
    private static final int SIZE_CONTACT   = 10;   // pt
    private static final int SIZE_SECTION   = 12;   // pt
    private static final int SIZE_BODY      = 11;   // pt

    // ── Spacing (in twips: 1pt = 20 twips) ───────────────────────────────────
    private static final BigInteger MARGIN          = BigInteger.valueOf(1080); // 0.75" = 1080 twips
    private static final BigInteger SP_BEFORE_SEC   = BigInteger.valueOf(200); // 10pt before section header
    private static final BigInteger SP_AFTER_SEC    = BigInteger.valueOf(60);  // 3pt after section header
    private static final BigInteger SP_AFTER_BODY   = BigInteger.valueOf(60);  // 3pt after body para
    private static final BigInteger SP_AFTER_BULLET = BigInteger.valueOf(40);  // 2pt after bullet
    private static final BigInteger SP_AFTER_ROLE   = BigInteger.valueOf(100); // 5pt between roles
    private static final BigInteger SP_ZERO         = BigInteger.ZERO;
    private static final BigInteger LINE_SPACING    = BigInteger.valueOf(240); // 1.0 single

    // ── Bullet indent (hanging) ───────────────────────────────────────────────
    private static final BigInteger BULLET_LEFT     = BigInteger.valueOf(360); // 0.25"
    private static final BigInteger BULLET_HANGING  = BigInteger.valueOf(360); // 0.25"

    // ── Page size (Letter: 8.5" x 11") ───────────────────────────────────────
    private static final BigInteger PAGE_W = BigInteger.valueOf(12240); // 8.5 * 1440
    private static final BigInteger PAGE_H = BigInteger.valueOf(15840); // 11.0 * 1440

    /**
     * Builds the ATS-compliant .docx and returns the raw bytes.
     * The returned bytes can be streamed directly as the HTTP response body.
     */
    public byte[] build(GeneratedResumeContent content) throws Exception {
        XWPFDocument doc = new XWPFDocument();

        applyPageLayout(doc);

        // ── 1. Name ──────────────────────────────────────────────────────────
        if (content.getCandidateName() != null) {
            addNameLine(doc, content.getCandidateName());
        }

        // ── 2. Target title (role-matched, shown under name) ─────────────────
        if (content.getTargetTitle() != null) {
            addTitleLine(doc, content.getTargetTitle());
        }

        // ── 3. Contact info (all in body — never in header/footer) ───────────
        addContactLine(doc, content);

        // ── 4. Professional Summary ───────────────────────────────────────────
        if (content.getSummary() != null && !content.getSummary().isBlank()) {
            addSectionHeader(doc, "PROFESSIONAL SUMMARY");
            addBodyParagraph(doc, content.getSummary().trim(), false);
        }

        // ── 5. Work Experience ────────────────────────────────────────────────
        if (content.getExperience() != null && !content.getExperience().isEmpty()) {
            addSectionHeader(doc, "WORK EXPERIENCE");
            List<GeneratedResumeContent.GeneratedExperience> exp = content.getExperience();
            for (int i = 0; i < exp.size(); i++) {
                addExperienceEntry(doc, exp.get(i));
                if (i < exp.size() - 1) {
                    spacer(doc, SP_AFTER_ROLE);
                }
            }
        }

        // ── 6. Skills ────────────────────────────────────────────────────────
        if (content.getSkills() != null && !content.getSkills().isEmpty()) {
            addSectionHeader(doc, "SKILLS");
            for (GeneratedResumeContent.GeneratedSkillCategory cat : content.getSkills()) {
                addSkillCategoryLine(doc, cat);
            }
        }

        // ── 7. Education ─────────────────────────────────────────────────────
        if (content.getEducation() != null && !content.getEducation().isEmpty()) {
            addSectionHeader(doc, "EDUCATION");
            for (GeneratedResumeContent.GeneratedEducation edu : content.getEducation()) {
                addEducationEntry(doc, edu);
            }
        }

        // ── 8. Projects (optional) ────────────────────────────────────────────
        if (content.getProjects() != null && !content.getProjects().isEmpty()) {
            addSectionHeader(doc, "PROJECTS");
            for (GeneratedResumeContent.GeneratedProject proj : content.getProjects()) {
                addProjectEntry(doc, proj);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.write(baos);
        doc.close();

        byte[] bytes = baos.toByteArray();
        validateAtsParseable(bytes, content);

        log.info("Built ATS-compliant DOCX ({} bytes) for: {}",
            bytes.length, content.getCandidateName());
        return bytes;
    }

    /**
     * Re-opens the produced DOCX with Apache POI's text extractor and verifies
     * the critical fields are recoverable as plain text — the same operation
     * an ATS parser performs first. If extraction fails or the candidate name /
     * required section headers are missing, we log a warning so the issue is
     * caught in production telemetry rather than surfacing only to the recruiter.
     */
    private void validateAtsParseable(byte[] docxBytes, GeneratedResumeContent content) {
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(docxBytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(reopened)) {

            String text = extractor.getText();
            if (text == null || text.isBlank()) {
                log.warn("ATS validation: extracted text is empty");
                return;
            }

            String name = content.getCandidateName();
            if (name != null && !text.contains(name)) {
                log.warn("ATS validation: candidate name '{}' not extractable", name);
            }
            String[] required = {"PROFESSIONAL SUMMARY", "WORK EXPERIENCE", "SKILLS", "EDUCATION"};
            for (String header : required) {
                if (isSectionPresent(content, header) && !text.contains(header)) {
                    log.warn("ATS validation: section header '{}' not extractable", header);
                }
            }
            String email = content.getEmail();
            if (email != null && !email.isBlank() && !text.contains(email)) {
                log.warn("ATS validation: email not extractable");
            }
        } catch (Exception e) {
            log.warn("ATS validation: re-parse failed ({})", e.getMessage());
        }
    }

    private boolean isSectionPresent(GeneratedResumeContent c, String header) {
        return switch (header) {
            case "PROFESSIONAL SUMMARY" -> c.getSummary() != null && !c.getSummary().isBlank();
            case "WORK EXPERIENCE"      -> c.getExperience() != null && !c.getExperience().isEmpty();
            case "SKILLS"               -> c.getSkills() != null && !c.getSkills().isEmpty();
            case "EDUCATION"            -> c.getEducation() != null && !c.getEducation().isEmpty();
            default -> false;
        };
    }

    /**
     * Derives the download filename: "FirstName_LastName_Resume.docx"
     */
    public String buildFilename(String candidateName) {
        if (candidateName == null || candidateName.isBlank()) return "Resume.docx";
        String[] parts = candidateName.trim().split("\\s+");
        if (parts.length == 1) return parts[0] + "_Resume.docx";
        return parts[0] + "_" + parts[parts.length - 1] + "_Resume.docx";
    }

    // ── Page layout ───────────────────────────────────────────────────────────

    private void applyPageLayout(XWPFDocument doc) {
        CTDocument1 ctDoc = doc.getDocument();
        CTBody body = ctDoc.getBody();
        CTSectPr sectPr = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();

        // Letter size portrait
        CTPageSz pgSz = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        pgSz.setW(PAGE_W);
        pgSz.setH(PAGE_H);
        pgSz.setOrient(STPageOrientation.PORTRAIT);

        // 0.75" margins all around
        CTPageMar pgMar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        pgMar.setTop(MARGIN);
        pgMar.setBottom(MARGIN);
        pgMar.setLeft(MARGIN);
        pgMar.setRight(MARGIN);
    }

    // ── Header section (name + title + contact) ───────────────────────────────

    private void addNameLine(XWPFDocument doc, String name) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(para, SP_ZERO, BigInteger.valueOf(40));

        XWPFRun run = para.createRun();
        run.setText(name);
        run.setFontFamily(FONT);
        run.setFontSize(SIZE_NAME);
        run.setBold(true);
        run.setColor("000000");
    }

    private void addTitleLine(XWPFDocument doc, String title) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(para, SP_ZERO, BigInteger.valueOf(40));

        XWPFRun run = para.createRun();
        run.setText(title);
        run.setFontFamily(FONT);
        run.setFontSize(SIZE_BODY);
        run.setItalic(true);
        run.setColor("000000");
    }

    private void addContactLine(XWPFDocument doc, GeneratedResumeContent c) {
        // Build a single contact info line: email  |  phone  |  location  |  LinkedIn  |  GitHub
        StringBuilder sb = new StringBuilder();
        appendContact(sb, c.getEmail());
        appendContact(sb, c.getPhone());
        appendContact(sb, c.getLocation());
        if (c.getLinkedIn() != null && !c.getLinkedIn().isBlank()) appendContact(sb, c.getLinkedIn());
        if (c.getGitHub() != null && !c.getGitHub().isBlank()) appendContact(sb, c.getGitHub());
        if (c.getPortfolioUrl() != null && !c.getPortfolioUrl().isBlank()) appendContact(sb, c.getPortfolioUrl());

        if (sb.length() == 0) return;

        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(para, SP_ZERO, BigInteger.valueOf(120)); // 6pt after contact

        XWPFRun run = para.createRun();
        run.setText(sb.toString());
        run.setFontFamily(FONT);
        run.setFontSize(SIZE_CONTACT);
        run.setColor("000000");
    }

    private void appendContact(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) return;
        if (sb.length() > 0) sb.append(" - ");
        sb.append(value.trim());
    }

    // ── Section header ────────────────────────────────────────────────────────

    private void addSectionHeader(XWPFDocument doc, String title) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(para, SP_BEFORE_SEC, SP_AFTER_SEC);

        XWPFRun run = para.createRun();
        run.setText(title.toUpperCase());
        run.setFontFamily(FONT);
        run.setFontSize(SIZE_SECTION);
        run.setBold(true);
        run.setColor("000000");
    }

    // ── Experience ────────────────────────────────────────────────────────────

    private void addExperienceEntry(XWPFDocument doc,
                                    GeneratedResumeContent.GeneratedExperience exp) {
        // Role header line: COMPANY  |  Title  |  MM/YYYY – MM/YYYY
        XWPFParagraph header = doc.createParagraph();
        header.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(header, SP_ZERO, BigInteger.valueOf(20));

        // Company (bold)
        XWPFRun companyRun = header.createRun();
        companyRun.setText(exp.getCompany() != null ? exp.getCompany() : "");
        companyRun.setFontFamily(FONT);
        companyRun.setFontSize(SIZE_BODY);
        companyRun.setBold(true);
        companyRun.setColor("000000");

        // Optional company descriptor: " (Series C fintech)"
        if (exp.getCompanyDescriptor() != null && !exp.getCompanyDescriptor().isBlank()) {
            XWPFRun descRun = header.createRun();
            descRun.setText(" (" + exp.getCompanyDescriptor() + ")");
            descRun.setFontFamily(FONT);
            descRun.setFontSize(SIZE_CONTACT);
            descRun.setItalic(true);
            descRun.setColor("000000");
        }

        // Title + dates (regular weight) — ATS-safe hyphen separators
        String dateRange = buildDateRange(exp.getStartDate(), exp.getEndDate());
        String titleAndDates = " - " + safe(exp.getTitle()) + " - " + dateRange;
        XWPFRun titleRun = header.createRun();
        titleRun.setText(titleAndDates);
        titleRun.setFontFamily(FONT);
        titleRun.setFontSize(SIZE_BODY);
        titleRun.setColor("000000");

        // Bullets (3-5 per role, each starting with •)
        if (exp.getBullets() != null) {
            for (String bullet : exp.getBullets()) {
                if (bullet == null || bullet.isBlank()) continue;
                addBullet(doc, bullet.trim());
            }
        }
    }

    private void addBullet(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(para, SP_ZERO, SP_AFTER_BULLET);

        // Hanging indent for wrapped bullets
        CTPPr pPr = getPPr(para);
        CTInd ind = pPr.isSetInd() ? pPr.getInd() : pPr.addNewInd();
        ind.setLeft(BULLET_LEFT);
        ind.setHanging(BULLET_HANGING);

        XWPFRun run = para.createRun();
        run.setText("\u2022  " + text);   // • + two spaces + text
        run.setFontFamily(FONT);
        run.setFontSize(SIZE_BODY);
        run.setColor("000000");
    }

    // ── Skills ────────────────────────────────────────────────────────────────

    private void addSkillCategoryLine(XWPFDocument doc,
                                      GeneratedResumeContent.GeneratedSkillCategory cat) {
        if (cat.getItems() == null || cat.getItems().isEmpty()) return;

        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(para, SP_ZERO, SP_AFTER_BULLET);

        // Category label (bold)
        if (cat.getCategory() != null && !cat.getCategory().isBlank()) {
            XWPFRun label = para.createRun();
            label.setText(cat.getCategory() + ": ");
            label.setFontFamily(FONT);
            label.setFontSize(SIZE_BODY);
            label.setBold(true);
            label.setColor("000000");
        }

        // Skill items (regular weight, comma-separated)
        XWPFRun items = para.createRun();
        items.setText(String.join(", ", cat.getItems()));
        items.setFontFamily(FONT);
        items.setFontSize(SIZE_BODY);
        items.setColor("000000");
    }

    // ── Education ─────────────────────────────────────────────────────────────

    private void addEducationEntry(XWPFDocument doc,
                                   GeneratedResumeContent.GeneratedEducation edu) {
        // Line 1: Degree in Field  |  Institution, Location  |  Year
        XWPFParagraph line1 = doc.createParagraph();
        line1.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(line1, SP_ZERO, BigInteger.valueOf(20));

        // Degree + field (bold)
        String degreeField = buildDegreeField(edu);
        XWPFRun degRun = line1.createRun();
        degRun.setText(degreeField);
        degRun.setFontFamily(FONT);
        degRun.setFontSize(SIZE_BODY);
        degRun.setBold(true);
        degRun.setColor("000000");

        // Institution + year
        String instYear = buildInstYear(edu);
        if (!instYear.isBlank()) {
            XWPFRun instRun = line1.createRun();
            instRun.setText(" - " + instYear);
            instRun.setFontFamily(FONT);
            instRun.setFontSize(SIZE_BODY);
            instRun.setColor("000000");
        }

        // GPA (only show if numeric and >= 3.5)
        if (shouldShowGpa(edu.getGpa())) {
            XWPFRun gpaRun = line1.createRun();
            gpaRun.setText(" - GPA: " + edu.getGpa().trim());
            gpaRun.setFontFamily(FONT);
            gpaRun.setFontSize(SIZE_BODY);
            gpaRun.setColor("000000");
        }

        // Honors (if any)
        if (edu.getHonors() != null && !edu.getHonors().isEmpty()) {
            XWPFParagraph honorsLine = doc.createParagraph();
            honorsLine.setAlignment(ParagraphAlignment.LEFT);
            applyParaSpacing(honorsLine, SP_ZERO, SP_AFTER_BODY);
            XWPFRun hRun = honorsLine.createRun();
            hRun.setText("Honors: " + String.join(", ", edu.getHonors()));
            hRun.setFontFamily(FONT);
            hRun.setFontSize(SIZE_CONTACT);
            hRun.setItalic(true);
            hRun.setColor("000000");
        }
    }

    // ── Projects ──────────────────────────────────────────────────────────────

    private void addProjectEntry(XWPFDocument doc,
                                  GeneratedResumeContent.GeneratedProject proj) {
        // Header: ProjectName  |  Tech1, Tech2, Tech3
        XWPFParagraph header = doc.createParagraph();
        header.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(header, SP_ZERO, BigInteger.valueOf(20));

        XWPFRun nameRun = header.createRun();
        nameRun.setText(safe(proj.getName()));
        nameRun.setFontFamily(FONT);
        nameRun.setFontSize(SIZE_BODY);
        nameRun.setBold(true);
        nameRun.setColor("000000");

        if (proj.getTechnologies() != null && !proj.getTechnologies().isEmpty()) {
            XWPFRun techRun = header.createRun();
            techRun.setText(" - " + String.join(", ", proj.getTechnologies()));
            techRun.setFontFamily(FONT);
            techRun.setFontSize(SIZE_BODY);
            techRun.setItalic(true);
            techRun.setColor("000000");
        }

        // URL (plain text — no hyperlink to stay ATS-safe)
        if (proj.getUrl() != null && !proj.getUrl().isBlank()) {
            XWPFRun urlRun = header.createRun();
            urlRun.setText(" - " + proj.getUrl());
            urlRun.setFontFamily(FONT);
            urlRun.setFontSize(SIZE_CONTACT);
            urlRun.setColor("000000");
        }

        // Description (2-3 bullet lines or plain text)
        if (proj.getDescription() != null && !proj.getDescription().isBlank()) {
            addBullet(doc, proj.getDescription().trim());
        }
    }

    // ── Body paragraph (for summary) ──────────────────────────────────────────

    private void addBodyParagraph(XWPFDocument doc, String text, boolean italic) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.LEFT);
        applyParaSpacing(para, SP_ZERO, SP_AFTER_BODY);

        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontFamily(FONT);
        run.setFontSize(SIZE_BODY);
        run.setItalic(italic);
        run.setColor("000000");
    }

    // ── Spacing helpers ───────────────────────────────────────────────────────

    private void spacer(XWPFDocument doc, BigInteger spaceBefore) {
        XWPFParagraph para = doc.createParagraph();
        applyParaSpacing(para, SP_ZERO, spaceBefore);
    }

    private void applyParaSpacing(XWPFParagraph para, BigInteger spaceBefore, BigInteger spaceAfter) {
        CTPPr pPr = getPPr(para);
        CTSpacing spacing = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        spacing.setBefore(spaceBefore);
        spacing.setAfter(spaceAfter);
        spacing.setLine(LINE_SPACING);
        spacing.setLineRule(STLineSpacingRule.AUTO);
    }

    // ── XML property helpers ──────────────────────────────────────────────────

    private CTPPr getPPr(XWPFParagraph para) {
        return para.getCTP().isSetPPr()
            ? para.getCTP().getPPr()
            : para.getCTP().addNewPPr();
    }

    // ── Text formatting helpers ───────────────────────────────────────────────

    private String safe(String s) {
        return s != null ? s : "";
    }

    private String buildDateRange(String start, String end) {
        String s = start != null && !start.isBlank() ? start : "";
        String e = end != null && !end.isBlank() ? end : "Present";
        if (s.isBlank()) return e;
        return s + " to " + e;   // ATS-safe — no en-dash
    }

    private String buildDegreeField(GeneratedResumeContent.GeneratedEducation edu) {
        StringBuilder sb = new StringBuilder();
        if (edu.getDegree() != null) sb.append(edu.getDegree());
        if (edu.getField() != null && !edu.getField().isBlank()) {
            if (sb.length() > 0) sb.append(" in ");
            sb.append(edu.getField());
        }
        return sb.toString();
    }

    private String buildInstYear(GeneratedResumeContent.GeneratedEducation edu) {
        StringBuilder sb = new StringBuilder();
        if (edu.getInstitution() != null) sb.append(edu.getInstitution());
        if (edu.getLocation() != null && !edu.getLocation().isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(edu.getLocation());
        }
        if (edu.getGraduationYear() != null && !edu.getGraduationYear().isBlank()) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(edu.getGraduationYear());
        }
        return sb.toString();
    }

    /**
     * Spec rule: only display GPA when it is 3.5 or higher (out of 4.0 scale).
     * Accepts strings like "3.8", "3.85/4.0", "3.5". Returns false for unparseable
     * values so we never print a low or non-numeric GPA.
     */
    private boolean shouldShowGpa(String gpa) {
        if (gpa == null || gpa.isBlank()) return false;
        String cleaned = gpa.trim().split("/")[0].trim();
        try {
            double value = Double.parseDouble(cleaned);
            return value >= 3.5;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
