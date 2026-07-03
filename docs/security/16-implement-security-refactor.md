# 16. Triển khai Thực tế: Tiêu chuẩn Security cho Finance Domain

Tài liệu này tổng hợp nguyên lý và mã nguồn (Code Snippets) của 3 hạng mục Security cực kỳ quan trọng đã được implement thực tế vào `loan-service`. Đây là "vũ khí hiển hiện" để bạn chứng minh năng lực lập trình bảo mật.

---

## 1. Field-Level Encryption (Mã hóa PII tại ổ cứng)

### Vấn đề:
Lưu trực tiếp Số CMND/Số Tài Khoản (`customerId`) thành chữ nổi (plaintext) trong Database. Nếu Database Admin (DBA) truy cập hoặc Hacker đánh cắp file Backup, toàn bộ dữ liệu nhạy cảm sẽ bị lộ.

### Giải pháp (JPA AttributeConverter):
Sử dụng AES-256 mã hóa Data ngay trong RAM của Java trước khi đẩy câu lệnh `INSERT`/`UPDATE` xuống CSDL. Đọc lên thì tự giải mã.
**A. Tạo Converter: `CryptoConverter.java`**
```java
@Converter
public class CryptoConverter implements AttributeConverter<String, String> {
    private static final String ALGORITHM = "AES";
    private static final byte[] KEY = "GFTSecretKey2026".getBytes(); 

    @Override
    public String convertToDatabaseColumn(String raw) {
        // Mã hóa bằng Cipher.ENCRYPT_MODE -> Trả về Base64 String
    }

    @Override
    public String convertToEntityAttribute(String encryptedDbData) {
        // Giải mã bằng Cipher.DECRYPT_MODE -> Trả về Raw String
    }
}
```
**B. Cắm vào Entity: `LoanApplicationEntity.java`**
Chỉ cần thêm duy nhất 1 dòng Annotation, không cần sửa Logic Business ở Service!
```java
    @Convert(converter = com.example.lending.loan.security.CryptoConverter.class)
    private String customerId;
```

---

## 2. Global Audit Log (Nhật ký Kiểm toán tự động)

### Vấn đề:
Yêu cầu lưu vết Hệ thống (Ai đang làm gì, gọi API nào, tốn bao nhiêu Mili-giây) nhưng không muốn Lập trình viên phải copy-paste lệnh `log.info(...)` thủ công ở hàng trăm hàm.

### Giải pháp (Spring AOP - Aspect Oriented Programming):
Sử dụng thẻ `@Aspect` và `@Around` để tạo một bức tường vô hình bọc bên ngoài tầng Controller.

**Cài đặt: `AuditLogAspect.java`**
```java
@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    // Chặn trước mồm tất cả các function trong thư mục API
    @Around("within(com.example.lending.loan.api..*)")
    public Object logAudit(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        String method = joinPoint.getSignature().toShortString();
        
        log.info("[AUDIT_START] Đang gọi API: {}", method);
        
        try {
            Object result = joinPoint.proceed(); // Nham thạch API đi qua
            log.info("[AUDIT_SUCCESS] API '{}' hoàn tất sau {} ms.", method, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("[AUDIT_FAILED] API '{}' xịt do lỗi: {}", method, e.getMessage());
            throw e;
        }
    }
}
```

---

## 3. Log Masking (Bộ lọc Regex chống lộ lọt chuẩn PCI-DSS)

### Vấn đề:
Lỡ Dev in bừa `log.info("Request: " + req.toString())` chứa nguyên cụm Căn cước. Log này đẩy lên màn hình console và văng ra ElasticSearch (ELK), toàn bộ công ty đọc được -> Vi phạm nghiêm trọng chuẩn thẻ tín dụng (PCI-DSS).

### Giải pháp (Logback Regex Replace):
Viết đè file `logback-spring.xml`. Áp dụng thẻ `%replace` để Filter (Đeo mặt nạ) tất cả các chữ chạy ra Console.

**Cấu hình: `logback-spring.xml`**
```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <!-- 
                1. Mọi chữ chuẩn bị in ra màn hình đi qua đây.
                2. Nếu nhận dạng thấy chuỗi 'customerId=' tiếp theo sau là các chữ số/ký tự.
                3. Đập nát nhóm số 2 thành chuỗi rác [MASKED_BY_PCI]
            -->
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %replace(%msg){'(customerId=)([^,\)]+)', '$1***[MASKED_BY_PCI]***'} %n</pattern>
        </encoder>
    </appender>

    <root level="info">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```
*Việc này biến `customerId=VCL-1234` thành `customerId=***[MASKED_BY_PCI]***` NGAY TẠI TẦNG NỀN TẢNG (Framework), Hacker nội bộ vô phương truy cập!*
