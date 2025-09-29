package com.example.scanner.repository;

import com.example.scanner.entity.ConsentHandle;

import java.util.List;
import java.util.Map;

public interface ConsentHandleRepository {

    ConsentHandle save(ConsentHandle consentHandle, String tenantId);

    ConsentHandle getByConsentHandleId(String consentHandleId, String tenantId);

}