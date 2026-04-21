package com.resumestudio.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TrackerReminderEmailSender {

    private static final Logger log = LoggerFactory.getLogger(TrackerReminderEmailSender.class);

    private final String resendApiKey;
    private final String fromEmail;
    private final String appBaseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public TrackerReminderEmailSender(
        @Value("${tracker.reminders.resend-api-key:}") String resendApiKey,
        @Value("${tracker.reminders.from-email:alerts@resume-studio.app}") String fromEmail,
        @Value("${app.base-url:http://localhost:3000}") String appBaseUrl
    ) {
        this.resendApiKey = resendApiKey;
        this.fromEmail = fromEmail;
        this.appBaseUrl = appBaseUrl;
    }

    public boolean isConfigured() {
        return resendApiKey != null && !resendApiKey.isBlank();
    }

    public boolean sendReminder(JobApplication job) {
        String recipient = job.getUserEmail();
        if (recipient == null || recipient.isBlank()) return false;
        if (!isConfigured()) return false;

        try {
            String company = safe(job.getCompany(), "this company");
            String role = safe(job.getPosition(), "the role");
            String subject = "To Apply".equals(job.getStage())
                ? "Reminder: Apply to " + role + " at " + company
                : "Reminder: Follow up on " + role + " at " + company;

            String trackerUrl = trimTrailingSlash(appBaseUrl) + "/tracker";
            String jobUrl = job.getJobUrl() != null && !job.getJobUrl().isBlank() ? job.getJobUrl() : trackerUrl;
            String bodyText = "Quick reminder from Resume Studio.\n\n"
                + "Company: " + company + "\n"
                + "Role: " + role + "\n"
                + "Stage: " + safe(job.getStage(), "To Apply") + "\n\n"
                + "Open job tracker: " + trackerUrl + "\n"
                + "Job listing: " + jobUrl + "\n";
            String bodyHtml = "<p>Quick reminder from Resume Studio.</p>"
                + "<ul>"
                + "<li><strong>Company:</strong> " + escapeHtml(company) + "</li>"
                + "<li><strong>Role:</strong> " + escapeHtml(role) + "</li>"
                + "<li><strong>Stage:</strong> " + escapeHtml(safe(job.getStage(), "To Apply")) + "</li>"
                + "</ul>"
                + "<p><a href=\"" + escapeHtml(trackerUrl) + "\">Open job tracker</a><br/>"
                + "<a href=\"" + escapeHtml(jobUrl) + "\">Open job listing</a></p>";

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", fromEmail);
            payload.put("to", recipient);
            payload.put("subject", subject);
            payload.put("text", bodyText);
            payload.put("html", bodyHtml);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.resend.com/emails"))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + resendApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return true;

            log.warn("Tracker reminder email failed with status {} for job {}: {}", response.statusCode(), job.getId(), response.body());
            return false;
        } catch (Exception e) {
            log.warn("Tracker reminder email send failed for job {}: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
