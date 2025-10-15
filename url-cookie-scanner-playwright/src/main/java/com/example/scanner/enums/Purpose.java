package com.example.scanner.enums;

public enum Purpose {
    NECESSARY("Necessary", "Essential cookies required for website functionality"),
    FUNCTIONAL("Functional", "Cookies for enhanced functionality and personalization"),
    ANALYTICS("Analytics", "Cookies to understand how visitors use the website"),
    ADVERTISEMENT("Advertisement", "Cookies for targeted advertising and marketing"),
    OTHERS("Others", "Other types of cookies");

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