package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.CompanyTier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Looks up company tier using company-tiers.json and detects career trajectory.
 *
 * company-tiers.json structure:
 * {
 *   "faang": ["Google", "Meta", "Apple", "Amazon", "Netflix", "Microsoft"],
 *   "tier_1": ["Stripe", "Airbnb", "Uber", "LinkedIn", "Salesforce", ...],
 *   "scale_up": ["Intercom", "Notion", "Figma", ...],
 *   "startup": []
 * }
 */
@Component
public class CompanySignalCalculator {

    private static final Logger log = LoggerFactory.getLogger(CompanySignalCalculator.class);

    private Map<CompanyTier, Set<String>> tierMap = new HashMap<>();

    // Descriptor pattern: "(Series B fintech, 200 engineers)"
    private static final Pattern DESCRIPTOR_PATTERN = Pattern.compile("\\(([^)]{5,80})\\)");

    @PostConstruct
    public void loadTiers() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("taxonomy/company-tiers.json")) {
            if (is == null) {
                log.warn("company-tiers.json not found — company tier lookup will return UNKNOWN");
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<String>> raw = mapper.readValue(is,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, List.class));

            for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
                CompanyTier tier = switch (entry.getKey().toLowerCase()) {
                    case "faang" -> CompanyTier.FAANG;
                    case "tier_1" -> CompanyTier.TIER_1;
                    case "scale_up" -> CompanyTier.SCALE_UP;
                    case "startup" -> CompanyTier.STARTUP;
                    default -> null;
                };
                if (tier != null) {
                    Set<String> names = new HashSet<>();
                    entry.getValue().forEach(n -> names.add(n.toLowerCase().trim()));
                    tierMap.put(tier, names);
                }
            }
            log.info("Company tiers loaded: {} companies across {} tiers",
                tierMap.values().stream().mapToInt(Set::size).sum(), tierMap.size());
        } catch (Exception e) {
            log.warn("Failed to load company-tiers.json: {}", e.getMessage());
        }
    }

    public void compute(List<WorkExperience> experience, String currentCompany,
                        String companyDescriptor, ResumeSignals signals) {

        signals.setCurrentCompanyName(currentCompany);
        signals.setCompanyHasDescriptor(companyDescriptor != null && !companyDescriptor.isBlank());

        if (currentCompany == null || currentCompany.isBlank()) {
            signals.setCurrentCompanyTier(CompanyTier.UNKNOWN);
            return;
        }

        // Look up current company tier
        CompanyTier currentTier = lookupTier(currentCompany);
        if (currentTier == CompanyTier.UNKNOWN && signals.isCompanyHasDescriptor()) {
            currentTier = CompanyTier.DESCRIBED;
        }
        signals.setCurrentCompanyTier(currentTier);

        // Compute trajectory across all roles
        if (experience != null && experience.size() >= 2) {
            computeTrajectory(experience, signals);
        }
    }

    public CompanyTier lookupTier(String companyName) {
        if (companyName == null) return CompanyTier.UNKNOWN;
        String lower = companyName.toLowerCase().trim();

        for (Map.Entry<CompanyTier, Set<String>> entry : tierMap.entrySet()) {
            for (String known : entry.getValue()) {
                if (lower.equals(known) || lower.contains(known) || known.contains(lower)) {
                    return entry.getKey();
                }
            }
        }
        return CompanyTier.UNKNOWN;
    }

    private void computeTrajectory(List<WorkExperience> experience, ResumeSignals signals) {
        // experience is sorted most-recent-first; reverse for chronological order
        List<WorkExperience> chronological = new ArrayList<>(experience);
        Collections.reverse(chronological);

        List<Integer> tierValues = chronological.stream()
            .map(e -> tierValue(lookupTier(e.getCompany())))
            .filter(v -> v > 0)
            .toList();

        if (tierValues.size() < 2) return;

        int first = tierValues.get(0);
        int last = tierValues.get(tierValues.size() - 1);

        signals.setCompanyTierImproving(last > first);
        signals.setCompanyTierDeclining(last < first);
    }

    private int tierValue(CompanyTier tier) {
        return switch (tier) {
            case FAANG -> 5;
            case TIER_1 -> 4;
            case SCALE_UP -> 3;
            case DESCRIBED -> 2;
            case STARTUP -> 1;
            case UNKNOWN -> 0;
        };
    }
}
