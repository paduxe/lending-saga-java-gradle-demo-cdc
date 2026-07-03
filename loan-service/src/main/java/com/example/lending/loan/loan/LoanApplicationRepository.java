package com.example.lending.loan.loan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanApplicationRepository extends JpaRepository<LoanApplicationEntity, String> {
    
    // Tìm toàn bộ khoản vay của 1 user
    List<LoanApplicationEntity> findByCustomerId(String customerId);

    // Tìm kiếm các khoản vay theo trạng thái với phân trang
    Page<LoanApplicationEntity> findByStatus(String status, Pageable pageable);
}
