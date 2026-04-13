package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.Education;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EducationExtractorTest {

    private EducationExtractor extractor;

    @BeforeEach
    void setUp() {
        EducationPrestigeService prestige = mock(EducationPrestigeService.class);
        when(prestige.institutionTier(anyString())).thenReturn("UNKNOWN");
        when(prestige.institutionBoost(anyString())).thenReturn(0.5);
        when(prestige.degreeRelevance(anyString())).thenReturn(0.5);
        extractor = new EducationExtractor(prestige);
    }

    @Test
    void extract_blank_returnsEmpty() {
        assertTrue(extractor.extract("  ").isEmpty());
    }

    @Test
    void extract_multipleEntries_parsesSeparateBlocks() {
        String text = """
            University College Dublin
            MSc Computer Science
            2021 - 2023
            
            Pune University
            B.E. Information Technology
            2015 - 2019
            """;

        List<Education> entries = extractor.extract(text);

        assertEquals(2, entries.size());
        assertEquals(2021, entries.get(0).getStartYear());
        assertEquals(2023, entries.get(0).getGraduationYear());
        assertEquals(2015, entries.get(1).getStartYear());
        assertEquals(2019, entries.get(1).getGraduationYear());
    }

    @Test
    void extract_singleYear_setsGraduationYear() {
        String text = """
            Trinity College Dublin
            Professional Certificate in Data Analytics
            2024
            """;

        List<Education> entries = extractor.extract(text);

        assertEquals(1, entries.size());
        Education entry = entries.get(0);
        assertNull(entry.getStartYear());
        assertEquals(2024, entry.getGraduationYear());
    }
}
