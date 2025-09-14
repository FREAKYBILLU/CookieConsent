package com.example.scanner.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "storage-patterns")
@Getter
@Setter
public class StoragePatternsConfig {

    private List<String> knownTrackingKeys;
    private Generic generic;

    @Getter
    @Setter
    public static class Generic {
        private ShortKeys shortKeys;
        private List<String> regexPatterns;
        private List<String> keywordPatterns;
        private List<String> prefixPatterns;

        @Getter
        @Setter
        public static class ShortKeys {
            private int minLength;
            private int maxLength;
        }
    }
}