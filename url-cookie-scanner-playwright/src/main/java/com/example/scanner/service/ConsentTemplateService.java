package com.example.scanner.service;
import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.repository.ConsentTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConsentTemplateService {

    @Autowired
    private ConsentTemplateRepository repository;

    public ConsentTemplate createTemplate(ConsentTemplate template) {
        template.setScanId(UUID.randomUUID().toString());
        template.setCreatedAt(Instant.now());
        template.setUpdatedAt(Instant.now());
        return repository.save(template);
    }

    public Optional<ConsentTemplate> getTemplateByScanId(String scanId) {
        return repository.findByScanId(scanId);
    }

    public List<ConsentTemplate> getTemplatesByBusinessId(String businessId) {
        return repository.findByBusinessId(businessId);
    }

    public List<ConsentTemplate> getTemplatesByStatus(String status) {
        return repository.findByStatus(status);
    }

    public ConsentTemplate updateTemplate(String scanId, ConsentTemplate template) {
        Optional<ConsentTemplate> existingTemplate = repository.findByScanId(scanId);
        if (existingTemplate.isPresent()) {
            ConsentTemplate existing = existingTemplate.get();
            existing.setTemplateName(template.getTemplateName());
            existing.setBusinessId(template.getBusinessId());
            existing.setStatus(template.getStatus());
            existing.setMultilingual(template.getMultilingual());
            existing.setUiConfig(template.getUiConfig());
            existing.setDocumentMeta(template.getDocumentMeta());
            existing.setPreferences(template.getPreferences());
            existing.setUpdatedAt(Instant.now());
            existing.setVersion(existing.getVersion() + 1);
            return repository.save(existing);
        }
        throw new RuntimeException("Template not found with scanId: " + scanId);
    }

    public boolean deleteTemplate(String scanId) {
        Optional<ConsentTemplate> template = repository.findByScanId(scanId);
        if (template.isPresent()) {
            repository.delete(template.get());
            return true;
        }
        return false;
    }

    public ConsentTemplate publishTemplate(String scanId) {
        Optional<ConsentTemplate> template = repository.findByScanId(scanId);
        if (template.isPresent()) {
            ConsentTemplate existing = template.get();
            existing.setStatus("PUBLISHED");
            existing.setUpdatedAt(Instant.now());
            return repository.save(existing);
        }
        throw new RuntimeException("Template not found with scanId: " + scanId);
    }
}
