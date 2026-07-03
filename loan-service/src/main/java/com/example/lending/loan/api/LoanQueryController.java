package com.example.lending.loan.api;

import com.example.lending.loan.loan.LoanApplicationDocument;
import com.example.lending.loan.service.LoanQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/queries/loans")
@RequiredArgsConstructor
public class LoanQueryController {

    private final LoanQueryService queryService;

    // CQRS Read Model với Elasticsearch + Redis Cache
    @GetMapping("/customer/{customerId}")
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "queryLoanApi")
    public ResponseEntity<List<LoanApplicationDocument>> getLoansByCustomer(
            @PathVariable String customerId) {
            
        return ResponseEntity.ok(queryService.findLoansByCustomerCached(customerId));
    }
    
    @GetMapping("/{loanId}")
    public ResponseEntity<LoanApplicationDocument> getLoanById(@PathVariable String loanId) {
        return queryService.findLoanById(loanId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<Page<LoanApplicationDocument>> getLoansByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
            
        return ResponseEntity.ok(queryService.findLoansByStatusSearch(status, PageRequest.of(page, size)));
    }
}
