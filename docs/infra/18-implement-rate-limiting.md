# 18. Triển khai Thực tế: Rate Limiting (Chống Spam/DDoS API)

Tài liệu này lưu lại mã nguồn để giải quyết tình trạng Hệ thống bị tấn công Spam API cạn kiệt Connection Database - một phương án triển khai "Rate Limiting Cục bộ" (Local Fallback) bằng Resilience4j ngay tại tầng Controller.

---

## 1. Cấu hình Ngưỡng (Thresholds)
Trong `loan-service/src/main/resources/application.yml`, bổ sung bộ giáp RateLimiter:

```yaml
resilience4j:
  ratelimiter:
    instances:
      createLoanApi:
        limitForPeriod: 3 # Chỉ cho phép 3 requests...
        limitRefreshPeriod: 1m # ...Được nạp lại ngầm sau mỗi 1 phút
        timeoutDuration: 0s # Gọi cú thứ 4 sẽ bị block lập tức, không khoan nhượng
```
**Ý nghĩa:** Cấu hình chuẩn cho API Mở thẻ/Vay vốn chặn Spam. Khách hàng thông thường không bao giờ bấm lưu 4 cái form đăng ký khác nhau trong 1 phút.

## 2. Gắn Token Bucket vào Controller
Ở Class `LoanController.java`, ta trang bị còi báo động thông qua thư viện AOP (được `spring-boot-starter-aop` hỗ trợ):

```java
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    // 1. CHIA LUỒNG (ROUTING) KHI BỊ ALERT
    // Gắn chíp báo động. Nếu User ngoan ngoãn (<3 req/phút): Luồng chạy vào hàm create().
    // Nếu User spam mũi số 4: Resilience4j ép luồng rẽ sang hàm rateLimitFallback() bên dưới.
    @PostMapping
    @RateLimiter(name = "createLoanApi", fallbackMethod = "rateLimitFallback")
    public CreateLoanResponse create(@Valid @RequestBody CreateLoanRequest request) {
        ...
        return service.create(loanId, request);
    }

    // 2. MÀNG LỌC FALLBACK (Lá chắn chặn DDoS)
    // Lưu ý: Cấu trúc hàm Fallback PHẢI GIỐNG HỆT hàm chính, chỉ thêm Throwable ở đuôi.
    public CreateLoanResponse rateLimitFallback(CreateLoanRequest request, Throwable t) {
        System.err.println("❌ [RATE_LIMIT] Đã chặn đứng cuộc Tấn công/Spam API từ IP khách hàng!");
        
        // Trả lỗi 429 cực nhanh! 
        // Database Postgres phía sau chưa hề mở Connection, Hibernate không bị gọi dậy!
        return new CreateLoanResponse("BLOCKED", "BLOCKED", "REJECTED_429_TOO_MANY_REQUESTS");
    }
}
```

## 3. Giá trị khi tranh luận Phỏng vấn (Interview Value)
Khi nhà tuyển dụng hỏi: *"Anh cố tình nã 5000 requests/s vào hàm Tạo Đơn Vay của em, Database Postgres của em sập rồi phải không?"*

Bạn hiên ngang đáp lại:
👉 *"Không sập ạ! Kể từ Request thứ 4, thư viện Resilience4j đã tự động ném luồng sang hàm Fallback. 4996 requests còn lại chỉ chạy tính toán trên RAM (CPU) tốn có 1 mili-giây để In Object `REJECTED_429` trả về và ngắt kết nối. Connection Pool ngầm của Postgres chưa hề bị đánh thức, Database của em hoàn toàn không biết gì nên sống khỏe rủ!"*
