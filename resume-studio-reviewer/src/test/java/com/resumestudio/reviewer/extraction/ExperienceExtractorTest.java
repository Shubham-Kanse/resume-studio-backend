package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.nlp.NlpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperienceExtractorTest {

    private ExperienceExtractor extractor;

    @BeforeEach
    void setUp() {
        NlpService nlp = mock(NlpService.class);
        when(nlp.findOrganizations(anyString())).thenReturn(List.of());
        DesignationOntologyService ontology = mock(DesignationOntologyService.class);
        when(ontology.isKnownTitle(anyString())).thenReturn(false);
        extractor = new ExperienceExtractor(nlp, ontology);
    }

    @Test void extract_null_returnsEmpty() {
        assertTrue(extractor.extract(null).isEmpty());
    }

    @Test void extract_blank_returnsEmpty() {
        assertTrue(extractor.extract("   ").isEmpty());
    }

    @Test void extract_singleRole_titleAndDateOnSeparateLines() {
        String text = """
            Sr. Associate Software Engineer
            Fiserv | May 2021 – July 2024
            • Built microservices using Java and Spring Boot
            """;
        List<WorkExperience> roles = extractor.extract(text);
        assertFalse(roles.isEmpty());
        WorkExperience role = roles.get(0);
        assertNotNull(role.getStartDate());
        assertEquals(2021, role.getStartDate().getYear());
        assertEquals(2024, role.getEndDate().getYear());
    }

    @Test void extract_currentRole_setsIsCurrentTrue() {
        String text = "Software Engineer\nAcme Corp | Jan 2023 – Present\n• Built things";
        List<WorkExperience> roles = extractor.extract(text);
        assertFalse(roles.isEmpty());
        assertTrue(roles.get(0).isCurrent());
        assertNull(roles.get(0).getEndDate());
    }

    @Test void extract_multipleRoles_sortedMostRecentFirst() {
        String text = """
            Junior Engineer
            OldCo | Jan 2018 – Dec 2019
            • Did stuff
            
            Senior Engineer
            NewCo | Mar 2022 – Present
            • Built things
            """;
        List<WorkExperience> roles = extractor.extract(text);
        assertEquals(2, roles.size());
        assertTrue(roles.get(0).getStartDate().isAfter(roles.get(1).getStartDate()));
    }

    @Test void extract_contractRole_flagged() {
        String text = "Contract Software Engineer\nAcme | Jan 2022 – Jun 2022\n• Delivered feature";
        List<WorkExperience> roles = extractor.extract(text);
        assertFalse(roles.isEmpty());
        assertTrue(roles.get(0).isContractOrFreelance());
    }

    @Test void extract_careerBreak_flagged() {
        String text = "Career Break\nJan 2023 – Dec 2023\n• Upskilling and family care";
        List<WorkExperience> roles = extractor.extract(text);
        assertFalse(roles.isEmpty());
        assertTrue(roles.get(0).isCareerBreak());
    }

    @Test void extract_yearOnlyDates_markedPartial() {
        String text = "Engineer\nAcme | 2020 – 2022\n• Built things";
        List<WorkExperience> roles = extractor.extract(text);
        assertFalse(roles.isEmpty());
        assertTrue(roles.get(0).isDatesArePartial());
    }

    @Test void extract_icLevel_seniorMapsTo4() {
        String text = "Senior Software Engineer\nAcme | Jan 2022 – Present\n• Built things";
        List<WorkExperience> roles = extractor.extract(text);
        assertFalse(roles.isEmpty());
        assertEquals(4, roles.get(0).getIcLevel());
    }

    @Test void extract_icLevel_staffMapsTo5() {
        String text = "Staff Engineer\nAcme | Jan 2022 – Present\n• Built things";
        List<WorkExperience> roles = extractor.extract(text);
        assertFalse(roles.isEmpty());
        assertEquals(5, roles.get(0).getIcLevel());
    }

    @Test void extract_durationComputed() {
        String text = "Engineer\nAcme | Jan 2020 – Jan 2023\n• Built things";
        List<WorkExperience> roles = extractor.extract(text);
        assertFalse(roles.isEmpty());
        assertEquals(3.0, roles.get(0).getDurationYears(), 0.1);
    }

    // ── parseDate (package-private, tested directly) ──────────────────────────

    @Test void parseDate_monthYear() {
        LocalDate d = extractor.parseDate("May 2021", true);
        assertNotNull(d);
        assertEquals(2021, d.getYear());
        assertEquals(5, d.getMonthValue());
    }

    @Test void parseDate_mmYyyy() {
        LocalDate d = extractor.parseDate("04/2022", true);
        assertNotNull(d);
        assertEquals(2022, d.getYear());
        assertEquals(4, d.getMonthValue());
    }

    @Test void parseDate_yearOnly() {
        LocalDate d = extractor.parseDate("2019", true);
        assertNotNull(d);
        assertEquals(2019, d.getYear());
    }

    @Test void parseDate_null_returnsNull() {
        assertNull(extractor.parseDate(null, true));
    }

    @Test void parseDate_garbage_returnsNull() {
        assertNull(extractor.parseDate("not a date", true));
    }
}
