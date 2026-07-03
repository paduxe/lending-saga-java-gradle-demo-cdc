package com.example.lending.loan.controller;

import com.example.lending.kyc.grpc.KycResponse;
import com.example.lending.credit.grpc.CreditResponse;
import com.example.lending.loan.grpc.KycGrpcClient;
import com.example.lending.loan.grpc.CreditGrpcClient;
import com.example.lending.loan.loan.LoanApplicationEntity;
import com.example.lending.loan.loan.LoanApplicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
public class LoanGraphQLController {

    private final LoanApplicationRepository repository;
    private final KycGrpcClient kycGrpcClient;
    private final CreditGrpcClient creditGrpcClient;

    @QueryMapping
    public Optional<LoanApplicationEntity> loanDetails(@Argument String loanId) {
        log.info("GraphQL Query: Fetching loan details for ID: {}", loanId);
        return repository.findById(loanId);
    }

    @SchemaMapping(typeName = "LoanApplication", field = "customer")
    public CustomerInfo getCustomerInfo(LoanApplicationEntity loan) {
        log.info("GraphQL Resolving: Fetching customer info for loan: {}", loan.getLoanApplicationId());
        // Gọi gRPC sang KYC Service để lấy thông tin chi tiết
        KycResponse response = kycGrpcClient.checkCustomer(loan.getCustomerId());
        return new CustomerInfo(response.getIsBlocked(), response.getStatus(), response.getReason());
    }

    @SchemaMapping(typeName = "LoanApplication", field = "credit")
    public CreditInfo getCreditInfo(LoanApplicationEntity loan) {
        log.info("GraphQL Resolving: Fetching credit info for loan: {}", loan.getLoanApplicationId());
        // Gọi gRPC sang Credit Service để lấy hạn mức
        CreditResponse response = creditGrpcClient.checkCredit(loan.getCustomerId(), loan.getAmount());
        return new CreditInfo(response.getIsQualified(), response.getAvailableLimit(), response.getReason());
    }

    // Records để map với GraphQL Schema
    public record CustomerInfo(boolean isBlocked, String status, String reason) {}
    public record CreditInfo(boolean isQualified, double availableLimit, String reason) {}
}
