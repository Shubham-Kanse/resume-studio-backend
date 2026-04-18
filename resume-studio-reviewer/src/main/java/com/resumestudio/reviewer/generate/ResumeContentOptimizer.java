package com.resumestudio.reviewer.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.reviewer.AiProperties;
import com.resumestudio.reviewer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calls the LLM to transform raw parsed resume data + a job description into
 * a perfectly optimized, ATS-beating resume JSON structure.
 *
 * The generated JSON is then rendered into a .docx by DocxBuilder.
 *
 * Core optimization objectives:
 *  1. Achieve 90-100% ATS keyword compatibility against the JD
 *  2. Rewrite every bullet in STAR-T format with quantified results
 *  3. Apply keyword density strategy: primary 3-5x, secondary 2-3x
 *  4. Generate a 100-150 word professional summary using the spec formula
 *  5. Order skills section with JD must-haves first (15-25 total)
 *  6. Enforce ATS structural rules: dates as MM/YYYY, no forbidden chars
 *
 * TRUTHFULNESS GUARANTEE: The optimizer never fabricates companies, titles,
 * dates, or metrics. It only reframes and quantifies real experience.
 */
@Service
public class ResumeContentOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ResumeContentOptimizer.class);

    private static final int MAX_PROMPT_CHARS = 24000; // Kimi K2 supports 262k context
    private static final int LLM_MAX_TOKENS   = 8000;  // Kimi K2 supports 16k output
    private static final double LLM_TEMPERATURE = 0.25;

    private final AiProperties ai;
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    public ResumeContentOptimizer(AiProperties ai) {
        this.ai = ai;
    }

    /**
     * Optimize resume content against the job description.
     * Returns fully structured content ready for DOCX rendering.
     */
    public GeneratedResumeContent optimize(Resume resume, JobDescription jd) {
        String prompt = buildPrompt(resume, jd);
        prompt = truncatePrompt(prompt);

        try {
            String raw = callLlm(prompt);
            GeneratedResumeContent result = parseResponse(raw, resume);
            result.setGenerationMode(GeneratedResumeContent.GenerationMode.OPTIMIZED);
            log.info("LLM optimization complete: {} experience entries, {} skill categories",
                result.getExperience().size(), result.getSkills().size());
            return result;
        } catch (Exception first) {
            log.warn("LLM call failed ({}), retrying once", first.getMessage());
            try {
                String raw = callLlm(prompt + "\n\nIMPORTANT: Output ONLY raw JSON — no markdown, no code blocks.");
                GeneratedResumeContent result = parseResponse(raw, resume);
                result.setGenerationMode(GeneratedResumeContent.GenerationMode.OPTIMIZED);
                return result;
            } catch (Exception second) {
                log.warn("LLM retry failed ({}), building passthrough content", second.getMessage());
                GeneratedResumeContent fallback = buildPassthrough(resume, jd);
                fallback.setGenerationMode(GeneratedResumeContent.GenerationMode.PASSTHROUGH);
                return fallback;
            }
        }
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private String buildPrompt(Resume resume, JobDescription jd) {
        StringBuilder sb = new StringBuilder();

        sb.append(ATS_RULES).append("\n\n");

        // JD analysis block
        sb.append("=== TARGET JOB DESCRIPTION ===\n");
        sb.append("Role Title: ").append(safe(jd.getRoleTitle())).append("\n");
        if (jd.getYoeMin() != null) {
            sb.append("Years of Experience Required: ").append(jd.getYoeMin().intValue());
            if (jd.getYoeMax() != null) sb.append("–").append(jd.getYoeMax().intValue());
            sb.append("+\n");
        }
        if (!jd.getMustHaveSkills().isEmpty()) {
            sb.append("PRIMARY KEYWORDS (must appear 3-5x each across resume): ")
              .append(String.join(", ", jd.getMustHaveSkills())).append("\n");
        }
        if (!jd.getNiceToHaveSkills().isEmpty()) {
            sb.append("SECONDARY KEYWORDS (must appear 2-3x each): ")
              .append(String.join(", ", jd.getNiceToHaveSkills())).append("\n");
        }
        if (jd.getCompanyCulture() != null) {
            sb.append("Company Culture Keywords: ").append(jd.getCompanyCulture()).append("\n");
        }
        if (jd.getTrimmedText() != null) {
            sb.append("JD Excerpt:\n").append(jd.getTrimmedText()).append("\n");
        }

        sb.append("\n=== CANDIDATE DATA ===\n");
        sb.append("Name: ").append(safe(resume.getCandidateName())).append("\n");
        sb.append("Email: ").append(safe(resume.getEmail())).append("\n");
        sb.append("Phone: ").append(safe(resume.getPhone())).append("\n");
        sb.append("Location: ").append(safe(resume.getLocation())).append("\n");
        if (resume.getLinkedInUrl() != null) sb.append("LinkedIn: ").append(resume.getLinkedInUrl()).append("\n");
        if (resume.getGitHubUrl() != null) sb.append("GitHub: ").append(resume.getGitHubUrl()).append("\n");
        sb.append("Current Title: ").append(safe(resume.getCurrentTitle())).append("\n");
        if (resume.getTotalYoeYears() != null) {
            sb.append("Total YOE: ").append(String.format("%.1f", resume.getTotalYoeYears())).append(" years\n");
        }
        if (resume.getSummaryText() != null) {
            sb.append("Existing Summary: ").append(resume.getSummaryText()).append("\n");
        }

        // Experience
        sb.append("\nExperience:\n");
        if (resume.getExperience() != null) {
            for (WorkExperience exp : resume.getExperience()) {
                sb.append("  ROLE: ").append(safe(exp.getTitle()))
                  .append(" at ").append(safe(exp.getCompany())).append("\n");
                sb.append("  DATES: ").append(formatDate(exp.getStartDate()))
                  .append(" – ").append(exp.isCurrent() ? "Present" : formatDate(exp.getEndDate())).append("\n");
                if (exp.getCompanyDescriptor() != null) {
                    sb.append("  CONTEXT: ").append(exp.getCompanyDescriptor()).append("\n");
                }
                if (exp.getBullets() != null && !exp.getBullets().isEmpty()) {
                    sb.append("  BULLETS:\n");
                    exp.getBullets().stream().limit(5).forEach(b ->
                        sb.append("    - ").append(b).append("\n"));
                }
                sb.append("\n");
            }
        }

        // Skills
        if (resume.getSkills() != null && !resume.getSkills().isEmpty()) {
            sb.append("Skills: ").append(
                resume.getSkills().stream()
                    .map(Skill::getRawName)
                    .filter(Objects::nonNull)
                    .limit(40)
                    .collect(Collectors.joining(", ")))
              .append("\n");
        }

        // Education
        if (resume.getEducation() != null && !resume.getEducation().isEmpty()) {
            sb.append("\nEducation:\n");
            for (Education edu : resume.getEducation()) {
                sb.append("  ").append(safe(edu.getDegree()))
                  .append(" in ").append(safe(edu.getField()))
                  .append(", ").append(safe(edu.getInstitution()));
                if (edu.getGraduationYear() != null) sb.append(" (").append(edu.getGraduationYear()).append(")");
                sb.append("\n");
            }
        }

        // Projects
        if (resume.getProjects() != null && !resume.getProjects().isEmpty()) {
            sb.append("\nProjects:\n");
            for (Project proj : resume.getProjects()) {
                sb.append("  ").append(safe(proj.getName()));
                if (proj.getTechStack() != null && !proj.getTechStack().isEmpty()) {
                    sb.append(" [").append(String.join(", ", proj.getTechStack())).append("]");
                }
                if (proj.getDescription() != null) sb.append(": ").append(proj.getDescription());
                sb.append("\n");
            }
        }

        sb.append("\n").append(OUTPUT_INSTRUCTIONS).append("\n\n");
        sb.append(JSON_SCHEMA);

        return sb.toString();
    }

    // ── LLM call ──────────────────────────────────────────────────────────────

    private String callLlm(String prompt) throws Exception {
        String body = buildRequestBody(prompt);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(ai.getUrl()))
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer " + ai.getKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("LLM returned " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(200, resp.body().length())));

        JsonNode root = mapper.readTree(resp.body());
        String content = root.path("choices").get(0).path("message").path("content").asText();

        // Strip markdown code fences
        content = content.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
        // Extract the JSON object
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) content = content.substring(start, end + 1);

        mapper.readTree(content); // validate
        return content;
    }

    private String buildRequestBody(String prompt) throws Exception {
        Map<String, Object> systemMessage = Map.of("role", "system", "content", SYSTEM_ROLE);
        Map<String, Object> userMessage = Map.of("role", "user", "content", prompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ai.getModel());
        body.put("messages", List.of(systemMessage, userMessage));
        body.put("temperature", LLM_TEMPERATURE);
        body.put("max_tokens", LLM_MAX_TOKENS);
        return mapper.writeValueAsString(body);
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private GeneratedResumeContent parseResponse(String json, Resume fallbackResume) throws Exception {
        JsonNode root = mapper.readTree(json);
        GeneratedResumeContent c = new GeneratedResumeContent();

        c.setCandidateName(text(root, "candidateName", fallbackResume.getCandidateName()));
        c.setEmail(text(root, "email", fallbackResume.getEmail()));
        c.setPhone(text(root, "phone", fallbackResume.getPhone()));
        c.setLocation(text(root, "location", fallbackResume.getLocation()));
        c.setLinkedIn(text(root, "linkedIn", fallbackResume.getLinkedInUrl()));
        c.setGitHub(text(root, "gitHub", fallbackResume.getGitHubUrl()));
        c.setTargetTitle(text(root, "targetTitle", fallbackResume.getCurrentTitle()));
        c.setSummary(text(root, "summary", null));

        // Experience
        JsonNode expArray = root.path("experience");
        if (expArray.isArray()) {
            List<GeneratedResumeContent.GeneratedExperience> list = new ArrayList<>();
            for (JsonNode e : expArray) {
                GeneratedResumeContent.GeneratedExperience exp = new GeneratedResumeContent.GeneratedExperience();
                exp.setTitle(e.path("title").asText(null));
                exp.setCompany(e.path("company").asText(null));
                exp.setCompanyDescriptor(e.path("companyDescriptor").asText(null));
                exp.setStartDate(e.path("startDate").asText(null));
                exp.setEndDate(e.path("endDate").asText(null));
                List<String> bullets = new ArrayList<>();
                for (JsonNode b : e.path("bullets")) {
                    if (bullets.size() >= 5) break;
                    String t = b.asText();
                    if (t != null && !t.isBlank()) bullets.add(t);
                }
                exp.setBullets(bullets);
                if (exp.getCompany() != null) list.add(exp);
            }
            c.setExperience(list);
        }

        // Skills — enforce 15-25 total cap from spec
        JsonNode skillsArray = root.path("skills");
        if (skillsArray.isArray()) {
            List<GeneratedResumeContent.GeneratedSkillCategory> list = new ArrayList<>();
            int totalSkills = 0;
            int skillCap = 25;
            for (JsonNode s : skillsArray) {
                if (totalSkills >= skillCap) break;
                GeneratedResumeContent.GeneratedSkillCategory cat = new GeneratedResumeContent.GeneratedSkillCategory();
                cat.setCategory(s.path("category").asText(null));
                List<String> items = new ArrayList<>();
                for (JsonNode item : s.path("items")) {
                    if (totalSkills >= skillCap) break;
                    String t = item.asText();
                    if (t != null && !t.isBlank()) {
                        items.add(t);
                        totalSkills++;
                    }
                }
                cat.setItems(items);
                if (!items.isEmpty()) list.add(cat);
            }
            c.setSkills(list);
        }

        // Education
        JsonNode eduArray = root.path("education");
        if (eduArray.isArray()) {
            List<GeneratedResumeContent.GeneratedEducation> list = new ArrayList<>();
            for (JsonNode e : eduArray) {
                GeneratedResumeContent.GeneratedEducation edu = new GeneratedResumeContent.GeneratedEducation();
                edu.setDegree(e.path("degree").asText(null));
                edu.setField(e.path("field").asText(null));
                edu.setInstitution(e.path("institution").asText(null));
                edu.setLocation(e.path("location").asText(null));
                edu.setGraduationYear(e.path("graduationYear").asText(null));
                edu.setGpa(emptyToNull(e.path("gpa").asText(null)));
                List<String> honors = new ArrayList<>();
                for (JsonNode h : e.path("honors")) honors.add(h.asText());
                edu.setHonors(honors);
                if (edu.getInstitution() != null) list.add(edu);
            }
            c.setEducation(list);
        }

        // Projects
        JsonNode projArray = root.path("projects");
        if (projArray.isArray()) {
            List<GeneratedResumeContent.GeneratedProject> list = new ArrayList<>();
            for (JsonNode p : projArray) {
                GeneratedResumeContent.GeneratedProject proj = new GeneratedResumeContent.GeneratedProject();
                proj.setName(p.path("name").asText(null));
                List<String> tech = new ArrayList<>();
                for (JsonNode t : p.path("technologies")) tech.add(t.asText());
                proj.setTechnologies(tech);
                proj.setUrl(emptyToNull(p.path("url").asText(null)));
                proj.setDescription(p.path("description").asText(null));
                if (proj.getName() != null) list.add(proj);
            }
            c.setProjects(list);
        }

        return c;
    }

    // ── Pass-through (LLM unavailable) ───────────────────────────────────────

    /**
     * When the LLM is unavailable, build a structurally correct content object
     * directly from parsed resume data with minimal transformation.
     * Skills are reordered to put JD must-haves first.
     */
    private GeneratedResumeContent buildPassthrough(Resume resume, JobDescription jd) {
        log.info("Building passthrough content (no LLM)");
        GeneratedResumeContent c = new GeneratedResumeContent();

        c.setCandidateName(resume.getCandidateName());
        c.setEmail(resume.getEmail());
        c.setPhone(resume.getPhone());
        c.setLocation(resume.getLocation());
        c.setLinkedIn(resume.getLinkedInUrl());
        c.setGitHub(resume.getGitHubUrl());
        c.setTargetTitle(resume.getCurrentTitle());
        c.setSummary(resume.getSummaryText());

        // Experience → passthrough
        if (resume.getExperience() != null) {
            List<GeneratedResumeContent.GeneratedExperience> expList = new ArrayList<>();
            for (WorkExperience exp : resume.getExperience()) {
                GeneratedResumeContent.GeneratedExperience ge = new GeneratedResumeContent.GeneratedExperience();
                ge.setTitle(exp.getTitle());
                ge.setCompany(exp.getCompany());
                ge.setCompanyDescriptor(exp.getCompanyDescriptor());
                ge.setStartDate(formatDate(exp.getStartDate()));
                ge.setEndDate(exp.isCurrent() ? "Present" : formatDate(exp.getEndDate()));
                ge.setBullets(exp.getBullets() != null
                    ? exp.getBullets().stream().limit(5).collect(Collectors.toList())
                    : List.of());
                expList.add(ge);
            }
            c.setExperience(expList);
        }

        // Skills → reorder JD must-haves first
        if (resume.getSkills() != null) {
            Set<String> mustHaveLower = jd.getMustHaveSkills().stream()
                .map(String::toLowerCase).collect(Collectors.toSet());
            List<String> primary = new ArrayList<>();
            List<String> secondary = new ArrayList<>();
            for (Skill s : resume.getSkills()) {
                String name = s.getRawName() != null ? s.getRawName() : s.getCanonicalName();
                if (name == null) continue;
                if (mustHaveLower.contains(name.toLowerCase())) primary.add(name);
                else secondary.add(name);
            }
            primary.addAll(secondary);

            // Group into a single "Technical Skills" category
            GeneratedResumeContent.GeneratedSkillCategory cat = new GeneratedResumeContent.GeneratedSkillCategory();
            cat.setCategory("Technical Skills");
            cat.setItems(primary.stream().limit(25).collect(Collectors.toList()));
            if (!cat.getItems().isEmpty()) c.setSkills(List.of(cat));
        }

        // Education → passthrough
        if (resume.getEducation() != null) {
            List<GeneratedResumeContent.GeneratedEducation> eduList = new ArrayList<>();
            for (Education edu : resume.getEducation()) {
                GeneratedResumeContent.GeneratedEducation ge = new GeneratedResumeContent.GeneratedEducation();
                ge.setDegree(edu.getDegree());
                ge.setField(edu.getField());
                ge.setInstitution(edu.getInstitution());
                ge.setGraduationYear(edu.getGraduationYear() != null ? edu.getGraduationYear().toString() : null);
                eduList.add(ge);
            }
            c.setEducation(eduList);
        }

        // Projects → passthrough
        if (resume.getProjects() != null) {
            List<GeneratedResumeContent.GeneratedProject> projList = new ArrayList<>();
            for (Project proj : resume.getProjects()) {
                GeneratedResumeContent.GeneratedProject gp = new GeneratedResumeContent.GeneratedProject();
                gp.setName(proj.getName());
                gp.setTechnologies(proj.getTechStack() != null ? proj.getTechStack() : List.of());
                gp.setUrl(proj.getUrl());
                gp.setDescription(proj.getDescription());
                projList.add(gp);
            }
            c.setProjects(projList);
        }

        return c;
    }

    // ── Prompt truncation ─────────────────────────────────────────────────────

    private String truncatePrompt(String prompt) {
        if (prompt.length() <= MAX_PROMPT_CHARS) return prompt;

        // Drop per-role bullets first (keep 3 bullets per role max)
        // Then truncate at last sentence boundary
        String trimmed = prompt.replaceAll("(?m)(    - .+\n){4,}", "    - [additional bullets omitted]\n");
        if (trimmed.length() <= MAX_PROMPT_CHARS) return trimmed;

        trimmed = trimmed.substring(0, MAX_PROMPT_CHARS);
        int lastNewline = trimmed.lastIndexOf('\n');
        return lastNewline > MAX_PROMPT_CHARS / 2 ? trimmed.substring(0, lastNewline) : trimmed;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private String text(JsonNode node, String key, String fallback) {
        String v = node.path(key).asText(null);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private String safe(String s) { return s != null ? s : ""; }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank() || "null".equals(s)) ? null : s;
    }

    private String formatDate(LocalDate date) {
        if (date == null) return "";
        return date.format(DateTimeFormatter.ofPattern("MM/yyyy"));
    }

    // ── Prompt constants ──────────────────────────────────────────────────────

    private static final String SYSTEM_ROLE = """
        You are an elite technical resume writer with deep expertise in ATS optimization, \
        hired by FAANG+ companies to filter 10,000+ resumes per year. \
        Your job is to transform a candidate's raw resume data into a perfectly optimized, \
        ATS-crushing resume that maximizes interview callback rate. \
        You produce output ONLY as valid JSON. Never add commentary or markdown formatting.""";

    private static final String ATS_RULES = """
        === ATS OPTIMIZATION RULES (NON-NEGOTIABLE) ===

        KEYWORD STRATEGY:
        - Primary keywords (JD must-haves): integrate NATURALLY 3-5 times each across the full resume
          → In the summary (first 50 words), in the skills section, and woven into experience bullets
        - Secondary keywords (JD nice-to-haves): integrate 2-3 times each
        - Job title: must appear in summary opening line AND match experience titles
        - Acronym rule: on FIRST use, spell out then abbreviate: "Kubernetes (K8s)" — then just "K8s" after

        PROFESSIONAL SUMMARY (100-150 words):
        Use this exact formula:
        - Sentence 1 (OPENING): "[X]+ year [role level] [exact JD title] with proven track record in [primary domain]"
        - Sentence 2 (TECHNICAL DEPTH): "Expert in [top 4-5 JD must-have skills, comma-separated]"
        - Sentence 3-4 (QUANTIFIED WINS): 2-3 specific achievements that DIRECTLY MATCH the JD requirements, each with a metric
        - Sentence 5 (VALUE PROP + CULTURAL FIT): "[Unique strength relevant to this JD role]"

        EXPERIENCE BULLETS (3-5 bullets per role — no fewer, no more):
        Use the STAR-T formula for EVERY bullet:
          [Strong Action Verb] + [Technical Implementation] + [Business Context] + [QUANTIFIED Result] + [Technologies Used]

        TIER 1 VERBS (for senior/leadership bullets): Architected, Led, Directed, Spearheaded, Championed
        TIER 2 VERBS (for achievement bullets): Delivered, Generated, Reduced, Accelerated, Exceeded
        TIER 3 VERBS (for improvement bullets): Optimized, Streamlined, Automated, Transformed, Enhanced
        TIER 4 VERBS (for technical bullets): Designed, Built, Implemented, Engineered, Deployed

        QUANTIFICATION REQUIREMENTS (TRUTHFULNESS-FIRST):
        - Prefer a quantified result on every bullet using one of:
          → Percentage: "reduced latency by 45%"
          → Dollar amount: "saved $1.2M annually"
          → Time: "from 4 hours to 8 minutes"
          → Scale: "serving 2M+ daily active users"
          → Team size: "led team of 12 engineers"
          → Coverage: "achieving 95%+ test coverage"
        - ONLY use a specific number if it is present in the candidate's source data
          (resume bullets, summary, project descriptions). NEVER invent a number.
        - If the source has no metric for a bullet, fall back IN THIS ORDER:
          1) Scope ("across 3 business units", "for 4 product teams") — only if scope is in source
          2) Frequency ("multiple deployments per week") — qualitative, no fake counts
          3) Relative impact ("substantially reduced", "significantly improved")
        - A bullet WITHOUT a metric is acceptable; a bullet with a FABRICATED metric is not.
        - When in doubt, choose truthful relative language over a guessed number.

        SKILLS SECTION (15-25 skills total):
        - Start with JD must-have skills (they must appear FIRST in their category)
        - Group into 3-5 role-relevant categories
        - Use EXACT terminology from the JD (e.g., if JD says "AWS", list "AWS" not "Amazon Web Services")
        - Total count: between 15 and 25 skills maximum

        DATE FORMAT: All dates must be MM/YYYY (e.g., "06/2021" not "June 2021" or "2021-06")

        TRUTHFULNESS (ABSOLUTE RULE):
        - NEVER invent companies, titles, employment dates, or certifications
        - NEVER claim a technology the candidate hasn't used
        - ONLY reframe and quantify real experience using the data provided
        - If you cannot confidently quantify something, use relative language ("significantly", "substantially")
          rather than a specific number you're guessing at
        - Contact information must be preserved EXACTLY as given — never modify name, email, phone, or dates""";

    private static final String OUTPUT_INSTRUCTIONS = """
        === TASK ===
        Using the JD data and candidate data above, generate a fully optimized resume JSON.

        Requirements:
        1. The summary must naturally contain the job title and top 4-5 JD must-have keywords in the first 50 words
        2. Bullets should be quantified WHENEVER the source data supports it; otherwise use scope or relative-impact language. Never invent numbers.
        3. Skills section must list JD must-haves first, total 15-25 skills
        4. All dates must be in MM/YYYY format
        5. Experience is ordered newest-first (as provided)
        6. The targetTitle should closely match the JD role title
        7. Do NOT fabricate experience, skills, companies, dates, or metrics
        8. Output ONLY the JSON object — no markdown, no preamble, no postscript""";

    private static final String JSON_SCHEMA = """
        Output this exact JSON schema (replace all placeholder values):
        {
          "candidateName": "Full Name",
          "email": "email@example.com",
          "phone": "+1 555-0123",
          "location": "City, ST",
          "linkedIn": "linkedin.com/in/handle",
          "gitHub": "github.com/handle",
          "targetTitle": "JD-matched role title",
          "summary": "100-150 word professional summary following the formula above",
          "experience": [
            {
              "title": "Job Title",
              "company": "Company Name",
              "companyDescriptor": "brief context e.g. Series B fintech or null",
              "startDate": "MM/YYYY",
              "endDate": "MM/YYYY or Present",
              "bullets": [
                "TIER-verb + technical implementation + quantified result + technology",
                "TIER-verb + technical implementation + quantified result + technology",
                "TIER-verb + technical implementation + quantified result + technology"
              ]
            }
          ],
          "skills": [
            {
              "category": "Category Name",
              "items": ["Skill1", "Skill2", "Skill3"]
            }
          ],
          "education": [
            {
              "degree": "Bachelor of Science",
              "field": "Computer Science",
              "institution": "University Name",
              "location": "City, ST",
              "graduationYear": "MM/YYYY or YYYY",
              "gpa": "3.8 or null",
              "honors": ["Dean's List"]
            }
          ],
          "projects": [
            {
              "name": "Project Name",
              "technologies": ["Tech1", "Tech2"],
              "url": "github.com/user/project or null",
              "description": "Problem solved, approach, and quantified impact in 1-2 sentences"
            }
          ]
        }""";
}
