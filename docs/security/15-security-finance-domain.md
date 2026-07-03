# 15. Security trong Finance Domain (Bảo mật Hệ thống Tài chính)

Ngành tài chính (Ngân hàng, Lending, Thanh toán) yêu cầu bảo mật ở cấp độ cực hình (Paranoia level). Dưới đây là cách ánh xạ các yêu cầu bảo mật vào mô hình Microservices hiện tại.

---

## 1. Encryption (Mã hóa)
### A. In Transit (Trên đường truyền mạng)
*   **REST API:** Buộc phải cấu hình SSL/TLS (HTTPS) chặn mọi traffic HTTP thuần.
*   **Internal JDBC (Database):** Java kết nối Postgres phải chèn thêm `?sslmode=require` trên connection string.
*   **Kafka Cluster:** Cấu hình Truststore và Keystore để kích hoạt **SSL Listener** thay cho `PLAINTEXT://`. Debezium và Java Consumer đều bắt buộc gắn chứng chỉ số mới được gửi/nhận message.

### B. At Rest (Khi nằm yên trên Ổ cứng)
*   **TDE (Transparent Data Encryption):** Mã hóa toàn bộ phân vùng vật lý của Server chạy Postgres. (Thường do phía Cloud Provider AWS KMS / K8s lo).
*   **Field-Level Encryption (Mã hóa cấp độ trường):** Đối với các dữ liệu PII (Thông tin cá nhân: CMND, Số tài khoản), ta dùng `AttributeConverter` của Spring Data JPA mã hóa AES-256 trước khi lưu xuống RAM/Disk. Ai hack được file Backup DB cũng chỉ thấy một chuỗi rác `x1a$@8q...`.

## 2. Audit Log (Nhật ký kiểm toán hệ thống)
*   **Yêu cầu:** Bất kỳ thao tác Ghi/Xóa/Sửa nào cũng phải biết rõ: Hệ thống nào (hoặc user ID nào) gọi, gọi qua endpoint nào, và kết quả là gì.
*   **Giải pháp 1 (Spring AOP):** Viết một Global Interceptor `@Aspect` bao trọn tầng Controller. Ghi log đẩy ra `stdout` để công cụ ELK (Elasticsearch-Logstash-Kibana) nuốt và lưu trữ chuẩn WORM (Write-Once-Read-Many).
*   **Giải pháp 2 (CDC Audit):** Tận dụng luôn Debezium. Mọi thay đổi DB (Insert/Update/Delete) đều có ảnh chụp Trước/Sau (Before/After snapshot) và thông tin Timestamp. Ta nắn Kafka Topic này chui vào 1 **Audit Service** chuyên dụng để lưu làm Lịch sử Kiểm toán.

## 3. PCI Compliance (Chuẩn bảo mật thẻ và dữ liệu tài chính)
*   **Không bao giờ in Log nhạy cảm (Log Masking):** Cấm tuyệt đối lỡ tay `log.info("Request: " + request.toString())` mà bên trong có Căn cước công dân. Cần thiết kế bộ lọc Logback Masking (`***1234`).
*   **Tokenization (Mã hóa thay thế):** Khi cần lưu PAN (Số thẻ / Số tài khoản), hệ thống nội bộ chỉ lưu nội dung Tokenizing (`TKN-98328-XYZ`). Khi cần gửi sao kê mới chọc vào một hệ thống Vault chuyên biệt để giải mã.
*   **Authentication & RBAC:** Không một API nội bộ nào `/api/loans` được chọc trực tiếp mà không có **JWT / OAuth2 Token**. Chỉ Service có mang Role hợp lệ (`ROLE_SYSTEM_WRITE`) mới được giao tiếp với nhau (Zero Trust Network).
