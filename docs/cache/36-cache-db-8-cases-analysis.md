# Cache & DB — Phân tích 8 trường hợp đầy đủ

> Đánh giá và correction cho taxonomy 8 cases.
> Framework gốc: Read/Write × Single/Multi × Hit/Miss = 2×2×2 = 8 cases (exhaustive).

---

## Đánh giá tổng thể về framework

```
Framework 8 cases là ĐÚNG và ĐỦ vì:

  Dimension 1: Operation   = Read  | Write       (2)
  Dimension 2: Cardinality = Single | Multi       (2)
  Dimension 3: Cache State = Hit   | Miss         (2)
                                             ─────────
                           Total cases: 2×2×2 = 8 ✅

  Đây là complete coverage — không có case nào bị miss.
  Không cần chia lại, nhưng cần refine một số solutions.
```

**Điểm mạnh của framework:**
- Hit/Miss là dimension quan trọng nhất — quyết định vấn đề thực sự xảy ra
- Phân biệt Single/Multi giúp chọn đúng chiến thuật (lock vs pipeline)
- 8 cases có thể map trực tiếp thành checklist khi thiết kế system

**Cần correction:**
- Case 4: "Queue Request" → cần làm rõ hơn
- Case 5: CDC/Outbox là overengineered cho cache update — có giải pháp đơn giản hơn
- Case 7: Batch Invalidation cần thêm "inconsistency window" handling

---

## Phân tích chi tiết từng case

---

### Case 1 — Read Single (Hit): Hot Key

```
Vấn đề: 1 Redis node nhận 1M reads/s cho cùng 1 key
         → CPU 100%, latency tăng vọt, node đỏ rực

         ┌──────────────────────────────────┐
         │  Redis Cluster                    │
         │  Node A ←──────── 1M reads/s    │  ← overwhelmed
         │  Node B ← 0                      │
         │  Node C ← 0                      │
         └──────────────────────────────────┘
         Cluster to nhưng chỉ 1 node bị hit
```

**Solution: Local Cache (In-Memory) — ĐÚNG ✅**

```java
// Caffeine per JVM pod — absorb 99%+ traffic tại application layer
private final LoadingCache<String, Product> l1 = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(30, TimeUnit.SECONDS)
    .build(key -> loadFromRedis(key));   // auto-populate on miss

public Product get(String id) {
    return l1.get(id);  // ~100ns, zero network — Redis không bị đụng tới
}
```

**Bổ sung — Key Replication (khi L1 không đủ):**

```java
// Khi hot key vượt quá throughput của 1 Redis node
// → nhân bản thành N copies, phân tán sang N nodes khác nhau

void write(String key, Object val) {
    for (int i = 0; i < N_REPLICAS; i++)
        redis.set(key + ":r" + i, val, ttl);  // N writes
}
Object read(String key) {
    int r = ThreadLocalRandom.current().nextInt(N_REPLICAS);
    return redis.get(key + ":r" + r);  // Random pick → tải đều N nodes
}
```

| Giải pháp | Khi nào dùng | Trade-off |
|---|---|---|
| L1 Caffeine | Luôn nên có | Stale tối đa TTL của L1 (30s) |
| Key Replication | Redis node bị hot | Write × N lần |

---

### Case 2 — Read Single (Miss): Cache Stampede

```
Vấn đề: Key expire → N threads cùng thấy miss → N DB queries đồng thời

  T=0: Key "product:123" TTL hết
  T=1: 1000 requests cùng GET → miss → cùng chạy vào DB
  T=2: DB nhận 1000 queries giống hệt nhau → quá tải
```

**Solution: Distributed Lock hoặc Promise Collapsing — ĐÚNG ✅**

Nhưng cần phân biệt rõ khi nào dùng cái nào:

```
Distributed Lock (SETNX):
  + Đúng cho: cross-pod coordination (nhiều pods cùng miss)
  - Vấn đề: N-1 threads BLOCKED chờ lock holder (50ms)

Promise Collapsing (Single-Flight):
  + Đúng cho: trong cùng 1 pod, nhiều goroutines/threads cùng miss
  + Advantage: threads SUBSCRIBE future, không blocked — wake up cùng lúc
  - Giới hạn: chỉ collapse trong 1 JVM

Best practice: Single-Flight per pod + Distributed Lock cross-pod
→ 99% stampede resolved tại L1, chỉ 1 request/pod cần cross-pod lock
```

