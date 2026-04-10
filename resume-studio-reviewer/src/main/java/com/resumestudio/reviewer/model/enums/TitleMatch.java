package com.resumestudio.reviewer.model.enums;

public enum TitleMatch {
    EXACT,       // "Senior Backend Engineer" == "Senior Backend Engineer"
    ADJACENT,    // "Backend Engineer" ~= "Senior Backend Engineer"
    RELATED,     // "Full Stack Engineer" applying for backend role
    MISS         // "QA Engineer" applying for backend role
}
