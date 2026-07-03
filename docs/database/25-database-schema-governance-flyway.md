# 25. Database Schema Governance: Chuyển đổi sang Flyway

Để đạt chuẩn **Production-Ready**, hệ thống đã loại bỏ hoàn toàn cơ chế `ddl-auto: update` (vốn cực kỳ nguy hiểm vì có khả năng khóa bảng hoặc gây sai lệch schema ngầm định). Thay vào đó, toàn bộ 5 microservices đã được trang bị **Flyway** để quản lý phiên bản Database.

## 1. Cấu hình Toàn diện
Toàn bộ `application.yml` của các service đã được cập nhật:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none # Cấm tuyệt đối Hibernate tự ý sửa bảng
  flyway:
    enabled: true
    baseline-on-migrate: true # Tự động tạo mốc baseline nếu DB đã có dữ liệu
```

## 2. Thách thức: Sharding Migration (Loan Service)
Với `loan-service`, do sử dụng mô hình đa Shard (2 Postgres độc lập), chúng ta không thể dùng cấu hình mặc định của Spring Boot (vốn chỉ trỏ vào 1 DataSource).

**Giải pháp:** Đã triển khai `FlywayConfig.java` để tóm lấy từng Shard DataSource và ép Flyway chạy quét qua cả 2:

```java
@Bean(initMethod = "migrate")
public Flyway flywayShard1(@Qualifier("shard1DataSource") DataSource dataSource) {
    return Flyway.configure().dataSource(dataSource)...load();
}

@Bean(initMethod = "migrate")
public Flyway flywayShard2(@Qualifier("shard2DataSource") DataSource dataSource) {
    return Flyway.configure().dataSource(dataSource)...load();
}
```

## 3. Quản lý kịch bản SQL
Toàn bộ cấu trúc bảng ban đầu đã được kết xuất (Export) vào tệp:
`src/main/resources/db/migration/V1__initial_schema.sql`

Điều này đảm bảo:
*   **Tính nhất quán:** Mọi môi trường (Dev, Staging, Prod) đều có cấu trúc bảng giống hệt nhau.
*   **Khả năng Rollback:** Dễ dàng kiểm soát các thay đổi schema trong tương lai thông qua các tệp `V2`, `V3`...
*   **Audit:** Biết chính xác ai đã thay đổi cột nào vào thời điểm nào qua bảng `flyway_schema_history`.
