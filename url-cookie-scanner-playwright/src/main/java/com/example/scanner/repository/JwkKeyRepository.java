package com.example.scanner.repository;

import com.example.scanner.dto.JwkKey;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JwkKeyRepository extends MongoRepository<JwkKey, String> {
    JwkKey findFirstByOrderByKidAsc();
}
