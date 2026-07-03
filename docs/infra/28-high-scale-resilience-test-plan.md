# 28. High-Scale Proof: Load Test & Chaos Engineering

Hệ thống được gọi là "Sẵn sàng Go-Live" khi và chỉ khi nó vượt qua các bài Test cực hạn sau đây. Mình đã chuẩn bị sẵn bộ công cụ (Tooling) để bạn thực thi.

## 1. Load Test (Bắn hạ API 10k TPS)
Sử dụng **K6** (Go-based Load Engine) để thử thách khả năng chịu tải của Kong Gateway và Loan Service.

*   **Script:** [k6-load-test.js](file:///Users/dcpm/Documents/GFT-Projects/private/lending-saga-java-gradle-demo-cdc/scripts/load-test/k6-load-test.js)
*   **Mục tiêu:** 2000 Virtual Users đồng thời, kỳ vọng vượt ngưỡng 10k TPS.
*   **Cách chạy:** 
    ```bash
    k6 run scripts/load-test/k6-load-test.js
    ```
*   **Theo dõi:** Mở Grafana Dashboard (mình đã cấu hình Micrometer) để xem CPU/RAM và các chỉ số `loans_disbursed_total_count`.

## 2. Chaos Engineering (Hủy diệt & Tự phục hồi)
Dùng script `chaos-monkey.sh` để mô phỏng "Thảm họa hạ tầng" (Infrastructure Disaster).

*   **Script:** [chaos-monkey.sh](file:///Users/dcpm/Documents/GFT-Projects/private/lending-saga-java-gradle-demo-cdc/scripts/chaos/chaos-monkey.sh)
    
### Scenario A: Chết Kafka Broker
```bash
./scripts/chaos/chaos-monkey.sh kafka
```
*   **Kỳ vọng:** Khi Broker 1 chết, Kafka Cluster vẫn còn Broker 2, 3 (ISR). Producer (Debezium) và Consumer (Java App) phải tự chuyển Failover mà không làm mất bất kỳ Event nào.

### Scenario B: Nghẽn CDC (CDC Backlog Replay)
```bash
./scripts/chaos/chaos-monkey.sh cdc
```
*   **Kỳ vọng:** Debezium bị dừng 2 phút -> Backlog trong Outbox Table dồn lại. Khi bật lại, Debezium phải quét lại LSN và "ném" dồn dập hàng nghìn Event vào Kafka. Các App Consumer phải xử lý được "Cơn lũ dữ liệu" này (HPA trong K8s sẽ tự động tăng số Pod nếu Lag quá cao).

### Scenario C: Sập Orchestrator (State Resurrection)
```bash
./scripts/chaos/chaos-monkey.sh orchestrator
```
*   **Kỳ vọng:** Một Saga đang chạy dở bị sập RAM. Khi khởi động lại, Orchestrator phải lôi State từ Postgres/Redis lên và tiếp tục bước còn dang dở (nhờ cơ chế Idempotency và Timeout Retry).

## 3. Hệ thống tự phòng vệ (Auto-Scaling)
Mình đã cấu hình **HPA (Horizontal Pod Autoscaler)** dựa trên **Custom Metrics (Kafka Consumer Lag)**.
*   **File:** [orchestrator-hpa.yaml](file:///Users/dcpm/Documents/GFT-Projects/private/lending-saga-java-gradle-demo-cdc/k8s/orchestrator-hpa.yaml)
*   **Cơ chế:** Nếu số lượng Event chưa kịp xử lý (Lag) vượt quá 5000, K8s sẽ tự động nhân bản Orchestrator từ 2 Pod lên 50 Pod để "dọn rác" cấp tốc.
