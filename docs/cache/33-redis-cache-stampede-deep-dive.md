# Redis Cache — Stampede, Jitter & Advanced Solutions

> **Context:** Phân tích Cache-Aside pattern trong project, trade-off Cache Stampede (Thundering Herd),
> và các giải pháp từ đơn giản đến nâng cao cho hệ thống financial scale triệu users.

---

## Mục lục

- [1. Project đang dùng pattern nào?](#1-project-đang-dùng-pattern-nào)
- [2. Cache Stampede là gì?](#2-cache-stampede-là-gì)
- [3. Chuỗi bottleneck — timeout của cái gì?](#3-chuỗi-bottleneck--timeout-của-cái-gì)
- [4. Diễn biến timeline 1 triệu requests](#4-diễn-biến-timeline-1-triệu-requests)
- [5. Giải pháp 1: TTL Jitter](#5-giải-pháp-1-ttl-jitter)
- [6. Giải pháp 2: Mutex Lock (SETNX)](#6-giải-pháp-2-mutex-lock-setnx)
- [7. Giải pháp 3: Stale-While-Revalidate](#7-giải-pháp-3-stale-while-revalidate)
- [8. Giải pháp 4: Probabilistic Early Expiration (PER)](#8-giải-pháp-4-probabilistic-early-expiration-per)
- [9. Giải pháp 5: Cache Warming](#9-giải-pháp-5-cache-warming)
- [10. Vấn đề: Mutex với single hot key](#10-vấn-đề-mutex-với-single-hot-key)
- [11. Single-Flight Pattern — Tốt hơn Mutex](#11-single-flight-pattern--tốt-hơn-mutex)
- [12. Two-Level Cache L1+L2](#12-two-level-cache-l1l2)
- [13. So sánh tổng hợp & Khuyến nghị](#13-so-sánh-tổng-hợp--khuyến-nghị)

---

## 1. Project đang dùng pattern nào?

Project thực ra có **2 cache pattern khác nhau** trong cùng một codebase:

| Service | File | Pattern | Cơ chế |
|---|---|---|---|
| `loan-service` | `LoanQueryService.java` | **Cache-Aside** | `@Cacheable` — Spring tự quản lý: miss → query ES → store Redis |
| `loan-orchestrator-service` | `SagaCacheService.java` | **Write-Through** | Manual: DB first → Redis set |

### Cache-Aside trong LoanQueryService

```java
// loan-service/LoanQueryService.java
@Cacheable(value = "customerLoansES", key = "#customerId", unless = "#result.isEmpty()")
@CircuitBreaker(name = "searchService", fallbackMethod = "fallbackGetLoans")
public List<LoanApplicationDocument> findLoansByCustomerCached(String customerId) {
    // Chỉ chạy khi cache MISS
    // Cache HIT → Spring trả từ Redis, method không được gọi
    return elasticsearchRepository.findByCustomerId(customerId);
}
```

```java
// loan-service/RedisCacheConfig.java
RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofMinutes(10))  // ← TTL cố định: nguồn gốc của stampede
    .disableCachingNullValues()
```

### Write-Through trong SagaCacheService

```java
// loan-orchestrator-service/SagaCacheService.java
public void updateStateInMemory(LoanSagaEntity saga) {
    dbRepository.save(saga);  // DB TRƯỚC — source of truth
    try {
        redisTemplate.opsForValue().set("SAGA:" + saga.getSagaId(), saga, Duration.ofMinutes(60));
    } catch (Exception e) {
        redisTemplate.delete("SAGA:" + saga.getSagaId());  // Xoá stale key nếu Redis write fail
    }
}
```

> **Write-Through không bị stampede** vì data luôn được write vào DB+Redis đồng thời, không có window miss.
> Stampede chỉ xảy ra với **Cache-Aside** khi key expire.

---

## 2. Cache Stampede là gì?

```
                    TTL = 10 phút hết hạn
                           │
                    1,000,000 requests đến cùng lúc
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         Thread 1      Thread 2    ... Thread 1,000,000
              │            │            │
         Cache MISS    Cache MISS   Cache MISS   ← tất cả cùng miss
              │            │            │
              └────────────┴────────────┘
                           │
                           ▼
                    Elasticsearch nhận
                    1,000,000 queries GIỐNG HỆT NHAU
                    findByCustomerId("cust-123")
                           │
                    ┌──────┴──────┐
                    │  ES đang    │
                    │  quá tải   │──► latency tăng vọt → timeout
                    │  CPU 100%  │──► Circuit Breaker OPEN
                    └────────────┘
                           │
                    fallbackGetLoans() → List.empty()
                    → Toàn bộ user thấy danh sách vay TRỐNG 💥
```

**Khi nào stampede xảy ra:**
- TTL cố định → tất cả keys expire cùng lúc (synchronized expiry)
- Redis restart / maintenance → toàn bộ cache mất trắng (cold start)
- First deployment → cache chưa có gì

---

## 3. Chuỗi bottleneck — timeout của cái gì?

> **Quan trọng:** Cache-Aside KHÔNG có cơ chế chờ. Cache miss → query DB/ES NGAY LẬP TỨC.
> 1M request miss cache → 1M request đồng loạt query Elasticsearch. Không có "timeout chờ cache".

Có **3 bottleneck nối tiếp**, mỗi cái có timeout riêng:

```
1,000,000 requests
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│  BOTTLENECK 1: HTTP Thread Pool (Tomcat)                        │
│                                                                  │
│  server.tomcat.threads.max = 200 (default)                      │
│  server.tomcat.accept-count = 100 (default)                     │
│                                                                  │
│  → 200 requests: vào thread pool, xử lý                        │
│  → 100 requests: vào accept queue, chờ thread                   │
│  → 999,700 requests: Connection REFUSED ngay (không có timeout)  │
└───────────────────────┬─────────────────────────────────────────┘
                        │ 200 requests lọt qua
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│  BOTTLENECK 2: HikariCP Connection Pool                         │
│                                                                  │
│  maximumPoolSize = 30                                            │
│                                                                  │
│  → 30 requests: lấy được connection → query ES/DB              │
│  → 170 requests: xếp hàng chờ connection rảnh                  │
│                                                                  │
│  connectionTimeout = 5000ms  ← ĐÂY LÀ TIMEOUT HAY NHẮC NHẤT   │
│  → Sau 5s không có connection → SQLTransientConnectionException │
└───────────────────────┬─────────────────────────────────────────┘
                        │ 30 connections thực sự query
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│  BOTTLENECK 3: Elasticsearch                                     │
│                                                                  │
│  ES thread pool: 10-30 search threads (default)                 │
│  → 30 queries đổ vào cùng lúc → CPU spike, GC pressure         │
│                                                                  │
│  ES search timeout = 30s (nếu set)                              │
│  → Query chậm → connection giữ lâu → pool càng lâu mới rảnh   │
│  → 170 requests chờ pool 5s → timeout → 500 error              │
└─────────────────────────────────────────────────────────────────┘
```

**Tóm lại timeout của các tầng:**

| Timeout | Của cái gì | Default | Hành vi khi hết |
|---|---|---|---|
| N/A | Tomcat accept queue | 100 slots | Connection Refused (ngay) |
| `connectionTimeout` | HikariCP connection pool | **5,000ms** | SQLTransientConnectionException |
| `search.timeout` | Elasticsearch | 30s | Partial result hoặc error |
| Circuit Breaker | Resilience4j | Tùy config | fallbackGetLoans() → `[]` |

---

## 4. Diễn biến timeline 1 triệu requests

```
T = 0ms   : Cache key "customerLoansES:cust-123" hết TTL 10 phút.
             1,000,000 requests ùa vào.

T = 0ms   : Tomcat:
             ├── 200 threads: xử lý
             ├── 100 requests: accept queue
             └── 999,700 requests: REJECTED (503)

T = 1ms   : 200 threads đang xử lý → ALL cache miss → chạy thẳng vào ES:
             ├── 30 requests: lấy HikariCP connection → query ES
             └── 170 requests: xếp hàng chờ connection (bắt đầu đếm 5s)

T = 50ms  : ES trả kết quả cho 30 queries đầu (bình thường ~10ms,
             nhưng đang overloaded nên ~50ms)
             → 30 connections được giải phóng → 30 requests tiếp theo lấy connection

T = 5000ms: HikariCP connectionTimeout:
             → Các request chờ quá 5s → SQLTransientConnectionException
             → Spring trả HTTP 500
             → Circuit Breaker đếm failures...

T = 5100ms: Circuit Breaker OPEN (failure rate > threshold):
             → Mọi request tiếp theo → fallbackGetLoans() → List.empty()
             → TOÀN BỘ user thấy danh sách khoản vay TRỐNG 💥
```

**Fail fast với HikariCP — nên config:**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      connection-timeout: 1000   # Giảm 5000ms → 1000ms
                                 # Fail fast → Circuit Breaker trip sớm hơn
                                 # → Bảo vệ DB sớm hơn, không để request chờ lâu
```

---

## 5. Giải pháp 1: TTL Jitter

**Vấn đề giải quyết:** Synchronized expiry — tất cả keys expire cùng lúc vì TTL cố định.

**Jitter KHÔNG giải quyết được:** Cold start (Redis restart) — khi cache trống rỗng, không có key nào để "spread expiry".

```java
@Bean
public RedisCacheManagerBuilderCustomizer cacheManagerCustomizer() {
    return builder -> builder
        .withCacheConfiguration("customerLoansES",
            RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(randomTtl(8, 12)));  // 8–12 phút ngẫu nhiên
}

private Duration randomTtl(int minMinutes, int maxMinutes) {
    long seconds = ThreadLocalRandom.current().nextLong(
        Duration.ofMinutes(minMinutes).toSeconds(),
        Duration.ofMinutes(maxMinutes).toSeconds()
    );
    return Duration.ofSeconds(seconds);
}
```

**Kết quả:**

```
KHÔNG có Jitter (TTL = 10 phút):
  T=0:00 : 1M users login → 1M keys, TTL = 10:00
  T=10:00: TẤT CẢ 1M keys expire CÙNG LÚC → stampede 💥

CÓ Jitter (TTL = 8–12 phút ngẫu nhiên):
  T=0:00 : 1M users login → 1M keys, TTL khác nhau
  T=8:00 : ~250K keys expire → ~250K miss → ES nhận 250K/4min = ~1K req/s ✅
  T=9:00 : ~250K keys expire → tương tự ✅
  T=10:00: ~250K keys expire → ✅
  T=11:00: ~250K keys expire → ✅
  Tải được trải đều, ES không bị spike
```

**Chi phí:** 5 dòng code. Giảm 80% nguy cơ stampede từ synchronized expiry.

---

## 6. Giải pháp 2: Mutex Lock (SETNX)

**Vấn đề giải quyết:** Cold start — chỉ 1 thread recompute, các thread khác chờ.

```java
public List<LoanApplicationDocument> findLoansByCustomerCached(String customerId) {
    String cacheKey = "customerLoansES:" + customerId;
    String lockKey  = "lock:" + cacheKey;

    // 1. Check cache
    Object cached = redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) return (List<LoanApplicationDocument>) cached;

    // 2. Tranh lock (atomic SETNX)
    Boolean acquired = stringRedisTemplate.opsForValue()
        .setIfAbsent(lockKey, "LOCKED", Duration.ofSeconds(5));

    if (Boolean.TRUE.equals(acquired)) {
        try {
            // Double-check: thread khác có thể đã populate cache
            cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) return (List<LoanApplicationDocument>) cached;

            List<LoanApplicationDocument> result =
                elasticsearchRepository.findByCustomerId(customerId);
            if (!result.isEmpty()) {
                redisTemplate.opsForValue().set(cacheKey, result, Duration.ofMinutes(10));
            }
            return result;
        } finally {
            stringRedisTemplate.delete(lockKey);  // LUÔN release lock
        }
    } else {
        // Thua lock → retry đọc cache sau khi winner populate xong
        return retryReadCache(cacheKey, customerId);
    }
}

private List<LoanApplicationDocument> retryReadCache(String cacheKey, String customerId) {
    for (int i = 0; i < 3; i++) {
        try { Thread.sleep(100); } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); break;
        }
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return (List<LoanApplicationDocument>) cached;
    }
    // Lock holder crashed → fallback trực tiếp ES
    return elasticsearchRepository.findByCustomerId(customerId);
}
```

**Nhược điểm với single hot key (xem mục 10):**

```
1M requests miss cache → 1 thread WIN lock → query ES
                       → 999,999 threads BLOCKED chờ lock (50ms)
                       → 999,999 threads bị block đồng loạt
                       → Thread pool cạn → latency P99 tăng vọt
```

---

## 7. Giải pháp 3: Stale-While-Revalidate

**Vấn đề giải quyết:** User không cần chờ — trả stale data ngay, refresh background.

```java
record CachedEntry<T>(T data, Instant cachedAt) {}

private static final Duration FRESH_TTL = Duration.ofMinutes(10);
private static final Duration STALE_TTL = Duration.ofMinutes(12); // Stale window 2 phút
private final Executor refreshExecutor = Executors.newVirtualThreadPerTaskExecutor();

@SuppressWarnings("unchecked")
public List<LoanApplicationDocument> findLoansByCustomerCached(String customerId) {
    String cacheKey = "customerLoansES:" + customerId;
    CachedEntry<List<LoanApplicationDocument>> entry =
        (CachedEntry<List<LoanApplicationDocument>>)
            redisTemplate.opsForValue().get(cacheKey);

    if (entry == null) {
        // Cold miss: block query ES lần đầu tiên
        return fetchAndCache(cacheKey, customerId);
    }

    boolean isFresh = entry.cachedAt().isAfter(Instant.now().minus(FRESH_TTL));
    if (isFresh) {
        return entry.data();  // Còn fresh → trả ngay
    }

    // Stale nhưng chưa hết stale window → trả stale + refresh async
    refreshExecutor.execute(() -> fetchAndCache(cacheKey, customerId));
    return entry.data();  // Trả NGAY, không chờ refresh
}

private List<LoanApplicationDocument> fetchAndCache(String cacheKey, String customerId) {
    List<LoanApplicationDocument> result =
        elasticsearchRepository.findByCustomerId(customerId);
    if (!result.isEmpty()) {
        redisTemplate.opsForValue().set(
            cacheKey,
            new CachedEntry<>(result, Instant.now()),
            STALE_TTL
        );
    }
    return result;
}
```

**So sánh latency:**

```
Mutex:                    Stale-While-Revalidate:
  T+0ms:  Cache miss       T+0ms:  Cache miss NHƯNG stale data có
          → block chờ ES            → trả stale NGAY (~1ms)
  T+50ms: ES trả kết quả   T+0ms:  Background refresh ES bắt đầu
  T+50ms: User nhận        T+50ms: Cache updated (user không thấy)
  Latency: 50ms ❌         Latency: ~1ms ✅ (data có thể cũ vài phút)
```

**Khi nào dùng:** Data có thể eventual consistency ngắn hạn (danh sách khoản vay, lịch sử giao dịch).
**Không dùng cho:** Số dư tài khoản, trạng thái giao dịch real-time.

---

## 8. Giải pháp 4: Probabilistic Early Expiration (PER)

**Ý tưởng:** Xác suất refresh tăng dần khi gần đến TTL — không cần lock, không cần coordination.

```java
record CachedEntry<T>(T data, Instant cachedAt, Duration ttl) {
    /**
     * XEP formula: P(refresh) tăng exponential khi elapsed gần ttl
     * beta = "độ nhạy" — beta cao = refresh sớm hơn
     */
    boolean shouldRecompute(double beta) {
        double elapsed = Duration.between(cachedAt, Instant.now()).toSeconds();
        double ttlSec  = ttl.toSeconds();
        double random  = -Math.log(ThreadLocalRandom.current().nextDouble());
        return elapsed + random * beta >= ttlSec;
    }
}

// Trong service:
if (entry != null && !entry.shouldRecompute(1.5)) {
    return entry.data();  // Xác suất thấp → không refresh
}
// Xác suất kích hoạt → 1 thread tình cờ refresh trước khi expire
// Phân tán tự nhiên, không cần distributed lock
refreshExecutor.execute(() -> fetchAndCache(cacheKey, customerId));
return entry != null ? entry.data() : fetchAndCache(cacheKey, customerId);
```

---

## 9. Giải pháp 5: Cache Warming

**Ý tưởng:** Không để cache expire cold — chủ động reload trước khi hết hạn.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheWarmingJob {

    private final LoanApplicationSearchRepository elasticsearchRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    // Chạy mỗi 8 phút — TTL là 10 phút → luôn warm trước khi expire
    @Scheduled(fixedDelay = 8 * 60 * 1000)
    @SchedulerLock(name = "cacheWarmingTask", lockAtLeastFor = "1m", lockAtMostFor = "5m")
    public void warmTopCustomers() {
        log.info("🔥 [CACHE WARMING] Pre-loading top active customers");
        List<String> hotCustomers = elasticsearchRepository.findTopActiveCustomers(1000);
        hotCustomers.parallelStream().forEach(customerId -> {
            String cacheKey = "customerLoansES:" + customerId;
            List<LoanApplicationDocument> loans =
                elasticsearchRepository.findByCustomerId(customerId);
            if (!loans.isEmpty()) {
                redisTemplate.opsForValue().set(cacheKey, loans, Duration.ofMinutes(10));
            }
        });
        log.info("✅ [CACHE WARMING] Warmed {} customer caches", hotCustomers.size());
    }
}
```

---

## 10. Vấn đề: Mutex với single hot key

**Mutex giải quyết được stampede nhưng tạo ra bottleneck mới:**

```
1,000,000 requests đồng thời miss cache key "customerLoansES:cust-hot"
         │
         ▼
  SETNX lock → chỉ 1 thread WIN

  Thread Winner   → query ES (~50ms) → set cache → release lock
  Thread 2-999999 → BLOCKED chờ lock (50ms mỗi thread)

Kết quả:
  ✅ ES chỉ bị hit 1 lần
  ❌ 999,999 threads BLOCK 50ms
  ❌ Tomcat thread pool cạn kiệt
  ❌ Latency P99 = 50ms thay vì <1ms
  ❌ Memory: 999,999 suspended threads (với platform threads = ~1TB RAM 💥)
```

**Mutex đổi stampede DB thành stampede ở tầng application** — cũng nguy hiểm không kém.

---

## 11. Single-Flight Pattern — Tốt hơn Mutex

**Ý tưởng cốt lõi:** Thay vì 999,999 threads xếp hàng chờ lock, tất cả **subscribe vào cùng 1 CompletableFuture**. Khi future hoàn thành → tất cả nhận kết quả cùng lúc.

```
Mutex:                          Single-Flight:

  T1 → [LOCK] → ES query          T1 ─┐
  T2 → [wait lock]                 T2 ─┤
  T3 → [wait lock]                 T3 ─┼──► [1 CompletableFuture] ──► ES (1 lần)
  ...                              ...─┤              │
  T999999 → [wait lock]            T999999─┘          │ complete
                                                       ▼
                                              Tất cả 999,999 threads
                                              nhận kết quả CÙNG LÚC ✅
```

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanQueryService {

    private final LoanApplicationSearchRepository elasticsearchRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Executor virtualExecutor =
            Executors.newVirtualThreadPerTaskExecutor(); // Java 21

    // Registry: key = cacheKey, value = Future đang in-flight
    private final ConcurrentHashMap<String,
            CompletableFuture<List<LoanApplicationDocument>>> inFlight =
            new ConcurrentHashMap<>();

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    @SuppressWarnings("unchecked")
    public List<LoanApplicationDocument> findLoansByCustomerCached(String customerId) {
        String cacheKey = "customerLoansES:" + customerId;

        // ① Happy path: cache hit → trả ngay
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return (List<LoanApplicationDocument>) cached;
        }

        // ② Cache miss → tham gia in-flight pool
        // computeIfAbsent là ATOMIC:
        //   Thread đầu tiên : KEY chưa có → tạo Future mới → submit ES query
        //   Mọi thread sau  : KEY đã có  → nhận CÙNG Future → không tạo thêm query
        CompletableFuture<List<LoanApplicationDocument>> future = inFlight.computeIfAbsent(
            cacheKey,
            key -> CompletableFuture
                .supplyAsync(
                    () -> {
                        log.info("🚀 [SINGLE-FLIGHT] 1 query ES duy nhất cho key={}", key);
                        return elasticsearchRepository.findByCustomerId(customerId);
                    },
                    virtualExecutor
                )
                .whenComplete((result, ex) -> {
                    inFlight.remove(key); // Cleanup để request tiếp theo có thể tạo Future mới
                    if (ex == null && result != null && !result.isEmpty()) {
                        redisTemplate.opsForValue().set(key, result, CACHE_TTL);
                        log.info("✅ [SINGLE-FLIGHT] Cache populated, all waiters unblocked");
                    }
                })
        );

        // ③ Tất cả threads JOIN vào cùng 1 future
        //    Virtual Threads: join() = suspend coroutine, không block OS thread
        //    1M virtual threads join = ~1GB heap (chịu được) vs 1M platform threads = ~1TB (crash)
        try {
            return future.join();
        } catch (Exception e) {
            log.error("❌ [SINGLE-FLIGHT] ES query failed for {}", customerId, e);
            return Collections.emptyList();
        }
    }
}
```

**Timeline với 1 triệu requests:**

```
T=0ms  : 1,000,000 requests đến, tất cả cache miss

  Thread-1      : computeIfAbsent → KEY CHƯA TỒN TẠI
                  → tạo CompletableFuture → submit ES query
                  → join() (suspend virtual thread, không block OS thread)

  Thread-2      : computeIfAbsent → KEY ĐÃ TỒN TẠI
                  → nhận CÙNG future của Thread-1
                  → join() (suspend)

  Thread 3..1M  : computeIfAbsent → nhận CÙNG future
                  → join() (suspend)

  ES nhận: ĐÚNG 1 QUERY

T=50ms : ES trả kết quả
         future.complete(result)
         → whenComplete: set Redis, remove inFlight registry
         → TẤT CẢ 999,999 suspended threads wake up cùng lúc
         → Mọi request nhận kết quả với latency ≈ 50ms (bằng nhau)
```

**Tại sao Virtual Threads quyết định sống còn ở đây:**

```
Platform Thread (trước Java 21):
  1M threads join() = 1M OS threads blocked
  ~1MB stack × 1M = ~1TB RAM = crash 💥

Virtual Thread (Java 21 — đã enable trong project):
  1M virtual threads join() = 1M suspended coroutines
  ~1KB × 1M = ~1GB JVM heap = chịu được ✅
  Chỉ dùng vài chục OS carrier threads
```

---

## 12. Two-Level Cache L1+L2

**Ý tưởng:** Mỗi pod có Caffeine cache local. Hot key phục vụ từ L1 (100ns), chỉ miss L1 mới xuống Redis/ES.

```
                   10 pods K8s
    ┌──────────┐  ┌──────────┐  ┌──────────┐
    │  Pod 1   │  │  Pod 2   │  │  Pod 3   │
    │ Caffeine │  │ Caffeine │  │ Caffeine │  ← L1: in-JVM, 30s TTL, ~100ns
    └────┬─────┘  └────┬─────┘  └────┬─────┘
         └──────────────┼──────────────┘
                        │ chỉ L1 miss mới xuống
                        ▼
                 ┌──────────────┐
                 │  Redis (L2)  │  ← 10 min TTL, ~1ms
                 └──────┬───────┘
                        │ chỉ L2 miss mới xuống
                        ▼
                 ┌──────────────┐
                 │ Elasticsearch│  ← Tối đa 10 queries/30s (1 per pod)
                 └──────────────┘
```

```java
@Configuration
public class CacheConfig {
    @Bean
    public Caffeine<Object, Object> caffeineSpec() {
        return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)  // L1 TTL: 30s
            .recordStats();  // Metrics → Prometheus
    }
    @Bean
    public CacheManager caffeineCacheManager(Caffeine<Object, Object> caffeine) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(caffeine);
        return manager;
    }
}
```

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanQueryService {

    private final LoanApplicationSearchRepository elasticsearchRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheManager caffeineCacheManager;
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);

    @SuppressWarnings("unchecked")
    public List<LoanApplicationDocument> findLoansByCustomerCached(String customerId) {
        String cacheKey = "customerLoansES:" + customerId;

        // ① L1: Caffeine (~100ns)
        Cache l1 = caffeineCacheManager.getCache("customerLoansL1");
        Cache.ValueWrapper l1Hit = l1 != null ? l1.get(customerId) : null;
        if (l1Hit != null) {
            log.debug("⚡ [L1 HIT] Caffeine: {}", customerId);
            return (List<LoanApplicationDocument>) l1Hit.get();
        }

        // ② L2: Redis (~1ms)
        Object l2Hit = redisTemplate.opsForValue().get(cacheKey);
        if (l2Hit != null) {
            log.debug("🟡 [L2 HIT] Redis: {}", customerId);
            List<LoanApplicationDocument> result = (List<LoanApplicationDocument>) l2Hit;
            if (l1 != null) l1.put(customerId, result);  // Backfill L1
            return result;
        }

        // ③ Full miss → query ES → populate cả 2 tầng
        log.info("🔴 [CACHE MISS] Querying Elasticsearch: {}", customerId);
        List<LoanApplicationDocument> result =
            elasticsearchRepository.findByCustomerId(customerId);
        if (!result.isEmpty()) {
            if (l1 != null) l1.put(customerId, result);
            redisTemplate.opsForValue().set(cacheKey, result, REDIS_TTL);
        }
        return result;
    }
}
```

**Kết quả với 1M requests trên 10 pods:**

```
Không có L1:
  1M requests → 1M Redis lookups → (miss) → 1M ES queries

Có L1 (30s TTL, 10 pods):
  Request 1 → Pod-1: L1 miss → L2 miss → ES query → populate L1+L2
  Request 2..100K → Pod-1 trong 30s: L1 HIT → 0 Redis, 0 ES
  Request → Pod-2: L1 miss → L2 HIT → populate L1 Pod-2
  Request → Pod-3..10: tương tự

  ES thực sự nhận: 1 query
  Redis nhận:      10 queries (1 per pod)
  Caffeine phục vụ: 999,990 queries (~100ns mỗi cái)
```

**Nhược điểm:** L1 stale risk — khi data thay đổi, mỗi pod có thể giữ version khác nhau trong 30s. Chấp nhận được cho read-heavy data ít thay đổi.

---

## 13. So sánh tổng hợp & Khuyến nghị

### So sánh 5 giải pháp

| Giải pháp | ES Queries | Threads blocked | Latency P99 | Giải quyết cold start | Complexity |
|---|---|---|---|---|---|
| **Không có gì** | N (stampede) | 0 | 5000ms+ (HikariCP timeout) | ❌ | None |
| **TTL Jitter** | Vẫn có đợt nhỏ | 0 | Thấp hơn | ❌ | Rất thấp |
| **Mutex Lock** | 1 | 999,999 (50ms) | 50ms | ✅ | Thấp |
| **Stale-While-Rev** | 1 (async) | 0 | ~1ms (stale) | ❌ cold | Trung bình |
| **Single-Flight** | 1 | 0 (join future) | 50ms (đồng đều) | ✅ | Trung bình |
| **Two-Level Cache** | 1 (per pod) | 0 | ~100ns (L1 hit) | ✅ (sau pod init) | Trung bình |

### Recommended Stack cho project

```
Kết hợp: TTL Jitter + Single-Flight + L1 Caffeine

Request
  │
  ▼
L1 Caffeine (30s TTL) ──HIT──► trả ngay (~100ns)
  │
  MISS
  ▼
L2 Redis (8–12 min jitter TTL) ──HIT──► backfill L1 → trả (~1ms)
  │
  MISS
  ▼
Single-Flight registry
  ├── Future tồn tại? → join() chờ kết quả (suspend virtual thread)
  └── Không có future? → tạo Future → query ES (1 lần duy nhất)
                       → populate Redis + Caffeine
                       → tất cả joiners nhận kết quả đồng thời
```

- **L1 Caffeine** → loại bỏ 99% requests, không đến Redis
- **TTL Jitter** → trải đều synchronized expiry
- **Single-Flight** → đảm bảo ES hit đúng 1 lần dù cold start
- **Virtual Threads** (đã enable) → `join()` gần như free về chi phí OS

### Priority implement

| # | Giải pháp | Effort | Impact | Ghi chú |
|---|---|---|---|---|
| 1 | TTL Jitter | 5 dòng code | Cao | Làm ngay — zero risk |
| 2 | Single-Flight | 1 class mới | Rất cao | Thay thế hoàn toàn Mutex |
| 3 | L1 Caffeine | 2 files | Rất cao | Cần thêm caffeine dependency |
| 4 | Stale-While-Rev | 1 class sửa | Trung bình | Nếu eventual consistency OK |
| 5 | Cache Warming | 1 job class | Trung bình | Cần biết top hot keys |

### HikariCP tuning (làm ngay)

```yaml
# loan-service/application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      connection-timeout: 1000    # Từ 5000ms → 1000ms: fail fast hơn
      keepalive-time: 30000
      pool-name: LoanServicePool
```

---

> **Rule of thumb:**
> - TTL Jitter → chống synchronized expiry (daily traffic)
> - Single-Flight → chống cold start (restart/deploy)
> - L1 Caffeine → chống hot key (viral loan product, peak hours)
> - Không cần tất cả — chọn theo threat model của hệ thống
