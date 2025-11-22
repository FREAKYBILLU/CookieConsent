package com.example.scanner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class CookieCsvLoaderService {

    private static final Map<String, String> COOKIE_CATEGORY_MAP = new ConcurrentHashMap<>();
    private static final String CSV_FILE_PATH = "open-cookie-database.csv";

    @PostConstruct
    public void loadCsvData() {
        log.info("Loading cookie categorization data from CSV...");

        try {
            ClassPathResource resource = new ClassPathResource(CSV_FILE_PATH);
            if (!resource.exists()) {
                log.warn("CSV file not found");
                return;
            }

            int count = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                boolean isFirstLine = true;

                while ((line = reader.readLine()) != null) {
                    if (isFirstLine) {
                        isFirstLine = false;
                        continue; // Skip header
                    }

                    String[] parts = line.split(",", 2);
                    if (parts.length == 2) {
                        COOKIE_CATEGORY_MAP.put(parts[1].trim(), parts[0].trim());
                        count++;
                    }
                }
            }

            log.info("CSV loaded successfully.");

        } catch (Exception e) {
            log.error("Failed to load");
        }
    }

    public String getCategoryForCookie(String cookieName) {
        return cookieName != null ? COOKIE_CATEGORY_MAP.get(cookieName.trim()) : null;
    }
}