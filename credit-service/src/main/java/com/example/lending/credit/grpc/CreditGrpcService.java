package com.example.lending.credit.grpc;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@GrpcService
public class CreditGrpcService extends CreditServiceGrpc.CreditServiceImplBase {

    @Override
    public void checkCredit(CreditRequest request, StreamObserver<CreditResponse> responseObserver) {
        log.info("gRPC received Credit check request for customer: {} with amount: {}", request.getCustomerId(), request.getLoanAmount());

        // MÔ PHỎNG BUSINESS LOGIC KIỂM TRA HẠN MỨC ĐỒNG BỘ
        double limit = 500000000.0; // 500 triệu
        boolean isQualified = request.getLoanAmount() <= limit;
        String reason = isQualified ? "Amount within limit" : "Amount exceeds pre-approved limit";

        // MÔ PHỎNG TRƯỜNG HỢP FAIL NHANH: NẾU CUSTOMER ID LÀ "BAD", AUTO REJECT
        if ("BAD_CREDIT".equals(request.getCustomerId())) {
            isQualified = false;
            reason = "Customer has bad credit history (Pre-check)";
        }

        CreditResponse response = CreditResponse.newBuilder()
                .setIsQualified(isQualified)
                .setAvailableLimit(limit)
                .setReason(reason)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
        log.info("gRPC Credit check completed for customer: {}. Qualified: {}", request.getCustomerId(), isQualified);
    }
}
