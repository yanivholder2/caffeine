package com.github.benmanes.caffeine.cache.simulator.policy.associative;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;

import com.google.common.base.Enums;

import com.typesafe.config.Config;
import java.util.*;
import static java.util.Locale.US;
import static java.lang.System.exit;

@PolicySpec(name = "associative.AssociativeCacheRust")
public class AssociativeCacheRustPolicy implements Policy.KeyOnlyPolicy {
  private final long cachePointer;
  private final PolicyStats policyStats;
  private final long debugMode;

  static {
    System.loadLibrary("dash");
  }

  public AssociativeCacheRustPolicy(AssociativeCacheSettings settings, AssociativeCacheRustEvictionPolicy evictionPolicy, int numOfBuckets) {
    long bucketSize = settings.maximumSize() / numOfBuckets;
    this.policyStats = new PolicyStats(name() + " (%s) - %d way", evictionPolicy, numOfBuckets);
    this.debugMode = settings.debugMode();


    if (this.debugMode > 0) {
      System.out.println("^^^^^^^^^^^^^^^^^^^^^^ AssociativeCache Constructor ^^^^^^^^^^^^^^^^^^^^^^^^^^");
      System.out.println("maximumSize: " + settings.maximumSize());
      System.out.println("numOfBuckets: " + settings.numOfBuckets());
      System.out.println("bucketSize: " + bucketSize);
      System.out.println("evictionPolicy: " + evictionPolicy);
    }

    this.cachePointer = associativeInitCache(numOfBuckets, bucketSize, evictionPolicy.ordinal(), this.debugMode);
    if (this.cachePointer == 0) {
      throw new RuntimeException("Failed to initialize Rust AssociativeCache (invalid settings or eviction policy)");
    }
  }

  public static Set<Policy> policies(Config config) {
    var settings = new AssociativeCacheSettings(config);
    Set<Policy> set = new HashSet<>();

    for (AssociativeCacheRustEvictionPolicy policy : settings.evictionPolicies()) {
      for (double num : settings.numOfBuckets()) {
        set.add(new AssociativeCacheRustPolicy(settings, policy, (int)num));
      }
    }

    return set;
  }

  @Override
  public PolicyStats stats() {
    return this.policyStats;
  }

  @Override
  public void record(long key) {
    long value = associativeGetFromCacheIfPresent(this.cachePointer, key);
    if (value == -1) {
      associativePutToCache(this.cachePointer, key, key);
      policyStats.recordMiss();
      if (this.debugMode > 2) {
        System.out.println("key: " + key + " is a miss");
      }
    } else {
      policyStats.recordHit();
      if (this.debugMode > 2) {
        System.out.println("key: " + key + " is a hit");
      }
      if (key != value) {
        System.out.println("key != value in Dash cache");
        exit(1);
      }
    }
  }

  @Override
  public void finished() {
    if (this.debugMode> 0) {
      System.out.println("^^^^^^^^^^^^^^^^^^^^^^ AssociativeCache Finished ^^^^^^^^^^^^^^^^^^^^^^^^^^");
    }
    associativeDropCache(this.cachePointer);
  }

  public enum AssociativeCacheRustEvictionPolicy {
    CLASSICLRU,
    TIMESTAMPLRU,
    LIFO,
    LFU,
    FIFO,
    SWAP;
  }

  public static final class AssociativeCacheSettings extends BasicSettings {
    public AssociativeCacheSettings(Config config) {
      super(config);
    }

    public List<Double> numOfBuckets() {
      return config().getDoubleList("associativeCache.numOfBuckets");
    }

    public long debugMode() {
      return this.config().getLong("associativeCache.debugMode");
    }

    public Set<AssociativeCacheRustEvictionPolicy> evictionPolicies() {
      var policies = EnumSet.noneOf(AssociativeCacheRustEvictionPolicy.class);
      for (var policy : config().getStringList("associativeCache.policy")) {
        var option = Enums.getIfPresent(AssociativeCacheRustEvictionPolicy.class, policy.toUpperCase(US)).toJavaUtil();
        option.ifPresentOrElse(policies::add, () -> {
          throw new IllegalArgumentException("Unknown policy: " + policy);
        });
      }
      return policies;
    }
  }

  /*
   * ---------------------------------------------------------------------------
   * Native (Rust) functions to create and drive Dash cache.
   * ---------------------------------------------------------------------------
   */

  /**
   * Creates the shared singleton instance of the Dash cache with given
   * parameters.
   */
  private static native long associativeInitCache(long num_of_buckets, long bucket_size, long eviction_policy, long debug_mode);

  /**
   * TODO: the value type
   * Returns the value of the given key if exists. Otherwise returns -1.
   *
   * @return The weight of the key if exists. Otherwise -1.
   */
  private static native long associativeGetFromCacheIfPresent(long cachePointer, long key);

  /**
   * TODO: the value type
   * Stores the value for the given key.
   */
  private static native void associativePutToCache(long cachePointer, long key, long value);

  /**
   * Drops the shared singleton instance of the Dash cache.
   */
  private static native void associativeDropCache(long cachePointer);

}
