# Cache & DB — Taxonomy đầy đủ: Read Heavy vs Write Heavy

> Tổng kết và correction cho framework phân tích cache/DB problems.
> Bao gồm: single key, multiple key, distributed lock, batch, Kafka integration.

---

## Correction trước khi đi vào chi tiết

| Ý kiến của bạn | Đánh giá | Correction |
|---|---|---|
| Chia thành Read Heavy / Write Heavy | ✅ Đúng | Giữ nguyên |
| Chia tiếp thành Single Key / Multiple Key | ✅ Đúng hướng | Cần thêm chiều **Hot vs Cold** |
| Single Key → Distributed Lock | ⚠️ Đúng một nửa | Lock chỉ dùng cho **cache miss stampede**, không phải cho mọi read/write single key. High-throughput single key cần giải pháp khác |
| Read Multiple Key → chưa có phương án | ✅ Cần bổ sung | Pipeline, MGET, Bulk Cache-Aside, Denormalization |
| Write Multiple Key → Batch Processing | ✅ Đúng nhưng chưa đủ | Thêm: Lua Script (atomicity), Kafka Fan-out (durability), Pipeline |

---

## Framework hoàn chỉnh

```
                        CACHE / DB PROBLEMS
                               │
              ┌────────────────┴────────────────┐
              │                                  │
         READ HEAVY                         WRITE HEAVY
              │                                  │
    ┌─────────┴──────────┐          ┌────────────┴───────────┐
    │                    │          │                         │
 Single Key          Multiple       Single Key            Multiple
 (Hot Key)            Keys          (Hot Write)             Keys
    │                    │          │                         │
  Stampede            N roundtrip  Throughput             Consistency
  Contention          Scatter-     per key                + Atomicity
  problem             gather       bottleneck             across keys
    │                    │          │                         │
  ─────               ──────      ──────                  ──────
  Lock (miss)         Pipeline    Atomic Ops              Pipeline
  Single-Flight       MGET        Counter Shard           Lua Script
  Local Cache (L1)    Bulk Load   Local Aggregation       Kafka Fan-out
  Key Replication     Denorm.     Write-Behind+Kafka      Batch DB write
```

---

## PHẦN 1: READ HEAVY

### 1A. Single Key — Hot Key Problem

**Vấn đề:** Một key duy nhất nhận hàng triệu reads (trending post, hot product, global config).
Distributed Lock chỉ giải quyết được **cache miss stampede** — không phải reads thông thường.

```
Scenario:
  Key "product:iphone-16" → 1M reads/giây
  Redis read throughput: ~100K ops/s/node
  → Redis bị overwhelm dù data đã có trong cache

  Distributed Lock ở đây KHÔNG giúp được gì
  vì data đã có trong cache — không có miss
  Lock chỉ dùng khi: cache miss → nhiều thread cùng recompute
```

#### Giải pháp 1: Local L1 Cache (Caffeine) — Hiệu quả nhất

```java
// Mỗi JVM pod tự cache hot key trong bộ nhớ local
// 10 pods × 100K reads/s/pod = 1M reads/s, Redis chỉ nhận 10 reads/30s

@Service
public class ProductService {
    // L1: Caffeine — in-JVM, 30s TTL, zero network
    private final LoadingCache<String, Product> localCache = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .build(key -> loadFromRedisOrDB(key));  // Auto-load on miss

    public Product getProduct(String productId) {
        return localCache.get(productId);  // ~100ns, không qua network
    }
}
```

```
Traffic flow với L1:
  1M reads/s ──► L1 Caffeine (10 pods)
                    │ 99.999% HIT (<1ms, no network)
                    │ 0.001% MISS (30s TTL expire)
                    ▼
                 Redis L2 (~1ms)
                    │ backfill L1
                    │ if miss → DB
                    ▼
                 DB (chỉ nhận ~10 queries/30s)
```

#### Giải pháp 2: Key Replication — Spread load across Redis nodes

