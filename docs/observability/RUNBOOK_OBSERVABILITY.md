# Lending System Runbook: Observability & Alerting

Hệ thống cho vay phân tán (Distributed Lending System) yêu cầu quy trình xử lý sự cố nghiêm ngặt để đảm bảo dòng tiền (Cashflow) không bị tắc nghẽn.

## 1. Hệ thống giám sát (Monitoring Stack)
- **Prometheus**: [http://localhost:9090](http://localhost:9090) (Giao diện truy vấn Metric và Rule)
- **AlertManager**: [http://localhost:9093](http://localhost:9093) (Giao diện quản lý Cảnh báo)
- **Grafana**: [http://localhost:3000](http://localhost:3000) (Dashboard Business Metrics)
  - *User/Pass mặc định*: `admin` / `admin`

---

## 2. Các kịch bản cảnh báo (Alerting Scenarios)

### 🚨 Cảnh báo 1: `ServiceDown` (Phát hiện Service chết)
- **Dấu hiệu**: Service biến mất khỏi Prometheus (Up metric = 0).
- **Nguyên nhân**: Pod bị CrashLoopBackOff, lỗi OOM (Out Of Memory), hoặc Network partition.
- **Cách xử lý**:
  1. `docker ps` hoặc `kubectl get pods` để kiểm tra trạng thái Container.
  2. Kiểm tra log bằng `docker logs` hoặc `kubectl logs`.
  3. Khởi động lại service nếu cần.

### 🚨 Cảnh báo 2: `HighFraudRate` (Tấn công lừa đảo/Bot)
- **Dấu hiệu**: Counter `loans_fraud_blocked_total` tăng đột biến.
- **Nguyên nhân**: Hệ thống đang bị tấn công bởi Bot hoặc một đợt Spam vay vốn hàng loạt.
- **Cách xử lý**:
  1. Kiểm tra Dashboard Grafana để xem ID khách hàng hoặc IP nào đang bị chặn nhiều nhất.
  2. Cập nhật rule trong `AntiFraudService` nếu là Fraud thật.
  3. Bật Rate Limiting gắt hơn nếu cần thiết qua Kong Gateway.

### 🚨 Cảnh báo 3: `SagaFailureSpike` (Lỗi Logic/Giao dịch nghẽn)
- **Dấu hiệu**: `saga_failed_total` tăng cao.
- **Nguyên nhân**: Thường do Service KYC hoặc Credit bị lỗi logic, hoặc Provider bên thứ 3 (Ví dụ: Disbursement API) đang sập.
- **Cách xử lý**:
  1. Truy tìm Trace ID trong Logs (Elasticsearch/Kibana) để xem Step nào bị FAIL.
  2. Kiểm tra Kafka Lag để xem tin nhắn có bị dồn ứ không.
  3. Nếu do lỗi mạng, Saga Compensation sẽ tự chạy. Nếu do lỗi Code, cần Hotfix ngay.

---

## 3. Quy trình "Réo điện thoại Dev lúc 2h sáng"
Khi một cảnh báo mức `critical` xuất hiện:
1. **Firewall/Network check**: Đảm bảo Kafka và Postgres vẫn sống.
2. **Resource check**: Kiểm tra xem CPU/RAM có bị Full không (Sử dụng Grafana).
3. **Dead Letter Queue (DLQ)**: Nếu có tin nhắn vào DLQ, tuyệt đối không delete. Phải analyze payload và thực hiện Replay sau khi fix lỗi.
