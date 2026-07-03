# 17. Rate Limiting & Anti-Fraud (Phát hiện gian lận tài chính)

Ngăn chặn Hacker (DDoS) và Ngăn chặn Lừa đảo (Fraud) là 2 tầng khiên độc lập nhau nhưng thường bị phỏng vấn chung để đánh giá tầm nhìn của Architect.

---

## 1. Rate Limiting (Chống DDoS & Spam API)
Mục tiêu là không cho một User (hoặc Bot) gọi hàng ngàn Request vay tiền 1 giây làm sập Database. Điểm chặn lý tưởng nhất là **Tầng rìa ngoài (Edge Layer)**.

*   **Vị trí Tốt nhất:** API Gateway (Kong, Nginx, Spring Cloud Gateway).
*   **Thuật toán kinh điển (Interview Keyword):** `Token Bucket` hoặc `Leaky Bucket`.
*   **Triển khai cục bộ (Local Fallback):** Nếu áp dụng thẳng trên code Java `loan-service`, bạn nên dùng cấu hình `Resilience4j RateLimiter` bọc bên ngoài `LoanController` (Ví dụ cấp 3 Tokens/phút. Gọi cái thứ 4 lập tức văng `HTTP 429 Too Many Requests`). Không cho Request lọt được xuống tầng Database hay Kafka.

---

## 2. Anti-fraud Layer (Lớp chống gian lận)
Lừa đảo không gọi nhanh (Không bị Rate Limit bắt), nhưng hành vi của chúng bất thường (VD: Dùng 1 CMND vay ở 10 công ty tài chính khác nhau trong cùng 1 ngày - Velocity Attack).

*   **Vị trí trong Saga:** Nó phải là **First Step** (Bước số 0) trước cả KYC. Nếu rớt Fraud Check, từ chối Đơn vay ngay và luôn để đỡ tốn chi phí gọi API 3rd-party (gọi CIC/KYC rất tốn tiền mỗi block).
*   **Kiến trúc Hệ thống (Rules Engine):** Hệ thống không dùng IF-ELSE cứng. Tín dụng dùng một **Rules Engine** chuyên biệt (như *Drools* hoặc *EasyRules*).
    *   *Rule 1:* Tuổi quá bé/quá lớn -> Đẩy điểm rủi ro.
    *   *Rule 2:* IP Khách hàng không khớp với Quốc gia của Số điện thoại -> Cộng 50 điểm Fraud.
    *   *Rule tổng:* Vượt 100 điểm -> `FRAUD_DETECTED`.

---

## 3. Anomaly Detection (Phát hiện dị thường bằng AI/Real-time)
Đây là Cảnh giới tối cao (Dùng cho System Mức tỷ đô). Anti-fraud dính liền với Code Logic (Synchronous), còn Anomaly Detection rà quét ngầm theo luồng (Asynchronous).

*   **Công nghệ cốt lõi:** `Apache Flink`, `Spark Streaming` kết hợp với `Kafka Streams`.
*   **Cách hoạt động:** Bạn viết 1 Job Flink lắng nghe cái ống nước `saga-events` khổng lồ kia.
*   **Thuật toán dị thường:** Flink soi theo Cửa sổ thời gian (Sliding Window). Nếu nó thấy: Tự nhiên trong 5 phút có 100 ông User trỏ cùng về chung 1 Tọa độ GPS tọa độ nhà trọ rách nát để đòi rút 10 Tỷ... Trí tuệ nhân tạo (AI/ML Models) bên trong Flink sẽ phát giác tín hiệu bất thường này (Anomaly).
*   **Hành động tức thời:** Flink sẽ bắn ngược 1 lệnh `BlockAccountCommand` lên Kafka. Các Service đọc được lập tức Cấm băng (Freeze) mọi hành động giải ngân tiếp theo của bầy đàn mạo danh kia.
