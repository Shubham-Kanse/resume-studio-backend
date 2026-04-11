package com.resumestudio.reviewer.skills;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads and exposes the ESCO skill taxonomy from esco.bin.
 *
 * esco.bin is expected to be a Java-serialized Map with structure:
 *   Map<String, EscoSkillNode> where key = canonical skill name (lowercase)
 *
 * Each EscoSkillNode contains:
 *   - canonicalName  : official ESCO name
 *   - category       : ESCO skill group (e.g. "Programming Language", "Framework")
 *   - synonyms       : List<String> of known aliases
 *   - relatedSkills  : List<String> of related canonical names
 *   - releaseYear    : Integer — when the technology was first released (for age checks)
 *
 * If esco.bin uses Kryo serialization instead of Java serialization,
 * swap the loadWithJavaSerialization() call for loadWithKryo().
 *
 * To check your format:
 *   xxd esco.bin | head -1
 *   → ACED 0005  = Java serialization   (use loadWithJavaSerialization)
 *   → EA 02      = Kryo                 (use loadWithKryo — add Kryo dependency)
 */
@Component
public class EscoSkillGraph {

    private static final Logger log = LoggerFactory.getLogger(EscoSkillGraph.class);

    @Value("${reviewer.esco.path:classpath:taxonomy/esco-index-v1.bin}")
    private String escoPath;

    // Primary index: canonical name (lowercase) → node
    private Map<String, EscoSkillNode> canonicalIndex = new HashMap<>();

    // Reverse synonym index: any alias (lowercase) → canonical name
    private Map<String, String> synonymIndex = new HashMap<>();

    @PostConstruct
    public void load() {
        // Load built-in technical skills first (primary source - curated, high-precision)
        log.info("Loading built-in technical skills taxonomy");
        loadBuiltInFallback();
        int builtInCount = canonicalIndex.size();
        
        // Then load ESCO, but FILTER to only technical skills
        try {
            log.info("Loading ESCO skill graph from: {}", escoPath);
            Map<String, EscoSkillNode> loaded = loadWithJavaSerialization();
            if (loaded != null && !loaded.isEmpty()) {
                int escoTotal = loaded.size();
                int escoFiltered = 0;
                
                // Filter: Only add ESCO skills that are technical
                for (Map.Entry<String, EscoSkillNode> entry : loaded.entrySet()) {
                    if (isTechnicalSkill(entry.getKey(), entry.getValue())) {
                        canonicalIndex.putIfAbsent(entry.getKey(), entry.getValue());
                        escoFiltered++;
                    }
                }
                
                // Rebuild synonym index
                buildSynonymIndex();
                
                log.info("Technical skills loaded: {} built-in + {} ESCO (filtered from {}) = {} total skills, {} synonyms",
                    builtInCount, escoFiltered, escoTotal, canonicalIndex.size(), synonymIndex.size());
            } else {
                log.info("Technical skills loaded: {} skills, {} synonyms (built-in only)",
                    canonicalIndex.size(), synonymIndex.size());
            }
        } catch (Exception e) {
            log.warn("Could not load esco.bin ({}), using built-in technical skills only", e.getMessage());
            log.info("Technical skills loaded: {} skills, {} synonyms (built-in only)",
                canonicalIndex.size(), synonymIndex.size());
        }
    }
    
    /**
     * Filter ESCO skills to only technical/hard skills.
     * Excludes: soft skills, domain-specific skills, non-technical competencies.
     */
    private boolean isTechnicalSkill(String skillName, EscoSkillNode node) {
        if (skillName == null) return false;
        String lower = skillName.toLowerCase().trim();
        
        // Exclude obvious soft skills and non-technical terms
        if (lower.matches(".*(communication|empathy|creativity|leadership|teamwork|" +
                          "problem.solving|critical.thinking|time.management|" +
                          "adaptability|collaboration|interpersonal|emotional|" +
                          "presentation|negotiation|conflict|motivation|" +
                          "customer.service|sales|marketing|management|" +
                          "administrative|clerical|organizational).*")) {
            return false;
        }
        
        // Exclude domain-specific non-tech skills
        if (lower.matches(".*(swimming.pool|plumbing|carpentry|welding|" +
                          "agriculture|farming|cooking|cleaning|driving|" +
                          "retail|hospitality|healthcare|nursing|teaching).*")) {
            return false;
        }
        
        // Include if it matches technical patterns
        return lower.matches(".*(programming|software|hardware|network|database|cloud|" +
                            "devops|api|framework|library|tool|platform|" +
                            "infrastructure|security|testing|deployment|" +
                            "architecture|engineering|development|system|" +
                            "web|mobile|backend|frontend|fullstack|data|" +
                            "machine.learning|ai|analytics|automation).*") ||
               TECHNICAL_SKILLS.contains(lower);
    }

