# Hướng dẫn kiểm tra GraphQL API (Aggregation Layer)

Tài liệu này hướng dẫn cách sử dụng GraphQL để truy vấn dữ liệu tổng hợp từ nhiều Microservices trong hệ thống Lending Saga.

## 1. Truy cập giao diện GraphiQL
Mặc định, Spring Boot cung cấp giao diện GraphiQL để bạn có thể viết và thực thi query ngay trên trình duyệt.

*   **URL:** `http://localhost:8081/graphiql` (Chạy trên port của `loan-service`)
*   **Cấu hình:** Đảm bảo `spring.graphql.graphiql.enabled: true` trong `application.yml`.

## 2. Các Query mẫu

### Query 1: Truy vấn Tổng hợp (Aggregation)
Query này sẽ kích hoạt `Loan Service` gọi đồng bộ sang `KYC Service` và `Credit Service` thông qua **gRPC** để lấy dữ liệu.

```graphql
query {
  loanDetails(loanId: "LOAN-SAMPLE-001") {
    loanApplicationId
    amount
    status
    # Tổng hợp từ KYC Service
    customer {
      status
      isBlocked
      reason
    }
    # Tổng hợp từ Credit Service
    credit {
      availableLimit
      isQualified
      reason
    }
  }
}
```

### Query 2: Mobile Layout (Tối ưu băng thông)
Giả sử trên màn hình danh sách, Mobile chỉ cần hiện ID và trạng thái Blacklist của User.

```graphql
query {
  loanDetails(loanId: "LOAN-SAMPLE-001") {
    loanApplicationId
    customer {
      isBlocked
    }
  }
}
```

## 3. Kiểm tra thông qua cURL

```bash
curl --location 'http://localhost:8081/graphql' \
--header 'Content-Type: application/json' \
--data '{"query":"query { loanDetails(loanId: \"LOAN-123\") { amount status customer { isBlocked } } }"}'
```
