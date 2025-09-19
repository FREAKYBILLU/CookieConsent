package com.example.scanner.repository;

import com.example.scanner.entity.ConsentTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentTemplateRepository extends MongoRepository<ConsentTemplate, String> {

    Optional<ConsentTemplate> findByScanId(String scanId);

    List<ConsentTemplate> findByBusinessId(String businessId);

    List<ConsentTemplate> findByStatus(String status);

    @Query("{'businessId': ?0, 'status': ?1}")
    List<ConsentTemplate> findByBusinessIdAndStatus(String businessId, String status);

    @Query("{'scanId': ?0, 'businessId': ?1}")
    Optional<ConsentTemplate> findByScanIdAndBusinessId(String scanId, String businessId);
}
