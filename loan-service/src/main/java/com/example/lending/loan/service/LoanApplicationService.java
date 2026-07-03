package com.example.lending.loan.service;

import com.example.lending.loan.api.CreateLoanRequest;
import com.example.lending.loan.api.CreateLoanResponse;
import com.example.lending.loan.events.LoanEvent;
import com.example.lending.loan.loan.LoanApplicationEntity;
import com.example.lending.loan.loan.LoanApplicationRepository;
import com.example.lending.loan.outbox.OutboxEventEntity;
import com.example.lending.loan.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanApplicationService {
    private final LoanApplicationRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final com.example.lending.loan.grpc.KycGrpcClient kycGrpcClient;
    private final com.example.lending.loan.grpc.CreditGrpcClient creditGrpcClient;

    @Transactional
    public CreateLoanResponse create(String loanId, CreateLoanRequest request) {
        // 🚀 BƯỚC MỚI: ĐỒNG BỘ GỌI GRPC KYC CHECK (FAST-FAIL)
        var kycResponse = kycGrpcClient.checkCustomer(request.customerId());
        if (kycResponse.getIsBlocked()) {
            log.warn("Loan application rejected immediately via KYC gRPC: {}", kycResponse.getReason());
            return new CreateLoanResponse("NONE", loanId, "REJECTED_BY_KYC_PRECHECK: " + kycResponse.getReason());
        }

        // 🚀 BƯỚC MỚI: ĐỒNG BỘ GỌI GRPC CREDIT CHECK (FAST-FAIL)
        var creditResponse = creditGrpcClient.checkCredit(request.customerId(), request.amount());
        if (!creditResponse.getIsQualified()) {
            log.warn("Loan application rejected immediately via Credit gRPC: {}", creditResponse.getReason());
            return new CreateLoanResponse("NONE", loanId, "REJECTED_BY_CREDIT_PRECHECK: " + creditResponse.getReason());
        }

        // sagaId: ID của toàn bộ workflow (transaction).
//        - correlate tất cả event/command trong cùng 1 saga
//        - lookup LoanSagaEntity
//        - trace end-to-end flow
//👉 1 saga = 1 business transaction (loan flow)
        String sagaId = UUID.randomUUID().toString();

//        loanId (loanApplicationId) nay duoc tao va tinh toan Shard tu Controller
//        - luu trong DB cua tung service
//        - lam Kafka key -> dam bao ordering theo loan
//        - xu ly business logic (check trang thai loan)

        repository.save(LoanApplicationEntity.builder()
                .loanApplicationId(loanId)
                .sagaId(sagaId)
                .customerId(request.customerId())
                .amount(request.amount())
                .productType(request.productType())
                .status("PENDING")
                .simulateKycFail(Boolean.TRUE.equals(request.simulateKycFail()))
                .simulateCreditReject(Boolean.TRUE.equals(request.simulateCreditReject()))
                .simulateDisbursementFail(Boolean.TRUE.equals(request.simulateDisbursementFail()))
                .createdAt(Instant.now())
                .build());

        try {
            LoanEvent event = new LoanEvent(
                    UUID.randomUUID().toString(), sagaId, "LoanApplicationCreated", loanId,
                    request.customerId(), request.amount(), request.productType(),
                    Boolean.TRUE.equals(request.simulateKycFail()),
                    Boolean.TRUE.equals(request.simulateCreditReject()),
                    Boolean.TRUE.equals(request.simulateDisbursementFail()),
                    Instant.now()
            );
            outboxRepository.save(OutboxEventEntity.builder()
                    .eventId(event.eventId())
                    .topicName("saga-events")
                    .eventType(event.eventType())
                    .aggregateId(loanId)
                    .payload(objectMapper.writeValueAsString(event))
                    .status("NEW")
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return new CreateLoanResponse(sagaId, loanId, "PENDING");
    }
}
