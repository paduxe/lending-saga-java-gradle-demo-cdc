# 14. Exactly-Once Semantics (EOS) với Kiến trúc CDC

Đây là "Câu hỏi cạm bẫy" chết người nhất của các hội đồng System Design. Khi triển khai **CDC (Debezium) + Transactional Outbox**, kiến trúc giao dịch của bạn đã bị lật ngược.

---

## 1. Cạm bẫy "Kafka Transactional Producer"
*   **Hoàn cảnh ra đời:** Nếu bạn dùng code Java thuần túy vừa `kafkaTemplate.send()` vừa `repository.save()`, bạn bắt buộc phải cấu hình **Kafka Transactional Producer** để ôm cả 2 hành động đó vào vòng tay "đồng sinh cộng tử".
*   **Sự thật về CDC:** Ở dự án này, Java App **KHÔNG HỀ LÀM PRODUCER**. Ứng dụng chỉ có duy nhất 1 Local Transaction (Lưu Logic Kinh Doanh + Lưu vào Bảng Outbox). Rõ ràng Transactional Producer trong Java trở nên vô tác dụng và **Hệ thống KHÔNG CẦN IMPLEMENT** cấu hình này! Atomicity đã được nhường lại 100% cho Postgres Local Transaction.

## 2. Kafka Idempotent Producer (Cơ chế chống gửi đúp)
*   **Lý thuyết:** Khi Producer (Debezium) đẩy tin vào Kafka, lỡ mạng bị chớp tắt, Debezium không biết Kafka đã nhận được tin hay chưa nên nó Gửi lại (Retry). Sinh ra tin nhắn bị trùng lặp. Cờ `enable.idempotence=true` giúp Kafka tự động lọc (Deduplicate) các gói tin bị Retry dựa trên `PID (Producer ID)` và `Sequence Number`.
*   **Thực tế CDC:** Kể từ Kafka 3.0, tính năng **Idempotent mặc định được BẬT (`true`)**. Do đó, bản thân thằng Debezium Connectors đang chạy đằng sau đã tự động được thừa hưởng lớp áo giáp chống rác này từ thư viện Kafka Client.

## 3. Tổng kết: Exactly-Once trong CDC có đạt được không?
*   Kafka chỉ cung cấp **At-Least-Once (Giao tối thiểu 1 lần)** từ Debezium. Đôi khi Debezium Crash khởi động lại, nó có thể đọc lại lùi file WAL 1 chút và bắn ra trùng lặp event (Duplicate).
*   **Công thức Vàng của Exactly-Once = At-Least-Once (Debezium) + Idempotent Consumer (Java).**
*   Chính vì vậy, trong file `KycCommandListener.java` hay `CreditCommandListener.java`, bạn đã thấy chúng ta thiết kế một tính năng:
    ```java
    // Idempotent Consumer (Nền tảng của Exactly-Once)
    if (processedRepository.existsById(command.commandId())) return;
    ...
    processedRepository.save(...commandId...);
    ```
    Đây là chốt chặn cuối cùng. Bạn nhường việc "Chống trùng lặp" từ người Gửi (Producer) sang người Nhận (Consumer - Database constraint).

👉 **Kết luận cho phỏng vấn:** "Hệ thống CDC Outbox của em không sử dụng Kafka Transactional, bù lại em dùng **Idempotent Consumer Pattern qua file Processed Message** để đạt cảnh giới Exactly-Once System-Wide".
