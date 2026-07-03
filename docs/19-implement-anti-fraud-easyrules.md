# 19. Triển khai Thực tế: Tầng Anti-Fraud bằng In-Memory Rules Engine (EasyRules)

Tài liệu này lưu trữ bằng chứng triển khai **Bước số 0 (Step 0)** của hệ thống trước khi Saga tiến hành. Nằm hoàn toàn trên RAM (In-Memory), hệ thống Anti-Fraud chặn đứng các hồ sơ lừa đảo mà không làm tốn đi 1 đồng phí API hay phình to Database.

---

## 1. Thiết kế Kiến trúc: Tại sao dùng EasyRules thay vì Drools?
Khi Phỏng vấn System Design ở Finance Domain, hãy tự tin bảo vệ Kiến trúc "In-Memory Java Engine" của bạn:
*   **Điểm yếu chết người của Drools (KIE Server):** Yêu cầu dựng Container `Kogito/Wildfly` khổng lồ. Luật lệ phải viết bằng file text `.drl` khó Debug. Muốn đẩy luật lên server thì phải Compile thành file nén `KJAR` rồi Publish lên kho chung `Nexus Maven Repo`.
*   **Lợi điểm của EasyRules:** Kế thừa mượt mà Pattern *Chain of Responsibility* thuần Túy Hướng Đối Tượng (OOP) của Java. Không cần dựng Database, Không cần dựng Docker Container. Luật được quản lý như 1 Java Bean, dễ dàng viết Unit-Test với độ trễ (Latency) gần như bằng `0 mili-giây`.

## 2. Các Tệp Mã Nguồn Đã Viết (Code Snippets)

**A. Kéo thư viện (`loan-service/build.gradle`):**
```gradle
implementation 'org.jeasy:easy-rules-core:4.1.0'
```

**B. Định nghĩa "Bộ Luật" Chống Lừa Đảo:**
*Luật lệ không viết gộp bằng chục cái `if-else` lồng nhau. Mỗi luật là 1 Class biệt lập (Single Responsibility).*

1. `SuspiciousAmountRule.java` (Chặn vay quá trán)
```java
@Rule(name = "Lượng tiền bất thường", description = "Vay quá 100M -> Phạt 60 điểm")
public class SuspiciousAmountRule {
    @Condition
    public boolean when(@Fact("request") CreateLoanRequest request) {
        return request.amount().compareTo(new BigDecimal("100000000")) > 0;
    }

    @Action
    public void then(@Fact("fraudScore") FraudScore fraudScore) {
        fraudScore.addPoints(60);
    }
}
```

2. `SuspiciousCustomerRule.java` (Chặn Blacklist)
```java
@Rule(name = "Mạo danh danh tính", description = "CMND thuộc dạng Blacklist -> Phạt 50 điểm")
public class SuspiciousCustomerRule {
    @Condition
    public boolean when(@Fact("request") CreateLoanRequest request) {
        return request.customerId().startsWith("FRAUD");
    }

    @Action
    public void then(@Fact("fraudScore") FraudScore fraudScore) {
        fraudScore.addPoints(50);
    }
}
```

**C. Động cơ Xét Xử (`AntiFraudService.java`):**
*Nơi nạp tất cả các bộ luật vào 1 phiên Tòa ảo và đánh giá (Fire).*
```java
@Service
public class AntiFraudService {
    public void evaluate(CreateLoanRequest request) {
        RulesEngine rulesEngine = new DefaultRulesEngine(); // Chạy In-Memory cực nhanh

        Rules rules = new Rules();
        rules.register(new SuspiciousAmountRule());
        rules.register(new SuspiciousCustomerRule());

        Facts facts = new Facts();
        facts.put("request", request);
        FraudScore score = new FraudScore();
        facts.put("fraudScore", score);

        // Phát hỏa toàn bộ Rules
        rulesEngine.fire(rules, facts);

        // Chốt sổ mức độ Lừa đảo
        if (score.points >= 100) {
            throw new RuntimeException("GIAO DỊCH BỊ HỦY: KHÁCH HÀNG THUỘC TẬP LỪA ĐẢO TRỌNG ĐIỂM!");
        }
    }
}
```

**D. Đường dây Cắt Ngang (Bộ phận lính gác Controller):**
*Ở `LoanController.java`, hàm `evaluate()` được gọi ngay từ giây phút đầu tiên chạm vào API. Kẻ thù bị chặn đứng tại Cửa Khẩu.*
```java
    @PostMapping
    @RateLimiter(...)
    public CreateLoanResponse create(@Valid @RequestBody CreateLoanRequest request) {
        // [CẮT NGANG TẠI ĐÂY] Anti-Fraud Check chớp nhoáng (0 mili-giây)
        antiFraudService.evaluate(request);

        // Vượt qua Tòa án thì mới cho đi vào Database và Saga Orchestrator!
        String loanId = UUID.randomUUID().toString();
        ...
        return service.create(loanId, request);
    }
```
*Lưu ý: Thực tế Doanh nghiệp lớn sẽ dùng Drools khi phía Ngân Hàng có đội ngũ "Nhân viên Phân tích Nghiệp vụ" (BA) không biết viết Code nhưng muốn đăng nhập Giao diện Web (Business Central) kéo thả tạo ra Luật mới. Còn với kĩ sư Phần mềm, In-Memory Class như vầy là siêu việt.*
