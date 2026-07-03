package com.example.lending.orchestrator.saga;

public enum SagaEvent {
    START_SAGA,
    KYC_PASSED,
    KYC_FAILED,
    CREDIT_APPROVED,
    CREDIT_REJECTED,
    DISBURSEMENT_SUCCEEDED,
    DISBURSEMENT_FAILED,
    CREDIT_CANCELLED,
    TIMEOUT
}
