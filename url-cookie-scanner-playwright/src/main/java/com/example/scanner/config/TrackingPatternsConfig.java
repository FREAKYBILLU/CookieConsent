package com.example.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "tracking.patterns")
@Data
public class TrackingPatternsConfig {

    private UrlPatterns urlPatterns = new UrlPatterns();
    private ParameterPatterns parameterPatterns = new ParameterPatterns();
    private ValuePatterns valuePatterns = new ValuePatterns();

    @Data
    public static class UrlPatterns {
        private List<String> fileExtensions = new ArrayList<>();
        private List<String> endpoints = new ArrayList<>();
        private List<String> singleCharFiles = new ArrayList<>();
        private List<String> domainPrefixes = new ArrayList<>();
        private List<String> specialPatterns = new ArrayList<>();
    }

    @Data
    public static class ParameterPatterns {
        private List<String> idPatterns = new ArrayList<>();
        private List<String> sessionAuth = new ArrayList<>();
        private List<String> trackingAnalytics = new ArrayList<>();
        private List<String> pageContent = new ArrayList<>();
        private List<String> userDevice = new ArrayList<>();
        private List<String> shortCryptic = new ArrayList<>();
        private List<String> timePatterns = new ArrayList<>();
        private List<String> randomCache = new ArrayList<>();
    }

    @Data
    public static class ValuePatterns {
        private List<String> regexPatterns = new ArrayList<>();
        private List<String> booleanValues = new ArrayList<>();
    }
}