commandType → “làm gì tiếp theo”
eventType → “điều gì đã xảy ra”
sagaId → workflow ID
loanId → business entity ID

1️⃣ saga-events: Chứa kết quả của các step (event đã xảy ra).
    Ví dụ:
    LoanApplicationCreated
    KycPassed / KycFailed
    CreditApproved

    Orchestrator consume topic này để:
    - update saga state
    - quyết định bước tiếp theo

2️⃣ saga-commands: Chứa yêu cầu thực hiện step tiếp theo (command).
    Ví dụ:
    VerifyKycCommand
    CheckCreditCommand
    DisburseLoanCommand

    Các service (KYC, Credit, Disbursement) consume topic này để:
     - thực thi business logic

1. Loan-Service:
 - Save LoanApplicationEntity:
        status = "PENDING"
 - Save OutboxEventEntity:
            eventId = UUID.randomUUID()
            eventType = "LoanApplicationCreated"
            topic = "saga-events"

 - publish:findTop20ByStatusOrderByCreatedAtAscIdAsc("NEW")
    -> send to topic "saga-events" | "saga-commands"

2.loan-orchestrator-service:
 - Listener from topic "saga-events"
    -> if eventType == "LoanApplicationCreated":
        onLoanCreated:
            - save LoanSagaEntity:
                 .currentStep(KYC_REQUESTED)
                 .sagaStatus(RUNNING)
            -  sendCommand(event, "CommandType: VerifyKycCommand")
                  -> save OutboxEventEntity:
                           .topicName("saga-commands")
                           .status("NEW")
        markProcessed:
            - save ProcessedMessageEntity
  - publish:findTop20ByStatusOrderByCreatedAtAscIdAsc("NEW")
     -> send to topic "saga-events" | "saga-commands"

3. KYC-Service:
 - Listener from topic "saga-commands"
    -> save KycResultEntity:
         .status(Boolean.TRUE.equals(command.simulateKycFail()) ? "FAILED" : "SUCCESS")
    -> save OutboxEventEntity:
                    .topicName("saga-events")
                    .eventType(event.eventType())
                    .status("NEW")
 - publish:findTop20ByStatusOrderByCreatedAtAscIdAsc("NEW")
     -> send to topic "saga-events" | "saga-commands"

4. credit-service:
 - Listener from topic "saga-commands"
   -> save CreditResultEntity:
          .status(Boolean.TRUE.equals(command.simulateCreditReject()) ? "FAILED" : "SUCCESS")
   -> save OutboxEventEntity
                          .topicName("saga-events")
                          .eventType(event.eventType())
                          .status("NEW")
  - publish:findTop20ByStatusOrderByCreatedAtAscIdAsc("NEW")
      -> send to topic "saga-events" | "saga-commands"

5. disbursement-service:
 - Listener from topic "saga-commands"
   -> save DisbursementResultEntity:
          .status(Boolean.TRUE.equals(command.simulateDisbursementFail()) ? "FAILED" : "SUCCESS")
   -> save OutboxEventEntity
                          .topicName("saga-events")
                          .eventType(event.eventType())
                          .status("NEW")
  - publish:findTop20ByStatusOrderByCreatedAtAscIdAsc("NEW")
      -> send to topic "saga-events" | "saga-commands"