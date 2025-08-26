package com.example.scanner.repository;

import com.example.scanner.entity.ScanResultEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ScanResultRepository extends MongoRepository<ScanResultEntity, String> {

    @Query("{ 'transactionId': ?#{#transactionId} }")
    Optional<ScanResultEntity> findByTransactionId(@Param("transactionId") String transactionId);

}
