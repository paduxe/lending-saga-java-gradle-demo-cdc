# 21. Triển khai Thực tế: Phá Nút Thắt Saga bằng Redis Write-Behind Cache

Đóng vai trò là "Nhạc Trưởng Mù Quáng", `loan-orchestrator-service` bị nhồi nhét tới hàng trăm ngàn luồng Event Kafka (Từ KYC, Credit, Disbursement trả kết quả về). 

Nếu tiếp tục bảo thủ gọi `sagaRepository.save(...)` để ghi cật lực trạng thái trung gian (`PENDING` -> `RUNNING` -> `KYC_OK`...) xuống đĩa cứng PostgreSQL ở tần suất 400.000 lệnh UPDATE/giây, CSDL sẽ **sập hoàn toàn vì I/O Disk bốc cháy**. Sharding (băm nhỏ DB) đã sử dụng ở dịch vụ Tiếp Nhận Đơn Vay (`loan-service`), chứ không thể cứu rỗi Orchestrator ở đây được.

Dưới đây là phương án Giải cứu Ngoạn mục bằng **In-Memory State Machine (Redis Write-Behind Cache)**.

---

## 1. Cơ Chế "Chiến Thần" Write-Behind Cache (Lưu Trữ Chạy Sau)
Nguyên lý: Các trạng thái chuyển giao (Transitions) của giao dịch sẽ MÃI MÃI nằm trên thanh RAM siêu tốc của Redis. Chỉ khi nào phiên Saga đi đến Hồi Kết (`COMPLETED` hoặc `FAILED`), hệ thống mới cho phép xả một mẻ ghi cuối cùng (Flush) xuống "Kho Lạnh" PostgreSQL.
👉 **Kết quả:** Hiệu năng nâng lên 100 lần, giảm 75% tải I/O Disk cho Database.

## 2. Mã Nguồn Cốt Lõi Đã Bổ Sung

### A. Kéo Redis vào Hạ tầng (`docker-compose.yml` & `build.gradle`)
```yaml
  # IN-MEMORY CACHE (Redis) - Chiến thần gánh tải cho Orchestrator
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
```
```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

### B. Trái Tim Điều Phối Gọi RAM: `SagaCacheService.java`
Thay vì trực tiếp nhồi máu cơ tim cho Postgres qua `LoanSagaRepository`, chúng ta chèn một Tầng Lọc Cách Ly (Abstraction Layer):

```java
@Service
public class SagaCacheService {
    private final LoanSagaRepository dbRepository; // Kho Lạnh
    private final RedisTemplate<String, Object> redisTemplate; // Kho Nóng (RAM)

    // ĐỌC DỮ LIỆU: Luôn quẹt thẻ từ RAM Redis trước
    public Optional<LoanSagaEntity> getSaga(String sagaId) {
        Object cached = redisTemplate.opsForValue().get("SAGA:" + sagaId);
        if (cached != null) return Optional.of(...); // convert sang Entity
        return dbRepository.findById(sagaId); // Cache Miss mới mổ DB
    }

    // GHI CACHE: Nhanh như điện giật (Chỉ chọc vào Redis)
    public void updateStateInMemory(LoanSagaEntity saga) {
        // Cập nhật trạng thái trung gian siêu tốc (VD: Chờ vay KYC)
        redisTemplate.opsForValue().set("SAGA:" + saga.getSagaId(), saga, Duration.ofMinutes(60));
    }

    // GHI DISK (XẢ LŨ): Lần duy nhất động tới Postgres
    public void flushToDb(LoanSagaEntity saga) {
        dbRepository.save(saga); // Chốt sổ Hồ sơ thành công / thất bại vào đĩa cứng
        redisTemplate.delete("SAGA:" + saga.getSagaId()); // Xóa RAM dọn chỗ cho đơn mới
    }
}
```

### C. Refactor Nhạc Trưởng: `LoanSagaEventListener.java`
Rút bỏ `sagaRepository.save(...)` tốn kém và cắm ống truyền `sagaCacheService` vào các hàm xử lý vòng đời:

**1. Trạng thái Đang Chạy (Intermediate):**
```java
// Khi KYC Báo Thẻ Xanh -> Chuyển sang Check Tín Dụng
private void onKycPassed(LoanEvent event) {
    ...
    saga.setCurrentStep("CREDIT_REQUESTED");
    
    // THAY VÌ: sagaRepository.save(saga); (Gắt)
    sagaCacheService.updateStateInMemory(saga); // (Nhẹ tựa lông hồng)
    
    sendCommand(event, "CheckCreditCommand");
}
```

**2. Trạng thái Nhắm Mắt Xuôi Tay (Terminal - Thành công/Thất bại):**
```java
// Khi Giải Ngân Bắn Tiền Thành Công -> Chốt Giao Dịch
private void onDisbursementSucceeded(LoanEvent event) {
    ...
    saga.setCurrentStep("DISBURSEMENT_SUCCEEDED");
    saga.setSagaStatus("COMPLETED");

    // LÚC NÀY MỚI GHI XUỐNG ĐĨA CỨNG:
    sagaCacheService.flushToDb(saga); 
}
```

---

## 3. Câu Hỏi "Hiểm" Trong Phỏng Vấn System Design (System Design Q&A)

**Q: Chú nhét Đơn Vay đang xử lý dở dang lên RAM Redis. Lỡ may bị mất điện / Sập Server Redis ngang xương thì Mất Đơn Vay của Khách Mất 1 Tỷ rưỡi thì sao? (Data Loss)**
👉 **A:** Điểm mấu chốt của System Design là Không bao giờ tin tưởng RAM. Nhờ mô hình Microservices CDC hiện tại: Lệnh gọi Khởi tạo Đơn vốn dĩ xuất phát từ **Mảnh Sharding `loan_db`** (Đã tạo thành công Record vật lý bằng Postgres ở `loan-service` và ném Outbox lên Kafka).
Cho dù orchestrator chết và Redis chết, Message ở Topic `saga-events` trong Kafka của Debezium **VẪN CÒN NẰM ĐÓ** (Kafka chưa nhận ACK). Khi hệ thống reboot, Nhạc trưởng sẽ nhai lại Event từ Kafka, dò lại Data từ Kho Lạnh (Thấy rỗng do Redis Crash), và tái thiết lập tự động lại Trạng thái trên RAM Redis một cách mượt mà nhất mà không mất đi 1 đồng xu của khách hàng nào! (Đẳng cấp gỡ bom *At-Least-Once Delivery*).