```java
// Hot key → nhân bản ra N copies → đọc random → tải được phân tán
// Phù hợp khi Redis cluster bị bottleneck ở 1 node

@Service
public class HotKeyService {
    private static final int REPLICA_COUNT = 10;
    private final RedisTemplate<String, Object> redisTemplate;

    // Write: ghi vào tất cả replicas
    public void setHotKey(String key, Object value, Duration ttl) {
        for (int i = 0; i < REPLICA_COUNT; i++) {
            redisTemplate.opsForValue().set(key + ":replica:" + i, value, ttl);
        }
    }

    // Read: random pick một replica → phân tán tải đều 10 nodes
    public Object getHotKey(String key) {
        int shard = ThreadLocalRandom.current().nextInt(REPLICA_COUNT);
        return redisTemplate.opsForValue().get(key + ":replica:" + shard);
    }
}

// Kết quả: 1M reads/s → 100K reads/s mỗi Redis node (manageable)
// Trade-off: Write phải ghi 10 lần (acceptable nếu write ít, read nhiều)
```

#### Giải pháp 3: Single-Flight (cho cache miss stampede)

```
Distributed Lock vs Single-Flight — khi nào dùng cái nào:

  Distributed Lock:
  ├── Use case: Distributed system, nhiều pods cùng detect miss
  ├── Cost: Network roundtrip cho lock acquire/release
  ├── Problem: 999,999 threads BLOCKED chờ lock holder
  └── Dùng khi: Cross-pod coordination cần thiết

  Single-Flight (ConcurrentHashMap + CompletableFuture):
  ├── Use case: Trong cùng 1 pod, nhiều threads cùng detect miss
  ├── Cost: Gần zero (in-memory map lookup)
  ├── Advantage: Threads SUBSCRIBE (không block), wake up cùng lúc
  └── Dùng khi: Stampede trong cùng JVM instance

  Best practice: Single-Flight per pod + L1 Cache = ít cần Lock nhất
```

---

### 1B. Multiple Keys — Scatter-Gather Problem

