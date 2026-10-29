# PayStream Benchmark History

Comprehensive performance engineering documentation, methodology, before-and-after comparisons, and tuning rationale are recorded in:

👉 **[Week 7 Performance Engineering & Observability Journal](WEEK7_PERFORMANCE.md)**

### Quick Reference: Week 7 Performance Gains

| Optimization Area | Bottleneck Solved | Baseline (Before) | Optimized (After) | Gain / Delta |
|---|---|---|---|:---:|
| **Full Cluster Throughput** | Multi-service cascade | 564 reqs (6.45 req/s) | **5,169 reqs (71.62 req/s)** | **9.16x (+816%) throughput expansion** |
| **Server Crash Rate (500)** | Pool / thread starvation | High under peak stress | **0 (0.00%)** | **100% elimination of 500 errors** |
| **Cache Hit Read Latency** | 5-query aggregate scans | 76.98 ms median | **< 1.0 ms (28.4 ms e2e)** | **> 15x faster internal lookup** |
| **Minimum End-to-End Latency** | Sequential round-trips | 114.20 ms | **28.61 ms** | **4.0x latency drop** |
| **Thread Memory Footprint** | OS kernel stack limits | ~1,024 KB / thread | **< 1 KB / thread** | **99.9% reduction in thread RAM** |
| **Database Connection Load** | Read/write contention | 1 conn / read | **0 conns on cache hit** | **100% pool preservation for writes** |
| **Kafka Event Throughput** | 1-partition serialization | ~10 msgs/s | **~40 msgs/s** | **4x consumer throughput ceiling** |
| **Cluster Memory Footprint** | 14 active containers | Unstable spikes | **3.8 GiB / 5.54 GiB limit** | **Safe within local VM RAM bounds** |
