package com.example.lending.loan.grpc;

import com.example.lending.kyc.grpc.KycRequest;
import com.example.lending.kyc.grpc.KycResponse;
import com.example.lending.kyc.grpc.KycServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Slf4j
@Service
public class KycGrpcClient {

    @GrpcClient("kyc-service")
    private KycServiceGrpc.KycServiceBlockingStub kycServiceBlockingStub;

    @CircuitBreaker(name = "kycServiceStub", fallbackMethod = "fallbackCheck")
    public KycResponse checkCustomer(String customerId) {
        log.info("Calling gRPC KycService for customer: {}", customerId);
        KycRequest request = KycRequest.newBuilder()
                .setCustomerId(customerId)
                .build();
        
        return kycServiceBlockingStub.checkCustomer(request);
    }

    public KycResponse fallbackCheck(String customerId, Throwable t) {
        log.warn("gRPC KYC check failed for customer: {}. Error: {}. Falling back to default 'OK' to maintain availability.", customerId, t.getMessage());
        // FALLBACK: Trong trường hợp service KYC chết, ta có thể chọn cho phép tiếp tục 
        // và tin tưởng vào flow bất đồng bộ Kafka bên dưới để xử lý triệt để sau.
        return KycResponse.newBuilder()
                .setIsBlocked(false)
                .setReason("Fallback: Service unavailable, proceeding via Async flow")
                .setStatus("PENDING_ASYNC")
                .build();
    }
}