**Vấn đề:** 1 request cần đọc N keys (user profile + N friends' data, order + N line items).
Đọc tuần tự = N network roundtrips = latency × N.

```
Vấn đề N+1 reads:

  Request: "Lấy dashboard cho user 123"
  ├── GET user:123           → 1ms
  ├── GET account:123        → 1ms
  ├── GET loan:active:123    → 1ms
  ├── GET credit-score:123   → 1ms
  └── GET kyc-status:123     → 1ms
  Total: 5ms sequential = 5 roundtrips

  → Với 1000 requests/s = 5000 Redis ops/s chỉ cho 1 user type
```

#### Giải pháp 1: Redis Pipeline — Batch nhiều commands, 1 roundtrip

```java
// Pipeline: gửi N commands trong 1 network call, nhận N responses cùng lúc
// Không atomic — các command độc lập nhau

@Service
public class DashboardService {

    public UserDashboard getDashboard(String userId) {
        List<Object> results = redisTemplate.executePipelined(connection -> {
            StringRedisConnection sc = (StringRedisConnection) connection;
            sc.get("user:" + userId);          // Command 1
            sc.get("account:" + userId);       // Command 2
            sc.get("loan:active:" + userId);   // Command 3
            sc.get("credit-score:" + userId);  // Command 4
            sc.get("kyc-status:" + userId);    // Command 5
            return null;  // Pipeline không return ở đây
        });
        // results[0..4] = responses cho 5 commands trên
        // Latency: 1 roundtrip thay vì 5

        return buildDashboard(results);
    }
}
```

```
Pipeline vs Sequential:

  Sequential:  ──cmd1──► ◄──r1── ──cmd2──► ◄──r2── ... = 5ms (5 roundtrips)
  Pipeline:    ──cmd1──cmd2──cmd3──cmd4──cmd5──► ◄──r1─r2─r3─r4─r5── = 1ms
                └──────── 1 roundtrip ────────┘

  Tiết kiệm: 4ms latency, giảm 80% Redis ops overhead
```

#### Giải pháp 2: Redis MGET — Atomic batch read

```java
// MGET: atomic, đọc nhiều keys cùng lúc
// Khác Pipeline: MGET là 1 command duy nhất (atomic)
// Phù hợp khi tất cả keys cùng type (đều là String)

public List<Product> getProducts(List<String> productIds) {
    // Tạo list keys
    List<String> keys = productIds.stream()
        .map(id -> "product:" + id)
        .collect(Collectors.toList());

    // 1 Redis roundtrip, nhận tất cả values
    List<Object> cached = redisTemplate.opsForValue().multiGet(keys);

    // Tìm các keys bị miss
    List<String> missedIds = new ArrayList<>();
    Map<String, Product> result = new HashMap<>();

    for (int i = 0; i < productIds.size(); i++) {
        if (cached.get(i) != null) {
            result.put(productIds.get(i), (Product) cached.get(i));
        } else {
            missedIds.add(productIds.get(i));
        }
    }

    // Bulk fetch missed keys từ DB (1 query với IN clause)
    if (!missedIds.isEmpty()) {
        List<Product> fromDB = productRepository.findAllById(missedIds);
        fromDB.forEach(p -> {
            result.put(p.getId(), p);
            redisTemplate.opsForValue().set("product:" + p.getId(), p, Duration.ofMinutes(10));
        });
    }

    return new ArrayList<>(result.values());
}
```

#### Giải pháp 3: Denormalization — Pre-aggregate vào 1 key

```java
// Thay vì đọc 5 keys, gộp tất cả vào 1 key "dashboard:userId"
// Trade-off: Write phức tạp hơn (phải update dashboard key mỗi khi 1 trong 5 source thay đổi)

// Write: khi bất kỳ source nào thay đổi → rebuild dashboard cache
@EventListener
public void onAccountUpdated(AccountUpdatedEvent event) {
    rebuildDashboardCache(event.getUserId());
}

@EventListener
public void onLoanUpdated(LoanUpdatedEvent event) {
    rebuildDashboardCache(event.getUserId());
}

private void rebuildDashboardCache(String userId) {
    UserDashboard dashboard = dashboardBuilder.build(userId);  // Query all sources
    redisTemplate.opsForValue().set("dashboard:" + userId, dashboard, Duration.ofMinutes(5));
}

// Read: 1 key duy nhất
public UserDashboard getDashboard(String userId) {
    UserDashboard cached = (UserDashboard) redisTemplate.opsForValue().get("dashboard:" + userId);
    if (cached != null) return cached;
    // Miss → build + cache
    UserDashboard dashboard = dashboardBuilder.build(userId);
    redisTemplate.opsForValue().set("dashboard:" + userId, dashboard, Duration.ofMinutes(5));
    return dashboard;
}
```

---

## PHẦN 2: WRITE HEAVY

### 2A. Single Key — Hot Write Problem

**Vấn đề:** Một key nhận hàng triệu writes (view counter, like counter, real-time inventory).
Distributed Lock ở đây = **serialization bottleneck** — tệ hơn không có lock.

```
Vấn đề với Distributed Lock cho write:

  1M writes/s trên key "counter:video-123:views"
  Lock acquire:  ~2ms (Redis roundtrip)
  Lock hold:     ~1ms (INCR + release)
  Total per write: ~3ms

  Throughput = 1000ms / 3ms = ~333 writes/s per lock
  → Cần 1M/333 = ~3000 locks để handle 1M/s
  → Không scale, lock contention cực cao
```

#### Giải pháp 1: Redis Atomic Operations — Không cần lock

```java
// Redis INCR/INCRBY là ATOMIC bởi thiết kế
// Không cần lock — Redis single-threaded command execution đảm bảo atomicity

@Service
public class CounterService {

    // Atomic increment — thread-safe, không cần lock
    public long incrementView(String videoId) {
        return redisTemplate.opsForValue().increment("views:" + videoId);
    }

    // Atomic increment by N
    public long addScore(String playerId, long points) {
        return redisTemplate.opsForValue().increment("score:" + playerId, points);
    }

    // Atomic field increment trong Hash
    public long incrementField(String key, String field) {
        return redisTemplate.opsForHash().increment(key, field, 1);
    }
}
// Throughput: ~100K-500K INCR/s per Redis node — không có lock overhead
```

#### Giải pháp 2: Counter Sharding — Scale beyond 1 Redis node

```java
// 1 counter → chia thành N shards → ghi random → đọc aggregate
// Viết 1M/s → 100K/s per shard (10 shards) → mỗi shard chịu được

@Service
public class ShardedCounterService {
    private static final int SHARD_COUNT = 10;

    // Write: random shard → phân tán tải đều
    public void increment(String counterId) {
        int shard = ThreadLocalRandom.current().nextInt(SHARD_COUNT);
        redisTemplate.opsForValue().increment(counterId + ":shard:" + shard);
    }

    // Read: aggregate tất cả shards (đọc ít hơn viết → acceptable)
    public long getTotal(String counterId) {
        List<String> shardKeys = IntStream.range(0, SHARD_COUNT)
            .mapToObj(i -> counterId + ":shard:" + i)
            .collect(Collectors.toList());

        List<Object> values = redisTemplate.opsForValue().multiGet(shardKeys);
        return values.stream()
            .filter(Objects::nonNull)
            .mapToLong(v -> Long.parseLong(v.toString()))
            .sum();
    }
}

// Trade-off: Read phải sum N shards → latency cao hơn cho reads
// Best for: write-heavy counters (view count, click count) — read ít hơn write
```

#### Giải pháp 3: Local Aggregation + Periodic Flush — Giảm Redis ops

```java
// Gộp nhiều increments tại local JVM → flush Redis mỗi 1 giây
// 1M writes/s → ~1K Redis writes/s (1000x reduction)

@Service
public class LocalAggregationService {

    // ConcurrentHashMap: thread-safe, atomic merge
    private final ConcurrentHashMap<String, LongAdder> localCounters =
        new ConcurrentHashMap<>();

    // Ghi local — không đụng Redis, không đụng network
    public void increment(String key) {
        localCounters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    // Flush lên Redis + DB mỗi 1 giây (batch)
    @Scheduled(fixedDelay = 1_000)
    public void flush() {
        if (localCounters.isEmpty()) return;

        // Swap ra map mới — giải phóng local accumulator
        Map<String, LongAdder> snapshot = new HashMap<>(localCounters);
        localCounters.clear();

        // Batch INCRBY lên Redis
        redisTemplate.executePipelined(connection -> {
            snapshot.forEach((key, adder) -> {
                long delta = adder.sumThenReset();
                if (delta > 0) {
                    ((StringRedisConnection) connection)
                        .incrBy(key, delta);
                }
            });
            return null;
        });
    }
}

// Trade-off: Mất tối đa 1 giây data nếu pod crash
// Acceptable cho: view counts, click counts, analytics
// NOT acceptable cho: inventory (mất 1s stock → oversell)
```

---

### 2B. Multiple Keys — Fan-out Write Problem

**Vấn đề:** 1 event kích hoạt writes lên N keys (user posts → update N followers' feeds,
price change → update N caches, order placed → update inventory + stats + user history).

```
Fan-out write problem:

  User A (10M followers) posts status
  → Cần update 10M feed keys
  → 10M Redis writes × 1ms = 10,000 giây
  → Không thể synchronous

  Price update product P
  → Invalidate cache trong: product detail, search index, cart, wishlist, recommendations
  → 5 keys × N users đang xem product P
```

#### Giải pháp 1: Redis Pipeline — Batch writes, 1 roundtrip

```java
// Ghi nhiều keys trong 1 network roundtrip
// Không atomic — nếu Redis fail giữa pipeline, một số keys được ghi, một số không

@Service
public class CacheInvalidationService {

    // Invalidate tất cả cache liên quan khi product thay đổi
    public void invalidateProductCaches(String productId, List<String> affectedUserIds) {
        redisTemplate.executePipelined(connection -> {
            StringRedisConnection sc = (StringRedisConnection) connection;
            // Product detail cache
            sc.del("product:" + productId);
            // Cart caches của users đang có product này trong cart
            affectedUserIds.forEach(userId ->
                sc.del("cart:" + userId));
            // Search index cache
            sc.del("search:product-index");
            return null;
        });
        // 1 roundtrip thay vì (2 + N) roundtrips
    }
}
```

#### Giải pháp 2: Lua Script — Atomic multi-key write

```java
// Lua script chạy atomic trên Redis server
// Đảm bảo: tất cả keys được update hoặc không key nào
// Phù hợp khi cần consistency across multiple keys

@Service
public class AtomicMultiKeyService {

    private static final String TRANSFER_SCRIPT = """
        local fromKey = KEYS[1]
        local toKey   = KEYS[2]
        local amount  = tonumber(ARGV[1])

        local fromBal = tonumber(redis.call('GET', fromKey) or '0')
        if fromBal < amount then
            return redis.error_reply('INSUFFICIENT_BALANCE')
        end

        redis.call('DECRBY', fromKey, amount)
        redis.call('INCRBY', toKey, amount)
        return 'OK'
        """;

    public void transferPoints(String fromUser, String toUser, long amount) {
        redisTemplate.execute(
            new DefaultRedisScript<>(TRANSFER_SCRIPT, String.class),
            List.of("points:" + fromUser, "points:" + toUser),
            String.valueOf(amount)
        );
        // Atomic: debit + credit xảy ra cùng lúc hoặc cả 2 fail
        // Không cần distributed lock
    }
}
```

#### Giải pháp 3: Kafka Fan-out — Async distributed write

```java
// 1 event → Kafka → N consumers độc lập update N keys
// Phù hợp khi: fan-out lớn (1M+ keys), eventual consistency OK

// Producer: chỉ publish 1 event
@Service
public class PostService {
    public void createPost(Post post) {
        postRepository.save(post);  // DB
        kafkaTemplate.send("post-created", post.getUserId(), post);  // 1 Kafka message
        // Không tự mình update N follower feeds
    }
}

// Consumer 1: Update follower feed caches (fan-out)
@KafkaListener(topics = "post-created", groupId = "feed-cache-updater")
public void updateFollowerFeeds(Post post) {
    List<String> followerIds = socialGraphService.getFollowers(post.getUserId());
    // Batch pipeline để update all follower caches
    redisTemplate.executePipelined(connection -> {
        followerIds.forEach(followerId -> {
            String feedKey = "feed:" + followerId;
            ((StringRedisConnection) connection).lPush(feedKey, post.getId());
            ((StringRedisConnection) connection).lTrim(feedKey, 0, 999); // Keep last 1000
        });
        return null;
    });
}

// Consumer 2: Update search index
@KafkaListener(topics = "post-created", groupId = "search-index-updater")
public void updateSearchIndex(Post post) {
    elasticsearchService.index(post);
}

// Ưu điểm: Mỗi consumer xử lý độc lập, có thể scale riêng
// Trade-off: Eventual consistency — followers thấy post sau vài ms-seconds
```

#### Giải pháp 4: Write-Behind với Batch DB Flush

```java
// Accumulate writes in Redis → batch flush DB mỗi N giây
// Giảm DB write load từ 1M/s xuống còn batch size/interval

@Service
public class BatchWriteService {

    // Stage writes vào Redis List/Set
    public void stageWrite(String entityId, Object data) {
        // Dùng Redis Hash để deduplicate (chỉ giữ latest value per key)
        redisTemplate.opsForHash().put("write-buffer:entities", entityId, data);
    }

    // Flush mỗi 5 giây — batch DB write
    @Scheduled(fixedDelay = 5_000)
    @SchedulerLock(name = "batchFlush", lockAtLeastFor = "1s", lockAtMostFor = "10s")
    public void flushToDB() {
        // Lấy tất cả staged writes
        Map<Object, Object> buffer = redisTemplate.opsForHash()
            .entries("write-buffer:entities");
        if (buffer.isEmpty()) return;

        // Xoá buffer trước (atomic delete → refill là OK)
        redisTemplate.delete("write-buffer:entities");

        // Batch upsert DB
        List<Entity> entities = buffer.entrySet().stream()
            .map(e -> deserialize(e.getKey(), e.getValue()))
            .collect(Collectors.toList());
        entityRepository.saveAll(entities);  // 1 batch INSERT/UPDATE
    }
}
```

---

## PHẦN 3: Kafka + Redis — Kiến trúc phối hợp

### Tại sao cần Kafka bên cạnh Redis

```
Redis đứng độc lập — giới hạn:
  ┌────────────────┬─────────────────────────────────────────┐
  │ Pattern        │ Vấn đề                                  │
  ├────────────────┼─────────────────────────────────────────┤
  │ Write-Through  │ Write amplification × 2, không scale    │
  │ Write-Behind   │ Data loss risk nếu Redis crash           │
  │ Cache-Aside    │ Stampede, cold start                    │
  │ Hot key        │ Single node bottleneck                  │
  └────────────────┴─────────────────────────────────────────┘

Kafka bổ sung những gì Redis thiếu:
  ┌──────────────────┬────────────┬────────────┐
  │ Capability       │ Redis      │ Kafka      │
  ├──────────────────┼────────────┼────────────┤
  │ Persistence      │ Volatile   │ Durable    │
  │ Replay           │ ❌         │ ✅         │
  │ Ordering         │ ❌         │ ✅ per key │
  │ Backpressure     │ ❌         │ ✅         │
  │ Fan-out          │ Manual     │ ✅ native  │
  │ Read speed       │ ✅ <1ms    │ ❌ ~10ms   │
  └──────────────────┴────────────┴────────────┘
```

### Pattern tối ưu: Kafka (write) + Redis (read)

```
WRITE PATH                         READ PATH
─────────────────────────────────  ──────────────────────────
Client                             Client
  │ Command                          │ Query
  ▼                                  ▼
API ──► Kafka ──► Consumer        API ──► Redis ──HIT──► ~1ms
         (durable)   │                      │
                     ├──► DB              MISS
                     └──► Redis           │
                          (sync           ▼
                          read model)   DB → backfill Redis

Mỗi layer có trách nhiệm rõ ràng:
  Kafka  = Write durability, ordering, backpressure, fan-out
  DB     = Source of truth, complex query, audit
  Redis  = Read acceleration, session, idempotency keys
```

---

## PHẦN 4: Bảng tổng hợp — Chọn giải pháp nào

### Ma trận quyết định

```
                     SINGLE KEY              MULTIPLE KEYS
                ─────────────────────    ─────────────────────
READ HEAVY      │ L1 Cache (Caffeine) │  │ Pipeline / MGET    │
                │ Single-Flight       │  │ Bulk Cache-Aside   │
                │ Key Replication     │  │ Denormalization    │
                │ Dist. Lock*         │  │ DataLoader pattern │
                └─────────────────────┘  └────────────────────┘
                    *chỉ cho miss

WRITE HEAVY     │ Redis Atomic Ops    │  │ Pipeline (MSET)    │
                │ Counter Sharding    │  │ Lua Script         │
                │ Local Aggregation   │  │ Kafka Fan-out      │
                │                     │  │ Batch DB write     │
                └─────────────────────┘  └────────────────────┘
```

### Khi nào dùng Distributed Lock

```
Distributed Lock chỉ phù hợp cho:

  ✅ Cache miss stampede — 1 thread recompute, others wait
  ✅ Coordinated single-time action (e.g., chỉ 1 pod send email)
  ✅ Scheduled job deduplication (ShedLock trong project)
  ✅ Critical section cần mutual exclusion (inventory reserve)

  ❌ KHÔNG dùng cho: High-throughput reads (lock = serialization)
  ❌ KHÔNG dùng cho: Counters (Redis Atomic Ops tốt hơn)
  ❌ KHÔNG dùng cho: Fan-out writes (Kafka tốt hơn)
  ❌ KHÔNG dùng cho: Batch operations (Pipeline tốt hơn)
```

### Optimal stack theo bài toán

| Bài toán | Optimal Solution | Lý do |
|---|---|---|
| Read single hot key | L1 Caffeine + Single-Flight | Zero network, deduplicate miss recompute |
| Read multiple keys | Pipeline + Bulk Cache-Aside | 1 roundtrip thay vì N |
| Read fan-out (social feed) | Denormalization (pre-compute) | 1 key thay vì N keys |
| Write single counter | Redis INCR (atomic) | Lock-free, sub-ms, 500K ops/s |
| Write single high-volume | Local Aggregation + flush | 1000x Redis ops reduction |
| Write multiple keys (atomic) | Lua Script | Atomic multi-key, no lock |
| Write fan-out (N followers) | Kafka → async consumers | Decoupled, scalable, durable |
| Write-heavy + DB sync | Kafka + batch consumer | Backpressure, no data loss |
| Read/Write + durability | Kafka (write) + Redis (read) | Best of both worlds |

---

## PHẦN 5: Áp dụng vào project

```
Hiện tại project đã có:
  ✅ Write-Through: SagaCacheService (DB first → Redis)
  ✅ Cache-Aside: LoanQueryService (@Cacheable + Elasticsearch)
  ✅ Distributed Lock: ShedLock cho scheduled jobs
  ✅ Kafka: Event bus cho tất cả services
  ✅ Redis SETNX: Idempotency keys (đúng use case)

Cần bổ sung:
  1. Read multiple keys → Pipeline cho dashboard queries
     (hiện tại: N sequential Redis reads)

  2. Hot write counter → Redis INCR atomic
     (ví dụ: loan application count, API call metrics)

  3. Fan-out invalidation → Kafka event → cache invalidation consumer
     (hiện tại: manual delete key khi data thay đổi)

  4. Local L1 Cache (Caffeine) cho hot read keys
     (dependency đã có trong Spring ecosystem, chỉ cần config)
```

---

> **Nguyên tắc vàng:**
>
> `Kafka` = Write path ownership (durability, ordering, backpressure)
>
> `Redis` = Read path acceleration (speed, volatile, single-purpose)
>
> `Distributed Lock` = Coordination primitive, KHÔNG phải throughput solution
>
> `Atomic Ops` = High-throughput single key writes (INCR, không cần lock)
>
> `Pipeline/MGET` = Multiple key reads/writes (1 roundtrip, không phải N)
