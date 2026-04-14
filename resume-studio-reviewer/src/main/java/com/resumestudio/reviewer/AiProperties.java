package com.resumestudio.reviewer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "ai.api")
public class AiProperties {

    private String key;
    private String url;
    private String model;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    @PostConstruct
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "AI_API_KEY is not configured. Set the 'ai.api.key' property or AI_API_KEY environment variable.");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("ai.api.url is not configured.");
        }
    }
}
