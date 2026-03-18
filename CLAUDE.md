# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assemble                # compile all modules
./gradlew check                   # all tests + checks
./gradlew caffeine:test           # core module tests
./gradlew caffeine:test --tests "com.github.benmanes.caffeine.cache.SomeTest"  # single test class
./gradlew caffeine:test --tests "*SomeTest.someMethod"                          # single test method
```

Other test suites: `lincheckTest`, `fuzzTest`, `jctoolsTest`, `jsr166Test`, `googleTest`, `apacheTest`, `eclipseTest`, `osgiTest`, `moduleTest`.

Static analysis (opt-in flags): `-Ppmd`, `-Pspotbugs`, `-PforbiddenApis`, `-Prevapi`.

## Architecture

**Caffeine** is a high-performance Java caching library. Gradle multi-module (Kotlin DSL), 4 subprojects:

- **`caffeine/`** — Core library (`com.github.benmanes.caffeine.cache`)
- **`guava/`** — Guava-compatible adapter
- **`jcache/`** — JSR-107 (JCache) adapter
- **`simulator/`** — Cache eviction policy simulator/benchmarking

### Core Abstractions (`caffeine/src/main/java/.../cache/`)

- `Caffeine` — Builder entry point (`Caffeine.newBuilder()`)
- `Cache`, `LoadingCache`, `AsyncCache`, `AsyncLoadingCache` — public API interfaces
- `BoundedLocalCache` — main implementation (W-TinyLFU eviction)
- `UnboundedLocalCache` — simple ConcurrentHashMap wrapper (no eviction)
- `FrequencySketch` — Count-Min Sketch for frequency estimation
- `TimerWheel` — hierarchical timer wheel for expiration
- `BoundedBuffer`/`StripedBuffer`/`MpscGrowableArrayQueue` — lock-free ring buffers

### Code Generation

The `javaPoet/` source set generates `BoundedLocalCache` subclass variants — combinatorial feature permutations (expiry, max-size, weak/soft refs, stats) to avoid runtime branching. This is a key design pattern.

### Simulator (`simulator/`)

Cache eviction policy simulator that replays access traces against configurable policies and reports hit rates. Uses HOCON config (Typesafe Config), actor-based parallel execution, and picocli CLI.

#### Running

```bash
./gradlew simulator:run                    # run with application.conf settings
./gradlew simulator:simulate -q \          # multi-size sweep with chart output
  --maximumSize=100,500,1_000 \
  --title=MyTrace \
  --outputDir=build/reports
```

System properties override config: `-Dcaffeine.simulator.maximum-size=1024`, `-Dcaffeine.simulator.policies.0=linked.Lru`.

#### Entry Points

- `Simulator` — main class. Reads config, instantiates policies via `Registry`, broadcasts trace events through `PolicyActor`s, prints report.
- `Simulate` — picocli command for multi-size sweeps. Runs `Simulator` at each size, combines CSV reports, renders chart (JFreeChart).

#### Configuration

HOCON-based. `reference.conf` has all defaults; `application.conf` overrides.

Key settings in `caffeine.simulator`:
- `maximum-size` — cache capacity
- `policies` — list of policies to evaluate (e.g., `linked.Lru`, `sketch.WindowTinyLfu`)
- `trace.source` — `files` or `synthetic`
- `files.paths` / `files.format` — trace file paths and format
- `synthetic.distribution` — generator type (zipfian, uniform, hotspot, etc.)
- `report.format` — `table` or `csv`
- `report.output` — `console` or file path
- `admission` — admission filter (Always, TinyLfu, Clairvoyant)

#### Policy System

All policies implement `Policy` interface (`record(AccessEvent)` + `stats()`). Registered in `Registry` via `@PolicySpec(name=...)` annotation. `KeyOnlyPolicy` sub-interface for policies that only need the key.

**Policy categories** (`policy/` subdirectories):

| Package | Policies | Algorithms |
|---------|----------|------------|
| `opt` | Unbounded, Clairvoyant | Upper bounds (no eviction, Belady's optimal) |
| `linked` | LinkedPolicy (Lru/Mru/Lfu/Mfu/Fifo/Clock), Sieve, S4Lru, SegmentedLru, MultiQueue, FrequentlyUsed | Linked-list based eviction |
| `sampled` | SampledPolicy (Lru/Mru/Lfu/Mfu/Fifo/Random/Hyperbolic) | Random-sampling based eviction |
| `sketch` | WindowTinyLfu, HillClimberWindowTinyLfu, FeedbackTinyLfu, FeedbackWindowTinyLfu, TinyCache variants | W-TinyLFU and sketch-based variants |
| `sketch/climbing` | SimpleClimber, SimulatedAnnealing, SGD, Adam, Nadam, AmsGrad, IndicatorClimber, MiniSim | Adaptive window sizing strategies |
| `irr` | Lirs, ClockPro, ClockProPlus, ClockProSimple, DClock, Frd, HillClimberFrd, IndicatorFrd | Inter-reference recency based |
| `adaptive` | Arc, Car, Cart | ARC-family adaptive algorithms |
| `two_queue` | TwoQueue, TuQueue, S3Fifo | Two-queue / multi-queue variants |
| `greedy_dual` | Camp, Gdsf, GDWheel | Greedy-Dual size/frequency algorithms |
| `product` | Caffeine, Guava, Cache2k, Ehcache3, Coherence, Hazelcast, TCache, ExpiringMap | Real caching library wrappers |
| `dash` | DashRustPolicy | Dash cache (Rust FFI) |
| `associative` | AssociativeCacheRustPolicy | Associative/set-associative cache (Rust FFI) |

#### Trace Formats

`TraceFormat` enum in `parser/` — 27 formats with dedicated readers:

arc, adapt-size, address, address-penalties, baleen, cache2k, cachelib, camelab, cloud-physics, corda, gl-cache, gradle, lcs_trace, lcs_twitter, lirs, lrb, outbrain, scarab, snia-cambridge, snia-enterprise, snia-k5cloud, snia-object-store, snia-systor, snia-tencent-block, snia-tencent-photo, tragen, twitter, umass-storage, umass-youtube, wikipedia.

Mixed formats supported via `"{format}:{path}"` syntax in `files.paths`.

Bundled sample traces in `src/main/resources/.../parser/` (lirs, address, cache2k, cloud_physics, corda, gradle, scarab, adapt_size).

#### Admission & Frequency Sketches (`admission/`)

- `TinyLfu` — frequency-based admission using Count-Min Sketch
- `Clairvoyant` — optimal admission using future knowledge
- Sketch variants: `CountMin4` (4-bit), `CountMin64` (64-bit), `PerfectFrequency`, `TinyCache`, `RandomRemovalFrequencyTable`
- Reset strategies: periodic, incremental, climber, indicator

#### Reporting

- `PolicyStats` — tracks hits, misses, evictions, execution time, custom metrics
- Report formats: `table` (ASCII table via flip-tables) or `csv` (FastCSV)
- `Simulate` command generates combined CSV + PNG chart across cache sizes

## Code Style

- **TestNG** is the primary test framework
- ErrorProne + NullAway always active during compilation
- `.editorconfig`: 2-space indent, 100-char max line, UTF-8, LF endings
- Compilation uses `-Xlint:all`; CI uses `-Werror`
