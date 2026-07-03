# 22. Triển khai Thực tế: Tự động Mở Rộng K8s (HPA) & Hệ thống Quan sát (Observability)

Phần cuối cùng của Hành trình tiến lên **Production-Ready** là các Tác động vào Tầng Hạ Tầng (Infrastructure) thay vì Tầng Business Logic. Không một ai dám ném một ứng dụng đen ngòm (Black box) lên các cụm Kubernetes Cloud chạy 100k TPS mà không cấy Trạm giám sát Mắt thần.

Dưới đây là tài liệu vạch ra những Vũ khí quan trọng nhất đã được lập trình sẵn.

---

## 1. Hệ thống Quan sát Chuyên Sâu (Custom Metrics)

Actuator mặc định của Spring Boot chỉ đếm CPU, RAM (Số liệu quá chung chung). Hệ thống Tài chính đòi hỏi "Số Liệu Kinh Doanh Thời Gian Thực" (Real-time Business Metrics).

### A. Kéo Micrometer
```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```
*(Đã mở khóa endpoint `/actuator/prometheus` cho máy quét Grafana).*

### B. Bơm "Máy dò mìn" vào Điểm nóng Code (Hotspots)

**1. Máy đếm "Tần suất Lừa Đảo (Fraud)":**
Nằm chốt chặn ở Đầu vào của Đơn Vay (Saga Step 0).
```java
// Trong AntiFraudService.java
import io.micrometer.core.instrument.MeterRegistry;

@Service
@RequiredArgsConstructor
public class AntiFraudService {
    private final MeterRegistry meterRegistry;

    public void evaluate(CreateLoanRequest request) {
        ...
        if (score.points >= 100) {
            // Cài bọ theo dõi: Mỗi lần bắt gặp Lừa đảo, Grafana sẽ nhảy số 1 lần
            meterRegistry.counter("loans_fraud_blocked_total").increment();
            throw new RuntimeException("GIAO DỊCH BỊ CẤM!");
        }
    }
}
```

**2. Máy đếm "Dòng Tiền Giải Ngân":**
Nằm ở Hồi kết của Saga (Nơi giao dịch chốt hạ).
```java
// Trong LoanSagaEventListener.java
    private void onDisbursementSucceeded(LoanEvent event) {
        ...
        sagaCacheService.flushToDb(saga); // Chốt sổ

        // Báo cáo thành tích nổ Đơn lên Bảng điều khiển Giám đốc
        meterRegistry.counter("loans_disbursed_total_count").increment();
        if (event.amount() != null) {
            meterRegistry.summary("loans_disbursed_amount_total").record(event.amount().doubleValue());
        }
    }
```

---

## 2. Rào chắn Kong API Gateway (Tập trung Hóa Hướng Gọi)

Loại bỏ việc Client ném Request trực tiếp vào trần trụi các Service BE (gây rủi ro DDos nội mạng và lộ IP).

* Dựng Kong theo mô hình **DB-Less** (Đọc file cấu hình ngầm tĩnh `kong/kong.yml`).
* Điều phối vạn cổng về đúng Cổng số 8000 của Hệ Sinh Thái.

```yaml
# docker-compose.yml
  kong:
    image: kong:3.6.0-ubuntu
    environment:
      - KONG_DATABASE=off
      - KONG_DECLARATIVE_CONFIG=/usr/local/kong/declarative/kong.yml
    ports:
      - "8000:8000"
    volumes:
      - ./kong/kong.yml:/usr/local/kong/declarative/kong.yml
    depends_on:
      - loan-service
```

---

## 3. Kiến trúc Đỉnh Cao: Auto-Scaling K8s (Thu phóng quy mô)

Docker-compose bị gạt sang 1 bên. Ứng dụng High Scale cần được vứt lên K8s thông qua bộ Hồ sơ Deployment đã được thêm vào thư mục `/k8s`.

### A. Không bao giờ tin tưởng Máy Ảo (Deployment Probes)
Tệp `k8s/loan-service-deployment.yaml` ra đời để:
1. Giam lỏng CPU/RAM bị ăn vã (Resource Limits/Requests).
2. Quy định `livenessProbe` để đâm kim tiêm (Ping Healthcheck) vào tim mạch Actuator. Chết thì tự dập Service đổi mâm mới.
3. Chặn không cho K8s điều phối LoadBalancer vào nếu App chưa tải xong DB (Qua `readinessProbe`).

### B. Giật nảy nở Đội quân nhân bản (HPA)
Tệp `k8s/loan-service-hpa.yaml` ra đời với luật lệ thép:

```yaml
  minReplicas: 3      # Cắm sào tối thiểu 3 chú lính
  maxReplicas: 50     # Quét được Siêu Sale (Tải cao)? Tự đẻ ngay tắp lự tối đa lên 50 lính
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70 # Ngưỡng giật mình: CPU > 70% thì Bấm đẻ
```

### C. Móc nối Mắt thần vào Trạm Kiểm Soát Dải Ngân Hà
Tệp `k8s/prometheus-servicemonitor.yaml` cấu hình cho K8s Prometheus Operator tự động biết chòi nào sinh ra (`loan-service`) để rà quét gom rác Metric đếm được (Fraud/Money) với tần suất `10 giây / lần`. Thằng nào nghẽn là nó hú còi!
