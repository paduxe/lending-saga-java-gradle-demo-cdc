package com.example.lending.orchestrator.saga;

public enum SagaState {
    INIT,
    KYC_REQUESTED,
    CREDIT_REQUESTED,
    DISBURSEMENT_REQUESTED,
    COMPENSATING_CREDIT,
    COMPLETED,
    FAILED
}
