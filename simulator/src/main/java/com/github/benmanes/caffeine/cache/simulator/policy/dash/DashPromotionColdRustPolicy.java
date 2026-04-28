package com.github.benmanes.caffeine.cache.simulator.policy.dash;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;

import com.typesafe.config.Config;
import java.util.*;
import static java.lang.System.exit;

@PolicySpec(name = "dash.DashPromotionColdRust")
public class DashPromotionColdRustPolicy implements Policy.KeyOnlyPolicy {
  private final long cachePointer;
  private final PolicyStats policyStats;
  private final long debugMode;

  static {
    System.loadLibrary("dash");
  }

  public DashPromotionColdRustPolicy(DashPromotionColdSettings settings, String evictionPolicy) {
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
      System.out.println("^^^^^^^^^^^^^^^^^^^^^^ DashPromotionCold Constructor ^^^^^^^^^^^^^^^^^^^^^^^^^^");
      System.out.println("maximumSize: " + settings.maximumSize());
      System.out.println("numOfSegments: " + numOfSegments);
      System.out.println("evictionPolicy: " + evictionPolicy);
    }

    // Strategy 1 only supports ClassicLRU (ordinal 0)
    this.cachePointer = initCache(numOfSegments, settings.numOfNormalBuckets(), settings.numOfStashBuckets(),
        settings.bucketSize(), 0, this.debugMode);
    if (this.cachePointer == 0) {
      throw new RuntimeException("Failed to initialize Rust DashPromotionCold cache");
    }
  }

  public static Set<Policy> policies(Config config) {
    var settings = new DashPromotionColdSettings(config);
    Set<Policy> set = new HashSet<>();
    set.add(new DashPromotionColdRustPolicy(settings, "ClassicLRU"));
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
        System.out.println("key != value in DashPromotionCold cache");
        exit(1);
      }
    }
  }

  @Override
  public void finished() {
    if (this.debugMode > 0) {
      System.out.println("^^^^^^^^^^^^^^^^^^^^^^ DashPromotionCold Finished ^^^^^^^^^^^^^^^^^^^^^^^^^^");
    }
    dropCache(this.cachePointer);
  }

  public static final class DashPromotionColdSettings extends BasicSettings {
    public DashPromotionColdSettings(Config config) {
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
  }

  private static native long initDefaultCache();
  private static native long initCache(long num_of_segments, long num_of_normal_buckets, long num_of_stash_buckets,
      long bucket_size, long eviction_policy, long debug_mode);
  private static native long getFromCacheIfPresent(long cachePointer, long key);
  private static native void putToCache(long cachePointer, long key, long value);
  private static native void dropCache(long cachePointer);
}
