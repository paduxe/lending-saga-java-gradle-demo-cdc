# Sequence Diagrams: Lending Saga Flows

This document describes the dynamic behavior of the system across different business scenarios.

## 1. Happy Path: Successful Loan Application
This flow represents the ideal case where all services process the requests successfully.

![Happy Path](./images/happy-path.png)

```mermaid
sequenceDiagram
    autonumber
    participant LS as Loan Service
    participant OR as Orchestrator
    participant KS as KYC Service
    participant CS as Credit Service
    participant DS as Disbursement Service

    LS->>OR: LoanApplicationCreated (Kafka)
    OR->>OR: Start Saga (Status: STARTED)
    OR->>KS: VerifyKycCommand (Kafka)
    KS-->>OR: KycVerified (Kafka)
    OR->>CS: CheckCreditCommand (Kafka)
    CS-->>OR: CreditApproved (Kafka)
    OR->>DS: DisburseFundsCommand (Kafka)
    DS-->>OR: FundsDisbursed (Kafka)
    OR->>OR: End Saga (Status: COMPLETED)
```

**Notes:**
- **Step 1**: Triggered by the REST API in Loan Service.
- **Orchestration**: The Orchestrator manages the state transition after each event.
- **Asynchronous**: All communication between services happens via Kafka.

---

## 2. Compensation Path: Disbursement Failure
This flow occurs when the final step (Disbursement) fails, requiring the system to roll back (compensate) previous successful actions (like Credit approval).

![Compensation Path](./images/compensation-flow.png)

```mermaid
sequenceDiagram
    autonumber
    participant CS as Credit Service
    participant OR as Orchestrator
    participant DS as Disbursement Service

    Note over OR,DS: Loan & Credit are already approved
    OR->>DS: DisburseFundsCommand
    DS-->>OR: DisbursementFailed (Error: Invalid Bank Account)
    OR->>OR: Start Compensation (Status: COMPENSATING)
    OR->>CS: CancelCreditCommand
    CS-->>OR: CreditCancelled
    OR->>OR: End Saga (Status: FAILED_COMPENSATED)
```

**Notes:**
- **State Guarantee**: The Orchestrator ensures that even if a failure occurs at the last step, the system eventually reaches a consistent state (Credit is cancelled).
- **Idempotency**: Credit Service must handle `CancelCreditCommand` safely even if it's already cancelled or if the original `CheckCredit` was never processed.
