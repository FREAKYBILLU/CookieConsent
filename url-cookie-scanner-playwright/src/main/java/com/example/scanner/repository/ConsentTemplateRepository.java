package com.example.scanner.repository;

import com.example.scanner.entity.ConsentTemplate;
import com.example.scanner.enums.VersionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentTemplateRepository extends MongoRepository<ConsentTemplate, String> {

}