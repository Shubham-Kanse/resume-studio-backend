package com.resumestudio.reviewer.generate;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

/**
 * Builds a .docx resume matching the sample resume formatting exactly:
 *  - Name: 14pt bold, center-aligned
 *  - Title + contact: 10pt, center-aligned, line-break separated in same paragraph
 *  - Section headers: 12pt bold, left-aligned
 *  - Body text: 10pt, justified (both)
 *  - Bullets: 10pt, justified, indent left=720 hanging=360
 *  - Margins: 0.5" all sides (720 twips)
 *  - Font: Calibri throughout
 *  - No explicit spacing overrides — compact like the sample
 */
@Component
public class DocxBuilder {

    private static final Logger log = LoggerFactory.getLogger(DocxBuilder.class);

    private static final String FONT        = "Calibri";
    private static final int    SZ_NAME     = 28;  // half-points = 14pt
    private static final int    SZ_SECTION  = 24;  // half-points = 12pt
    private static final int    SZ_BODY     = 20;  // half-points = 10pt

    private static final BigInteger MARGIN       = BigInteger.valueOf(720);  // 0.5"
    private static final BigInteger PAGE_W       = BigInteger.valueOf(12240);
    private static final BigInteger PAGE_H       = BigInteger.valueOf(15840);
    // Bullet indent matching sample: left=720, hanging=360
    private static final BigInteger BULLET_LEFT  = BigInteger.valueOf(720);
    private static final BigInteger BULLET_HANG  = BigInteger.valueOf(360);
    // Compact spacing — matches sample's no-explicit-spacing style
    private static final BigInteger SP_ZERO      = BigInteger.ZERO;
    private static final BigInteger SP_SMALL     = BigInteger.valueOf(40);   // 2pt
    private static final BigInteger SP_SEC_AFTER = BigInteger.valueOf(40);
    private static final BigInteger SP_SEC_BEFORE= BigInteger.valueOf(120);  // 6pt before section
    private static final BigInteger LINE_SINGLE  = BigInteger.valueOf(240);  // 1.0

