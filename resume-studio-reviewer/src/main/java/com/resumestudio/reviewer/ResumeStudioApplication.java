package com.resumestudio.reviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.resumestudio")
@EnableJpaRepositories(basePackages = "com.resumestudio")
@EntityScan(basePackages = "com.resumestudio")
@EnableAsync
public class ResumeStudioApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResumeStudioApplication.class, args);
    }
}