package com.example.lending.loan.loan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-Model Document cho Elasticsearch.
 * Document này ánh xạ tự động với cấu hình CDC Sink từ Postgres.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "loan_db.public.loan_application") // Trùng tên topic CDC sinh ra ES index
public class LoanApplicationDocument {

    @Id
    private String loanApplicationId;

    @Field(type = FieldType.Keyword)
    private String sagaId;

    @Field(type = FieldType.Keyword)
    private String customerId;

    @Field(type = FieldType.Double)
    private BigDecimal amount;

    @Field(type = FieldType.Keyword)
    private String productType;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Date)
    private Instant createdAt;
}
