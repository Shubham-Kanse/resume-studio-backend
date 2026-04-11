package com.resumestudio.reviewer.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads and exposes the MIND-tech ontology for skill relationships and role mappings.
 * 
 * Provides:
 * - Skill dependencies (impliesKnowingSkills)
 * - Role-to-skill mappings
 * - Skill categorization (Library, Framework, Language, etc.)
 * - Synonym resolution
 */
@Component
public class MindTechOntology {

    private static final Logger log = LoggerFactory.getLogger(MindTechOntology.class);

    @Value("classpath:taxonomy/__aggregated_skills.json")
    private Resource skillsResource;

    @Value("classpath:taxonomy/__aggregated_concepts.json")
    private Resource conceptsResource;

    private final ObjectMapper mapper = new ObjectMapper();

    // Skill name (lowercase) -> MindTechSkill
    private Map<String, MindTechSkill> skillIndex = new HashMap<>();
    
    // Synonym (lowercase) -> canonical skill name
    private Map<String, String> synonymIndex = new HashMap<>();
    
    // Concept name -> MindTechConcept
    private Map<String, MindTechConcept> conceptIndex = new HashMap<>();

    @PostConstruct
    public void load() {
        try {
            loadSkills();
            loadConcepts();
            log.info("MIND-tech ontology loaded: {} skills, {} synonyms, {} concepts",
                skillIndex.size(), synonymIndex.size(), conceptIndex.size());
        } catch (IOException e) {
            log.error("Failed to load MIND-tech ontology", e);
            throw new RuntimeException("Failed to load MIND-tech ontology", e);
        }
    }

    private void loadSkills() throws IOException {
        JsonNode skills = mapper.readTree(skillsResource.getInputStream());
        
        for (JsonNode skillNode : skills) {
            String name = skillNode.get("name").asText();
            String nameLower = name.toLowerCase();
            
            MindTechSkill skill = new MindTechSkill();
            skill.name = name;
            skill.type = jsonArrayToList(skillNode.get("type"));
            skill.impliesKnowingSkills = jsonArrayToList(skillNode.get("impliesKnowingSkills"));
            skill.supportedProgrammingLanguages = jsonArrayToList(skillNode.get("supportedProgrammingLanguages"));
            skill.specificToFrameworks = jsonArrayToList(skillNode.get("specificToFrameworks"));
            skill.solvesApplicationTasks = jsonArrayToList(skillNode.get("solvesApplicationTasks"));
            
            skillIndex.put(nameLower, skill);
            
            // Index synonyms
            JsonNode synonymsNode = skillNode.get("synonyms");
            if (synonymsNode != null && synonymsNode.isArray()) {
                for (JsonNode syn : synonymsNode) {
                    synonymIndex.put(syn.asText().toLowerCase(), nameLower);
                }
            }
            synonymIndex.put(nameLower, nameLower); // Self-reference
        }
    }

    private void loadConcepts() throws IOException {
        JsonNode concepts = mapper.readTree(conceptsResource.getInputStream());
        
        for (JsonNode conceptNode : concepts) {
            String name = conceptNode.get("name").asText();
            
            MindTechConcept concept = new MindTechConcept();
            concept.name = name;
            concept.category = jsonArrayToList(conceptNode.get("category"));
            concept.synonyms = jsonArrayToList(conceptNode.get("synonyms"));
            
            conceptIndex.put(name.toLowerCase(), concept);
        }
    }