```java
// Promise Collapsing (Single-Flight) — tốt hơn plain Distributed Lock
private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight
    = new ConcurrentHashMap<>();

public Object get(String key) {
    Object cached = redis.get(key);
    if (cached != null) return cached;

    // computeIfAbsent = ATOMIC: chỉ 1 Future được tạo, mọi caller dùng chung
    CompletableFuture<Object> future = inFlight.computeIfAbsent(key, k ->
        CompletableFuture.supplyAsync(() -> {
            Object val = db.query(k);
            redis.set(k, val, ttl);
            return val;
        }).whenComplete((v, e) -> inFlight.remove(k))
    );
    return future.join(); // Subscribe, không block OS thread nếu dùng Virtual Thread
}
```

**Bổ sung — TTL Jitter (prevention trước khi stampede xảy ra):**

```java
// Ngăn synchronized expiry = ngăn stampede từ gốc
Duration jitteredTtl = baseTtl.plus(
    Duration.ofSeconds(ThreadLocalRandom.current().nextLong(0, jitterWindow))
);
redis.set(key, value, jitteredTtl);
```

---

### Case 3 — Read Multi (Hit): Network Latency

```
Vấn đề: 1 request cần đọc N keys → N roundtrips = N × 1ms = chậm

  Loan dashboard cần: user + account + loan + credit + kyc = 5 roundtrips
  Sequential: 5ms | Pipeline: 1ms
```

**Solution: Pipeline / MGET — ĐÚNG ✅**

```java
// Pipeline: N commands, 1 roundtrip
List<Object> results = redis.executePipelined(conn -> {
    conn.get("user:"    + id);
    conn.get("account:" + id);
    conn.get("loan:"    + id);
    conn.get("credit:"  + id);
    conn.get("kyc:"     + id);
    return null;
});

// MGET: chỉ dùng khi tất cả keys cùng kiểu String, cần atomic read
List<Object> vals = redis.opsForValue().multiGet(keys);
```

**Phân biệt Pipeline vs MGET:**

| | Pipeline | MGET |
|---|---|---|
| **Atomic** | ❌ Không | ✅ Có |
| **Key types** | Bất kỳ (String, Hash, List...) | Chỉ String |
| **Lệnh** | N commands gộp | 1 command |
| **Dùng khi** | Mixed types, bulk ops | Đọc nhiều String keys |

---

### Case 4 — Read Multi (Miss): Database Storm

```
Vấn đề: N keys đều miss → N DB queries đồng thời → DB chết

  Search "loans by customer" → 100 keys miss → 100 DB queries/giây
  Mỗi query full-text scan → DB CPU 100% → timeout → cascade failure
```

**Solution gốc: Elasticsearch hoặc Queue Request**

**Đánh giá:**
- **Elasticsearch** ✅ — đúng cho search/analytics workload, nhưng đây là "thay DB" chứ không phải "xử lý multi miss"
- **Queue Request** ✅ ý đúng nhưng cần làm rõ: đây là **Request Coalescing** cho multi-key

**Solution đầy đủ:**

```
Layer 1: Bulk Cache-Aside — 1 DB query thay vì N queries

  N keys miss → collect tất cả missed keys → 1 DB query với IN clause
  → populate tất cả keys → return

  Thay vì:
    for key in missing_keys: db.query(key)  ← N queries
  Dùng:
    db.findAllByIdIn(missing_keys)           ← 1 query
```

