package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.JobDescription;
import com.resumestudio.reviewer.skills.EscoSkillGraph;
import com.resumestudio.reviewer.skills.MindTechOntology;
import com.resumestudio.reviewer.skills.SkillEmbeddingIndex;
import com.resumestudio.reviewer.nlp.TfIdfVectorizer;
import com.resumestudio.reviewer.nlp.PosTagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdParserServiceTest {

    @Mock
    private EscoSkillGraph escoGraph;
    
    @Mock
    private MindTechOntology mindTech;
    
    @Mock
    private SkillEmbeddingIndex embeddingIndex;
    
    @Mock
    private TfIdfVectorizer tfidfVectorizer;
    
    @Mock
    private PosTagService posTagService;

    @Mock
    private JdRolePatternsService rolePatternsService;

    private JdParserService parser;

    @BeforeEach
    void setUp() {
        // Default: unknown skill — returns input unchanged (no canonical form)
        when(escoGraph.isKnownSkill(anyString())).thenReturn(false);
        when(escoGraph.isTechnicalSkill(anyString())).thenReturn(false);
        when(escoGraph.resolve(anyString())).thenAnswer(inv -> inv.getArgument(0));
        
        // MIND-tech defaults
        when(mindTech.isKnownSkill(anyString())).thenReturn(false);
        when(mindTech.resolve(anyString())).thenReturn(null);
        when(mindTech.getImpliedSkills(anyString())).thenReturn(List.of());
        when(mindTech.getRoleSkillRelevance(anyString(), anyString())).thenReturn(0.5);
        
        // Configure implied skills for test cases
        when(mindTech.getImpliedSkills("Spring Boot")).thenReturn(List.of("Java", "Maven"));
        when(mindTech.getImpliedSkills("React")).thenReturn(List.of("JavaScript", "HTML", "CSS"));
        when(mindTech.getImpliedSkills("Kubernetes")).thenReturn(List.of("Docker", "Linux"));
        when(mindTech.getImpliedSkills("Angular")).thenReturn(List.of("TypeScript", "JavaScript"));
        when(mindTech.getImpliedSkills("Django")).thenReturn(List.of("Python"));
        when(mindTech.getImpliedSkills("Rails")).thenReturn(List.of("Ruby"));
        when(mindTech.getImpliedSkills("Terraform")).thenReturn(List.of("Cloud infrastructure"));
        when(mindTech.getImpliedSkills("Next.js")).thenReturn(List.of("React", "JavaScript"));
        
        // Embedding index defaults
        when(embeddingIndex.cosineSimilarity(anyString(), anyString())).thenReturn(0.3f);
        
        // TF-IDF defaults
        when(tfidfVectorizer.computeTfIdf(anyString(), any())).thenAnswer(inv -> {
            List<String> skills = inv.getArgument(1);
            Map<String, Double> result = new java.util.HashMap<>();
            for (String skill : skills) {
                result.put(skill, 0.5);
            }
            return result;
        });
        when(tfidfVectorizer.computePositionalWeight(anyString(), anyString())).thenReturn(0.8);
        
        // POS tagger defaults
        when(posTagService.isVerb(anyString(), anyString())).thenReturn(false);
        when(posTagService.isNoun(anyString(), anyString())).thenReturn(true);

        // Register the skills referenced by tests so the taxonomy lookup succeeds
        registerSkill("Java");
        registerSkill("Spring Boot");
        registerSkill("Kubernetes");
        registerSkill("Docker");
        registerSkill("Kotlin");
        registerSkill("Scala");
        registerSkill("Node.js", "node", "nodejs");
        registerSkill("PostgreSQL", "postgres");
        registerSkill("SQL");
        registerSkill("Redis");
        registerSkill("Kafka");
        registerSkill("Airflow");
        registerSkill("Spark");
        registerSkill("Snowflake");
        registerSkill("dbt");
        registerSkill(".NET");
        registerSkill("C#");
        registerSkill("Azure");
        registerSkill("React");
        registerSkill("AWS");
        registerSkill("GitOps");
        registerSkill("Terraform");
        registerSkill("Prometheus");
        registerSkill("Grafana");
        registerSkill("GCP");
        registerSkill("OpenStack");
        registerSkill("OpenTelemetry");
        registerSkill("Go", "golang");

        parser = new JdParserService(escoGraph, mindTech, embeddingIndex, tfidfVectorizer, posTagService,
                rolePatternsService);
    }

    /**
     * Makes the mock recognise a canonical skill and its aliases.
     * Tokens are lowercased before lookup in JdParserService (matching the real EscoSkillGraph
     * which calls toLowerCase() internally), so we register lowercase here.
     */
    private void registerSkill(String canonical, String... aliases) {
        String lc = canonical.toLowerCase();
        when(escoGraph.isKnownSkill(lc)).thenReturn(true);
        when(escoGraph.isTechnicalSkill(canonical)).thenReturn(true);
        when(escoGraph.isTechnicalSkill(lc)).thenReturn(true);
        when(escoGraph.resolve(lc)).thenReturn(canonical);
        for (String alias : aliases) {
            String alc = alias.toLowerCase();
            when(escoGraph.isKnownSkill(alc)).thenReturn(true);
            when(escoGraph.isTechnicalSkill(alias)).thenReturn(true);
            when(escoGraph.isTechnicalSkill(alc)).thenReturn(true);
            when(escoGraph.resolve(alc)).thenReturn(canonical);
        }
    }

    // ── Null / blank ──────────────────────────────────────────────────────────

    @Test
    void parse_null_returnsEmptyJd() {
        JobDescription jd = parser.parse(null);
        assertEquals(0.0, jd.getParseConfidence());
    }

    @Test
    void parse_blank_returnsEmptyJd() {
        JobDescription jd = parser.parse("   ");
        assertEquals(0.0, jd.getParseConfidence());
    }

    // ── Title extraction ──────────────────────────────────────────────────────

    @Test
    void extractTitle_fromExplicitLabel() {
        String text = "Position: Senior Backend Engineer\n\nWe need strong Java skills and cloud experience.";
        JobDescription jd = parser.parse(text);
        // The label match captures up to 50 chars of word+whitespace — assert starts with the title
        assertTrue(jd.getRoleTitle().trim().startsWith("Senior Backend Engineer"));
    }

    @Test
    void extractTitle_fromFirstMatchingLine() {
        // No explicit label keywords — fallback to first line containing "engineer"
        String text = "Senior Backend Engineer\n\nJoin our great team and build amazing software.";
        JobDescription jd = parser.parse(text);
        assertEquals("Senior Backend Engineer", jd.getRoleTitle());
    }

    @Test
    void extractTitle_fallsBackToUnknownRole() {
        String text = "We need someone. Please apply. Contact us today.";
        JobDescription jd = parser.parse(text);
        assertEquals("Unknown Role", jd.getRoleTitle());
    }

    @Test
    void extractTitle_rejectsRequirementBulletThatContainsDeveloperKeyword() {
        String text = "* Experience with CI/CD, GitOps, or developer platform enablement practices.\n" +
            "* Experience with Kubernetes, Terraform, and Prometheus.";
        JobDescription jd = parser.parse(text);
        assertEquals("Unknown Role", jd.getRoleTitle());
    }

    // ── YOE extraction ────────────────────────────────────────────────────────

    @Test
    void extractYoe_rangePattern() {
        String text = "Senior Engineer\n\nRequirements:\n3-5 years of Java experience";
        JobDescription jd = parser.parse(text);
        assertEquals(3.0, jd.getYoeMin());
        assertEquals(5.0, jd.getYoeMax());
        assertNotNull(jd.getYoeRawStatement());
    }

    @Test
    void extractYoe_plusPattern() {
        String text = "Backend Engineer\n\nRequirements:\n5+ years experience with distributed systems";
        JobDescription jd = parser.parse(text);
        assertEquals(5.0, jd.getYoeMin());
        assertNull(jd.getYoeMax()); // open-ended
    }

    @Test
    void extractYoe_atLeastPattern() {
        String text = "Engineer\n\nMinimum qualifications:\nAt least 3 years of experience with Java.";
        JobDescription jd = parser.parse(text);
        assertEquals(3.0, jd.getYoeMin());
    }

    @Test
    void extractYoe_noYoeMentioned() {
        String text = "Backend Engineer\n\nRequirements:\n- Java\n- Docker";
        JobDescription jd = parser.parse(text);
        assertNull(jd.getYoeMin());
    }

    // ── Skills extraction ─────────────────────────────────────────────────────

    @Test
    void extractSkills_mustHaveSection() {
        String text = "Senior Backend Engineer\n\nRequirements\nJava\nSpring Boot\nKubernetes";
        JobDescription jd = parser.parse(text);
        assertFalse(jd.getMustHaveSkills().isEmpty());
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("Java")));
    }

    @Test
    void extractSkills_niceToHaveSection() {
        String text = "Engineer\n\nRequirements\n- Java\n\nNice-to-have\n- Kotlin\n- Scala";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.getNiceToHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("Kotlin")));
    }

    @Test
    void extractSkills_deduplicates() {
        String text = "Backend Engineer\n\nRequirements\n- Java\n- Java\n- Docker";
        JobDescription jd = parser.parse(text);
        long javaCount = jd.getMustHaveSkills().stream()
            .filter(s -> s.equalsIgnoreCase("Java")).count();
        assertEquals(1, javaCount);
    }

    @Test
    void extractSkills_filtersGenericNoiseEvenWhenTaxonomyKnowsTheWord() {
        when(escoGraph.isKnownSkill("teams")).thenReturn(true);
        when(escoGraph.resolve("teams")).thenReturn("teams");
        when(escoGraph.isTechnicalSkill("teams")).thenReturn(false);
        when(escoGraph.isKnownSkill("tools")).thenReturn(true);
        when(escoGraph.resolve("tools")).thenReturn("tools");
        when(escoGraph.isTechnicalSkill("tools")).thenReturn(false);
        when(escoGraph.isKnownSkill("meet")).thenReturn(true);
        when(escoGraph.resolve("meet")).thenReturn("meet");
        when(escoGraph.isTechnicalSkill("meet")).thenReturn(false);

        String text = "Platform Engineer\n\nRequirements\n" +
            "- Experience with Kubernetes, OpenStack, Azure, GCP, Go, Prometheus, Grafana, Terraform, GitOps, and OpenTelemetry\n" +
            "- Work across teams to build tools and meet stakeholders";

        JobDescription jd = parser.parse(text);
        List<String> extractedSkills = new java.util.ArrayList<>(jd.getMustHaveSkills());
        extractedSkills.addAll(jd.getNiceToHaveSkills());

        assertTrue(extractedSkills.contains("Kubernetes"));
        assertTrue(extractedSkills.contains("Terraform"));
        assertTrue(extractedSkills.contains("GitOps"));
        assertFalse(extractedSkills.contains("teams"));
        assertFalse(extractedSkills.contains("tools"));
        assertFalse(extractedSkills.contains("meet"));
    }

    // ── Implied skills ────────────────────────────────────────────────────────

    @Test
    void inferImpliedSkills_springBootImpliesJava() {
        String text = "Backend Engineer\n\nRequirements\n- Spring Boot\n- Docker";
        JobDescription jd = parser.parse(text);
        // Spring Boot implies Java
        assertFalse(jd.getImpliedSkills().isEmpty());
        assertTrue(jd.getImpliedSkills().contains("Java"));
    }

    @Test
    void inferImpliedSkills_reactImpliesJavaScript() {
        String text = "Frontend Engineer\n\nRequirements\n- React\n- Docker";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.getImpliedSkills().contains("JavaScript"));
    }

    // ── IC level ──────────────────────────────────────────────────────────────

    @Test
    void icLevel_seniorDetected() {
        String text = "Senior Backend Engineer\n\nRequirements\n- Java\n5+ years experience";
        JobDescription jd = parser.parse(text);
        assertEquals(4, jd.getIcLevel()); // senior = 4
    }

    @Test
    void icLevel_juniorDetected() {
        String text = "Junior Software Engineer\n\nRequirements\n- Java";
        JobDescription jd = parser.parse(text);
        assertEquals(1, jd.getIcLevel()); // junior = 1
    }

    @Test
    void icLevel_defaultsMidLevel() {
        String text = "Software Engineer\n\nRequirements\n- Java";
        JobDescription jd = parser.parse(text);
        assertEquals(3, jd.getIcLevel()); // default mid
    }

    // ── Context detection ─────────────────────────────────────────────────────

    @Test
    void detectContext_remote() {
        String text = "Backend Engineer\n\nThis is a fully remote role.";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.isRemote());
    }

    @Test
    void detectContext_notRemote() {
        String text = "Backend Engineer\n\nOffice-based in London.";
        JobDescription jd = parser.parse(text);
        assertFalse(jd.isRemote());
    }

    @Test
    void detectContext_startupCulture() {
        String text = "Backend Engineer at a fast-paced startup.\n\nRequirements\n- Java";
        JobDescription jd = parser.parse(text);
        assertEquals("fast-paced startup", jd.getCompanyCulture());
    }

    @Test
    void detectContext_enterprise() {
        String text = "Backend Engineer\n\nFortune 500 enterprise looking for talent.\n\nRequirements\n- Java";
        JobDescription jd = parser.parse(text);
        assertEquals("enterprise", jd.getCompanyCulture());
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    @Test
    void confidence_highWhenAllPresent() {
        String text = "Requirements\nSenior Backend Engineer\n\n3-5 years\nJava, Spring Boot, Docker\n5+ years";
        JobDescription jd = parser.parse(text);
        // Title (0.3) + skills (0.4) + yoe (0.2) = 0.9
        assertTrue(jd.getParseConfidence() >= 0.6);
    }

    @Test
    void wellStructured_whenHasRequirementsSection() {
        String text = "Backend Engineer\n\nRequirements\n- Java\n- Docker";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.isWellStructured());
    }

    @Test
    void wellStructured_false_whenUnstructured() {
        String text = "We need a Java developer with Docker experience.";
        JobDescription jd = parser.parse(text);
        assertFalse(jd.isWellStructured());
    }

    // ── Pure-text / narrative JD handling ───────────────────────────────────���─

    @Test
    void extractTitle_fromNarrativeSeekingSentence() {
        // Title embedded in a long sentence — was previously returning "Unknown Role"
        String text = "We are looking for a talented Senior Backend Engineer to join our growing platform team.";
        JobDescription jd = parser.parse(text);
        assertNotEquals("Unknown Role", jd.getRoleTitle());
        assertTrue(jd.getRoleTitle().toLowerCase().contains("engineer"));
    }

    @Test
    void extractTitle_fromHiringNarrativeLine() {
        String text = "We're hiring an experienced Staff Software Engineer to lead our infrastructure team.";
        JobDescription jd = parser.parse(text);
        assertNotEquals("Unknown Role", jd.getRoleTitle());
        assertTrue(jd.getRoleTitle().toLowerCase().contains("engineer"));
    }

    @Test
    void extractSkills_pureTextParagraph_noSectionHeaders() {
        // Common real-world JD: no "Requirements" header, skills inline in prose
        String text = "We are building a high-scale payments platform and need a Backend Engineer " +
            "with strong Java and Spring Boot skills. The ideal candidate is comfortable with " +
            "AWS, Docker, and PostgreSQL. Experience with Kafka is a plus.";
        JobDescription jd = parser.parse(text);
        assertFalse(jd.getMustHaveSkills().isEmpty());
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("Java")));
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("Spring Boot")));
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s ->
            s.equalsIgnoreCase("PostgreSQL") || s.equalsIgnoreCase("Postgres")));
    }

    @Test
    void extractSkills_commonAliases_nodeAndPostgres() {
        // "Node" and "Postgres" are common shorthands not previously in the list
        String text = "Backend Engineer\n\nRequirements\n- Node\n- Postgres\n- SQL";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s ->
            s.equalsIgnoreCase("Node.js") || s.equalsIgnoreCase("Node")));
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s ->
            s.equalsIgnoreCase("PostgreSQL") || s.equalsIgnoreCase("Postgres")));
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("SQL")));
    }

    @Test
    void extractSkills_contextPhrases_experienceWith() {
        // "experience with X" pattern in prose should surface X as a skill
        String text = "Senior Data Engineer\n\nYou will have experience with Airflow and Spark, " +
            "and solid knowledge of Snowflake and dbt.";
        JobDescription jd = parser.parse(text);
        assertFalse(jd.getMustHaveSkills().isEmpty());
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("Spark")));
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s ->
            s.equalsIgnoreCase("Airflow") || s.equalsIgnoreCase("Snowflake")));
    }

    @Test
    void extractSkills_dotNet_recognised() {
        String text = "Backend Developer\n\nRequirements\n- .NET\n- C#\n- Azure";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s ->
            s.equalsIgnoreCase(".NET") || s.equalsIgnoreCase("dotnet")));
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("C#")));
    }

    @Test
    void extractSkills_pureText_noSkillsFromJd_returnsEmptyNotVacuousPass() {
        // Completely generic JD with no recognisable tech terms
        String text = "We need a hard-working team player who communicates well and delivers results on time.";
        JobDescription jd = parser.parse(text);
        // Parser should not crash; mustHaves may be empty
        assertNotNull(jd.getMustHaveSkills());
    }
}
