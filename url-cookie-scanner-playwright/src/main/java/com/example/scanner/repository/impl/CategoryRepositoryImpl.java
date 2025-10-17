package com.example.scanner.repository.impl;

import com.example.scanner.entity.CookieCategory;
import com.example.scanner.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Slf4j
public class CategoryRepositoryImpl {

    private final MongoTemplate mongoTemplate;

    public CookieCategory save(CookieCategory cookieCategory) {
        log.debug("Saving cookie category: {}", cookieCategory.getCategory());
        return mongoTemplate.save(cookieCategory, "cookie_category_master");
    }

    public Optional<CookieCategory> findByCategory(String category) {
        log.debug("Finding cookie by category: {}", category);
        Query query = new Query(Criteria.where("category").is(category));
        CookieCategory cookieCategory = mongoTemplate.findOne(query, CookieCategory.class, "cookie_category_master");
        return Optional.ofNullable(cookieCategory);
    }

    public Optional<CookieCategory> findByCategoryId(UUID categoryId) {
        log.debug("Finding cookie by categoryId: {}", categoryId);
        Query query = new Query(Criteria.where("_id").is(categoryId));
        CookieCategory cookieCategory = mongoTemplate.findOne(query, CookieCategory.class, "cookie_category_master");
        return Optional.ofNullable(cookieCategory);
    }

    public boolean existsByCategory(String category) {
        log.debug("Checking if category exists: {}", category);
        Query query = new Query(Criteria.where("category").is(category));
        return mongoTemplate.exists(query, CookieCategory.class, "cookie_category_master");
    }

    public void delete(CookieCategory cookieCategory) {
        log.debug("Deleting cookie category: {}", cookieCategory.getCategory());
        mongoTemplate.remove(cookieCategory, "cookie_category_master");
    }

    public CookieCategory update(CookieCategory cookieCategory) {
        log.debug("Updating cookie category: {}", cookieCategory.getCategoryId());
        return mongoTemplate.save(cookieCategory, "cookie_category_master");
    }
}