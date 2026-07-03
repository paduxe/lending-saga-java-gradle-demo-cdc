package com.example.lending.loan.loan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface LoanApplicationSearchRepository extends ElasticsearchRepository<LoanApplicationDocument, String> {
    
    // Tìm kiếm tốc độ cao nhờ Inverted Index của Elasticsearch
    List<LoanApplicationDocument> findByCustomerId(String customerId);

    // Full-Text Search hoặc Filter kết hợp theo trạng thái
    Page<LoanApplicationDocument> findByStatus(String status, Pageable pageable);
}