```java
public List<Product> getProducts(List<String> ids) {
    // 1. MGET tất cả
    List<Object> cached = redis.multiGet(toKeys(ids));

    // 2. Tìm ids bị miss
    List<String> missedIds = new ArrayList<>();
    Map<String, Product> result = new HashMap<>();
    for (int i = 0; i < ids.size(); i++) {
        if (cached.get(i) != null) result.put(ids.get(i), (Product) cached.get(i));
        else missedIds.add(ids.get(i));
    }

    // 3. 1 DB query cho tất cả miss — KHÔNG phải N queries
    if (!missedIds.isEmpty()) {
        productRepo.findAllById(missedIds).forEach(p -> {
            result.put(p.getId(), p);
            redis.set("product:" + p.getId(), p, ttl);  // backfill
        });
    }
    return new ArrayList<>(result.values());
}
```

```
Layer 2: Elasticsearch — khi query là search/filter, không phải key lookup

  GET /products?customerId=123    → Cache-Aside với bulk fetch ✅
  GET /products?q=iphone&price<10 → Elasticsearch ✅ (full-text, filter)

  Elasticsearch không phải giải pháp cho "cache miss" —
  nó là giải pháp cho "DB không phù hợp với loại query này"
```

```
Layer 3: Request Coalescing cho search queries (Queue Request đúng ý)

  100 requests cùng search "status=PENDING" → chỉ 1 ES query
  Giống Single-Flight nhưng cho complex query keys
```

---

### Case 5 — Write Single (Hit): Stale Data

```
Vấn đề: Update DB → cache vẫn còn giá trị cũ → đọc ra stale

  T=1: DB: balance = $1000, Cache: balance = $1000  (consistent)
  T=2: DB UPDATE balance = $500
  T=3: DB: balance = $500,  Cache: balance = $1000  (STALE ← vấn đề)
  T=4: Read → Cache HIT → trả $1000 → SAI
```

**Solution gốc: Transactional Outbox / CDC**

**Đánh giá: ⚠️ Đúng nhưng overengineered cho hầu hết cases**

```
CDC/Outbox phù hợp khi:
  ✅ DB được update từ NHIỀU nguồn (app A, app B, migration script, admin tool)
  ✅ Cache và DB ở 2 hệ thống khác nhau, không share transaction
  ✅ Cần audit trail / event log của mọi change
  ✅ Eventual consistency là chấp nhận được (CDC có độ trễ ~ms-seconds)

CDC/Outbox là overkill khi:
  ❌ Chỉ có 1 app duy nhất write vào DB
  ❌ Cần synchronous cache update (CDC async → window of stale)
  ❌ Đơn giản chỉ cần cache reflect DB write ngay lập tức
```

**Giải pháp theo thứ tự phức tạp tăng dần:**

```
Độ phức tạp:    SIMPLE ──────────────────────────────── COMPLEX
                   │                                        │
                   ▼                                        ▼
          Write-Through        Invalidation          CDC/Outbox
          (update cache        (xóa cache,           (Debezium
           khi write DB)        next read repopulate)  async sync)
```

```java
// Option 1: Write-Through (đơn giản nhất, đúng cho financial)
@Transactional
public void updateBalance(String accountId, BigDecimal amount) {
    accountRepo.updateBalance(accountId, amount);      // DB
    // Cache sau khi commit (không phải trong transaction!)
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.set("balance:" + accountId, amount, ttl);
            }
        }
    );
}

// Option 2: Invalidation (đơn giản, eventual consistency 1 read sau)
@Transactional
public void updateBalance(String accountId, BigDecimal amount) {
    accountRepo.updateBalance(accountId, amount);      // DB
    // afterCommit để tránh phantom cache nếu TX rollback
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.delete("balance:" + accountId);  // Next read sẽ repopulate từ DB
            }
        }
    );
}

// Option 3: CDC/Outbox (dùng khi multi-writer hoặc cần audit)
// Debezium capture WAL → publish event → cache update consumer
// → đã implement trong project với Outbox pattern
```

**Write-Through vs Invalidation:**

| | Write-Through | Invalidation |
|---|---|---|
| **Next read sau write** | Cache HIT (warm) | Cache MISS (cold, 1 lần) |
| **Race condition** | Có thể nếu không dùng afterCommit | Ít hơn — chỉ cần delete |
| **Complexity** | Trung bình | Thấp |
| **Dùng khi** | Đọc ngay sau write nhiều | Đọc sau write không nhiều |

---

