package com.resumestudio.reviewer.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Resolves a job description input — either raw text or a URL.
 *
 * If the input is a URL, fetches it via Jina Reader (r.jina.ai),
 * which renders JavaScript and returns clean markdown text.
 * Works with LinkedIn, Cisco, Workday, Greenhouse, Lever, and any other job board.
 */
@Service
public class JdFetchService {

    private static final Logger log = LoggerFactory.getLogger(JdFetchService.class);

    private static final Pattern URL_PATTERN = Pattern.compile(
        "^https?://[\\w\\-.]+(:\\d+)?(/.*)?$", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public String resolve(String input) {
        if (input == null || input.isBlank()) return input;

        String trimmed = input.trim();
        if (!URL_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }

        log.info("JD input is a URL — fetching via Jina Reader: {}", trimmed);
        try {
            String text = fetchViaJina(trimmed);
            log.info("JD fetched: {} chars from {}", text.length(), trimmed);
            if (text.length() < 50) {
                throw new RuntimeException(
                    "Could not extract content from this URL. Please paste the JD text directly.");
            }
            return text;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to fetch JD from URL ({}): {}", trimmed, e.getMessage());
            throw new RuntimeException("Could not fetch job description from URL: " + e.getMessage());
        }
    }

    private String fetchViaJina(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://r.jina.ai/" + url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "text/plain")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Jina Reader returned HTTP " + response.statusCode());
        }
        return response.body().trim();
    }
}