    // ── Resolution API ────────────────────────────────────────────────────────

    /**
     * Resolves a raw skill name to its canonical ESCO name.
     * Returns the input unchanged if not found.
     */
    public String resolve(String rawSkill) {
        if (rawSkill == null) return null;
        String lower = rawSkill.toLowerCase().trim();

        // Direct canonical match
        if (canonicalIndex.containsKey(lower)) return canonicalIndex.get(lower).getCanonicalName();

        // Synonym lookup
        String canonical = synonymIndex.get(lower);
        return canonical != null ? canonical : rawSkill;
    }

    /**
     * Returns the ESCO category for a skill (e.g. "Programming Language", "Framework").
     */
    public String categoryOf(String skill) {
        EscoSkillNode node = findNode(skill);
        return node != null ? node.getCategory() : "Unknown";
    }

    /**
     * Returns all known synonyms/aliases for a skill.
     */
    public List<String> synonymsOf(String skill) {
        EscoSkillNode node = findNode(skill);
        return node != null ? node.getSynonyms() : List.of();
    }

    /**
     * Returns skills that are closely related in the ESCO graph.
     * Used for implicit skill detection.
     */
    public List<String> relatedSkills(String skill) {
        EscoSkillNode node = findNode(skill);
        return node != null ? node.getRelatedSkills() : List.of();
    }

    /**
     * Returns the year the technology was first released.
     * Used for skill-age mismatch detection.
     * Returns null if unknown.
     */
    public Integer releaseYearOf(String skill) {
        EscoSkillNode node = findNode(skill);
        return node != null ? node.getReleaseYear() : null;
    }

    /**
     * Returns true if this exact string (after normalisation) is a known skill.
     */
    public boolean isKnownSkill(String skill) {
        if (skill == null) return false;
        String lower = skill.toLowerCase().trim();
        return canonicalIndex.containsKey(lower) || synonymIndex.containsKey(lower);
    }

    /**
     * Returns true if this is a technical skill (not a soft skill or domain-specific skill).
     * Uses category and a curated list of technical skill names.
     */
    public boolean isTechnicalSkill(String skill) {
        if (skill == null) return false;
        String lower = skill.toLowerCase().trim();
        String canonical = resolve(skill).toLowerCase();
        
        // Check if it's in our curated technical skills list
        if (TECHNICAL_SKILLS.contains(canonical) || TECHNICAL_SKILLS.contains(lower)) {
            return true;
        }
        
        // Check category from ESCO
        String category = categoryOf(skill);
        if (category != null) {
            String catLower = category.toLowerCase();
            // Technical categories
            if (catLower.contains("programming") || catLower.contains("framework") || 
                catLower.contains("database") || catLower.contains("cloud") ||
                catLower.contains("container") || catLower.contains("infrastructure") ||
                catLower.contains("ci/cd") || catLower.contains("monitoring") ||
                catLower.contains("api") || catLower.contains("security") ||
                catLower.contains("version control") || catLower.contains("testing") ||
                catLower.contains("build tool")) {
                return true;
            }
        }
        
        return false;
    }

    // Curated list of technical skills
    private static final Set<String> TECHNICAL_SKILLS = Set.of(
        "javascript", "typescript", "python", "java", "go", "golang", "rust", "kotlin",
        "c++", "cpp", "c#", "csharp", "ruby", "php", "swift", "scala", "r", "sql",
        "spring boot", "springboot", "react", "reactjs", "angular", "vue", "vue.js",
        "node.js", "nodejs", "django", "flask", "fastapi", "express", ".net", "dotnet",
        "flutter", "aws", "azure", "gcp", "google cloud", "openstack", "docker",
        "kubernetes", "k8s", "postgresql", "postgres", "mysql", "mongodb", "redis",
        "elasticsearch", "cassandra", "dynamodb", "kafka", "rabbitmq", "terraform",
        "ansible", "cloudformation", "jenkins", "gitlab ci", "github actions", "circleci",
        "gitops", "prometheus", "grafana", "splunk", "datadog", "new relic", "appdynamics",
        "rest", "graphql", "grpc", "oauth2", "jwt", "git", "github", "gitlab",
        "junit", "mockito", "jest", "pytest", "selenium", "maven", "gradle", "npm", "webpack"
    );


