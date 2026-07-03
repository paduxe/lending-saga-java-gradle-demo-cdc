package com.example.lending.loan.service;

import com.example.lending.loan.loan.LoanApplicationDocument;
import com.example.lending.loan.loan.LoanApplicationSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanQueryService {

    // NÂNG CẤP CQRS: Dùng Elasticsearch cho toàn bộ Luồng Đọc. 
    // Không bao giờ còn phải đụng tới Postgres cho thao tác Search nữa.
    private final LoanApplicationSearchRepository elasticsearchRepository;

    /**
     * Cache-Aside kết hợp Elasticsearch:
     * - Vẫn ưu tiên trả Kết quả từ RAM Redis (< 1ms).
     * - Nếu Redis trượt (Cache Miss), quét vào Elasticsearch (~ 10ms).
     */
    @Cacheable(value = "customerLoansES", key = "#customerId", unless = "#result.isEmpty()")
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "searchService", fallbackMethod = "fallbackGetLoans")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "searchService", type = io.github.resilience4j.bulkhead.annotation.Bulkhead.Type.SEMAPHORE)
    public List<LoanApplicationDocument> findLoansByCustomerCached(String customerId) {
        log.info("🔥 [CACHE MISS] Truy vấn Data từ ELASTICSEARCH Engine: customerId={}", customerId);
        return elasticsearchRepository.findByCustomerId(customerId);
    }

    public List<LoanApplicationDocument> fallbackGetLoans(String customerId, Throwable t) {
        log.error("⚠️ [CRITICAL] Search ES gãy! Trả về danh sách rỗng để bảo toàn UI. Lỗi: {}", t.getMessage());
        return java.util.Collections.emptyList();
    }

    /**
     * Thống kê báo cáo, tìm kiếm Free-Text tải nặng. 
     * Phân trang mượt mà nhờ kiến trúc Inverted Index của Elasticsearch.
     */
    public Page<LoanApplicationDocument> findLoansByStatusSearch(String status, Pageable pageable) {
        log.info("📊 [CQRS ELASTICSEARCH] Đang quét khối lượng lớn Log theo trạng thái: {}", status);
        return elasticsearchRepository.findByStatus(status, pageable);
    }
}
