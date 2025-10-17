package com.example.scanner.service;
import com.example.scanner.config.TenantContext;
import com.example.scanner.dto.request.AddCookieCategoryRequest;
import com.example.scanner.dto.request.UpdateCookieCategoryRequest;
import com.example.scanner.dto.response.CookieCategoryResponse;
import com.example.scanner.entity.CookieCategory;
import com.example.scanner.repository.impl.CategoryRepositoryImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CategoryService {

    private final CategoryRepositoryImpl categoryRepository;

    public CookieCategoryResponse addCategory(AddCookieCategoryRequest request, String tenantId) {
        try {
            // Set tenant context for multi-tenant database connection
            TenantContext.setCurrentTenant(tenantId);
            log.info("Processing add category request for tenant: {}", tenantId);

            // Validate request
            if (request == null) {
                log.error("Request is null for tenant: {}", tenantId);
                return CookieCategoryResponse.builder()
                        .success(false)
                        .message("Invalid request")
                        .build();
            }

            // Check if category already exists
            Optional<CookieCategory> existingCookie = categoryRepository.findByCategory(request.getCategory());

            if (existingCookie.isPresent()) {
                log.warn("Category '{}' already exists for tenant: {}", request.getCategory(), tenantId);
                return CookieCategoryResponse.builder()
                        .success(false)
                        .message("Category already exists: " + request.getCategory())
                        .build();
            }

            // Create new cookie category
            CookieCategory cookieCategory = new CookieCategory();
            cookieCategory.setCategoryId(UUID.randomUUID().toString());
            cookieCategory.setCategory(request.getCategory());
            cookieCategory.setDescription(request.getDescription());
            cookieCategory.setDefault(request.getIsDefault() != null ? request.getIsDefault() : false);
            cookieCategory.setCreatedAt(new Date());
            cookieCategory.setUpdatedAt(new Date());

            // Save to database
            CookieCategory savedCookie = categoryRepository.save(cookieCategory);

            log.info("Successfully added category '{}' with ID: {} for tenant: {}",
                    savedCookie.getCategory(), savedCookie.getCategoryId(), tenantId);

            // Build success response
            return CookieCategoryResponse.builder()
                    .categoryId(savedCookie.getCategoryId())
                    .category(savedCookie.getCategory())
                    .description(savedCookie.getDescription())
                    .isDefault(savedCookie.isDefault())
                    .createdAt(savedCookie.getCreatedAt())
                    .updatedAt(savedCookie.getUpdatedAt())
                    .success(true)
                    .message("Category added successfully")
                    .build();

        } catch (Exception e) {
            log.error("Error adding category for tenant: {}. Error: {}", tenantId, e.getMessage(), e);
            return CookieCategoryResponse.builder()
                    .success(false)
                    .message("Failed to add category: " + e.getMessage())
                    .build();
        } finally {
            // Clear tenant context
            TenantContext.clear();
        }
    }

    public CookieCategoryResponse updateCookieCategory(UpdateCookieCategoryRequest request, String tenantId) {
        try {
            TenantContext.setCurrentTenant(tenantId);
            log.info("Processing add category request for tenant: {}", tenantId);

            // Validate request
            if (request == null) {
                log.error("Request is null for tenant: {}", tenantId);
                return CookieCategoryResponse.builder()
                        .success(false)
                        .message("Invalid request")
                        .build();
            }

            Optional<CookieCategory> category = categoryRepository.findByCategory(request.getCategory());

            if (category.isEmpty()) {
                log.error("Error updating category for tenant: {}. Error: no Category found with name {}", tenantId, request.getCategory());
                return CookieCategoryResponse.builder()
                        .success(false)
                        .message("Error updating category for tenant:" + tenantId + " Error: no Category found with name " + request.getCategory())
                        .build();
            }

            category.get().setDescription(request.getDescription());
            CookieCategory upDatedCookieCategory = categoryRepository.save(category.get());

            log.info("Successfully added category '{}' with ID: {} for tenant: {}",
                    upDatedCookieCategory.getCategory(), upDatedCookieCategory.getCategoryId(), tenantId);

            return CookieCategoryResponse.builder()
                    .categoryId(upDatedCookieCategory.getCategoryId())
                    .category(upDatedCookieCategory.getCategory())
                    .description(upDatedCookieCategory.getDescription())
                    .isDefault(upDatedCookieCategory.isDefault())
                    .createdAt(upDatedCookieCategory.getCreatedAt())
                    .updatedAt(upDatedCookieCategory.getUpdatedAt())
                    .success(true)
                    .message("Category updated successfully")
                    .build();
        } catch (Exception e) {
            log.error("Error updating category for tenant: {}. Error: {}", tenantId, e.getMessage(), e);
            return CookieCategoryResponse.builder()
                    .success(false)
                    .message("Failed to update category: " + e.getMessage())
                    .build();
        } finally {
            TenantContext.clear();
        }
    }

}