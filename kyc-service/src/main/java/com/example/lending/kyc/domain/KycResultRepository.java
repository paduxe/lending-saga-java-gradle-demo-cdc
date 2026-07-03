package com.example.lending.kyc.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KycResultRepository extends JpaRepository<KycResultEntity, String> {
    Optional<KycResultEntity> findBySagaId(String sagaId);
}
