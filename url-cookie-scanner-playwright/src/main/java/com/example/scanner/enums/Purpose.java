package com.example.scanner.enums;

public enum Purpose {
    STRICTLY_NECESSARY("Strictly Necessary", "Essential cookies for website functionality"),
    FUNCTIONAL("Functional", "Cookies for enhanced functionality and personalization"),
    ANALYTICS_PERFORMANCE("Analytics & Performance", "Cookies to understand how visitors use the website"),
    MARKETING_ADVERTISING("Marketing & Advertising", "Cookies for targeted advertising"),
    SOCIAL_MEDIA("Social Media", "Cookies for social media integration");

    private final String displayName;
    private final String description;

    Purpose(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}