### Case 6 — Write Single (Miss): Không có vấn đề lớn

```
Vấn đề: Ghi DB, cache không có key này → không ai bị ảnh hưởng
         Reads sau đó sẽ miss → populate từ DB → bình thường

         Write → DB (key không có trong cache, không có gì để stale)
         Read  → Miss → DB → Cache populate → HIT lần sau
```

**Solution: Write-Through nếu muốn cache warm ngay — ĐÚNG ✅**

```java
// Nếu muốn read ngay sau write không bị miss:
public Loan createLoan(Loan loan) {
    Loan saved = loanRepo.save(loan);           // DB
    redis.set("loan:" + saved.getId(), saved, ttl);  // Pre-warm cache
    return saved;
}

// Nếu không cần: bỏ qua, Cache-Aside tự populate khi có read
// → Simple, không có vấn đề gì
```

**Lưu ý:** Case này thực ra là **Write-Around** — write chỉ vào DB, không populate cache. Hoàn toàn hợp lệ khi object vừa tạo chưa chắc được đọc ngay.

---

### Case 7 — Write Multi (Hit): Cache-DB Inconsistency

```
Vấn đề: Update nhiều DB records → nhiều cache keys lỗi thời

  Ví dụ: Giá sản phẩm thay đổi → cần invalidate:
  - product:detail:123        (product page)
  - product:search:electronics (search cache)
  - cart:user:456             (cart của users có product này)
  - recommendation:user:789   (recommendation cache)
  - category:phones           (category listing)
  → 5+ keys cần invalidate

  Vấn đề sâu hơn: Partial invalidation
  T=1: redis.delete("product:detail:123")   ← OK
  T=2: App crash 💥
  T=3: redis.delete("cart:user:456")        ← KHÔNG chạy
  → Inconsistency: product detail đúng, cart vẫn stale
```

**Solution gốc: Batch Invalidation — ĐÚNG nhưng chưa đủ ✅⚠️**

**Cần thêm: xử lý Partial Invalidation với Pipeline:**

```java
// Pipeline: tất cả deletes trong 1 roundtrip
// Không atomic nhưng nhanh hơn nhiều lần gọi riêng lẻ
public void invalidateProductCaches(String productId, List<String> affectedCartUserIds) {
    redisTemplate.executePipelined(conn -> {
        StringRedisConnection sc = (StringRedisConnection) conn;
        sc.del("product:detail:"  + productId);
        sc.del("product:search:electronics");
        sc.del("category:phones");
        affectedCartUserIds.forEach(uid -> sc.del("cart:" + uid));
        return null;
    });
    // 1 roundtrip, atomicity ở Redis network level
    // Nếu connection drop giữa chừng → retry toàn bộ pipeline
}
```

**Bổ sung — Tag-based Invalidation (xử lý nhóm keys):**

```java
// Gán tag cho nhóm keys liên quan → invalidate cả nhóm bằng 1 lệnh
// Dùng Redis Set để track keys trong cùng 1 tag

// Khi cache 1 key, đăng ký vào tag
public void setWithTag(String key, Object val, String tag, Duration ttl) {
    redis.opsForValue().set(key, val, ttl);
    redis.opsForSet().add("tag:" + tag, key);     // Register key vào tag group
    redis.expire("tag:" + tag, ttl.plusMinutes(1)); // Tag TTL >= key TTL
}

// Invalidate toàn bộ tag
public void invalidateTag(String tag) {
    Set<Object> keys = redis.opsForSet().members("tag:" + tag);
    if (keys != null && !keys.isEmpty()) {
        redis.delete(keys.stream().map(Object::toString).collect(Collectors.toList()));
        redis.delete("tag:" + tag);
    }
}

// Usage: tất cả cache liên quan đến product:123 đều được xóa 1 lệnh
invalidateTag("product:123");
```

```
Khi nào dùng gì:

  Pipeline Delete: số lượng keys biết trước, fix list
  Tag Invalidation: keys liên quan đến 1 entity (product, user, order)
  Version Key:      khi muốn invalidate mà không cần biết danh sách keys
```

