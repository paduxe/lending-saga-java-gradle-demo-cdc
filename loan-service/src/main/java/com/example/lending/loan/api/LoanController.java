package com.example.lending.loan.api;

import com.example.lending.loan.service.LoanApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanController {
    private final LoanApplicationService service;
    private final com.example.lending.loan.antifraud.AntiFraudService antiFraudService;

    @PostMapping
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "createLoanApi", fallbackMethod = "rateLimitFallback")
    public CreateLoanResponse create(@Valid @RequestBody CreateLoanRequest request) {
        
        // Bước 0 của TẤT CẢ Hệ thống Tài chính: RULES ENGINE CHECK (ANTI-FRAUD)
        // Gọi Engine để quét độ khả nghi. Nếu lừa đảo, báo lỗi RuntimeException, App sẽ rơi luồng.
        // Chưa tốn bất kỳ 1 ID hay 1 DB Transaction nào của Postgres.
        antiFraudService.evaluate(request);

        // Sinh ID từ tầng Controller (trước khi vào Transaction)
        String loanId = java.util.UUID.randomUUID().toString();
        
        // Thuật toán Sharding đơn giản (Hash Modulo 2)
        int shardIndex = Math.abs(loanId.hashCode()) % 2;
        String targetShard = (shardIndex == 0) ? "SHARD_1" : "SHARD_2";
        
        System.out.println("🚀 [SHARDING] Routing request to: " + targetShard + " for loanId: " + loanId);
        com.example.lending.loan.config.ShardContext.setCurrentShard(targetShard);
        
        try {
            return service.create(loanId, request);
        } finally {
            com.example.lending.loan.config.ShardContext.clear(); // Xóa Context để không bị Memory Leak
        }
    }

    // 1. RATE LIMITING (FALLBACK)
    // Khi User vượt mốc 3 Request / Phút, hàm chính bị block không cho gọi Service
    // Resilience4j sẽ đá luồng chạy vào hàm này để trả về mã 429
    public CreateLoanResponse rateLimitFallback(CreateLoanRequest request, Throwable t) {
        System.err.println("❌ [RATE_LIMIT] Đã chặn đứng cuộc Tấn công/Spam API từ IP khách hàng!");
        return new CreateLoanResponse("BLOCKED", "BLOCKED", "REJECTED_429_TOO_MANY_REQUESTS");
    }
}
