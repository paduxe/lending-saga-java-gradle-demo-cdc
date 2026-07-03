# Write-Through Cache — Trade-offs & Real-World Use Cases

> **Context:** Phân tích Write-Through Cache pattern đang dùng trong `SagaCacheService.java`,
> trade-off so với các pattern khác, và các use case thực tế trong ngành.

---

## Mục lục

- [1. Các Cache Write Pattern tổng quan](#1-các-cache-write-pattern-tổng-quan)
- [2. Write-Through trong project thực tế](#2-write-through-trong-project-thực-tế)
- [3. Trade-offs của Write-Through](#3-trade-offs-của-write-through)
- [4. So sánh 4 pattern đầy đủ](#4-so-sánh-4-pattern-đầy-đủ)
- [5. Failure Scenarios & cách xử lý](#5-failure-scenarios--cách-xử-lý)
- [6. Real-World Use Cases](#6-real-world-use-cases)
- [7. Anti-patterns cần tránh](#7-anti-patterns-cần-tránh)
- [8. Decision Framework — chọn pattern nào?](#8-decision-framework--chọn-pattern-nào)

---

## 1. Các Cache Write Pattern tổng quan

```
4 CACHE WRITE PATTERNS

┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  WRITE-THROUGH  │  │  WRITE-BEHIND   │  │   WRITE-AROUND  │  │   CACHE-ASIDE   │
│                 │  │  (Write-Back)   │  │                 │  │  (Lazy Loading) │
│  App → DB       │  │  App → Cache   │  │  App → DB only  │  │  App → Cache?   │
│      → Cache    │  │      → DB(async)│  │  Cache: on read │  │  Miss → DB      │
│  (đồng thời)    │  │                 │  │                 │  │  → populate     │
└─────────────────┘  └─────────────────┘  └─────────────────┘  └─────────────────┘
  Consistency HIGH      Throughput HIGH      Cache stays clean    Flexibility HIGH
  Latency: DB+Cache     Latency: Cache only  Write latency: DB    Control: Manual
  Loss risk: ZERO       Loss risk: HIGH      Read latency: HIGH   Stampede risk: YES
```

---

## 2. Write-Through trong project thực tế

### SagaCacheService — Implementation hiện tại

```java
// loan-orchestrator-service/SagaCacheService.java
public void updateStateInMemory(LoanSagaEntity saga) {

    // STEP 1: Ghi DB TRƯỚC — source of truth, không thể thương lượng
    dbRepository.save(saga);

    // STEP 2: Ghi Cache SAU — best-effort, không phải critical path
    try {
        redisTemplate.opsForValue()
            .set("SAGA:" + saga.getSagaId(), saga, Duration.ofMinutes(60));
    } catch (Exception e) {
        // STEP 3: Redis fail → XÓA key cũ để tránh đọc stale data
        // Lần đọc tiếp theo sẽ fallback về DB (luôn có data mới nhất)
        redisTemplate.delete("SAGA:" + saga.getSagaId());
    }
}
```

**Điều làm pattern này đúng với financial system:**

```
WRITE SEQUENCE:
  ┌─────┐  1. save()    ┌────────┐  2. set()   ┌───────┐
  │ App │ ──────────── ►│  DB    │             │ Redis │
  │     │               │(commit)│────────────►│       │
  └─────┘               └────────┘             └───────┘
                              ▲
                              │
                        SOURCE OF TRUTH
                        luôn có data mới nhất
                        kể cả khi Redis down

READ SEQUENCE:
  ┌─────┐  get()   ┌───────┐  HIT  ┌─────┐
  │ App │ ────────►│ Redis │──────►│Data │ (~1ms)
  └─────┘          └───┬───┘       └─────┘
                       │ MISS
                       ▼
                   ┌────────┐
                   │  DB    │──────► Data (~5ms)
                   └────────┘
```

---

## 3. Trade-offs của Write-Through

### ✅ Ưu điểm

#### A. Zero data loss — DB luôn là source of truth

```
Scenario: Redis crash ngay sau khi write

Write-Through:
  T1: dbRepository.save(saga)    → DB có data ✅
  T2: Redis SET                  → Redis crash 💥
  T3: redisTemplate.delete(key)  → xoá stale key ✅
  T4: Read request → Redis miss → fallback DB → vẫn có data ✅

Write-Behind (so sánh):
  T1: Redis SET                  → Redis có data (nhưng chưa flush DB)
  T2: Redis crash 💥
  T3: Data BIẾN MẤT hoàn toàn   → Saga state lost ❌
  → Loan bị stuck, không biết đang ở bước nào → cần manual recovery
```

#### B. Strong consistency — Read luôn thấy write mới nhất

```
Thread A: updateStateInMemory(saga, "KYC_IN_PROGRESS")
  → DB save ✅ → Redis SET ✅

Thread B (ngay sau đó): getSaga(sagaId)
  → Redis GET → hit → trả "KYC_IN_PROGRESS" ✅
  (không phải "PENDING" — stale data)
```

#### C. Đơn giản về mental model

Chỉ cần nhớ 1 rule: **"DB luôn đúng."**
- Redis fail → đọc DB → vẫn đúng
- Redis stale → xoá → đọc DB → vẫn đúng
- Không cần reconciliation job để sync DB ↔ Cache

---

### ❌ Nhược điểm

#### A. Write Latency Tăng (Write Amplification)

```
Write-Through latency = DB write latency + Redis write latency

Ví dụ thực tế:
  DB write (PostgreSQL):  ~5ms
  Redis write:            ~1ms
  Total:                  ~6ms (tăng 20% so với chỉ write DB)

  Với Write-Behind:
  App write:              ~1ms (chỉ write Redis)
  DB flush (async):       tách riêng, không block caller
  Total (caller thấy):    ~1ms

  → Write-Through chậm hơn ~6x cho caller
```

**Khi nào đây là vấn đề nghiêm trọng:**

```
Saga state transition flow:
  PENDING → KYC_IN_PROGRESS    : 1 write (6ms)
  KYC_IN_PROGRESS → CREDIT_CHK : 1 write (6ms)
  CREDIT_CHK → APPROVED        : 1 write (6ms)
  APPROVED → DISBURSED         : 1 write (6ms)

  Total write latency: 24ms chỉ riêng cache operations
  (chưa kể Kafka, external service calls)

  Với Write-Behind: 4ms total cache write latency
  → Write-Through = 6x chậm hơn Write-Behind cho write-heavy saga
```

#### B. Write-Heavy workload — Cache bị "ô nhiễm"

```
Vấn đề: Mỗi lần update saga state → ghi vào Redis
        Nhưng 80% saga states này KHÔNG BAO GIỜ được đọc từ cache
        (vì state transition xảy ra rất nhanh — ms level)

PENDING ──(5ms)──► KYC_IN_PROGRESS ──(200ms)──► CREDIT_CHECKING ──(500ms)──► APPROVED

  State "PENDING" được cache → nhưng 5ms sau đã bị overwrite
  State "KYC_IN_PROGRESS" được cache → nhưng chỉ sống 200ms
  State "CREDIT_CHECKING" được cache → chỉ sống 500ms

  → Ghi 4 state vào Redis, nhưng hầu như không ai đọc intermediate states
  → Redis memory bị waste, write bandwidth lãng phí
  → Cache pollution: Redis đầy với data "vô nghĩa"
```

#### C. Redis Availability Dependency

```
Write-Through với Redis down:

  updateStateInMemory():
    Step 1: dbRepository.save()  → ✅ thành công
    Step 2: redisTemplate.set()  → ❌ timeout (default 2s Redis timeout)
    Step 3: redisTemplate.delete()→ ❌ cũng timeout

  Tổng latency tệ nhất = DB write (5ms) + Redis timeout (2000ms) + Redis delete timeout (2000ms)
                        = ~4s per write operation ← UNACCEPTABLE

  → Cần config Redis timeout hợp lý + circuit breaker cho Redis
```

**Fix trong project (nên thêm):**

```java
// RedisConfig.java — cần config timeout
@Bean
public LettuceConnectionFactory redisConnectionFactory() {
    SocketOptions socketOptions = SocketOptions.builder()
        .connectTimeout(Duration.ofMillis(200))  // Connect timeout
        .build();

    ClientOptions clientOptions = ClientOptions.builder()
        .socketOptions(socketOptions)
        .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500))) // Command timeout
        .build();

    LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
        .clientOptions(clientOptions)
        .commandTimeout(Duration.ofMillis(500))  // ← 500ms max, không phải 2s default
        .build();

    return new LettuceConnectionFactory(
        new RedisStandaloneConfiguration("redis", 6379),
        clientConfig
    );
}
```

#### D. Cache Bypass Problem — Stale Read sau Write fail

```
Race condition tinh vi:

  T1: Thread A: dbRepository.save(saga v2)  → DB = v2 ✅
  T2: Thread A: redisTemplate.set(saga v2)  → Redis = v2 ✅
  T3: Thread B: updateStateInMemory(saga v3) → DB = v3 ✅
  T4: Thread B: redisTemplate.set(saga v3)  → Redis timeout 💥
  T5: Thread B: redisTemplate.delete()      → Redis timeout 💥 (key vẫn là v2!)

  T6: Thread C: getSaga() → Redis HIT → trả v2 ❌ (STALE — DB đã là v3)

Vấn đề: delete() cũng fail → key cũ vẫn tồn tại → stale read
```

**Fix: Dùng GETDEL hoặc version-based invalidation:**

```java
public void updateStateInMemory(LoanSagaEntity saga) {
    dbRepository.save(saga);  // DB luôn mới nhất
    try {
        redisTemplate.opsForValue()
            .set("SAGA:" + saga.getSagaId(), saga, Duration.ofMinutes(60));
    } catch (Exception e) {
        // Dù delete có fail, TTL 60 phút sẽ tự clean up
        // Trade-off: chấp nhận stale read tối đa 60 phút nếu cả set + delete đều fail
        // vs. làm phức tạp thêm với retry
        safeDelete("SAGA:" + saga.getSagaId());
    }
}

private void safeDelete(String key) {
    for (int attempt = 1; attempt <= 3; attempt++) {
        try {
            redisTemplate.delete(key);
            return;  // Thành công
        } catch (Exception e) {
            log.warn("Redis delete attempt {}/3 failed for key {}", attempt, key, e);
            if (attempt < 3) {
                try { Thread.sleep(50L * attempt); }  // Backoff: 50ms, 100ms
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
    log.error("Failed to delete Redis key {} after 3 attempts — stale data risk until TTL expires", key);
}
```

---

## 4. So sánh 4 pattern đầy đủ

```
                WRITE-      WRITE-      WRITE-      CACHE-
                THROUGH     BEHIND      AROUND      ASIDE
                ──────────  ──────────  ──────────  ──────────
Write latency   DB + Cache  Cache only  DB only     N/A (read)
                (~6ms)      (~1ms)      (~5ms)

Read latency    Cache first Cache first DB only     Cache first
                → DB miss   → DB miss   (always)    → DB miss

Data loss       ZERO        HIGH risk   ZERO        ZERO
                            (if cache
                            crash b4
                            flush)

Consistency     Strong ✅   Eventual    Strong ✅   Eventual
                            (async)                 (after miss)

Cache           Warm        Warm        COLD        Warm after
freshness       always      always      always      first read

Write amp.      YES (×2)    NO          NO          NO

Best for        Financial   Analytics   Large obj   General
                State       IoT         rarely read read-heavy
                Session     Gaming      (media)     workload
```

---

## 5. Failure Scenarios & cách xử lý

### Scenario Map đầy đủ

```
┌──────────────────────┬──────────────────────────────────────────────┐
│ Failure               │ Write-Through Behavior & Recovery            │
├──────────────────────┼──────────────────────────────────────────────┤
│ Redis DOWN khi write  │ DB write ✅ → Redis skip → delete (fail ok) │
│                       │ Read: DB fallback → data đúng ✅             │
├──────────────────────┼──────────────────────────────────────────────┤
│ DB DOWN khi write     │ DB fail → exception → Redis KHÔNG được ghi  │
│                       │ Transaction rollback → consistent ✅         │
├──────────────────────┼──────────────────────────────────────────────┤
│ App crash sau DB      │ DB có data ✅, Redis không có               │
│ write, trước Redis    │ Read: cache miss → DB fallback ✅            │
├──────────────────────┼──────────────────────────────────────────────┤
│ Redis stale (set ok   │ TTL 60min tự clean ✅                       │
│ nhưng delete fail)    │ Worst case: 60min stale read cho edge case   │
├──────────────────────┼──────────────────────────────────────────────┤
│ Network partition     │ DB write ok, Redis unreachable               │
│ (DB ok, Redis ko)     │ System hoạt động bình thường với DB only ✅ │
│                       │ Redis tự recover khi network restored        │
├──────────────────────┼──────────────────────────────────────────────┤
│ Redis full (OOM)      │ eviction xảy ra → cache miss → DB fallback ✅│
│                       │ Nên set: maxmemory-policy allkeys-lru        │
└──────────────────────┴──────────────────────────────────────────────┘
```

### Ghi chú quan trọng — comment sai trong project

```java
// loan-orchestrator-service/SagaCacheService.java — dòng 12
// THE WRITE-BEHIND CACHE (Siêu vũ khí...)
//                ↑
//    ❌ COMMENT SAI — đây thực ra là WRITE-THROUGH
//    Write-Behind = ghi cache trước, DB async sau
//    Nhưng code thực tế: dbRepository.save() TRƯỚC → đây là Write-Through ✅
```

---

## 6. Real-World Use Cases

### 🏦 Use Case 1: Banking — Account Session State (Write-Through)

**Bài toán:** Internet banking session cần consistent giữa các requests. User chuyển khoản xong → page reload phải thấy số dư mới ngay.

```java
@Service
public class BankingSessionService {

    public void updateAccountBalance(String accountId, BigDecimal newBalance) {
        // Write-Through: DB trước → Cache sau
        // Lý do: Số dư tài khoản là CRITICAL — không chấp nhận stale read
        accountRepository.updateBalance(accountId, newBalance); // DB: source of truth

        String cacheKey = "balance:" + accountId;
        try {
            redisTemplate.opsForValue().set(cacheKey, newBalance, Duration.ofMinutes(5));
        } catch (Exception e) {
            redisTemplate.delete(cacheKey); // Force next read from DB
        }
    }

    public BigDecimal getBalance(String accountId) {
        BigDecimal cached = (BigDecimal) redisTemplate.opsForValue().get("balance:" + accountId);
        if (cached != null) return cached;
        return accountRepository.findBalance(accountId); // Always consistent
    }
}
```

**Tại sao Write-Through, không phải Write-Behind:**
- User chuyển $1000 → nếu cache ghi trước nhưng DB fail → balance update lost
- Core banking system: mất 1 transaction = compliance violation + potential lawsuit

---

### 🚗 Use Case 2: Ride-Sharing — Driver Location (Write-Behind, KHÔNG dùng Write-Through)

**Bài toán:** Driver gửi GPS location mỗi 2 giây. 100K drivers = 50K writes/giây.

```java
// Write-Behind phù hợp hơn ở đây — Write-Through quá chậm
@Service
public class DriverLocationService {

    // Write-Behind: Cache trước, DB async
    public void updateLocation(String driverId, GeoPoint location) {
        // Chỉ ghi Redis — sub-millisecond
        redisTemplate.opsForGeo().add("driver:locations",
            new RedisGeoCommands.GeoLocation<>(driverId, location));

        // Queue để batch write DB mỗi 30 giây
        locationUpdateQueue.add(new LocationUpdate(driverId, location, Instant.now()));
    }

    // Batch flush mỗi 30s — không cần realtime DB accuracy
    @Scheduled(fixedDelay = 30_000)
    public void flushLocationsToDB() {
        List<LocationUpdate> batch = locationUpdateQueue.drain();
        // Bulk insert/upsert 30s worth of location updates
        locationRepository.batchUpsert(batch);
    }
}
```

**Tại sao Write-Behind ở đây:**
- Location accuracy = 2s là đủ (user không cần biết driver đang ở đâu chính xác đến từng giây)
- Mất 30s location history nếu Redis crash → chấp nhận được
- Throughput: 50K writes/s với Write-Through cần 50K DB writes/s → không scale

---

### 🛒 Use Case 3: E-Commerce — Shopping Cart (Write-Through)

**Bài toán:** Cart phải consistent — không thể thêm item thành công mà checkout lại không có.

```java
@Service
public class CartService {

    @Transactional
    public void addItem(String userId, CartItem item) {
        // Write-Through với @Transactional
        // Nếu DB fail → Redis không được ghi → consistent
        cartRepository.addItem(userId, item);  // DB first

        String cartKey = "cart:" + userId;
        Cart updatedCart = cartRepository.findByUserId(userId);
        try {
            redisTemplate.opsForValue().set(cartKey, updatedCart, Duration.ofHours(24));
        } catch (Exception e) {
            redisTemplate.delete(cartKey);  // Force DB read next time
        }
    }

    public Cart getCart(String userId) {
        Cart cached = (Cart) redisTemplate.opsForValue().get("cart:" + userId);
        return cached != null ? cached : cartRepository.findByUserId(userId);
    }
}
```

**Trade-off chấp nhận được:**
- Write latency tăng ~1ms (DB + Redis) → không ai notice khi thêm item vào cart
- Gain: Zero cart data loss, consistent checkout

---

### 🎮 Use Case 4: Gaming — Player Score Leaderboard (Write-Behind)

**Bài toán:** 1M players update score real-time. Leaderboard refresh mỗi 5 phút.

```java
// Write-Behind phù hợp — eventual consistency là OK cho leaderboard
@Service
public class LeaderboardService {

    public void updateScore(String playerId, long score) {
        // Chỉ write Redis Sorted Set (ZSet) — O(log N), cực nhanh
        redisTemplate.opsForZSet().add("leaderboard:global", playerId, score);
        // DB async sau — leaderboard không cần real-time DB accuracy
    }

    // Top 100 real-time từ Redis (không cần DB)
    public List<PlayerScore> getTop100() {
        Set<ZSetOperations.TypedTuple<Object>> top = redisTemplate.opsForZSet()
            .reverseRangeWithScores("leaderboard:global", 0, 99);
        return mapToPlayerScores(top);
    }

    // Persist DB mỗi 5 phút cho durability
    @Scheduled(fixedDelay = 300_000)
    public void persistLeaderboard() {
        // Snapshot Redis ZSet → DB
        Set<ZSetOperations.TypedTuple<Object>> all =
            redisTemplate.opsForZSet().rangeWithScores("leaderboard:global", 0, -1);
        scoreRepository.batchUpsert(mapToEntities(all));
    }
}
```

---

### 🏥 Use Case 5: Healthcare — Patient Record (Write-Through + Audit)

**Bài toán:** Patient records phải audit trail, không được mất. Nhưng doctor cần đọc nhanh.

```java
@Service
public class PatientRecordService {

    @Transactional
    public void updateRecord(String patientId, PatientRecord record) {
        // Write-Through với audit log trong cùng transaction
        patientRepository.save(record);
        auditLogRepository.save(new AuditEntry(patientId, "UPDATE", Instant.now()));
        // Cả 2 trong cùng transaction → atomic

        // Cache sau transaction commit
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Chỉ cache SAU KHI transaction commit thành công
                    try {
                        redisTemplate.opsForValue()
                            .set("patient:" + patientId, record, Duration.ofHours(1));
                    } catch (Exception e) {
                        redisTemplate.delete("patient:" + patientId);
                    }
                }
            }
        );
    }
}
```

**Pattern quan trọng:** Cache chỉ được set **sau khi** `@Transactional` commit — tránh cache có data nhưng DB rollback.

---

### 📊 Use Case 6: Loan Saga State (Project hiện tại — Write-Through đúng)

```java
// SagaCacheService.java — Write-Through cho saga state
public void updateStateInMemory(LoanSagaEntity saga) {
    // Lý do Write-Through là đúng:
    // 1. Saga state = distributed transaction state — không được mất
    // 2. Multiple consumers read saga state (orchestrator đọc mỗi event)
    // 3. Consistency quan trọng hơn write latency
    // 4. Write frequency thấp: 4-6 state transitions per loan, không phải 50K/s

    dbRepository.save(saga);   // Source of truth
    redisTemplate.opsForValue()
        .set("SAGA:" + saga.getSagaId(), saga, Duration.ofMinutes(60));
}
```

**Đây là quyết định đúng vì:**

| Factor | Giá trị | Kết luận |
|---|---|---|
| Write frequency | ~5 writes/loan × N loans | Thấp — không cần Write-Behind |
| Data criticality | Transaction state | Cao — cần Write-Through |
| Consistency requirement | Strong | Cần Write-Through |
| Read frequency | ~5 reads/loan | Trung bình |
| Acceptable data loss | Zero | Write-Through mandatory |

---

## 7. Anti-patterns cần tránh

### ❌ Anti-pattern 1: Cache trước DB (Write-Behind cho financial data)

```java
// ❌ NGUY HIỂM cho financial system
public void processPayment(Payment payment) {
    redisTemplate.opsForValue().set("payment:" + payment.id(), payment);  // Cache trước
    paymentRepository.save(payment);  // DB sau
    // Nếu DB fail sau khi cache set → cache có data, DB không có
    // → User nhìn thấy payment thành công → thực ra không có trong DB
    // → Audit fail, compliance violation
}

// ✅ ĐÚNG
public void processPayment(Payment payment) {
    paymentRepository.save(payment);  // DB trước — if fail, exception thrown
    redisTemplate.opsForValue().set("payment:" + payment.id(), payment);  // Cache sau
}
```

### ❌ Anti-pattern 2: Cache trong @Transactional — Phantom Read

```java
// ❌ BUG TINH VI: Cache set bên trong transaction chưa commit
@Transactional
public void transferFunds(String fromId, String toId, BigDecimal amount) {
    accountRepo.debit(fromId, amount);
    accountRepo.credit(toId, amount);
    // Cache được set ở đây — transaction CHƯA COMMIT
    redisTemplate.opsForValue().set("balance:" + fromId, getBalance(fromId));
    // Nếu sau đó transaction ROLLBACK → Cache có data sai, DB thì rollback về cũ
}

// ✅ ĐÚNG: Cache sau khi commit
@Transactional
public void transferFunds(String fromId, String toId, BigDecimal amount) {
    accountRepo.debit(fromId, amount);
    accountRepo.credit(toId, amount);
    // Đăng ký callback chỉ chạy SAU KHI commit thành công
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTemplate.opsForValue().set("balance:" + fromId, ...);
                redisTemplate.opsForValue().set("balance:" + toId, ...);
            }
        }
    );
}
```

### ❌ Anti-pattern 3: Không xoá cache khi DB write fail

```java
// ❌ BUG: Redis set trước, DB fail → Redis stale mãi mãi
public void updateLoanStatus(String loanId, String status) {
    redisTemplate.opsForValue().set("loan:" + loanId, status);  // Redis set
    loanRepository.updateStatus(loanId, status);                  // DB fail → exception
    // Vì exception, Redis key không được xoá → stale data vĩnh viễn (đến hết TTL)
}

// ✅ ĐÚNG: DB trước, Redis sau với proper cleanup
public void updateLoanStatus(String loanId, String status) {
    loanRepository.updateStatus(loanId, status);  // DB trước — fail fast
    try {
        redisTemplate.opsForValue().set("loan:" + loanId, status, Duration.ofMinutes(30));
    } catch (Exception e) {
        redisTemplate.delete("loan:" + loanId);  // Cleanup
    }
}
```

### ❌ Anti-pattern 4: Write-Through với Write-Heavy workload

```java
// ❌ KHÔNG PHÙ HỢP: Metrics counter — 10K increments/giây
@Service
public class MetricsService {
    public void incrementApiCall(String endpoint) {
        // Write-Through: 10K DB writes + 10K Redis writes mỗi giây
        // DB: không cần real-time accuracy cho metrics
        counterRepository.increment(endpoint);          // DB write (5ms)
        redisTemplate.opsForValue().increment("metric:" + endpoint);  // Redis write (1ms)
        // Total: 6ms × 10K/s = 60s worth of write latency → không scale
    }
}

// ✅ PHÙ HỢP: Write-Behind cho metrics
public void incrementApiCall(String endpoint) {
    redisTemplate.opsForValue().increment("metric:" + endpoint);  // Only Redis (<1ms)
    // Batch flush to DB mỗi 1 phút
}
```

---

## 8. Decision Framework — chọn pattern nào?

```
                    START
                      │
           Dữ liệu có critical không?
           (mất data = mất tiền/compliance?)
                      │
             ┌────────┴────────┐
            YES                NO
             │                  │
    Write frequency cao?    Read frequency cao?
    (>1K writes/giây)       (hot read path?)
             │                  │
       ┌─────┴─────┐      ┌─────┴─────┐
      YES          NO    YES          NO
       │            │     │            │
  Write-Behind  Write-  Cache-Aside  Write-Around
  (với careful  Through  (Lazy load)  (Skip cache)
   durability)    ✅
   + audit log

Quy tắc nhanh:
  Financial / Medical / Legal data    → Write-Through (luôn DB first)
  User preference / session           → Write-Through (consistency matters)
  IoT sensor / location updates       → Write-Behind (volume quá cao)
  Leaderboard / counters / analytics  → Write-Behind (eventual OK)
  Large media (images, videos)        → Write-Around (không cache write)
  Product catalog (đọc nhiều, ít ghi) → Cache-Aside (lazy populate)
```

### Bảng quyết định nhanh

| Tiêu chí | Write-Through | Write-Behind | Write-Around | Cache-Aside |
|---|---|---|---|---|
| **Data loss = 0** | ✅ | ❌ | ✅ | ✅ |
| **Write speed tối đa** | ❌ | ✅ | Trung bình | N/A |
| **Read speed tối đa** | ✅ (always warm) | ✅ (always warm) | ❌ (cold) | Trung bình |
| **Strong consistency** | ✅ | ❌ | ✅ | Eventual |
| **High write volume** | ❌ | ✅ | ✅ | N/A |
| **Simple ops model** | ✅ | ❌ (complex) | ✅ | ✅ |
| **Financial system** | ✅ **Best** | ❌ Avoid | Niche | OK for reads |

---

> **Kết luận cho project:**
> `SagaCacheService` dùng Write-Through là **quyết định đúng** cho saga state management.
> Trade-off write latency tăng ~1ms là chấp nhận được so với rủi ro mất saga state.
> Cần thêm: Redis command timeout (500ms), retry cho `delete()`, và
> `TransactionSynchronization.afterCommit()` để tránh phantom cache trong @Transactional.
