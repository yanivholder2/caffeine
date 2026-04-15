package com.github.benmanes.caffeine.cache.simulator.policy.lrurust;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;

import com.typesafe.config.Config;
import java.util.*;
import static java.lang.System.exit;

@PolicySpec(name = "lrurust.LruRust")
public class LruRustPolicy implements Policy.KeyOnlyPolicy {
  private final long cachePointer;
  private final PolicyStats policyStats;
  private final long debugMode;

  static {
    System.loadLibrary("dash");
  }

  public LruRustPolicy(LruRustSettings settings) {
    long capacity = settings.maximumSize();
    this.policyStats = new PolicyStats(name());
    this.debugMode = settings.debugMode();

    if (this.debugMode > 0) {
      System.out.println("^^^^^^^^^^^^^^^^^^^^^^ LruRust Constructor ^^^^^^^^^^^^^^^^^^^^^^^^^^");
      System.out.println("capacity: " + capacity);
    }

    this.cachePointer = lruInitCache(capacity, this.debugMode);
    if (this.cachePointer == 0) {
      throw new RuntimeException("Failed to initialize Rust LRU cache");
    }
  }

  public static Set<Policy> policies(Config config) {
    var settings = new LruRustSettings(config);
    return Set.of(new LruRustPolicy(settings));
  }

  @Override
  public PolicyStats stats() {
    return this.policyStats;
  }

  @Override
  public void record(long key) {
    long value = lruGetFromCacheIfPresent(this.cachePointer, key);
    if (value == -1) {
      lruPutToCache(this.cachePointer, key, key);
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
        System.out.println("key != value in LruRust cache");
        exit(1);
      }
    }
  }

  @Override
  public void finished() {
    if (this.debugMode > 0) {
      System.out.println("^^^^^^^^^^^^^^^^^^^^^^ LruRust Finished ^^^^^^^^^^^^^^^^^^^^^^^^^^");
    }
    lruDropCache(this.cachePointer);
  }

  public static final class LruRustSettings extends BasicSettings {
    public LruRustSettings(Config config) {
      super(config);
    }

    public long debugMode() {
      return this.config().getLong("lruRust.debugMode");
    }
  }

  /*
   * ---------------------------------------------------------------------------
   * Native (Rust) functions
   * ---------------------------------------------------------------------------
   */

  private static native long lruInitCache(long capacity, long debug_mode);
  private static native long lruGetFromCacheIfPresent(long cachePointer, long key);
  private static native void lruPutToCache(long cachePointer, long key, long value);
  private static native void lruDropCache(long cachePointer);
}
