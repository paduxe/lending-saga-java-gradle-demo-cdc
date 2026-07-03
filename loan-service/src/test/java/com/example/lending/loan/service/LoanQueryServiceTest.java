package com.example.lending.loan.service;

import com.example.lending.loan.loan.LoanApplicationDocument;
import com.example.lending.loan.loan.LoanApplicationSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// GIẢI QUYẾT HẠN CHẾ #1: Test Coverage - Block cuối cùng cản bước Production
@ExtendWith(MockitoExtension.class)
class LoanQueryServiceTest {

    @Mock
    private LoanApplicationSearchRepository elasticsearchRepository;

    @InjectMocks
    private LoanQueryService loanQueryService;

    private LoanApplicationDocument mockDocument;

    @BeforeEach
    void setUp() {
        mockDocument = new LoanApplicationDocument();
        mockDocument.setLoanId("L-123");
        mockDocument.setCustomerId("CUS-999");
        mockDocument.setStatus("APPROVED");
    }

    @Test
    void testFindLoansByCustomerCached_Success() {
        // Arrange
        when(elasticsearchRepository.findByCustomerId("CUS-999"))
                .thenReturn(Arrays.asList(mockDocument));

        // Act
        List<LoanApplicationDocument> result = loanQueryService.findLoansByCustomerCached("CUS-999");

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("L-123", result.get(0).getLoanId());
        verify(elasticsearchRepository, times(1)).findByCustomerId("CUS-999");
    }

    @Test
    void testFindLoansByCustomerCached_Fallback() {
        // Mô phỏng trường hợp Elasticsearch bị sập -> Trả về danh sách rỗng (Fallback logic thực tế)
        // Act
        List<LoanApplicationDocument> result = loanQueryService.fallbackGetLoans("CUS-999", new RuntimeException("ES Timeout"));

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindLoansByStatusSearch_Pagination() {
        // Arrange
        Page<LoanApplicationDocument> expectedPage = new PageImpl<>(Arrays.asList(mockDocument));
        when(elasticsearchRepository.findByStatus(eq("APPROVED"), any(PageRequest.class)))
                .thenReturn(expectedPage);

        // Act
        Page<LoanApplicationDocument> result = loanQueryService.findLoansByStatusSearch("APPROVED", PageRequest.of(0, 10));

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("L-123", result.getContent().get(0).getLoanId());
        verify(elasticsearchRepository, times(1)).findByStatus(eq("APPROVED"), any(PageRequest.class));
    }
}
