package com.github.benmanes.caffeine.cache.simulator.policy.dash;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;

import com.google.common.base.Enums;

import com.typesafe.config.Config;
import java.util.*;
import static java.util.Locale.US;
import static java.lang.System.exit;

@PolicySpec(name = "dash.DashCombinedRust")
public class DashCombinedRustPolicy implements Policy.KeyOnlyPolicy {
  private final long cachePointer;
  private final PolicyStats policyStats;
  private final long debugMode;

  static {
    System.loadLibrary("dash");
  }

  public DashCombinedRustPolicy(DashCombinedSettings settings, DashCombinedEvictionPolicy evictionPolicy) {
    this.policyStats = new PolicyStats(name() + " (%s)", evictionPolicy);
    this.debugMode = settings.debugMode();

    long numOfSegments = settings.numOfSegments();
    long capacity = numOfSegments
        * (settings.numOfNormalBuckets() + settings.numOfStashBuckets())
        * settings.bucketSize();
    if (capacity != settings.maximumSize()) {
      throw new IllegalArgumentException(String.format(
          "Dash topology must fill maximumSize exactly: numOfSegments(%d) * (numOfNormalBuckets(%d) + numOfStashBuckets(%d)) * bucketSize(%d) = %d != maximumSize(%d)",
          numOfSegments, settings.numOfNormalBuckets(), settings.numOfStashBuckets(),
          settings.bucketSize(), capacity, settings.maximumSize()));
    }

    if (this.debugMode > 0) {
      System.out.println("^^^^^^^^^^^^^^^^^^^^^^ DashCombined Constructor ^^^^^^^^^^^^^^^^^^^^^^^^^^");
      System.out.println("maximumSize: " + settings.maximumSize());
      System.out.println("numOfSegments: " + numOfSegments);
      System.out.println("evictionPolicy: " + evictionPolicy);
      System.out.println("tieBreakEvictProbing: " + settings.tieBreakEvictProbing());
    }

    this.cachePointer = initCache(numOfSegments, settings.numOfNormalBuckets(), settings.numOfStashBuckets(),
        settings.bucketSize(), evictionPolicy.rustIndex(), this.debugMode, settings.tieBreakEvictProbing() ? 1 : 0);
    if (this.cachePointer == 0) {
      throw new RuntimeException("Failed to initialize Rust DashCombined cache");
    }
  }

  public static Set<Policy> policies(Config config) {
    var settings = new DashCombinedSettings(config);
    Set<Policy> set = new HashSet<>();
    for (DashCombinedEvictionPolicy policy : settings.evictionPolicies()) {
      set.add(new DashCombinedRustPolicy(settings, policy));
    }
    return set;
  }

  @Override
  public PolicyStats stats() {
    return this.policyStats;
  }

  @Override
  public void record(long key) {
    long value = getFromCacheIfPresent(this.cachePointer, key);
    if (value == -1) {
      putToCache(this.cachePointer, key, key);
      policyStats.recordMiss();
    } else {
      policyStats.recordHit();
      if (key != value) {
        System.out.println("key != value in DashCombined cache");
        exit(1);
      }
    }
  }

  @Override
  public void finished() {
    if (this.debugMode > 0) {
      System.out.println("^^^^^^^^^^^^^^^^^^^^^^ DashCombined Finished ^^^^^^^^^^^^^^^^^^^^^^^^^^");
    }
    dropCache(this.cachePointer);
  }

  /** Combined eviction only supports policies with comparable metadata.
   *  Values match Rust EvictionPolicy::from_usize indices. */
  public enum DashCombinedEvictionPolicy {
    TIMESTAMPLRU(1),
    LFU(3);

    private final int rustIndex;
    DashCombinedEvictionPolicy(int rustIndex) { this.rustIndex = rustIndex; }
    public int rustIndex() { return this.rustIndex; }
  }

  public static final class DashCombinedSettings extends BasicSettings {
    public DashCombinedSettings(Config config) {
      super(config);
    }

    public long numOfSegments() {
      return this.config().getLong("dash.numOfSegments");
    }

    public long numOfNormalBuckets() {
      return this.config().getLong("dash.numOfNormalBuckets");
    }

    public long numOfStashBuckets() {
      return this.config().getLong("dash.numOfStashBuckets");
    }

    public long bucketSize() {
      return this.config().getLong("dash.bucketSize");
    }

    public long debugMode() {
      return this.config().getLong("dash.debugMode");
    }

    public boolean tieBreakEvictProbing() {
      return this.config().getBoolean("dash.tieBreakEvictProbing");
    }

    public Set<DashCombinedEvictionPolicy> evictionPolicies() {
      var policies = EnumSet.noneOf(DashCombinedEvictionPolicy.class);
      for (var policy : config().getStringList("dash.combinedPolicy")) {
        var option = Enums.getIfPresent(DashCombinedEvictionPolicy.class, policy.toUpperCase(US)).toJavaUtil();
        option.ifPresentOrElse(policies::add, () -> {
          throw new IllegalArgumentException("Unknown combined policy: " + policy);
        });
      }
      return policies;
    }
  }

  private static native long initDefaultCache();
  private static native long initCache(long num_of_segments, long num_of_normal_buckets, long num_of_stash_buckets,
      long bucket_size, long eviction_policy, long debug_mode, long tie_break_evict_probing);
  private static native long getFromCacheIfPresent(long cachePointer, long key);
  private static native void putToCache(long cachePointer, long key, long value);
  private static native void dropCache(long cachePointer);
}