    // ── Private helpers ───────────────────────────────────────────────────────

    private EscoSkillNode findNode(String skill) {
        if (skill == null) return null;
        String lower = skill.toLowerCase().trim();
        if (canonicalIndex.containsKey(lower)) return canonicalIndex.get(lower);
        String canonical = synonymIndex.get(lower);
        if (canonical != null) return canonicalIndex.get(canonical.toLowerCase());
        return null;
    }

    private void buildSynonymIndex() {
        synonymIndex.clear(); // Start fresh
        for (Map.Entry<String, EscoSkillNode> entry : canonicalIndex.entrySet()) {
            EscoSkillNode node = entry.getValue();
            if (node.getSynonyms() != null) {
                for (String synonym : node.getSynonyms()) {
                    String synLower = synonym.toLowerCase().trim();
                    // Only add if not already present (built-in takes precedence)
                    synonymIndex.putIfAbsent(synLower, node.getCanonicalName().toLowerCase());
                }
            }
            // Also index the canonical name itself
            String canonLower = node.getCanonicalName().toLowerCase().trim();
            synonymIndex.putIfAbsent(canonLower, canonLower);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, EscoSkillNode> loadWithJavaSerialization() throws IOException, ClassNotFoundException {
        Path path = resolveEscoPath();
        try (InputStream fis = Files.newInputStream(path);
             InputStream gzis = new java.util.zip.GZIPInputStream(fis)) {
            com.upokecenter.cbor.CBORObject cbor = com.upokecenter.cbor.CBORObject.Read(gzis);
            
            log.info("CBOR root keys: {}", cbor.getKeys().size());
            
            // The CBOR file has structure: { "aliasToCanonical": { alias: canonical, ... } }
            if (!cbor.ContainsKey("aliasToCanonical")) {
                log.warn("CBOR file missing 'aliasToCanonical' key, available keys: {}", 
                    cbor.getKeys().stream().map(k -> k.AsString()).toList());
                return Map.of();
            }
            
            com.upokecenter.cbor.CBORObject aliasMap = cbor.get("aliasToCanonical");
            log.info("aliasToCanonical has {} entries", aliasMap.getKeys().size());
            
            Map<String, EscoSkillNode> result = new HashMap<>();
            
            // Build nodes from alias mappings
            for (com.upokecenter.cbor.CBORObject key : aliasMap.getKeys()) {
                String alias = key.AsString();
                String canonical = aliasMap.get(key).AsString();
                
                // Get or create node for canonical name
                EscoSkillNode node = result.computeIfAbsent(canonical.toLowerCase(), k -> {
                    EscoSkillNode n = new EscoSkillNode();
                    n.setCanonicalName(canonical);
                    n.setSynonyms(new ArrayList<>());
                    n.setRelatedSkills(new ArrayList<>());
                    return n;
                });
                
                // Add alias as synonym if different from canonical
                if (!alias.equalsIgnoreCase(canonical)) {
                    node.getSynonyms().add(alias);
                }
            }
            
            log.info("Built {} canonical skill nodes", result.size());
            return result;
        }
    }

    private Map<String, EscoSkillNode> parseCborToMap(com.upokecenter.cbor.CBORObject cbor) {
        Map<String, EscoSkillNode> result = new HashMap<>();
        for (com.upokecenter.cbor.CBORObject key : cbor.getKeys()) {
            String skillName = key.AsString();
            com.upokecenter.cbor.CBORObject value = cbor.get(key);
            result.put(skillName, parseCborToNode(value));
        }
        return result;
    }

    private EscoSkillNode parseCborToNode(com.upokecenter.cbor.CBORObject cbor) {
        EscoSkillNode node = new EscoSkillNode();
        node.setCanonicalName(cbor.get("canonicalName").AsString());
        if (cbor.ContainsKey("category")) node.setCategory(cbor.get("category").AsString());
        if (cbor.ContainsKey("releaseYear")) node.setReleaseYear(cbor.get("releaseYear").AsInt32());
        
        List<String> synonyms = new ArrayList<>();
        if (cbor.ContainsKey("synonyms")) {
            for (com.upokecenter.cbor.CBORObject syn : cbor.get("synonyms").getValues()) {
                synonyms.add(syn.AsString());
            }
        }
        node.setSynonyms(synonyms);
        
        List<String> related = new ArrayList<>();
        if (cbor.ContainsKey("relatedSkills")) {
            for (com.upokecenter.cbor.CBORObject rel : cbor.get("relatedSkills").getValues()) {
                related.add(rel.AsString());
            }
        }
        node.setRelatedSkills(related);
        
        return node;
    }

    /**
     * Kryo loading — uncomment and add Kryo dependency if your esco.bin uses Kryo.
     *
     * pom.xml: <dependency><groupId>com.esotericsoftware</groupId>
     *                       <artifactId>kryo</artifactId><version>5.6.0</version></dependency>
     */
    /*
    private Map<String, EscoSkillNode> loadWithKryo() throws IOException {
        Path path = resolveEscoPath();
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        try (Input input = new Input(Files.newInputStream(path))) {
            return kryo.readObject(input, HashMap.class);
        }
    }
    */

    private Path resolveEscoPath() throws IOException {
        if (escoPath.startsWith("classpath:")) {
            String resource = escoPath.substring("classpath:".length());
            var url = getClass().getClassLoader().getResource(resource);
            if (url == null) throw new FileNotFoundException("ESCO file not found in classpath: " + resource);
            return Path.of(url.getPath());
        }
        return Path.of(escoPath);
    }

    /**
     * Built-in technical skills taxonomy.
     * Covers programming languages, frameworks, cloud platforms, tools, and practices.
     */
    private void loadBuiltInFallback() {
        record Alias(String canonical, String category, Integer releaseYear, String... synonyms) {}

        List<Alias> builtIn = List.of(
            // Programming Languages
            new Alias("JavaScript", "Programming Language", 1995, "js", "ecmascript", "es6", "es2015"),
            new Alias("TypeScript", "Programming Language", 2012, "ts"),
            new Alias("Python", "Programming Language", 1991, "py"),
            new Alias("Java", "Programming Language", 1995),
            new Alias("Go", "Programming Language", 2009, "golang"),
            new Alias("Rust", "Programming Language", 2015),
            new Alias("Kotlin", "Programming Language", 2011, "kt"),
            new Alias("C++", "Programming Language", 1985, "cpp", "c plus plus"),
            new Alias("C#", "Programming Language", 2000, "csharp", "c sharp"),
            new Alias("Ruby", "Programming Language", 1995),
            new Alias("PHP", "Programming Language", 1995),
            new Alias("Swift", "Programming Language", 2014),
            new Alias("Scala", "Programming Language", 2004),
            new Alias("R", "Programming Language", 1993),
            new Alias("SQL", "Query Language", 1974),
            
            // Frameworks & Libraries
            new Alias("Spring Boot", "Framework", 2014, "springboot", "spring-boot", "sb"),
            new Alias("React", "Framework", 2013, "reactjs", "react.js"),
            new Alias("Angular", "Framework", 2016),
            new Alias("Vue.js", "Framework", 2014, "vue", "vuejs"),
            new Alias("Node.js", "Runtime", 2009, "node", "nodejs"),
            new Alias("Django", "Framework", 2005),
            new Alias("Flask", "Framework", 2010),
            new Alias("FastAPI", "Framework", 2018),
            new Alias("Express", "Framework", 2010, "express.js", "expressjs"),
            new Alias(".NET", "Framework", 2002, "dotnet", "dot net"),
            new Alias("Flutter", "Framework", 2018, "flutter sdk"),
            
            // Cloud Platforms
            new Alias("AWS", "Cloud Platform", 2006, "amazon web services"),
            new Alias("Azure", "Cloud Platform", 2010, "microsoft azure"),
            new Alias("GCP", "Cloud Platform", 2008, "google cloud platform", "google cloud"),
            new Alias("OpenStack", "Cloud Platform", 2010),
            
            // Container & Orchestration
            new Alias("Docker", "Containerization", 2013, "containers"),
            new Alias("Kubernetes", "Container Orchestration", 2014, "k8s", "kube"),
            
            // Databases
            new Alias("PostgreSQL", "Database", 1996, "postgres", "pg", "psql"),
            new Alias("MySQL", "Database", 1995, "my sql"),
            new Alias("MongoDB", "Database", 2009, "mongo"),
            new Alias("Redis", "Cache / Database", 2009),
            new Alias("Elasticsearch", "Search Engine", 2010, "elastic", "es"),
            new Alias("Cassandra", "Database", 2008),
            new Alias("DynamoDB", "Database", 2012),
            
            // Message Brokers
            new Alias("Kafka", "Message Broker", 2011, "apache kafka"),
            new Alias("RabbitMQ", "Message Broker", 2007),
            
            // Infrastructure as Code
            new Alias("Terraform", "Infrastructure as Code", 2014, "tf"),
            new Alias("Ansible", "Infrastructure as Code", 2012),
            new Alias("CloudFormation", "Infrastructure as Code", 2011),
            
            // CI/CD
            new Alias("Jenkins", "CI/CD", 2011),
            new Alias("GitLab CI", "CI/CD", 2012, "gitlab ci/cd", "gitlab"),
            new Alias("GitHub Actions", "CI/CD", 2019, "gh actions"),
            new Alias("CircleCI", "CI/CD", 2011),
            new Alias("GitOps", "Practice", 2017),
            
            // Monitoring & Observability
            new Alias("Prometheus", "Monitoring", 2012),
            new Alias("Grafana", "Monitoring", 2014),
            new Alias("Splunk", "Monitoring", 2003),
            new Alias("Datadog", "Monitoring", 2010),
            new Alias("New Relic", "Monitoring", 2008),
            new Alias("AppDynamics", "Monitoring", 2008),
            
            // API & Integration
            new Alias("REST", "API Technology", 2000, "rest api", "restful"),
            new Alias("GraphQL", "API Technology", 2015, "gql"),
            new Alias("gRPC", "API Technology", 2015),
            new Alias("OAuth2", "Security", 2012, "oauth 2.0", "oauth"),
            new Alias("JWT", "Security", 2015, "json web token"),
            
            // Version Control
            new Alias("Git", "Version Control", 2005),
            new Alias("GitHub", "Version Control", 2008),
            new Alias("GitLab", "Version Control", 2011),
            
            // Testing
            new Alias("JUnit", "Testing", 2000),
            new Alias("Mockito", "Testing", 2008),
            new Alias("Jest", "Testing", 2014),
            new Alias("Pytest", "Testing", 2004),
            new Alias("Selenium", "Testing", 2004),
            
            // Build Tools
            new Alias("Maven", "Build Tool", 2004),
            new Alias("Gradle", "Build Tool", 2008),
            new Alias("npm", "Build Tool", 2010),
            new Alias("Webpack", "Build Tool", 2012)
        );

        for (Alias alias : builtIn) {
            EscoSkillNode node = new EscoSkillNode();
            node.setCanonicalName(alias.canonical());
            node.setCategory(alias.category());
            node.setReleaseYear(alias.releaseYear());
            node.setSynonyms(Arrays.asList(alias.synonyms()));
            node.setRelatedSkills(List.of());
            canonicalIndex.put(alias.canonical().toLowerCase(), node);
            for (String syn : alias.synonyms()) {
                synonymIndex.put(syn.toLowerCase(), alias.canonical().toLowerCase());
            }
        }
    }

    // ── EscoSkillNode ─────────────────────────────────────────────────────────

    public static class EscoSkillNode implements Serializable {
        private String canonicalName;
        private String category;
        private List<String> synonyms = new ArrayList<>();
        private List<String> relatedSkills = new ArrayList<>();
        private Integer releaseYear;

        public String getCanonicalName() { return canonicalName; }
        public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public List<String> getSynonyms() { return synonyms; }
        public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
        public List<String> getRelatedSkills() { return relatedSkills; }
        public void setRelatedSkills(List<String> relatedSkills) { this.relatedSkills = relatedSkills; }
        public Integer getReleaseYear() { return releaseYear; }
        public void setReleaseYear(Integer releaseYear) { this.releaseYear = releaseYear; }
    }
}