**Bonus — Version-based Cache (không cần explicit invalidation):**

```java
// Thay vì delete keys, tăng version → key cũ tự obsolete
// Keys cũ tự expire theo TTL, không cần track và delete

// Key format: "product:{id}:v{version}"
public Product getProduct(String id) {
    long version = redis.opsForValue().increment("product:version:" + id);
    // version tăng khi có write → key cũ sẽ miss (version mismatch)
    return (Product) redis.get("product:" + id + ":v" + version);
}
```

---

### Case 8 — Write Multi (Miss): Throughput Bottleneck

```
Vấn đề: Nhiều records cần ghi DB nhưng cache không có → DB bị overwhelm

  Event storm: 100K transactions/giây cần insert DB
  Mỗi INSERT riêng lẻ: ~5ms
  Sequential: 100K × 5ms = 500 giây để process → impossible

  Cache miss ở đây không phải về cache — là về DB write throughput
```

**Solution gốc: Kafka Batching Consumer — ĐÚNG ✅**

```
Kafka absorb write spikes:

  100K events/s ──► Kafka (buffer)
                         │
                         │ Consumer đọc batch 500 events
                         ▼
                    DB BULK INSERT (500 records / 1 query)
                    = 100K/500 = 200 batch queries/s thay vì 100K individual queries

  Kafka cũng cung cấp:
  ✅ Backpressure tự nhiên (consumer tự điều chỉnh tốc độ)
  ✅ Replay nếu DB down tạm thời
  ✅ Ordered processing per key
```

```java
// Kafka Batch Consumer — bulk insert
@KafkaListener(topics = "write-events",
               groupId = "db-writer",
               containerFactory = "batchListenerFactory")
@Transactional
public void processBatch(List<ConsumerRecord<String, WriteEvent>> records) {
    // Collect tất cả records trong batch
    List<Entity> entities = records.stream()
        .map(r -> toEntity(r.value()))
        .collect(Collectors.toList());

    // 1 bulk INSERT thay vì N inserts
    entityRepo.saveAll(entities);  // Spring Data: 1 batch SQL

    // Cache update sau khi batch commit (optional, nếu cần warm cache)
    redisTemplate.executePipelined(conn -> {
        entities.forEach(e ->
            ((StringRedisConnection) conn).set("entity:" + e.getId(),
                serialize(e), Expiration.from(ttl), SetOption.UPSERT));
        return null;
    });
}
```

**Bổ sung — Write-Behind Buffer tại Redis (nếu không có Kafka):**

```java
// Redis List/Hash làm write buffer tạm thời
// → Periodic flush DB với bulk insert

public void stageWrite(String id, Entity entity) {
    redis.opsForHash().put("write-buffer", id, entity);  // O(1) write
}

@Scheduled(fixedDelay = 5_000)  // Flush mỗi 5s
@SchedulerLock(name = "writeFlush")
public void flush() {
    Map<Object, Object> buffer = redis.opsForHash().entries("write-buffer");
    if (buffer.isEmpty()) return;
    redis.delete("write-buffer");
    entityRepo.saveAll(toEntities(buffer));  // Bulk insert
}
// Trade-off: Mất tối đa 5s data nếu pod crash — acceptable cho non-critical data
```

---

## Bảng tổng hợp đã correction

| # | Case | Vấn đề | Solution Gốc | Verdict | Solution Tối ưu |
|---|---|---|---|---|---|
| 1 | Read Single (Hit) | Hot Key | Local Cache | ✅ Đúng | L1 Caffeine + Key Replication |
| 2 | Read Single (Miss) | Stampede | Dist. Lock / Promise Collapsing | ✅ Đúng | TTL Jitter (prevent) + Single-Flight (handle) |
| 3 | Read Multi (Hit) | Network Latency | Pipeline / MGET | ✅ Đúng | Pipeline (mixed types) / MGET (string keys) |
| 4 | Read Multi (Miss) | DB Storm | Elasticsearch / Queue Request | ⚠️ Cần rõ hơn | **Bulk Cache-Aside** (1 IN query) → ES (chỉ cho search) |
| 5 | Write Single (Hit) | Stale Data | Outbox / CDC | ⚠️ Overengineered | **Invalidation** (đơn giản) → Write-Through → CDC (multi-writer) |
| 6 | Write Single (Miss) | Không lớn | Write-Through | ✅ Đúng | Write-Through nếu muốn warm, bỏ qua nếu không cần |
| 7 | Write Multi (Hit) | Inconsistency | Batch Invalidation | ✅ Đúng nhưng thiếu | + Pipeline Delete + Tag Invalidation (nhóm keys) |
| 8 | Write Multi (Miss) | Throughput | Kafka Batching | ✅ Đúng | Kafka + Bulk Insert; hoặc Redis Write-Behind Buffer |

