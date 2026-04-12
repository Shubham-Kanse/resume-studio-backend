package com.resumestudio.reviewer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "redis")
public class RedisProperties {

    private String url;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
