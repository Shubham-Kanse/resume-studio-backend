package com.resumestudio.reviewer;

import com.resumestudio.reviewer.model.FeedbackReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "logging.level.com.resumestudio.reviewer=ERROR"
})
class ReviewerPipelineChronologyTrustTest {

    @Autowired
    private ReviewerPipeline pipeline;

    @Test
    void reviewRawText_educationCoveredGap_doesNotHallucinateCareerGapFix() {
        String resumeText = """
            Alex Candidate
            Platform Engineer

            Summary
            Platform engineer with 5 years of experience in Kubernetes, Terraform, Prometheus, Grafana, and GitOps.

            Experience
            Platform Engineer
            Fiserv | Jan 2022 – Present
            • Built Kubernetes platforms using Terraform and Prometheus.

            Software Engineer
            Digitech Controls & Systems | Jan 2018 – Dec 2019
            • Worked on automation and internal tooling.

            Education
            University College Dublin
            MSc Computer Science
            2020 - 2021

            Skills
            Kubernetes, Terraform, Prometheus, Grafana, GitOps
            """;

        String jdText = """
            Platform Engineer

            Requirements
            - Kubernetes
            - Terraform
            - Prometheus
            - Grafana
            - GitOps
            """;

        FeedbackReport report = pipeline.reviewRawText(resumeText, jdText);

        assertNotNull(report);
        assertTrue(report.getFixes().stream().noneMatch(f -> "Label your career gap".equals(f.getAction())));
        assertTrue(report.getSignals().stream().noneMatch(s -> "chronology".equals(s.getId())));
    }

    @Test
    void reviewRawText_unreliableChronology_prefersChronologyFixOverGapNarrative() {
        String resumeText = """
            Jordan Candidate
            Platform Engineer

            Summary
            Platform engineer with 6 years of experience in Kubernetes, Terraform, Prometheus, Grafana, and GitOps.

            Experience
            Platform Engineer
            Fiserv | Jan 2022 – Present
            • Built Kubernetes platforms using Terraform and Prometheus.

            DevOps Engineer
            Acme Corp | Mar 2023 – Present
            • Owned Grafana dashboards and GitOps workflows.

            Skills
            Kubernetes, Terraform, Prometheus, Grafana, GitOps
            """;

        String jdText = """
            Platform Engineer

            Requirements
            - Kubernetes
            - Terraform
            - Prometheus
            - Grafana
            - GitOps
            """;

        FeedbackReport report = pipeline.reviewRawText(resumeText, jdText);

        assertNotNull(report);
        assertTrue(report.getSignals().stream().anyMatch(s -> "trust".equals(s.getId())));
        assertTrue(report.getFixes().stream().noneMatch(f -> "Label your career gap".equals(f.getAction())));
    }
}