    public byte[] build(GeneratedResumeContent c) throws Exception {
        XWPFDocument doc = new XWPFDocument();
        applyPageLayout(doc);

        // ── Header block: name + title + contact (all center-aligned) ─────────
        addHeader(doc, c);

        // ── Professional Summary ──────────────────────────────────────────────
        if (c.getSummary() != null && !c.getSummary().isBlank()) {
            addSectionHeader(doc, "PROFESSIONAL SUMMARY");
            addJustifiedPara(doc, c.getSummary().trim());
        }

        // ── Technical Skills ──────────────────────────────────────────────────
        if (c.getSkills() != null && !c.getSkills().isEmpty()) {
            addSectionHeader(doc, "TECHNICAL SKILLS");
            for (GeneratedResumeContent.GeneratedSkillCategory cat : c.getSkills()) {
                addSkillLine(doc, cat);
            }
        }

        // ── Professional Experience ───────────────────────────────────────────
        if (c.getExperience() != null && !c.getExperience().isEmpty()) {
            addSectionHeader(doc, "PROFESSIONAL EXPERIENCE");
            for (GeneratedResumeContent.GeneratedExperience exp : c.getExperience()) {
                addExperience(doc, exp);
            }
        }

        // ── Education ─────────────────────────────────────────────────────────
        if (c.getEducation() != null && !c.getEducation().isEmpty()) {
            addSectionHeader(doc, "EDUCATION");
            for (GeneratedResumeContent.GeneratedEducation edu : c.getEducation()) {
                addEducation(doc, edu);
            }
        }

        // ── Projects ──────────────────────────────────────────────────────────
        if (c.getProjects() != null && !c.getProjects().isEmpty()) {
            addSectionHeader(doc, "PROJECTS");
            for (GeneratedResumeContent.GeneratedProject proj : c.getProjects()) {
                addProject(doc, proj);
            }
        }

        // ── Achievements ──────────────────────────────────────────────────────
        if (c.getAchievements() != null && !c.getAchievements().isEmpty()) {
            addSectionHeader(doc, "ACHIEVEMENTS");
            for (String a : c.getAchievements()) {
                if (a != null && !a.isBlank()) addBullet(doc, a.trim());
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.write(baos);
        doc.close();
        byte[] bytes = baos.toByteArray();
        validateAtsParseable(bytes, c);
        log.info("Built DOCX ({} bytes) for: {}", bytes.length, c.getCandidateName());
        return bytes;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private void addHeader(XWPFDocument doc, GeneratedResumeContent c) {
        // Single paragraph: name (bold 14pt) + line break + title (10pt) + line break + contact (10pt)
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.CENTER);
        setSpacing(para, SP_ZERO, BigInteger.valueOf(80));

        // Name
        XWPFRun nameRun = para.createRun();
        nameRun.setText(safe(c.getCandidateName()));
        nameRun.setFontFamily(FONT);
        nameRun.setFontSize(0); // use half-point
        setHalfPointSize(nameRun, SZ_NAME);
        nameRun.setBold(true);

        // Title on next line
        if (c.getTargetTitle() != null && !c.getTargetTitle().isBlank()) {
            nameRun.addBreak();
            XWPFRun titleRun = para.createRun();
            titleRun.setText(c.getTargetTitle());
            titleRun.setFontFamily(FONT);
            setHalfPointSize(titleRun, SZ_BODY);
        }

        // Contact line: location • phone • email • LinkedIn • GitHub
        String contact = buildContactLine(c);
        if (!contact.isBlank()) {
            XWPFRun prevRun = para.getRuns().get(para.getRuns().size() - 1);
            prevRun.addBreak();
            XWPFRun contactRun = para.createRun();
            contactRun.setText(contact);
            contactRun.setFontFamily(FONT);
            setHalfPointSize(contactRun, SZ_BODY);
        }
    }

    private String buildContactLine(GeneratedResumeContent c) {
        StringBuilder sb = new StringBuilder();
        append(sb, c.getLocation());
        append(sb, c.getPhone());
        append(sb, c.getEmail());
        if (c.getLinkedIn() != null && !c.getLinkedIn().isBlank()) append(sb, c.getLinkedIn());
        if (c.getGitHub() != null && !c.getGitHub().isBlank()) append(sb, c.getGitHub());
        return sb.toString();
    }

    private void append(StringBuilder sb, String val) {
        if (val == null || val.isBlank()) return;
        if (sb.length() > 0) sb.append(" \u2022 ");
        sb.append(val.trim());
    }

    // ── Section header ────────────────────────────────────────────────────────

    private void addSectionHeader(XWPFDocument doc, String title) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.LEFT);
        setSpacing(para, SP_SEC_BEFORE, SP_SEC_AFTER);

        XWPFRun run = para.createRun();
        run.setText(title.toUpperCase());
        run.setFontFamily(FONT);
        setHalfPointSize(run, SZ_SECTION);
        run.setBold(true);

        // Bottom border under section header (like the sample's horizontal rule)
        CTPPr pPr = getPPr(para);
        CTPBdr pBdr = pPr.isSetPBdr() ? pPr.getPBdr() : pPr.addNewPBdr();
        CTBorder bottom = pBdr.isSetBottom() ? pBdr.getBottom() : pBdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(4));
        bottom.setSpace(BigInteger.valueOf(1));
        bottom.setColor("000000");
    }

    // ── Skills ────────────────────────────────────────────────────────────────

    private void addSkillLine(XWPFDocument doc, GeneratedResumeContent.GeneratedSkillCategory cat) {
        if (cat.getItems() == null || cat.getItems().isEmpty()) return;
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.BOTH);
        setSpacing(para, SP_ZERO, SP_SMALL);

        XWPFRun label = para.createRun();
        label.setText(safe(cat.getCategory()) + ": ");
        label.setFontFamily(FONT);
        setHalfPointSize(label, SZ_BODY);
        label.setBold(true);

        XWPFRun items = para.createRun();
        items.setText(String.join(", ", cat.getItems()));
        items.setFontFamily(FONT);
        setHalfPointSize(items, SZ_BODY);
    }

    // ── Experience ────────────────────────────────────────────────────────────

    private void addExperience(XWPFDocument doc, GeneratedResumeContent.GeneratedExperience exp) {
        // Company line: "Company • Title • MM/YYYY – MM/YYYY"
        XWPFParagraph header = doc.createParagraph();
        header.setAlignment(ParagraphAlignment.BOTH);
        setSpacing(header, BigInteger.valueOf(80), SP_SMALL);

        XWPFRun compRun = header.createRun();
        compRun.setText(safe(exp.getCompany()));
        compRun.setFontFamily(FONT);
        setHalfPointSize(compRun, SZ_BODY);
        compRun.setBold(true);

        String descriptor = exp.getCompanyDescriptor();
        String title = safe(exp.getTitle());
        String dates = buildDates(exp.getStartDate(), exp.getEndDate());
        String rest = (descriptor != null && !descriptor.isBlank() ? " (" + descriptor + ")" : "")
            + " \u2022 " + title + " \u2022 " + dates;

        XWPFRun restRun = header.createRun();
        restRun.setText(rest);
        restRun.setFontFamily(FONT);
        setHalfPointSize(restRun, SZ_BODY);
        restRun.setBold(false);

        // Bullet groups (with sub-headings) or flat bullets
        if (exp.getBulletGroups() != null && !exp.getBulletGroups().isEmpty()) {
            for (GeneratedResumeContent.BulletGroup group : exp.getBulletGroups()) {
                if (group.getHeading() != null && !group.getHeading().isBlank()) {
                    XWPFParagraph subHead = doc.createParagraph();
                    subHead.setAlignment(ParagraphAlignment.LEFT);
                    setSpacing(subHead, BigInteger.valueOf(60), BigInteger.valueOf(20));
                    XWPFRun sh = subHead.createRun();
                    sh.setText(group.getHeading());
                    sh.setFontFamily(FONT);
                    setHalfPointSize(sh, SZ_BODY);
                    sh.setItalic(true);
                }
                if (group.getBullets() != null) {
                    for (String b : group.getBullets()) {
                        if (b != null && !b.isBlank()) addBullet(doc, b.trim());
                    }
                }
            }
        } else if (exp.getBullets() != null) {
            for (String b : exp.getBullets()) {
                if (b != null && !b.isBlank()) addBullet(doc, b.trim());
            }
        }
    }

    // ── Bullet ────────────────────────────────────────────────────────────────

    private void addBullet(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.BOTH);
        setSpacing(para, SP_ZERO, SP_SMALL);

        CTPPr pPr = getPPr(para);
        CTInd ind = pPr.isSetInd() ? pPr.getInd() : pPr.addNewInd();
        ind.setLeft(BULLET_LEFT);
        ind.setHanging(BULLET_HANG);

        XWPFRun run = para.createRun();
        run.setText("\u2022  " + text);
        run.setFontFamily(FONT);
        setHalfPointSize(run, SZ_BODY);
    }

    // ── Education ─────────────────────────────────────────────────────────────

    private void addEducation(XWPFDocument doc, GeneratedResumeContent.GeneratedEducation edu) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.BOTH);
        setSpacing(para, SP_ZERO, SP_SMALL);

        // "MSc in Computer Science – Artificial Intelligence"
        String degree = buildDegree(edu);
        XWPFRun degRun = para.createRun();
        degRun.setText(degree);
        degRun.setFontFamily(FONT);
        setHalfPointSize(degRun, SZ_BODY);
        degRun.setBold(true);

        // " - University of Galway, Ireland - 2025"
        String inst = buildInst(edu);
        if (!inst.isBlank()) {
            XWPFRun instRun = para.createRun();
            instRun.setText(" \u2022 " + inst);
            instRun.setFontFamily(FONT);
            setHalfPointSize(instRun, SZ_BODY);
        }

        if (shouldShowGpa(edu.getGpa())) {
            XWPFRun gpaRun = para.createRun();
            gpaRun.setText(" \u2022 GPA: " + edu.getGpa().trim());
            gpaRun.setFontFamily(FONT);
            setHalfPointSize(gpaRun, SZ_BODY);
        }

        // Honors / thesis on next line
        if (edu.getHonors() != null && !edu.getHonors().isEmpty()) {
            for (String honor : edu.getHonors()) {
                if (honor == null || honor.isBlank()) continue;
                XWPFParagraph hp = doc.createParagraph();
                hp.setAlignment(ParagraphAlignment.BOTH);
                setSpacing(hp, SP_ZERO, SP_SMALL);
                XWPFRun hr = hp.createRun();
                hr.setText(honor);
                hr.setFontFamily(FONT);
                setHalfPointSize(hr, SZ_BODY);
                hr.setItalic(true);
            }
        }
    }

    // ── Projects ──────────────────────────────────────────────────────────────

    private void addProject(XWPFDocument doc, GeneratedResumeContent.GeneratedProject proj) {
        XWPFParagraph header = doc.createParagraph();
        header.setAlignment(ParagraphAlignment.BOTH);
        setSpacing(header, BigInteger.valueOf(60), SP_SMALL);

        XWPFRun nameRun = header.createRun();
        nameRun.setText(safe(proj.getName()));
        nameRun.setFontFamily(FONT);
        setHalfPointSize(nameRun, SZ_BODY);
        nameRun.setBold(true);

        if (proj.getTechnologies() != null && !proj.getTechnologies().isEmpty()) {
            XWPFRun techRun = header.createRun();
            techRun.setText(" | " + String.join(", ", proj.getTechnologies()));
            techRun.setFontFamily(FONT);
            setHalfPointSize(techRun, SZ_BODY);
            techRun.setItalic(true);
        }

        if (proj.getUrl() != null && !proj.getUrl().isBlank()) {
            XWPFRun urlRun = header.createRun();
            urlRun.setText(" | " + proj.getUrl());
            urlRun.setFontFamily(FONT);
            setHalfPointSize(urlRun, SZ_BODY);
        }

        if (proj.getDescription() != null && !proj.getDescription().isBlank()) {
            addBullet(doc, proj.getDescription().trim());
        }
    }

    // ── Justified paragraph (summary) ─────────────────────────────────────────

    private void addJustifiedPara(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.BOTH);
        setSpacing(para, SP_ZERO, SP_SMALL);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontFamily(FONT);
        setHalfPointSize(run, SZ_BODY);
    }

    // ── Page layout ───────────────────────────────────────────────────────────

    private void applyPageLayout(XWPFDocument doc) {
        CTDocument1 ctDoc = doc.getDocument();
        CTBody body = ctDoc.getBody();
        CTSectPr sectPr = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();

        CTPageSz pgSz = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        pgSz.setW(PAGE_W);
        pgSz.setH(PAGE_H);
        pgSz.setOrient(STPageOrientation.PORTRAIT);

        CTPageMar pgMar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        pgMar.setTop(MARGIN);
        pgMar.setBottom(MARGIN);
        pgMar.setLeft(MARGIN);
        pgMar.setRight(MARGIN);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setSpacing(XWPFParagraph para, BigInteger before, BigInteger after) {
        CTPPr pPr = getPPr(para);
        CTSpacing sp = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        sp.setBefore(before);
        sp.setAfter(after);
        sp.setLine(LINE_SINGLE);
        sp.setLineRule(STLineSpacingRule.AUTO);
    }

    private CTPPr getPPr(XWPFParagraph para) {
        return para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();
    }

    private void setHalfPointSize(XWPFRun run, int halfPoints) {
        run.setFontSize(halfPoints / 2);
    }

    private String safe(String s) { return s != null ? s : ""; }

    private String buildDates(String start, String end) {
        String s = start != null && !start.isBlank() ? start : "";
        String e = end != null && !end.isBlank() ? end : "Present";
        return s.isBlank() ? e : s + " \u2013 " + e;
    }

    private String buildDegree(GeneratedResumeContent.GeneratedEducation edu) {
        StringBuilder sb = new StringBuilder();
        if (edu.getDegree() != null) sb.append(edu.getDegree());
        if (edu.getField() != null && !edu.getField().isBlank()) {
            sb.append(sb.length() > 0 ? " in " : "").append(edu.getField());
        }
        return sb.toString();
    }

    private String buildInst(GeneratedResumeContent.GeneratedEducation edu) {
        StringBuilder sb = new StringBuilder();
        if (edu.getInstitution() != null) sb.append(edu.getInstitution());
        if (edu.getLocation() != null && !edu.getLocation().isBlank()) {
            sb.append(sb.length() > 0 ? ", " : "").append(edu.getLocation());
        }
        if (edu.getGraduationYear() != null && !edu.getGraduationYear().isBlank()) {
            sb.append(sb.length() > 0 ? " \u2013 " : "").append(edu.getGraduationYear());
        }
        return sb.toString();
    }

    private boolean shouldShowGpa(String gpa) {
        if (gpa == null || gpa.isBlank()) return false;
        try { return Double.parseDouble(gpa.trim().split("/")[0].trim()) >= 3.5; }
        catch (NumberFormatException e) { return false; }
    }

    // ── ATS validation ────────────────────────────────────────────────────────

    private void validateAtsParseable(byte[] bytes, GeneratedResumeContent c) {
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(reopened)) {
            String text = extractor.getText();
            if (text == null || text.isBlank()) { log.warn("ATS validation: empty text"); return; }
            if (c.getCandidateName() != null && !text.contains(c.getCandidateName()))
                log.warn("ATS validation: name not extractable");
            if (c.getEmail() != null && !c.getEmail().isBlank() && !text.contains(c.getEmail()))
                log.warn("ATS validation: email not extractable");
        } catch (Exception e) {
            log.warn("ATS validation failed: {}", e.getMessage());
        }
    }

    public String buildFilename(String candidateName) {
        if (candidateName == null || candidateName.isBlank()) return "Resume.docx";
        String[] parts = candidateName.trim().split("\\s+");
        return (parts.length == 1 ? parts[0] : parts[0] + "_" + parts[parts.length - 1]) + "_Resume.docx";
    }
}
