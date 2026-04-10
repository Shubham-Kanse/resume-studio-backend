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

    @Value("${screener.esco.path:classpath:taxonomy/esco.bin}")
    private String escoPath;

    // Primary index: canonical name (lowercase) → node
    private Map<String, EscoSkillNode> canonicalIndex = new HashMap<>();

    // Reverse synonym index: any alias (lowercase) → canonical name
    private Map<String, String> synonymIndex = new HashMap<>();

    @PostConstruct
    public void load() {
        try {
            log.info("Loading ESCO skill graph from: {}", escoPath);
            Map<String, EscoSkillNode> loaded = loadWithJavaSerialization();
            if (loaded != null && !loaded.isEmpty()) {
                canonicalIndex = loaded;
                buildSynonymIndex();
                log.info("ESCO graph loaded: {} skills, {} synonyms",
                    canonicalIndex.size(), synonymIndex.size());
            } else {
                log.warn("ESCO graph empty or failed to load — falling back to built-in synonyms");
                loadBuiltInFallback();
            }
        } catch (Exception e) {
            log.warn("Could not load esco.bin ({}), using built-in fallback synonym map", e.getMessage());
            loadBuiltInFallback();
        }
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
        for (Map.Entry<String, EscoSkillNode> entry : canonicalIndex.entrySet()) {
            EscoSkillNode node = entry.getValue();
            if (node.getSynonyms() != null) {
                for (String synonym : node.getSynonyms()) {
                    synonymIndex.put(synonym.toLowerCase().trim(), node.getCanonicalName().toLowerCase());
                }
            }
            // Also index the canonical name itself
            synonymIndex.put(node.getCanonicalName().toLowerCase().trim(), node.getCanonicalName().toLowerCase());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, EscoSkillNode> loadWithJavaSerialization() throws IOException, ClassNotFoundException {
        Path path = resolveEscoPath();
        try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            Object obj = ois.readObject();
            if (obj instanceof Map) {
                return (Map<String, EscoSkillNode>) obj;
            }
            log.warn("esco.bin did not deserialise to expected Map<String, EscoSkillNode>");
            return Map.of();
        }
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
     * Built-in fallback synonym map — used if esco.bin fails to load.
     * Covers the most common tech abbreviations and aliases.
     */
    private void loadBuiltInFallback() {
        record Alias(String canonical, String category, Integer releaseYear, String... synonyms) {}

        List<Alias> builtIn = List.of(
            new Alias("JavaScript", "Programming Language", 1995, "js", "ecmascript", "es6", "es2015"),
            new Alias("TypeScript", "Programming Language", 2012, "ts"),
            new Alias("PostgreSQL", "Database", 1996, "postgres", "pg", "psql"),
            new Alias("MySQL", "Database", 1995, "my sql"),
            new Alias("MongoDB", "Database", 2009, "mongo"),
            new Alias("Kubernetes", "Container Orchestration", 2014, "k8s", "kube"),
            new Alias("Docker", "Containerisation", 2013, "containers"),
            new Alias("Spring Boot", "Framework", 2014, "springboot", "spring-boot", "sb"),
            new Alias("React", "Framework", 2013, "reactjs", "react.js"),
            new Alias("Node.js", "Runtime", 2009, "node", "nodejs"),
            new Alias("Amazon Web Services", "Cloud Platform", 2006, "aws"),
            new Alias("Google Cloud Platform", "Cloud Platform", 2008, "gcp", "google cloud"),
            new Alias("Microsoft Azure", "Cloud Platform", 2010, "azure"),
            new Alias("GraphQL", "API Technology", 2015, "gql"),
            new Alias("Terraform", "Infrastructure as Code", 2014, "tf"),
            new Alias("GitHub Actions", "CI/CD", 2019, "gh actions"),
            new Alias("Flutter", "Framework", 2018, "flutter sdk"),
            new Alias("Kotlin", "Programming Language", 2011, "kt"),
            new Alias("Go", "Programming Language", 2009, "golang"),
            new Alias("Rust", "Programming Language", 2015),
            new Alias("Redis", "Cache / Database", 2009),
            new Alias("Kafka", "Message Broker", 2011, "apache kafka"),
            new Alias("Elasticsearch", "Search Engine", 2010, "elastic", "es"),
            new Alias("Python", "Programming Language", 1991, "py"),
            new Alias("Java", "Programming Language", 1995)
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