---

## Vấn đề nền tảng: "Inconsistency Window"

> Tất cả 8 cases đều có chung 1 vấn đề ẩn mà bảng gốc chưa đề cập.

```
Dù dùng solution nào, luôn có "window" mà DB và Cache không đồng nhất:

  Write-Through:
    DB save ──► [window: DB đúng, cache cũ] ──► Cache update
                  └── nếu read xảy ra ở đây → stale data

  Invalidation:
    DB save ──► [window: DB đúng, cache stale] ──► Cache delete
                  └── nếu read xảy ra ở đây → stale data

  CDC/Debezium:
    DB save ──► [window: ms-seconds lag] ──► Kafka event ──► Cache update
                  └── longer window, nhưng bounded
```

**Cách xử lý inconsistency window:**

```
Approach 1: Minimize window (Write-Through afterCommit)
  → Cache update ngay khi TX commit
  → Window = microseconds (thực tế gần 0)

Approach 2: Accept and bound (TTL)
  → Stale data tự expire sau TTL
  → Dùng khi: eventual consistency OK (analytics, recommendations)

Approach 3: Read-your-writes routing
  → Request từ user X sau khi write → route đến DB trong N giây
  → Sau đó trở lại đọc cache như bình thường
  → Dùng cho: financial balance (user phải thấy balance mới ngay)

Approach 4: Version tag trong response
  → Client gửi kèm version → server check version → nếu stale → fetch DB
  → Dùng cho: mobile apps cần consistency
```

---

## Decision Flowchart

```
Nhận được bài toán cache/DB, hỏi theo thứ tự:

Q1: Read hay Write?
    │
    ├── READ ──► Q2: Có key trong cache không?
    │               ├── HIT ──► Q3: 1 key hay nhiều key?
    │               │               ├── 1 key: Hot Key? → L1 Cache + Key Replication
    │               │               └── N keys: → Pipeline / MGET
    │               └── MISS ──► Q3: 1 key hay nhiều key?
    │                               ├── 1 key: Stampede? → Single-Flight + Jitter
    │                               └── N keys: DB Storm? → Bulk Cache-Aside + ES
    │
    └── WRITE ──► Q2: Có key trong cache không?
                    ├── HIT ──► Q3: 1 key hay nhiều key?
                    │               ├── 1 key: Stale? → Invalidation hoặc Write-Through
                    │               └── N keys: Inconsistency? → Pipeline Delete + Tag Invalidation
                    └── MISS ──► Q3: 1 key hay nhiều key?
                                    ├── 1 key: No problem → Write-Through nếu muốn warm
                                    └── N keys: Throughput? → Kafka Batch + Bulk Insert
```

---

## Kết luận: Có nên dùng 8 cases không?

> **Có — đây là taxonomy tốt nhất cho bài toán này.**

**Lý do:**
- **Complete:** 2×2×2 = 8 = cover mọi tổ hợp có thể
- **Orthogonal:** 3 dimensions độc lập nhau, không overlap
- **Actionable:** Mỗi case → solution riêng, không nhầm lẫn
- **Teachable:** Dễ nhớ, dễ áp dụng vào system design interview

**Không nên chia thêm vì:**
- Thêm dimension → exponential cases (16, 32...) → quá phức tạp
- Các nuances (hot vs cold, consistency level) → xử lý trong từng case

**Nên chia lại không?** Không — chỉ cần refine solutions như đã phân tích ở trên.
