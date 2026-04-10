package com.resumestudio.reviewer.model;

import java.util.List;

public class Project {

    private String name;
    private String description;
    private List<String> techStack;
    private String url;               // GitHub or live URL if present

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getTechStack() { return techStack; }
    public void setTechStack(List<String> techStack) { this.techStack = techStack; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