    private List<String> jsonArrayToList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(item.asText());
        }
        return result;
    }

    /**
     * Resolve a skill name or synonym to its canonical form.
     */
    public String resolve(String skillName) {
        if (skillName == null) return null;
        String canonical = synonymIndex.get(skillName.toLowerCase());
        return canonical != null ? skillIndex.get(canonical).name : null;
    }

    /**
     * Check if a skill is known in the ontology.
     */
    public boolean isKnownSkill(String skillName) {
        return skillName != null && synonymIndex.containsKey(skillName.toLowerCase());
    }

    /**
     * Get skills implied by knowing this skill.
     * E.g., Spring Boot -> Java, Maven
     */
    public List<String> getImpliedSkills(String skillName) {
        String canonical = synonymIndex.get(skillName.toLowerCase());
        if (canonical == null) return List.of();
        
        MindTechSkill skill = skillIndex.get(canonical);
        if (skill == null) return List.of();
        
        List<String> implied = new ArrayList<>(skill.impliesKnowingSkills);
        implied.addAll(skill.supportedProgrammingLanguages);
        
        return implied.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Get the type of a skill (Library, Framework, Language, etc.)
     */
    public List<String> getSkillType(String skillName) {
        String canonical = synonymIndex.get(skillName.toLowerCase());
        if (canonical == null) return List.of();
        
        MindTechSkill skill = skillIndex.get(canonical);
        return skill != null ? skill.type : List.of();
    }

    /**
     * Get all skills that solve a specific application task.
     * E.g., "Authentication and Authorization" -> [OmniAuth, Passport.js, ...]
     */
    public List<String> getSkillsForTask(String task) {
        return skillIndex.values().stream()
            .filter(s -> s.solvesApplicationTasks.stream()
                .anyMatch(t -> t.equalsIgnoreCase(task)))
            .map(s -> s.name)
            .collect(Collectors.toList());
    }

    /**
     * Get role-relevant skills based on common patterns.
     * This is a heuristic until we have explicit role mappings.
     */
    public List<String> getRoleRelevantSkills(String roleTitle) {
        String lower = roleTitle.toLowerCase();
        
        // Backend roles
        if (lower.contains("backend") || lower.contains("server")) {
            return getSkillsByType(List.of("Framework", "Library"))
                .stream()
                .filter(s -> {
                    MindTechSkill skill = skillIndex.get(s.toLowerCase());
                    return skill.supportedProgrammingLanguages.stream()
                        .anyMatch(lang -> lang.matches("Java|Python|Golang|Ruby|PHP|C#|Rust"));
                })
                .collect(Collectors.toList());
        }
        
        // Frontend roles
        if (lower.contains("frontend") || lower.contains("ui") || lower.contains("react") || lower.contains("angular")) {
            return getSkillsByType(List.of("Framework", "Library"))
                .stream()
                .filter(s -> {
                    MindTechSkill skill = skillIndex.get(s.toLowerCase());
                    return skill.supportedProgrammingLanguages.stream()
                        .anyMatch(lang -> lang.matches("JavaScript|TypeScript"));
                })
                .collect(Collectors.toList());
        }
        
        // DevOps/SRE roles
        if (lower.contains("devops") || lower.contains("sre") || lower.contains("infrastructure")) {
            return getSkillsForTask("Infrastructure as Code");
        }
        
        return List.of();
    }

    /**
     * Get all skills of specific types.
     */
    public List<String> getSkillsByType(List<String> types) {
        return skillIndex.values().stream()
            .filter(s -> s.type.stream().anyMatch(types::contains))
            .map(s -> s.name)
            .collect(Collectors.toList());
    }

    /**
     * Compute role-skill relevance score (0.0-1.0).
     * Higher score = more relevant to the role.
     */
    public double getRoleSkillRelevance(String roleTitle, String skillName) {
        String canonical = synonymIndex.get(skillName.toLowerCase());
        if (canonical == null) return 0.0;
        
        MindTechSkill skill = skillIndex.get(canonical);
        if (skill == null) return 0.0;
        
        String roleLower = roleTitle.toLowerCase();
        
        // Direct language match (e.g., "Java Developer" + Java skill)
        for (String lang : skill.supportedProgrammingLanguages) {
            if (roleLower.contains(lang.toLowerCase())) {
                return 1.0;
            }
        }
        
        // Framework match (e.g., "Spring Developer" + Spring Boot)
        if (roleLower.contains(skill.name.toLowerCase())) {
            return 0.95;
        }
        
        // Role category heuristics
        if (roleLower.contains("backend") && skill.type.contains("Framework")) {
            if (skill.supportedProgrammingLanguages.stream()
                .anyMatch(lang -> lang.matches("Java|Python|Golang|Ruby|PHP|C#|Rust"))) {
                return 0.7;
            }
        }
        
        if (roleLower.contains("frontend") && skill.type.contains("Framework")) {
            if (skill.supportedProgrammingLanguages.stream()
                .anyMatch(lang -> lang.matches("JavaScript|TypeScript"))) {
                return 0.7;
            }
        }
        
        // Default: moderate relevance for any technical skill
        return 0.3;
    }

    public static class MindTechSkill {
        public String name;
        public List<String> type = List.of();
        public List<String> impliesKnowingSkills = List.of();
        public List<String> supportedProgrammingLanguages = List.of();
        public List<String> specificToFrameworks = List.of();
        public List<String> solvesApplicationTasks = List.of();
    }

    public static class MindTechConcept {
        public String name;
        public List<String> category = List.of();
        public List<String> synonyms = List.of();
    }
}
