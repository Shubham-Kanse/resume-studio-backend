package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.extraction.JdParserService;
import com.resumestudio.reviewer.model.JobDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstantVerdictControllerTest {

    private JdParserService jdParser;
    private InstantVerdictController controller;

    @BeforeEach
    void setUp() {
        jdParser = mock(JdParserService.class);
        controller = new InstantVerdictController(jdParser);
    }

    @Test
    void returnsBadRequestWhenJobDescriptionMissing() {
        var response = controller.instantCheck(Map.of("jobDescription", "   "));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Job description required", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void returnsRoleTitleAndRequiredYoeUsingCurrentJobDescriptionModel() {
        JobDescription jd = new JobDescription();
        jd.setRoleTitle("Senior Backend Engineer");
        jd.setYoeMin(4.6);
        jd.setMustHaveSkills(List.of("Java", "Spring Boot"));
        when(jdParser.parse("jd text")).thenReturn(jd);

        String resume = "Senior backend engineer with 6 years experience building Java and Spring Boot APIs.";
        var response = controller.instantCheck(Map.of("jobDescription", "jd text", "resumeText", resume));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Senior Backend Engineer", body.get("roleTitle"));
        assertEquals(5, body.get("requiredYoe"));
        assertEquals("STRONG_FIT", body.get("verdict"));
    }

    @Test
    void emptyResumeReturnsUnknownAndGuidance() {
        JobDescription jd = new JobDescription();
        jd.setRoleTitle("Backend Engineer");
        jd.setMustHaveSkills(List.of("Java", "PostgreSQL"));
        when(jdParser.parse("jd text")).thenReturn(jd);

        var response = controller.instantCheck(Map.of("jobDescription", "jd text", "resumeText", ""));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("UNKNOWN", body.get("verdict"));
        @SuppressWarnings("unchecked")
        List<String> keyPoints = (List<String>) body.get("keyPoints");
        assertTrue(keyPoints.stream().anyMatch(p -> p.toLowerCase().contains("resume")));
    }
}
