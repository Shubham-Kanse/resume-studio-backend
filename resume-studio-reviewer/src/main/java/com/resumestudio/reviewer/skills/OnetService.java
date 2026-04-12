package com.resumestudio.reviewer.skills;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * O*NET occupation data stub.
 * Returns empty data until O*NET API key is configured.
 *
 * When ready:
 *   1. Register at https://services.onetcenter.org/
 *   2. Add onet.api.key to application-local.properties
 *   3. Implement fetchOccupation() against O*NET Web Services v1.9
 *
 * Used for:
 *   - implicitExpectations in ParsedJD (Layer 0b Step 9)
 *   - education_fit signal (Layer 4)
 *   - competitiveContext in ClassificationResult (Layer 6)
 */
@Component
public class OnetService {

    /**
     * Returns implicit skill expectations for a role title.
     * e.g. "Software Engineer" → ["version control", "agile", "code review"]
     */
    public List<String> getImplicitExpectations(String roleTitle) {
        return List.of(); // stub
    }

    /**
     * Returns the O*NET occupation code for a role title.
     */
    public String getOccupationCode(String roleTitle) {
        return null; // stub
    }
}
