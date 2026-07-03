package com.example.lending.loan.grpc;

import com.example.lending.credit.grpc.CreditRequest;
import com.example.lending.credit.grpc.CreditResponse;
import com.example.lending.credit.grpc.CreditServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.math.BigDecimal;

@Slf4j
@Service
public class CreditGrpcClient {

    @GrpcClient("credit-service")
    private CreditServiceGrpc.CreditServiceBlockingStub creditServiceBlockingStub;

    @CircuitBreaker(name = "creditServiceStub", fallbackMethod = "fallbackCheck")
    public CreditResponse checkCredit(String customerId, BigDecimal amount) {
        log.info("Calling gRPC CreditService for customer: {} with amount: {}", customerId, amount);
        CreditRequest request = CreditRequest.newBuilder()
                .setCustomerId(customerId)
                .setLoanAmount(amount.doubleValue())
                .build();
        
        return creditServiceBlockingStub.checkCredit(request);
    }

    public CreditResponse fallbackCheck(String customerId, BigDecimal amount, Throwable t) {
        log.warn("gRPC Credit check failed for customer: {}. Error: {}. Falling back to default 'OK'.", customerId, t.getMessage());
        return CreditResponse.newBuilder()
                .setIsQualified(true) // Cho phép đi tiếp vào Saga bất đồng bộ
                .setReason("Fallback: Credit service unavailable")
                .build();
    }
}
