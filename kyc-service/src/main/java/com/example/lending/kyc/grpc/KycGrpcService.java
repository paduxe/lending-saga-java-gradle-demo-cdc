package com.example.lending.kyc.grpc;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@GrpcService
public class KycGrpcService extends KycServiceGrpc.KycServiceImplBase {

    @Override
    public void checkCustomer(KycRequest request, StreamObserver<KycResponse> responseObserver) {
        log.info("gRPC received KYC check request for customer: {}", request.getCustomerId());

        // MÔ PHỎNG BUSINESS LOGIC KIỂM TRA ĐỒNG BỘ
        // Trong thực tế, đây có thể là check Blacklist trong Redis hoặc gọi một service 3rd party cực nhanh.
        
        boolean isBlocked = false;
        String reason = "Customer is clean";
        String status = "VERIFIED";

        // MÔ PHỎNG TRƯỜNG HỢP FAIL NHANH (FAST-FAIL)
        if (request.getCustomerId().startsWith("BL-")) {
            isBlocked = true;
            reason = "Customer is in Blacklist (System Pre-check)";
            status = "BLOCKED";
        }

        KycResponse response = KycResponse.newBuilder()
                .setIsBlocked(isBlocked)
                .setReason(reason)
                .setStatus(status)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
        log.info("gRPC KYC check completed for customer: {}. Result: {}", request.getCustomerId(), status);
    }
}
