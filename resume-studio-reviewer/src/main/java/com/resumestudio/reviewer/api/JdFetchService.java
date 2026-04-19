package com.resumestudio.reviewer.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Resolves a job description input — either raw text or a URL.
 *
 * If the input is a URL, fetches it via Jina Reader (r.jina.ai),
 * which renders JavaScript and returns clean markdown text.
 * Works with LinkedIn, Cisco, Workday, Greenhouse, Lever, and any other job board.
 *
 * Results are cached in-memory for the JVM lifetime to avoid redundant fetches
 * when the same URL is submitted multiple times in quick succession.
 */
@Service
public class JdFetchService {

    private static final Logger log = LoggerFactory.getLogger(JdFetchService.class);

    private static final Pattern URL_PATTERN = Pattern.compile(
        "^https?://[^\\s]+$", Pattern.CASE_INSENSITIVE);

    private static final Pattern JINA_TITLE_PATTERN = Pattern.compile(
        "(?m)^Title:\\s*(.+)$");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // Cache: URL → resolved text. Bounded to 500 entries, 1 hour TTL.
    private final Cache<String, String> cache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build();

    public String resolve(String input) {
        if (input == null || input.isBlank()) return input;

        String trimmed = input.trim();
        if (!URL_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }

        String cached = cache.getIfPresent(trimmed);
        if (cached != null) {
            log.debug("JD URL cache hit: {}", trimmed);
            return cached;
        }

        log.info("JD input is a URL — fetching via Jina Reader: {}", trimmed);

        // Detect known blocked job boards upfront — saves a round-trip and gives a clear message
        String host = trimmed.toLowerCase();
        if (host.contains("linkedin.com") || host.contains("indeed.com") ||
            host.contains("glassdoor.com") || host.contains("ziprecruiter.com")) {
            throw new RuntimeException(
                "LinkedIn, Indeed, Glassdoor, and ZipRecruiter block automated access. " +
                "Please paste the job description text directly instead of the URL.");
        }

        try {
            String text = fetchViaJina(trimmed);
            log.info("JD fetched: {} chars from {}", text.length(), trimmed);
            cache.put(trimmed, text);
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
        if (response.statusCode() == 404) {
            throw new IOException("Job posting not found (404) — the job may have been removed or the URL is incorrect");
        }
        if (response.statusCode() == 403 || response.statusCode() == 401) {
            throw new IOException("Access denied (" + response.statusCode() + ") — this page requires login");
        }
        if (response.statusCode() >= 400) {
            throw new IOException("Jina Reader returned HTTP " + response.statusCode());
        }
        
        String body = response.body();
        if (body == null) {
            throw new IOException("Jina Reader returned null body");
        }

        // Detect login/auth walls — Jina renders them but they're not job descriptions
        String bodyLower = body.toLowerCase();
        boolean looksLikeLoginPage = (bodyLower.contains("sign in") || bodyLower.contains("log in") || bodyLower.contains("login"))
            && (bodyLower.contains("password") || bodyLower.contains("email address"))
            && !bodyLower.contains("requirements") && !bodyLower.contains("responsibilities");
        if (looksLikeLoginPage) {
            throw new IOException("This job posting requires login to view. Please paste the job description text directly.");
        }

        // Detect generic error pages
        boolean looksLikeErrorPage = (bodyLower.contains("page not found") || bodyLower.contains("404") || bodyLower.contains("job no longer available") || bodyLower.contains("this job has expired"))
            && body.length() < 2000;
        if (looksLikeErrorPage) {
            throw new IOException("Job posting not found or has expired. Please paste the job description text directly.");
        }
        
        // Strip Jina Reader metadata and extract only markdown content
        String cleaned = cleanJinaResponse(body);
        
        if (cleaned.length() < 50) {
            throw new IOException("Jina Reader returned insufficient content (" + cleaned.length() + " chars)");
        }
        
        return cleaned;
    }
    
    /**
     * Clean Jina Reader response by removing metadata headers.
     * Jina returns: Title, URL Source, Published Time, Warning, then Markdown Content.
     */
    private String cleanJinaResponse(String jinaOutput) {
        String extractedTitle = extractJinaTitle(jinaOutput);

        // Find "Markdown Content:" marker
        int contentStart = jinaOutput.indexOf("Markdown Content:");
        if (contentStart != -1) {
            // Skip the "Markdown Content:" line itself
            int actualStart = jinaOutput.indexOf('\n', contentStart);
            if (actualStart != -1) {
                return prependTitle(extractedTitle, jinaOutput.substring(actualStart + 1).trim());
            }
        }
        
        // Fallback: Remove common Jina metadata lines
        String[] lines = jinaOutput.split("\n");
        StringBuilder cleaned = new StringBuilder();
        boolean inContent = false;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Skip metadata lines
            if (trimmed.startsWith("Title:") || 
                trimmed.startsWith("URL Source:") ||
                trimmed.startsWith("Published Time:") ||
                trimmed.startsWith("Warning:") ||
                trimmed.equals("Markdown Content:")) {
                inContent = trimmed.equals("Markdown Content:");
                continue;
            }
            
            // Include everything after metadata
            if (inContent || cleaned.length() > 0) {
                cleaned.append(line).append("\n");
            }
        }
        
        return prependTitle(extractedTitle, cleaned.toString().trim());
    }

    private String extractJinaTitle(String jinaOutput) {
        var matcher = JINA_TITLE_PATTERN.matcher(jinaOutput);
        if (!matcher.find()) {
            return null;
        }

        String title = matcher.group(1).trim();
        if (title.isBlank() || title.equalsIgnoreCase("Untitled") || title.startsWith("http")) {
            return null;
        }

        // Jina titles are often "Company - Role - Location" or "Role | Company"
        // Extract the segment that looks like a job title
        for (String sep : new String[]{" - ", " | ", " – ", " — "}) {
            String[] parts = title.split(Pattern.quote(sep));
            for (String part : parts) {
                String p = part.trim();
                String lower = p.toLowerCase();
                if (lower.matches(".*(engineer|developer|architect|manager|analyst|designer|scientist|devops|sre|lead|specialist|programmer|director|officer).*")
                    && p.split("\\s+").length <= 8) {
                    return p;
                }
            }
        }

        return title;
    }

    private String prependTitle(String title, String content) {
        String trimmedContent = content == null ? "" : content.trim();
        if (title == null || title.isBlank()) {
            return trimmedContent;
        }
        if (trimmedContent.isBlank()) {
            return title;
        }
        if (trimmedContent.startsWith(title)) {
            return trimmedContent;
        }
        return title + "\n\n" + trimmedContent;
    }
}
