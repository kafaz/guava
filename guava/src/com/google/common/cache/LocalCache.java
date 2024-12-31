/*
 * Copyright (C) 2009 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.cache;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.cache.CacheBuilder.NULL_TICKER;
import static com.google.common.cache.CacheBuilder.UNSET_INT;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly;
import static java.lang.Math.min;
import static java.util.Collections.unmodifiableSet;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.jspecify.annotations.NullUnmarked;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.CacheBuilder.NullListener;
import com.google.common.cache.CacheBuilder.OneWeigher;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.CacheLoader.UnsupportedLoadingOperationException;
import com.google.common.cache.LocalCache.LoadingValueReference;
import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.errorprone.annotations.concurrent.LazyInit;
import com.google.j2objc.annotations.RetainedWith;
import com.google.j2objc.annotations.Weak;

/**
 * The concurrent hash map implementation built by {@link CacheBuilder}.
 *
 * <p>
 * This implementation is heavily derived from revision 1.96 of <a
 * href="http://tinyurl.com/ConcurrentHashMap">ConcurrentHashMap.java</a>.
 *
 * @author Charles Fry
 * @author Bob Lee ({@code com.google.common.collect.MapMaker})
 * @author Doug Lea ({@code ConcurrentHashMap})
 */
@SuppressWarnings({
    "GoodTime", // lots of violations (nanosecond math)
    "nullness", // too much trouble for the payoff
})
@GwtCompatible(emulated = true)
@NullUnmarked // TODO(cpovirk): Annotate for nullness.
class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {

  /*
   * The basic strategy is to subdivide the table among Segments, each of which
   * itself is a
   * concurrently readable hash table. The map supports non-blocking reads and
   * concurrent writes
   * across different segments.
   * 基本策略是将哈希表分成多个 Segment（段），每个段本身都是一个可并发读取的哈希表。
   * 这种设计支持跨不同段的非阻塞读操作和并发写操作。
   *
   * If a maximum size is specified, a best-effort bounding is performed per
   * segment, using a
   * page-replacement algorithm to determine which entries to evict when the
   * capacity has been exceeded.
   * 如果指定了最大容量，系统会在每个段上执行尽力而为的容量限制，使用页面替换算法来决定当容量
   * 超出限制时要驱逐哪些条目。
   *
   * The page replacement algorithm's data structures are kept casually consistent
   * with the map. The
   * ordering of writes to a segment is sequentially consistent. An update to the
   * map and recording
   * of reads may not be immediately reflected on the algorithm's data structures.
   * These structures
   * are guarded by a lock and operations are applied in batches to avoid lock
   * contention. The
   * penalty of applying the batches is spread across threads so that the
   * amortized cost is slightly
   * higher than performing just the operation without enforcing the capacity
   * constraint.
   * 页面替换算法的数据结构与映射表保持最终一致性。对段的写入操作保持顺序一致性。对映射表的
   * 更新和读取记录可能不会立即反映在算法的数据结构上。这些结构由锁保护，并且操作以批处理方式
   * 应用以避免锁竞争。批处理的开销被分摊到多个线程中，使得平摊成本仅比不执行容量限制的单纯
   * 操作略高。
   *
   * This implementation uses a per-segment queue to record a memento of the
   * additions, removals,
   * and accesses that were performed on the map. The queue is drained on writes
   * and when it exceeds
   * its capacity threshold.
   * 该实现为每个段使用一个队列来记录在映射表上执行的添加、删除和访问操作的备忘录。当发生写操作
   * 或队列超过其容量阈值时，队列会被清空。
   *
   * The Least Recently Used page replacement algorithm was chosen due to its
   * simplicity, high hit
   * rate, and ability to be implemented with O(1) time complexity. The initial
   * LRU implementation
   * operates per-segment rather than globally for increased implementation
   * simplicity. We expect
   * the cache hit rate to be similar to that of a global LRU algorithm.
   * 选择最近最少使用（LRU）页面替换算法是因为它简单、命中率高，并且可以在 O(1) 时间复杂度内
   * 实现。为了简化实现，初始的 LRU 实现是基于段而不是全局进行的。我们预期这种缓存命中率与全局
   * LRU 算法相近。
   */

  // Constants

  /**
   * The maximum capacity, used if a higher value is implicitly specified by
   * either of the
   * constructors with arguments. MUST be a power of two {@code <= 1<<30} to
   * ensure that entries are
   * indexable using ints.
   * 最大容量，当构造函数参数隐式指定了更高的值时使用。必须是2的幂次方且 <= 1<<30，以确保条目可以
   * 使用整数进行索引。
   */
  static final int MAXIMUM_CAPACITY = 1 << 30;

  /**
   * The maximum number of segments to allow; used to bound constructor arguments.
   * 允许的最大段数；用于限制构造函数参数。
   */
  static final int MAX_SEGMENTS = 1 << 16; // slightly conservative 稍微保守的值

  /**
   * Number of (unsynchronized) retries in the containsValue method.
   * containsValue 方法中（非同步）重试的次数。
   */
  static final int CONTAINS_VALUE_RETRIES = 3;

  /**
   * Number of cache access operations that can be buffered per segment before the
   * cache's recency
   * ordering information is updated. This is used to avoid lock contention by
   * recording a memento
   * of reads and delaying a lock acquisition until the threshold is crossed or a
   * mutation occurs.
   * 每个段在更新缓存的最近访问顺序信息之前可以缓冲的缓存访问操作数。这通过记录读取操作的备忘录
   * 并延迟锁获取直到超过阈值或发生修改来避免锁竞争。
   *
   * This must be a (2^n)-1 as it is used as a mask.
   * 这必须是 (2^n)-1 的形式，因为它被用作掩码。
   */
  static final int DRAIN_THRESHOLD = 0x3F; // 0011 1111

  /**
   * Maximum number of entries to be drained in a single cleanup run. This applies
   * independently to
   * the cleanup queue and both reference queues.
   * 单次清理运行中要清空的最大条目数。这独立应用于清理队列和两个引用队列。
   */
  static final int DRAIN_MAX = 16;

  // Fields

  static final Logger logger = Logger.getLogger(LocalCache.class.getName());

  /**
   * Mask value for indexing into segments. The upper bits of a key's hash code
   * are used to choose
   * the segment.
   * 用于段索引的掩码值。使用键的哈希码的高位bits来选择段。
   */
  final int segmentMask;

  /**
   * Shift value for indexing within segments. Helps prevent entries that end up
   * in the same segment
   * from also ending up in the same bucket.
   * 用于段内索引的位移值。帮助防止最终在同一段中的条目也最终在同一个桶中。
   */
  final int segmentShift;

  /**
   * The segments, each of which is a specialized hash table.
   * 段数组，每个段都是一个特殊的哈希表。
   */
  final Segment<K, V>[] segments;

  /**
   * The concurrency level.
   * 并发级别。
   */
  final int concurrencyLevel;

  /**
   * Strategy for comparing keys.
   * 键比较策略。
   */
  final Equivalence<Object> keyEquivalence;

  /**
   * Strategy for comparing values.
   * 值比较策略。
   */
  final Equivalence<Object> valueEquivalence;

  /**
   * Strategy for referencing keys.
   * 键引用策略。
   */
  final Strength keyStrength;

  /**
   * Strategy for referencing values.
   * 值引用策略。
   */
  final Strength valueStrength;

  /**
   * The maximum weight of this map. UNSET_INT if there is no maximum.
   * 此映射的最大权重。如果没有最大值则为 UNSET_INT。
   */
  final long maxWeight;

  /**
   * Weigher to weigh cache entries.
   * 用于计算缓存条目权重的计重器。
   */
  final Weigher<K, V> weigher;

  /**
   * How long after the last access to an entry the map will retain that entry.
   * 在最后一次访问条目后，映射将保留该条目多长时间。
   */
  final long expireAfterAccessNanos;

  /**
   * How long after the last write to an entry the map will retain that entry.
   * 在最后一次写入条目后，映射将保留该条目多长时间。
   */
  final long expireAfterWriteNanos;

  /**
   * How long after the last write an entry becomes a candidate for refresh.
   * 在最后一次写入后多长时间条目将成为刷新候选。
   */
  final long refreshNanos;

  /**
   * Entries waiting to be consumed by the removal listener.
   * 等待被移除监听器消费的条目。
   */
  final Queue<RemovalNotification<K, V>> removalNotificationQueue;

  /**
   * A listener that is invoked when an entry is removed due to expiration or
   * garbage collection of
   * soft/weak entries.
   * 当条目由于过期或软/弱引用的垃圾回收而被移除时调用的监听器。
   */
  final RemovalListener<K, V> removalListener;

  /**
   * Measures time in a testable way.
   * 以可测试的方式计量时间。
   */
  final Ticker ticker;

  /**
   * Factory used to create new entries.
   * 用于创建新条目的工厂。
   */
  final EntryFactory entryFactory;

  /**
   * Accumulates global cache statistics. Note that there are also per-segments
   * stats counters which
   * must be aggregated to obtain a global stats view.
   * 累积全局缓存统计信息。注意还有每个段的统计计数器，必须聚合这些计数器才能获得全局统计视图。
   */
  final StatsCounter globalStatsCounter;

  /**
   * The default cache loader to use on loading operations.
   * 用于加载操作的默认缓存加载器。
   */
  @CheckForNull
  final CacheLoader<? super K, V> defaultLoader;

  /**
   * Creates a new, empty map with the specified strategy, initial capacity and
   * concurrency level.
   */
  LocalCache(
      CacheBuilder<? super K, ? super V> builder, @CheckForNull CacheLoader<? super K, V> loader) {
    concurrencyLevel = min(builder.getConcurrencyLevel(), MAX_SEGMENTS);

    keyStrength = builder.getKeyStrength();
    valueStrength = builder.getValueStrength();

    keyEquivalence = builder.getKeyEquivalence();
    valueEquivalence = builder.getValueEquivalence();

    maxWeight = builder.getMaximumWeight();
    weigher = builder.getWeigher();
    expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
    expireAfterWriteNanos = builder.getExpireAfterWriteNanos();
    refreshNanos = builder.getRefreshNanos();

    removalListener = builder.getRemovalListener();
    removalNotificationQueue = (removalListener == NullListener.INSTANCE)
        ? LocalCache.discardingQueue()
        : new ConcurrentLinkedQueue<>();

    ticker = builder.getTicker(recordsTime());
    entryFactory = EntryFactory.getFactory(keyStrength, usesAccessEntries(), usesWriteEntries());
    globalStatsCounter = builder.getStatsCounterSupplier().get();
    defaultLoader = loader;

    int initialCapacity = min(builder.getInitialCapacity(), MAXIMUM_CAPACITY);
    if (evictsBySize() && !customWeigher()) {
      initialCapacity = (int) min(initialCapacity, maxWeight);
    }

    // Initialize segment shift and count
    // 初始化段偏移量和段数量
    int segmentShift = 0;
    int segmentCount = 1;

    // Determine the number of segments based on concurrency level and weight
    // constraints
    // 基于并发级别和权重约束确定段的数量
    while (segmentCount < concurrencyLevel
        && (!evictsBySize() || segmentCount * 20L <= maxWeight)) {
      ++segmentShift;
      segmentCount <<= 1; // Double the segment count (左移1位相当于乘2)
    }

    // Calculate the shift value for segment lookup (32 bits - shift)
    // 计算段查找的偏移值（32位 - 位移量）
    this.segmentShift = 32 - segmentShift;
    // Create segment mask for hash value mapping
    // 创建段掩码用于哈希值映射
    segmentMask = segmentCount - 1;

    // Create the segments array with calculated count
    // 使用计算出的段数量创建段数组
    this.segments = newSegmentArray(segmentCount);

    // Calculate the initial capacity for each segment
    // 计算每个段的初始容量
    int segmentCapacity = initialCapacity / segmentCount;
    // Round up if there's a remainder
    // 如果有余数，向上取整
    if (segmentCapacity * segmentCount < initialCapacity) {
      ++segmentCapacity;
    }

    // Find the nearest power of 2 for segment size
    // 找到大于等于 segmentCapacity 的最小2的幂
    int segmentSize = 1;
    while (segmentSize < segmentCapacity) {
      segmentSize <<= 1;
    }

    if (evictsBySize()) {
      // If using weight-based eviction, distribute max weight among segments
      // 如果使用基于权重的淘汰策略，在各个段之间分配最大权重
      long maxSegmentWeight = maxWeight / segmentCount + 1;
      long remainder = maxWeight % segmentCount;

      for (int i = 0; i < this.segments.length; ++i) {
        // Adjust weight distribution to ensure total equals maxWeight
        // 调整权重分配以确保总和等于maxWeight
        if (i == remainder) {
          maxSegmentWeight--;
        }
        this.segments[i] = createSegment(
            segmentSize,
            maxSegmentWeight,
            builder.getStatsCounterSupplier().get());
      }
    } else {
      // If not using weight-based eviction, create segments with no weight limit
      // 如果不使用基于权重的淘汰策略，创建没有权重限制的段
      for (int i = 0; i < this.segments.length; ++i) {
        this.segments[i] = createSegment(
            segmentSize,
            UNSET_INT, // No weight limit 无权重限制
            builder.getStatsCounterSupplier().get());
      }
    }
  }

  boolean evictsBySize() {
    return maxWeight >= 0;
  }

  boolean customWeigher() {
    return weigher != OneWeigher.INSTANCE;
  }

  boolean expires() {
    return expiresAfterWrite() || expiresAfterAccess();
  }

  boolean expiresAfterWrite() {
    return expireAfterWriteNanos > 0;
  }

  boolean expiresAfterAccess() {
    return expireAfterAccessNanos > 0;
  }

  boolean refreshes() {
    return refreshNanos > 0;
  }

  boolean usesAccessQueue() {
    return expiresAfterAccess() || evictsBySize();
  }

  boolean usesWriteQueue() {
    return expiresAfterWrite();
  }

  boolean recordsWrite() {
    return expiresAfterWrite() || refreshes();
  }

  boolean recordsAccess() {
    return expiresAfterAccess();
  }

  boolean recordsTime() {
    return recordsWrite() || recordsAccess();
  }

  boolean usesWriteEntries() {
    return usesWriteQueue() || recordsWrite();
  }

  boolean usesAccessEntries() {
    return usesAccessQueue() || recordsAccess();
  }

  boolean usesKeyReferences() {
    return keyStrength != Strength.STRONG;
  }

  boolean usesValueReferences() {
    return valueStrength != Strength.STRONG;
  }

  enum Strength {
    /*
     * TODO(kevinb): If we strongly reference the value and aren't loading, we
     * needn't wrap the value.
     * This could save ~8 bytes per entry.
     * 
     * 待办(kevinb)：如果我们使用强引用且不需要加载，我们可以不包装这个值。
     * 这可以为每个条目节省约8字节的空间。
     */

    /**
     * 强引用：不会被垃圾收集器回收的普通引用
     * Strong reference: normal references that won't be collected by the garbage
     * collector
     */
    STRONG {
      @Override
      <K, V> ValueReference<K, V> referenceValue(
          Segment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
        // 根据权重选择不同的强引用实现
        // Choose different strong reference implementation based on weight
        return (weight == 1)
            ? new StrongValueReference<K, V>(value) // 普通强引用
            : new WeightedStrongValueReference<K, V>(value, weight); // 带权重的强引用
      }

      @Override
      Equivalence<Object> defaultEquivalence() {
        // 使用equals()方法进行相等性比较
        // Use equals() method for equality comparison
        return Equivalence.equals();
      }
    },

    /**
     * 软引用：在内存不足时可能被回收的引用
     * Soft reference: references that might be collected when memory is tight
     */
    SOFT {
      @Override
      <K, V> ValueReference<K, V> referenceValue(
          Segment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
        // 根据权重选择不同的软引用实现
        // Choose different soft reference implementation based on weight
        return (weight == 1)
            ? new SoftValueReference<K, V>(segment.valueReferenceQueue, value, entry)
            : new WeightedSoftValueReference<K, V>(
                segment.valueReferenceQueue, value, entry, weight);
      }

      @Override
      Equivalence<Object> defaultEquivalence() {
        // 使用身份比较（==）进行相等性判断
        // Use identity comparison (==) for equality
        return Equivalence.identity();
      }
    },

    /**
     * 弱引用：在下次GC时就可能被回收的引用
     * Weak reference: references that will be collected on next GC
     */
    WEAK {
      @Override
      <K, V> ValueReference<K, V> referenceValue(
          Segment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
        // 根据权重选择不同的弱引用实现
        // Choose different weak reference implementation based on weight
        return (weight == 1)
            ? new WeakValueReference<K, V>(segment.valueReferenceQueue, value, entry)
            : new WeightedWeakValueReference<K, V>(
                segment.valueReferenceQueue, value, entry, weight);
      }

      @Override
      Equivalence<Object> defaultEquivalence() {
        // 使用身份比较（==）进行相等性判断
        // Use identity comparison (==) for equality
        return Equivalence.identity();
      }
    };

    /**
     * 根据当前引用强度创建对应的值引用
     * Creates a reference for the given value according to this value strength.
     */
    abstract <K, V> ValueReference<K, V> referenceValue(
        Segment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight);

    /**
     * 返回用于比较和哈希在此强度下引用的键或值的默认等价性策略。
     * 除非用户明确指定替代策略，否则将使用此策略。
     * 
     * Returns the default equivalence strategy used to compare and hash keys or
     * values referenced
     * at this strength. This strategy will be used unless the user explicitly
     * specifies an
     * alternate strategy.
     */
    abstract Equivalence<Object> defaultEquivalence();
  }

  /** Creates new entries. */
  enum EntryFactory {
    /**
     * 强引用条目 - 最基本的实现
     * 不记录访问时间和写入时间
     */
    STRONG {
      @Override
      <K, V> ReferenceEntry<K, V> newEtry(
          Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
        return new StrongEntry<>(key, hash, next);
      }
    },

    /**
     * 强引用条目 + 访问时间记录
     * 记录最后访问时间，用于支持基于访问时间的过期
     */
    STRONG_ACCESS {
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
        return new StrongAccessEntry<>(key, hash, next);
      }

      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment,
          ReferenceEntry<K, V> original,
          ReferenceEntry<K, V> newNext,
          K key) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext, key);
        copyAccessEntry(original, newEntry); // 复制访问时间信息
        return newEntry;
      }
    },

    /**
     * 强引用条目 + 写入时间记录
     * 记录最后写入时间，用于支持基于写入时间的过期
     */
    STRONG_WRITE {
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
        return new StrongWriteEntry<>(key, hash, next);
      }

      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment,
          ReferenceEntry<K, V> original,
          ReferenceEntry<K, V> newNext,
          K key) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext, key);
        copyWriteEntry(original, newEntry);
        return newEntry;
      }
    },

    /**
     * 强引用条目 + 访问时间记录 + 写入时间记录
     * 同时记录访问和写入时间，支持两种过期策略
     */
    STRONG_ACCESS_WRITE {
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
        return new StrongAccessWriteEntry<>(key, hash, next);
      }

      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment,
          ReferenceEntry<K, V> original,
          ReferenceEntry<K, V> newNext,
          K key) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext, key);
        copyAccessEntry(original, newEntry);
        copyWriteEntry(original, newEntry);
        return newEntry;
      }
    },

    /**
     * 弱引用条目 - 基本实现
     * 使用 WeakReference 包装 key，允许 key 被 GC 回收
     */
    WEAK {
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
        return new WeakEntry<>(segment.keyReferenceQueue, key, hash, next);
      }
    },

    /**
     * 弱引用条目 + 访问时间记录
     */
    WEAK_ACCESS {
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
        return new WeakAccessEntry<>(segment.keyReferenceQueue, key, hash, next);
      }

      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment,
          ReferenceEntry<K, V> original,
          ReferenceEntry<K, V> newNext,
          K key) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext, key);
        copyAccessEntry(original, newEntry);
        return newEntry;
      }
    },

    /**
     * 弱引用条目 + 写入时间记录
     */
    WEAK_WRITE {
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
        return new WeakWriteEntry<>(segment.keyReferenceQueue, key, hash, next);
      }

      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment,
          ReferenceEntry<K, V> original,
          ReferenceEntry<K, V> newNext,
          K key) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext, key);
        copyWriteEntry(original, newEntry);
        return newEntry;
      }
    },

    /**
     * 弱引用条目 + 访问时间记录 + 写入时间记录
     */
    WEAK_ACCESS_WRITE {
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
        return new WeakAccessWriteEntry<>(segment.keyReferenceQueue, key, hash, next);
      }

      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment,
          ReferenceEntry<K, V> original,
          ReferenceEntry<K, V> newNext,
          K key) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext, key);
        copyAccessEntry(original, newEntry);
        copyWriteEntry(original, newEntry);
        return newEntry;
      }
    };

    // Masks used to compute indices in the following table.

    static final int ACCESS_MASK = 1;
    static final int WRITE_MASK = 2;
    static final int WEAK_MASK = 4;

    /** Look-up table for factories. */
    static final EntryFactory[] factories = {
        STRONG,
        STRONG_ACCESS,
        STRONG_WRITE,
        STRONG_ACCESS_WRITE,
        WEAK,
        WEAK_ACCESS,
        WEAK_WRITE,
        WEAK_ACCESS_WRITE,
    };

    static EntryFactory getFactory(
        Strength keyStrength, boolean usesAccessQueue, boolean usesWriteQueue) {
      int flags = ((keyStrength == Strength.WEAK) ? WEAK_MASK : 0)
          | (usesAccessQueue ? ACCESS_MASK : 0)
          | (usesWriteQueue ? WRITE_MASK : 0);
      return factories[flags];
    }

    /**
     * Creates a new entry.
     *
     * @param segment to create the entry for
     * @param key     of the entry
     * @param hash    of the key
     * @param next    entry in the same bucket
     */
    abstract <K, V> ReferenceEntry<K, V> newEntry(
        Segment<K, V> segment, K key, int hash, @CheckForNull ReferenceEntry<K, V> next);

    /**
     * Copies an entry, assigning it a new {@code next} entry.
     *
     * @param original the entry to copy. But avoid calling {@code getKey} on it:
     *                 Instead, use the
     *                 {@code key} parameter. That way, we prevent the key from
     *                 being garbage collected in the
     *                 case of weak keys. If we create a new entry with a key that
     *                 is null at construction time,
     *                 we're not sure if entry will necessarily ever be garbage
     *                 collected.
     * @param newNext  entry in the same bucket
     * @param key      the key to copy from the original entry to the new one. Use
     *                 this in preference to
     *                 {@code original.getKey()}.
     */
    // Guarded By Segment.this
    <K, V> ReferenceEntry<K, V> copyEntry(
        Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext, K key) {
      return newEntry(segment, key, original.getHash(), newNext);
    }

    // Guarded By Segment.this
    <K, V> void copyAccessEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newEntry) {
      // TODO(fry): when we link values instead of entries this method can go
      // away, as can connectAccessOrder, nullifyAccessOrder.
      newEntry.setAccessTime(original.getAccessTime());

      connectAccessOrder(original.getPreviousInAccessQueue(), newEntry);
      connectAccessOrder(newEntry, original.getNextInAccessQueue());

      nullifyAccessOrder(original);
    }

    // Guarded By Segment.this
    <K, V> void copyWriteEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newEntry) {
      // TODO(fry): when we link values instead of entries this method can go
      // away, as can connectWriteOrder, nullifyWriteOrder.
      newEntry.setWriteTime(original.getWriteTime());

      connectWriteOrder(original.getPreviousInWriteQueue(), newEntry);
      connectWriteOrder(newEntry, original.getNextInWriteQueue());

      nullifyWriteOrder(original);
    }
  }

  /** A reference to a value. */
  interface ValueReference<K, V> {
    /**
     * Returns the value. Does not block or throw exceptions.
     * 返回值。不会阻塞或抛出异常。
     */
    @CheckForNull
    V get();

    /**
     * Waits for a value that may still be loading. Unlike get(), this method can
     * block
     * (in the case of FutureValueReference).
     * 等待可能仍在加载的值。与get()不同，此方法可能会阻塞
     * （在FutureValueReference的情况下）。
     *
     * @throws ExecutionException if the loading thread throws an exception
     *                            如果加载线程抛出异常
     * @throws ExecutionError     if the loading thread throws an error
     *                            如果加载线程抛出错误
     */
    V waitForValue() throws ExecutionException;

    /**
     * Returns the weight of this entry. This is assumed to be static between calls
     * to setValue.
     * 返回此条目的权重。在调用setValue之间，该值假定为静态的。
     */
    int getWeight();

    /**
     * Returns the entry associated with this value reference, or null if this value
     * reference is independent of any entry.
     * 返回与此值引用关联的条目，如果此值引用独立于任何条目，则返回null。
     */
    @CheckForNull
    ReferenceEntry<K, V> getEntry();

    /**
     * Creates a copy of this reference for the given entry.
     * 为给定条目创建此引用的副本。
     *
     * value may be null only for a loading reference.
     * 仅对于正在加载的引用，value可以为null。
     */
    ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue,
        @CheckForNull V value,
        ReferenceEntry<K, V> entry);

    /**
     * Notify pending loads that a new value was set.
     * This is only relevant to loading value references.
     * 通知待处理的加载操作已设置新值。
     * 这仅与正在加载的值引用相关。
     */
    void notifyNewValue(@CheckForNull V newValue);

    /**
     * Returns true if a new value is currently loading, regardless of whether there
     * is
     * an existing value. It is assumed that the return value of this method is
     * constant
     * for any given ValueReference instance.
     * 如果当前正在加载新值，则返回true，无论是否存在现有值。
     * 假定对于任何给定的ValueReference实例，此方法的返回值都是常量。
     */
    boolean isLoading();

    /**
     * Returns true if this reference contains an active value, meaning one that is
     * still
     * considered present in the cache. Active values consist of:
     * 如果此引用包含活动值，则返回true，表示该值仍被认为存在于缓存中。活动值包括：
     * 
     * - live values: which are returned by cache lookups
     * 活跃值：通过缓存查找返回的值
     * - dead values: which have been evicted but awaiting removal
     * 死亡值：已被逐出但等待移除的值
     * 
     * Non-active values consist strictly of loading values, though during refresh a
     * value
     * may be both active and loading.
     * 非活动值严格由加载值组成，但在刷新期间，一个值可能既是活动的又是加载中的。
     */
    boolean isActive();
  }

  /** Placeholder. Indicates that the value hasn't been set yet. */
  static final ValueReference<Object, Object> UNSET = new ValueReference<Object, Object>() {
    @CheckForNull
    @Override
    public Object get() {
      return null;
    }

    @Override
    public int getWeight() {
      return 0;
    }

    @CheckForNull
    @Override
    public ReferenceEntry<Object, Object> getEntry() {
      return null;
    }

    @Override
    public ValueReference<Object, Object> copyFor(
        ReferenceQueue<Object> queue,
        @CheckForNull Object value,
        ReferenceEntry<Object, Object> entry) {
      return this;
    }

    @Override
    public boolean isLoading() {
      return false;
    }

    @Override
    public boolean isActive() {
      return false;
    }

    @CheckForNull
    @Override
    public Object waitForValue() {
      return null;
    }

    @Override
    public void notifyNewValue(Object newValue) {
    }
  };

  /** Singleton placeholder that indicates a value is being loaded. */
  @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
  static <K, V> ValueReference<K, V> unset() {
    return (ValueReference<K, V>) UNSET;
  }

  private enum NullEntry implements ReferenceEntry<Object, Object> {
    INSTANCE;

    @CheckForNull
    @Override
    public ValueReference<Object, Object> getValueReference() {
      return null;
    }

    @Override
    public void setValueReference(ValueReference<Object, Object> valueReference) {
    }

    @CheckForNull
    @Override
    public ReferenceEntry<Object, Object> getNext() {
      return null;
    }

    @Override
    public int getHash() {
      return 0;
    }

    @CheckForNull
    @Override
    public Object getKey() {
      return null;
    }

    @Override
    public long getAccessTime() {
      return 0;
    }

    @Override
    public void setAccessTime(long time) {
    }

    @Override
    public ReferenceEntry<Object, Object> getNextInAccessQueue() {
      return this;
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<Object, Object> next) {
    }

    @Override
    public ReferenceEntry<Object, Object> getPreviousInAccessQueue() {
      return this;
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<Object, Object> previous) {
    }

    @Override
    public long getWriteTime() {
      return 0;
    }

    @Override
    public void setWriteTime(long time) {
    }

    @Override
    public ReferenceEntry<Object, Object> getNextInWriteQueue() {
      return this;
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<Object, Object> next) {
    }

    @Override
    public ReferenceEntry<Object, Object> getPreviousInWriteQueue() {
      return this;
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<Object, Object> previous) {
    }
  }

  abstract static class AbstractReferenceEntry<K, V> implements ReferenceEntry<K, V> {
    @Override
    public ValueReference<K, V> getValueReference() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getNext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getHash() {
      throw new UnsupportedOperationException();
    }

    @Override
    public K getKey() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getAccessTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setAccessTime(long time) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getWriteTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setWriteTime(long time) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }
  }

  @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
  static <K, V> ReferenceEntry<K, V> nullEntry() {
    return (ReferenceEntry<K, V>) NullEntry.INSTANCE;
  }

  static final Queue<?> DISCARDING_QUEUE = new AbstractQueue<Object>() {
    @Override
    public boolean offer(Object o) {
      return true;
    }

    @CheckForNull
    @Override
    public Object peek() {
      return null;
    }

    @CheckForNull
    @Override
    public Object poll() {
      return null;
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public Iterator<Object> iterator() {
      return ImmutableSet.of().iterator();
    }
  };

  /** Queue that discards all elements. */
  @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
  static <E> Queue<E> discardingQueue() {
    return (Queue) DISCARDING_QUEUE;
  }

  /*
   * Note: All of this duplicate code sucks, but it saves a lot of memory. If only
   * Java had mixins!
   * To maintain this code, make a change for the strong reference type. Then, cut
   * and paste, and
   * replace "Strong" with "Soft" or "Weak" within the pasted text. The primary
   * difference is that
   * strong entries store the key reference directly while soft and weak entries
   * delegate to their
   * respective superclasses.
   */

  /** Used for strongly-referenced keys. */
  static class StrongEntry<K, V> extends AbstractReferenceEntry<K, V> {
    final K key;

    StrongEntry(K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      this.key = key;
      this.hash = hash;
      this.next = next;
    }

    @Override
    public K getKey() {
      return this.key;
    }

    // The code below is exactly the same for each entry type.

    final int hash;
    @CheckForNull
    final ReferenceEntry<K, V> next;
    volatile ValueReference<K, V> valueReference = unset();

    @Override
    public ValueReference<K, V> getValueReference() {
      return valueReference;
    }

    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
      this.valueReference = valueReference;
    }

    @Override
    public int getHash() {
      return hash;
    }

    @Override
    public ReferenceEntry<K, V> getNext() {
      return next;
    }
  }

  static final class StrongAccessEntry<K, V> extends StrongEntry<K, V> {
    StrongAccessEntry(K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      super(key, hash, next);
    }

    // The code below is exactly the same for each access entry type.

    volatile long accessTime = Long.MAX_VALUE;

    @Override
    public long getAccessTime() {
      return accessTime;
    }

    @Override
    public void setAccessTime(long time) {
      this.accessTime = time;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> nextAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
      return nextAccess;
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
      this.nextAccess = next;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> previousAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
      return previousAccess;
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
      this.previousAccess = previous;
    }
  }

  static final class StrongWriteEntry<K, V> extends StrongEntry<K, V> {
    StrongWriteEntry(K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      super(key, hash, next);
    }

    // The code below is exactly the same for each write entry type.

    volatile long writeTime = Long.MAX_VALUE;

    @Override
    public long getWriteTime() {
      return writeTime;
    }

    @Override
    public void setWriteTime(long time) {
      this.writeTime = time;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> nextWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
      return nextWrite;
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
      this.nextWrite = next;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> previousWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
      return previousWrite;
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
      this.previousWrite = previous;
    }
  }

  static final class StrongAccessWriteEntry<K, V> extends StrongEntry<K, V> {
    StrongAccessWriteEntry(K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      super(key, hash, next);
    }

    // The code below is exactly the same for each access entry type.

    volatile long accessTime = Long.MAX_VALUE;

    @Override
    public long getAccessTime() {
      return accessTime;
    }

    @Override
    public void setAccessTime(long time) {
      this.accessTime = time;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> nextAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
      return nextAccess;
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
      this.nextAccess = next;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> previousAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
      return previousAccess;
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
      this.previousAccess = previous;
    }

    // The code below is exactly the same for each write entry type.

    volatile long writeTime = Long.MAX_VALUE;

    @Override
    public long getWriteTime() {
      return writeTime;
    }

    @Override
    public void setWriteTime(long time) {
      this.writeTime = time;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> nextWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
      return nextWrite;
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
      this.nextWrite = next;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> previousWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
      return previousWrite;
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
      this.previousWrite = previous;
    }
  }

  /** Used for weakly-referenced keys. */
  static class WeakEntry<K, V> extends WeakReference<K> implements ReferenceEntry<K, V> {
    WeakEntry(ReferenceQueue<K> queue, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      super(key, queue);
      this.hash = hash;
      this.next = next;
    }

    @Override
    public K getKey() {
      return get();
    }

    /*
     * It'd be nice to get these for free from AbstractReferenceEntry, but we're
     * already extending
     * WeakReference<K>.
     */

    // null access

    @Override
    public long getAccessTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setAccessTime(long time) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    // null write

    @Override
    public long getWriteTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setWriteTime(long time) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    // The code below is exactly the same for each entry type.

    final int hash;
    @CheckForNull
    final ReferenceEntry<K, V> next;
    volatile ValueReference<K, V> valueReference = unset();

    @Override
    public ValueReference<K, V> getValueReference() {
      return valueReference;
    }

    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
      this.valueReference = valueReference;
    }

    @Override
    public int getHash() {
      return hash;
    }

    @Override
    public ReferenceEntry<K, V> getNext() {
      return next;
    }
  }

  static final class WeakAccessEntry<K, V> extends WeakEntry<K, V> {
    WeakAccessEntry(
        ReferenceQueue<K> queue, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each access entry type.

    volatile long accessTime = Long.MAX_VALUE;

    @Override
    public long getAccessTime() {
      return accessTime;
    }

    @Override
    public void setAccessTime(long time) {
      this.accessTime = time;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> nextAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
      return nextAccess;
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
      this.nextAccess = next;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> previousAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
      return previousAccess;
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
      this.previousAccess = previous;
    }
  }

  static final class WeakWriteEntry<K, V> extends WeakEntry<K, V> {
    WeakWriteEntry(
        ReferenceQueue<K> queue, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each write entry type.

    volatile long writeTime = Long.MAX_VALUE;

    @Override
    public long getWriteTime() {
      return writeTime;
    }

    @Override
    public void setWriteTime(long time) {
      this.writeTime = time;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> nextWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
      return nextWrite;
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
      this.nextWrite = next;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> previousWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
      return previousWrite;
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
      this.previousWrite = previous;
    }
  }

  static final class WeakAccessWriteEntry<K, V> extends WeakEntry<K, V> {
    WeakAccessWriteEntry(
        ReferenceQueue<K> queue, K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each access entry type.

    volatile long accessTime = Long.MAX_VALUE;

    @Override
    public long getAccessTime() {
      return accessTime;
    }

    @Override
    public void setAccessTime(long time) {
      this.accessTime = time;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> nextAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
      return nextAccess;
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
      this.nextAccess = next;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> previousAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
      return previousAccess;
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
      this.previousAccess = previous;
    }

    // The code below is exactly the same for each write entry type.

    volatile long writeTime = Long.MAX_VALUE;

    @Override
    public long getWriteTime() {
      return writeTime;
    }

    @Override
    public void setWriteTime(long time) {
      this.writeTime = time;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> nextWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
      return nextWrite;
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
      this.nextWrite = next;
    }

    // Guarded By Segment.this
    @Weak
    ReferenceEntry<K, V> previousWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
      return previousWrite;
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
      this.previousWrite = previous;
    }
  }

  /** References a weak value. */
  static class WeakValueReference<K, V> extends WeakReference<V> implements ValueReference<K, V> {
    // WeakReference提供:
    // - 弱引用语义
    // - GC自动处理
    // - 引用队列集成

    // ValueReference提供:
    // - 缓存条目关联
    // - 值状态管理
    // - 复制和通知机制
    final ReferenceEntry<K, V> entry;

    WeakValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
      super(referent, queue);
      this.entry = entry;
    }

    @Override
    public int getWeight() {
      return 1;
    }

    @Override
    public ReferenceEntry<K, V> getEntry() {
      return entry;
    }

    @Override
    public void notifyNewValue(V newValue) {
    }

    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
      return new WeakValueReference<>(queue, value, entry);
    }

    @Override
    public boolean isLoading() {
      return false;
    }

    @Override
    public boolean isActive() {
      return true;
    }

    @Override
    public V waitForValue() {
      return get();
    }
  }

  /** References a soft value. */
  static class SoftValueReference<K, V> extends SoftReference<V> implements ValueReference<K, V> {
    final ReferenceEntry<K, V> entry;

    SoftValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
      super(referent, queue);
      this.entry = entry;
    }

    @Override
    public int getWeight() {
      return 1;
    }

    @Override
    public ReferenceEntry<K, V> getEntry() {
      return entry;
    }

    @Override
    public void notifyNewValue(V newValue) {
    }

    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
      return new SoftValueReference<>(queue, value, entry);
    }

    @Override
    public boolean isLoading() {
      return false;
    }

    @Override
    public boolean isActive() {
      return true;
    }

    @Override
    public V waitForValue() {
      return get();
    }
  }

  /** References a strong value. */
  static class StrongValueReference<K, V> implements ValueReference<K, V> {
    final V referent;

    StrongValueReference(V referent) {
      this.referent = referent;
    }

    @Override
    public V get() {
      return referent;
    }

    @Override
    public int getWeight() {
      return 1;
    }

    @Override
    public ReferenceEntry<K, V> getEntry() {
      return null;
    }

    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
      return this;
    }

    @Override
    public boolean isLoading() {
      return false;
    }

    @Override
    public boolean isActive() {
      return true;
    }

    @Override
    public V waitForValue() {
      return get();
    }

    @Override
    public void notifyNewValue(V newValue) {
    }
  }

  /** References a weak value. */
  static final class WeightedWeakValueReference<K, V> extends WeakValueReference<K, V> {
    final int weight;

    WeightedWeakValueReference(
        ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry, int weight) {
      super(queue, referent, entry);
      this.weight = weight;
    }

    @Override
    public int getWeight() {
      return weight;
    }

    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
      return new WeightedWeakValueReference<>(queue, value, entry, weight);
    }
  }

  /** References a soft value. */
  static final class WeightedSoftValueReference<K, V> extends SoftValueReference<K, V> {
    final int weight;

    WeightedSoftValueReference(
        ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry, int weight) {
      super(queue, referent, entry);
      this.weight = weight;
    }

    @Override
    public int getWeight() {
      return weight;
    }

    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
      return new WeightedSoftValueReference<>(queue, value, entry, weight);
    }
  }

  /** References a strong value. */
  static final class WeightedStrongValueReference<K, V> extends StrongValueReference<K, V> {
    final int weight;

    WeightedStrongValueReference(V referent, int weight) {
      super(referent);
      this.weight = weight;
    }

    @Override
    public int getWeight() {
      return weight;
    }
  }

  /**
   * Applies a supplemental hash function to a given hash code, which defends
   * against poor quality
   * hash functions. This is critical when the concurrent hash map uses
   * power-of-two length hash
   * tables, that otherwise encounter collisions for hash codes that do not differ
   * in lower or upper
   * bits.
   *
   * @param h hash code
   */
  static int rehash(int h) {
    // Spread bits to regularize both segment and index locations,
    // using variant of single-word Wang/Jenkins hash.
    // TODO(kevinb): use Hashing/move this to Hashing?
    h += (h << 15) ^ 0xffffcd7d;
    h ^= (h >>> 10);
    h += (h << 3);
    h ^= (h >>> 6);
    h += (h << 2) + (h << 14);
    return h ^ (h >>> 16);
  }

  /**
   * This method is a convenience for testing. Code should call
   * {@link Segment#newEntry} directly.
   */
  @VisibleForTesting
  ReferenceEntry<K, V> newEntry(K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
    Segment<K, V> segment = segmentFor(hash);
    segment.lock();
    try {
      return segment.newEntry(key, hash, next);
    } finally {
      segment.unlock();
    }
  }

  /**
   * This method is a convenience for testing. Code should call
   * {@link Segment#copyEntry} directly.
   */
  // Guarded By Segment.this
  @SuppressWarnings("GuardedBy")
  @VisibleForTesting
  ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
    int hash = original.getHash();
    return segmentFor(hash).copyEntry(original, newNext);
  }

  /**
   * This method is a convenience for testing. Code should call
   * {@link Segment#setValue} instead.
   */
  // Guarded By Segment.this
  @VisibleForTesting
  ValueReference<K, V> newValueReference(ReferenceEntry<K, V> entry, V value, int weight) {
    int hash = entry.getHash();
    return valueStrength.referenceValue(segmentFor(hash), entry, checkNotNull(value), weight);
  }

  int hash(@CheckForNull Object key) {
    int h = keyEquivalence.hash(key);
    return rehash(h);
  }

  void reclaimValue(ValueReference<K, V> valueReference) {
    ReferenceEntry<K, V> entry = valueReference.getEntry();
    int hash = entry.getHash();
    segmentFor(hash).reclaimValue(entry.getKey(), hash, valueReference);
  }

  /**
   * 回收指定条目中已被GC回收的键
   * 
   * @param entry 需要回收键的缓存条目
   */
  void reclaimKey(ReferenceEntry<K, V> entry) {
    // 获取条目的哈希值
    int hash = entry.getHash();

    // 根据哈希值定位到对应的段(Segment)，并在该段中执行键的回收操作
    segmentFor(hash).reclaimKey(entry, hash);
  }

  /**
   * This method is a convenience for testing. Code should call
   * {@link Segment#getLiveValue}
   * instead.
   */
  @VisibleForTesting
  boolean isLive(ReferenceEntry<K, V> entry, long now) {
    return segmentFor(entry.getHash()).getLiveValue(entry, now) != null;
  }

  /**
   * Returns the segment that should be used for a key with the given hash.
   *
   * @param hash the hash code for the key
   * @return the segment
   */
  Segment<K, V> segmentFor(int hash) {
    // TODO(fry): Lazily create segments?
    return segments[(hash >>> segmentShift) & segmentMask];
  }

  Segment<K, V> createSegment(
      int initialCapacity, long maxSegmentWeight, StatsCounter statsCounter) {
    return new Segment<>(this, initialCapacity, maxSegmentWeight, statsCounter);
  }

  /**
   * Gets the value from an entry. Returns null if the entry is invalid,
   * partially-collected,
   * loading, or expired. Unlike {@link Segment#getLiveValue} this method does not
   * attempt to clean
   * up stale entries. As such it should only be called outside a segment context,
   * such as during
   * iteration.
   */
  @CheckForNull
  V getLiveValue(ReferenceEntry<K, V> entry, long now) {
    if (entry.getKey() == null) {
      return null;
    }
    V value = entry.getValueReference().get();
    if (value == null) {
      return null;
    }

    if (isExpired(entry, now)) {
      return null;
    }
    return value;
  }

  // expiration

  /** Returns true if the entry has expired. */
  boolean isExpired(ReferenceEntry<K, V> entry, long now) {
    checkNotNull(entry);
    if (expiresAfterAccess() && (now - entry.getAccessTime() >= expireAfterAccessNanos)) {
      return true;
    }
    if (expiresAfterWrite() && (now - entry.getWriteTime() >= expireAfterWriteNanos)) {
      return true;
    }
    return false;
  }

  // queues

  // Guarded By Segment.this
  static <K, V> void connectAccessOrder(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
    previous.setNextInAccessQueue(next);
    next.setPreviousInAccessQueue(previous);
  }

  // Guarded By Segment.this
  static <K, V> void nullifyAccessOrder(ReferenceEntry<K, V> nulled) {
    ReferenceEntry<K, V> nullEntry = nullEntry();
    nulled.setNextInAccessQueue(nullEntry);
    nulled.setPreviousInAccessQueue(nullEntry);
  }

  // Guarded By Segment.this
  static <K, V> void connectWriteOrder(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
    previous.setNextInWriteQueue(next);
    next.setPreviousInWriteQueue(previous);
  }

  // Guarded By Segment.this
  static <K, V> void nullifyWriteOrder(ReferenceEntry<K, V> nulled) {
    ReferenceEntry<K, V> nullEntry = nullEntry();
    nulled.setNextInWriteQueue(nullEntry);
    nulled.setPreviousInWriteQueue(nullEntry);
  }

  /**
   * Notifies listeners that an entry has been automatically removed due to
   * expiration, eviction, or
   * eligibility for garbage collection. This should be called every time
   * expireEntries or
   * evictEntry is called (once the lock is released).
   */
  void processPendingNotifications() {
    RemovalNotification<K, V> notification;
    while ((notification = removalNotificationQueue.poll()) != null) {
      try {
        removalListener.onRemoval(notification);
      } catch (Throwable e) {
        logger.log(Level.WARNING, "Exception thrown by removal listener", e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  final Segment<K, V>[] newSegmentArray(int ssize) {
    return (Segment<K, V>[]) new Segment<?, ?>[ssize];
  }

  // Inner Classes

  /**
   * Segments are specialized versions of hash tables. This subclass inherits from
   * ReentrantLock
   * opportunistically, just to simplify some locking and avoid separate
   * construction.
   */
  @SuppressWarnings("serial") // This class is never serialized.
  static class Segment<K, V> extends ReentrantLock {

    /*
     * TODO(fry): Consider copying variables (like evictsBySize) from outer class
     * into this class.
     * It will require more memory but will reduce indirection.
     */

    /*
     * Segments maintain a table of entry lists that are ALWAYS kept in a consistent
     * state,
     * so can be read without locking.
     * 段(Segments)维护着一个始终保持一致状态的条目列表表，因此可以在无锁的情况下进行读取。
     * 
     * Next fields of nodes are immutable (final). All list additions are performed
     * at the
     * front of each bin.
     * 节点的next字段是不可变的(final)。所有列表添加操作都在每个桶(bin)的前端执行。
     * 
     * This makes it easy to check changes, and also fast to traverse. When nodes
     * would
     * otherwise be changed, new nodes are created to replace them.
     * 这使得检查变更容易，遍历也很快。当需要改变节点时，会创建新节点来替换它们。
     * 
     * This works well for hash tables since the bin lists tend to be short.
     * (The average length is less than two.)
     * 这种方式对哈希表很有效，因为桶列表往往很短。(平均长度小于2)
     *
     * Read operations can thus proceed without locking, but rely on selected uses
     * of
     * volatiles to ensure that completed write operations performed by other
     * threads are noticed.
     * 因此读操作可以在无锁的情况下进行，但依赖于对volatile变量的选择性使用，
     * 以确保能察觉到其他线程完成的写操作。
     * 
     * For most purposes, the "count" field, tracking the number of elements, serves
     * as
     * that volatile variable ensuring visibility.
     * 在大多数情况下，追踪元素数量的"count"字段作为确保可见性的volatile变量。
     * 
     * This is convenient because this field needs to be read in many read
     * operations anyway:
     * 这很方便，因为无论如何这个字段在许多读操作中都需要被读取：
     *
     * - All (unsynchronized) read operations must first read the "count" field, and
     * should
     * not look at table entries if it is 0.
     * - 所有（非同步的）读操作必须首先读取"count"字段，如果它是0就不应该查看表项。
     *
     * - All (synchronized) write operations should write to the "count" field after
     * structurally changing any bin.
     * - 所有（同步的）写操作在对任何桶进行结构性更改后都应该写入"count"字段。
     * 
     * The operations must not take any action that could even momentarily cause a
     * concurrent
     * read operation to see inconsistent data.
     * 这些操作不能采取任何可能导致并发读操作看到不一致数据的行为，即使是暂时的。
     * 
     * This is made easier by the nature of the read operations in Map. For example,
     * no
     * operation can reveal that the table has grown but the threshold has not yet
     * been updated.
     * 这因Map中读操作的特性而变得更容易。例如，没有操作能够显示出表已经增长但阈值尚未更新的情况。
     *
     * As a guide, all critical volatile reads and writes to the count field are
     * marked
     * in code comments.
     * 作为指导，所有对count字段的关键volatile读写操作都在代码注释中标记。
     */

    /**
     * 对LocalCache的弱引用。使用@Weak注解标记，允许在不需要时被GC回收。
     * 主要用于访问缓存的全局配置和策略。
     */
    @Weak
    final LocalCache<K, V> map;

    /**
     * 该段中存活元素的数量。
     * volatile保证多线程可见性，是实现无锁读的关键。
     * 其他线程通过读取这个值来感知写入操作的完成。
     */
    volatile int count;

    /**
     * 该段中存活元素的总权重。
     * 用于基于权重的驱逐策略，由@GuardedBy注解保证只能在持有锁时访问。
     */
    @GuardedBy("this")
    long totalWeight;

    /**
     * 表结构修改的计数器。
     * 用于在批量读取操作时确保看到一致的快照：
     * 如果在遍历段过程中modCount发生变化，说明可能看到不一致的状态，通常需要重试。
     */
    int modCount;

    /**
     * 扩容阈值。当表的大小超过此阈值时进行扩容。
     * 值始终是容量的75%（capacity * 0.75）。
     */
    int threshold;

    /**
     * 每个段的哈希表。
     * 使用AtomicReferenceArray保证原子性，volatile保证可见性。
     */
    @CheckForNull
    volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;

    /**
     * 该段的最大权重限制。
     * 如果没有设置最大值则为UNSET_INT。
     */
    final long maxSegmentWeight;

    /**
     * 键引用队列。
     * 包含已被垃圾回收的键的条目，这些条目需要内部清理。
     * 当使用弱引用键时才会创建此队列。
     */
    @CheckForNull
    final ReferenceQueue<K> keyReferenceQueue;

    /**
     * 值引用队列。
     * 包含值已被垃圾回收的值引用，这些引用需要内部清理。
     * 当使用软引用或弱引用值时才会创建此队列。
     */
    @CheckForNull
    final ReferenceQueue<V> valueReferenceQueue;

    /**
     * 最近访问队列。
     * 用于记录哪些条目被访问过，以更新访问列表的顺序。
     * 当达到DRAIN_THRESHOLD阈值或段发生写入操作时，
     * 会批量处理（排空）该队列。
     */
    final Queue<ReferenceEntry<K, V>> recencyQueue;

    /**
     * 读操作计数器。
     * 记录自上次写入以来的读取次数，用于在少量读操作时排空队列。
     * 使用AtomicInteger保证原子性。
     */
    final AtomicInteger readCount = new AtomicInteger();

    /**
     * 写入时间顺序队列。
     * 保存当前map中的元素，按写入时间排序。
     * 元素在写入时添加到队列尾部。
     * 由@GuardedBy注解保证只能在持有锁时访问。
     */
    @GuardedBy("this")
    final Queue<ReferenceEntry<K, V>> writeQueue;

    /**
     * 访问时间顺序队列。
     * 保存当前map中的元素，按访问时间排序。
     * 元素在被访问时（注意写入也计为访问）添加到队列尾部。
     * 由@GuardedBy注解保证只能在持有锁时访问。
     */
    @GuardedBy("this")
    final Queue<ReferenceEntry<K, V>> accessQueue;

    /**
     * 缓存统计计数器。
     * 累积记录缓存的统计信息，如命中率、加载时间等。
     */
    final StatsCounter statsCounter;

    /**
     * Segment段的构造函数
     *
     * @param map              所属的LocalCache实例
     * @param initialCapacity  初始容量
     * @param maxSegmentWeight 段的最大权重限制
     * @param statsCounter     统计计数器
     */
    Segment(
        LocalCache<K, V> map,
        int initialCapacity,
        long maxSegmentWeight,
        StatsCounter statsCounter) {
      // 保存对LocalCache的引用，用于访问缓存的全局配置
      this.map = map;

      // 设置段的最大权重限制，用于基于权重的驱逐策略
      this.maxSegmentWeight = maxSegmentWeight;

      // 设置统计计数器，用于收集性能指标
      this.statsCounter = checkNotNull(statsCounter);

      // 初始化哈希表，使用给定的初始容量创建条目数组
      initTable(newEntryArray(initialCapacity));

      // 如果使用弱引用键，创建键引用队列用于处理被GC回收的键
      // 否则设置为null
      keyReferenceQueue = map.usesKeyReferences() ? new ReferenceQueue<>() : null;

      // 如果使用软引用或弱引用值，创建值引用队列用于处理被GC回收的值
      // 否则设置为null
      valueReferenceQueue = map.usesValueReferences() ? new ReferenceQueue<>() : null;

      // 如果使用访问顺序，创建并发访问队列用于记录最近访问的条目
      // 否则使用一个空队列实现（丢弃所有操作）
      recencyQueue = map.usesAccessQueue() ? new ConcurrentLinkedQueue<>() : LocalCache.discardingQueue();

      // 如果使用写入顺序，创建写入队列用于记录写入顺序
      // 否则使用一个空队列实现
      writeQueue = map.usesWriteQueue() ? new WriteQueue<>() : LocalCache.discardingQueue();

      // 如果使用访问顺序，创建访问队列用于维护访问顺序
      // 否则使用一个空队列实现
      accessQueue = map.usesAccessQueue() ? new AccessQueue<>() : LocalCache.discardingQueue();
    }

    AtomicReferenceArray<ReferenceEntry<K, V>> newEntryArray(int size) {
      return new AtomicReferenceArray<>(size);
    }

    void initTable(AtomicReferenceArray<ReferenceEntry<K, V>> newTable) {
      this.threshold = newTable.length() * 3 / 4; // 0.75
      if (!map.customWeigher() && this.threshold == maxSegmentWeight) {
        // prevent spurious expansion before eviction
        this.threshold++;
      }
      this.table = newTable;
    }

    @GuardedBy("this")
    ReferenceEntry<K, V> newEntry(K key, int hash, @CheckForNull ReferenceEntry<K, V> next) {
      return map.entryFactory.newEntry(this, checkNotNull(key), hash, next);
    }

    /**
     * Copies {@code original} into a new entry chained to {@code newNext}. Returns
     * the new entry,
     * or {@code null} if {@code original} was already garbage collected.
     */
    @CheckForNull
    @GuardedBy("this")
    ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
      K key = original.getKey();
      if (key == null) {
        // key collected
        return null;
      }

      ValueReference<K, V> valueReference = original.getValueReference();
      V value = valueReference.get();
      if ((value == null) && valueReference.isActive()) {
        // value collected
        return null;
      }

      ReferenceEntry<K, V> newEntry = map.entryFactory.copyEntry(this, original, newNext, key);
      newEntry.setValueReference(valueReference.copyFor(this.valueReferenceQueue, value, newEntry));
      return newEntry;
    }

    /**
     * Sets a new value of an entry. Adds newly created entries at the end of the
     * access queue.
     */
    @GuardedBy("this")
    void setValue(ReferenceEntry<K, V> entry, K key, V value, long now) {
      ValueReference<K, V> previous = entry.getValueReference();
      int weight = map.weigher.weigh(key, value);
      checkState(weight >= 0, "Weights must be non-negative");

      ValueReference<K, V> valueReference = map.valueStrength.referenceValue(this, entry, value, weight);
      entry.setValueReference(valueReference);
      recordWrite(entry, weight, now);
      previous.notifyNewValue(value);
    }

    // loading

    @CanIgnoreReturnValue
    V get(K key, int hash, CacheLoader<? super K, V> loader) throws ExecutionException {
      checkNotNull(key);
      checkNotNull(loader);
      try {
        if (count != 0) { // read-volatile
          // don't call getLiveEntry, which would ignore loading values
          ReferenceEntry<K, V> e = getEntry(key, hash);
          if (e != null) {
            long now = map.ticker.read();
            V value = getLiveValue(e, now);
            if (value != null) {
              recordRead(e, now);
              statsCounter.recordHits(1);
              return scheduleRefresh(e, key, hash, value, now, loader);
            }
            ValueReference<K, V> valueReference = e.getValueReference();
            if (valueReference.isLoading()) {
              return waitForLoadingValue(e, key, valueReference);
            }
          }
        }

        // at this point e is either null or expired;
        return lockedGetOrLoad(key, hash, loader);
      } catch (ExecutionException ee) {
        Throwable cause = ee.getCause();
        if (cause instanceof Error) {
          throw new ExecutionError((Error) cause);
        } else if (cause instanceof RuntimeException) {
          throw new UncheckedExecutionException(cause);
        }
        throw ee;
      } finally {
        postReadCleanup();
      }
    }

    @CheckForNull
    V get(Object key, int hash) {
      try {
        if (count != 0) { // read-volatile
          long now = map.ticker.read();
          ReferenceEntry<K, V> e = getLiveEntry(key, hash, now);
          if (e == null) {
            return null;
          }

          V value = e.getValueReference().get();
          if (value != null) {
            recordRead(e, now);
            return scheduleRefresh(e, e.getKey(), hash, value, now, map.defaultLoader);
          }
          tryDrainReferenceQueues();
        }
        return null;
      } finally {
        postReadCleanup();
      }
    }

    V lockedGetOrLoad(K key, int hash, CacheLoader<? super K, V> loader) throws ExecutionException {
      ReferenceEntry<K, V> e;
      ValueReference<K, V> valueReference = null;
      LoadingValueReference<K, V> loadingValueReference = null;
      boolean createNewEntry = true;

      lock();
      try {
        // re-read ticker once inside the lock
        long now = map.ticker.read();
        preWriteCleanup(now);

        int newCount = this.count - 1;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            valueReference = e.getValueReference();
            if (valueReference.isLoading()) {
              createNewEntry = false;
            } else {
              V value = valueReference.get();
              if (value == null) {
                enqueueNotification(
                    entryKey, hash, value, valueReference.getWeight(), RemovalCause.COLLECTED);
              } else if (map.isExpired(e, now)) {
                // This is a duplicate check, as preWriteCleanup already purged expired
                // entries, but let's accommodate an incorrect expiration queue.
                enqueueNotification(
                    entryKey, hash, value, valueReference.getWeight(), RemovalCause.EXPIRED);
              } else {
                recordLockedRead(e, now);
                statsCounter.recordHits(1);
                // we were concurrent with loading; don't consider refresh
                return value;
              }

              // immediately reuse invalid entries
              writeQueue.remove(e);
              accessQueue.remove(e);
              this.count = newCount; // write-volatile
            }
            break;
          }
        }

        if (createNewEntry) {
          loadingValueReference = new LoadingValueReference<>();

          if (e == null) {
            e = newEntry(key, hash, first);
            e.setValueReference(loadingValueReference);
            table.set(index, e);
          } else {
            e.setValueReference(loadingValueReference);
          }
        }
      } finally {
        unlock();
        postWriteCleanup();
      }

      if (createNewEntry) {
        try {
          // Synchronizes on the entry to allow failing fast when a recursive load is
          // detected. This may be circumvented when an entry is copied, but will fail
          // fast most
          // of the time.
          synchronized (e) {
            return loadSync(key, hash, loadingValueReference, loader);
          }
        } finally {
          statsCounter.recordMisses(1);
        }
      } else {
        // The entry already exists. Wait for loading.
        return waitForLoadingValue(e, key, valueReference);
      }
    }

    V waitForLoadingValue(ReferenceEntry<K, V> e, K key, ValueReference<K, V> valueReference)
        throws ExecutionException {
      if (!valueReference.isLoading()) {
        throw new AssertionError();
      }

      checkState(!Thread.holdsLock(e), "Recursive load of: %s", key);
      // don't consider expiration as we're concurrent with loading
      try {
        V value = valueReference.waitForValue();
        if (value == null) {
          throw new InvalidCacheLoadException("CacheLoader returned null for key " + key + ".");
        }
        // re-read ticker now that loading has completed
        long now = map.ticker.read();
        recordRead(e, now);
        return value;
      } finally {
        statsCounter.recordMisses(1);
      }
    }

    @CheckForNull
    V compute(
        K key,
        int hash,
        BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> function) {
      ReferenceEntry<K, V> e;
      ValueReference<K, V> valueReference = null;
      ComputingValueReference<K, V> computingValueReference = null;
      boolean createNewEntry = true;
      V newValue;

      lock();
      try {
        // re-read ticker once inside the lock
        long now = map.ticker.read();
        preWriteCleanup(now);

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            valueReference = e.getValueReference();
            if (map.isExpired(e, now)) {
              // This is a duplicate check, as preWriteCleanup already purged expired
              // entries, but let's accommodate an incorrect expiration queue.
              enqueueNotification(
                  entryKey,
                  hash,
                  valueReference.get(),
                  valueReference.getWeight(),
                  RemovalCause.EXPIRED);
            }

            // immediately reuse invalid entries
            writeQueue.remove(e);
            accessQueue.remove(e);
            createNewEntry = false;
            break;
          }
        }

        // note valueReference can be an existing value or even itself another loading
        // value if
        // the value for the key is already being computed.
        computingValueReference = new ComputingValueReference<>(valueReference);

        if (e == null) {
          createNewEntry = true;
          e = newEntry(key, hash, first);
          e.setValueReference(computingValueReference);
          table.set(index, e);
        } else {
          e.setValueReference(computingValueReference);
        }

        newValue = computingValueReference.compute(key, function);
        if (newValue != null) {
          if (valueReference != null && newValue == valueReference.get()) {
            computingValueReference.set(newValue);
            e.setValueReference(valueReference);
            recordWrite(e, 0, now); // no change in weight
            return newValue;
          }
          try {
            return getAndRecordStats(key, hash, computingValueReference, immediateFuture(newValue));
          } catch (ExecutionException exception) {
            throw new AssertionError("impossible; Futures.immediateFuture can't throw");
          }
        } else if (createNewEntry || valueReference.isLoading()) {
          removeLoadingValue(key, hash, computingValueReference);
          return null;
        } else {
          removeEntry(e, hash, RemovalCause.EXPLICIT);
          return null;
        }
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    // at most one of loadSync/loadAsync may be called for any given
    // LoadingValueReference

    V loadSync(
        K key,
        int hash,
        LoadingValueReference<K, V> loadingValueReference,
        CacheLoader<? super K, V> loader)
        throws ExecutionException {
      ListenableFuture<V> loadingFuture = loadingValueReference.loadFuture(key, loader);
      return getAndRecordStats(key, hash, loadingValueReference, loadingFuture);
    }

    ListenableFuture<V> loadAsync(
        final K key,
        final int hash,
        final LoadingValueReference<K, V> loadingValueReference,
        CacheLoader<? super K, V> loader) {
      final ListenableFuture<V> loadingFuture = loadingValueReference.loadFuture(key, loader);
      loadingFuture.addListener(
          () -> {
            try {
              getAndRecordStats(key, hash, loadingValueReference, loadingFuture);
            } catch (Throwable t) {
              logger.log(Level.WARNING, "Exception thrown during refresh", t);
              loadingValueReference.setException(t);
            }
          },
          directExecutor());
      return loadingFuture;
    }

    /**
     * Waits uninterruptibly for {@code newValue} to be loaded, and then records
     * loading stats.
     */
    @CanIgnoreReturnValue
    V getAndRecordStats(
        K key,
        int hash,
        LoadingValueReference<K, V> loadingValueReference,
        ListenableFuture<V> newValue)
        throws ExecutionException {
      V value = null;
      try {
        value = getUninterruptibly(newValue);
        if (value == null) {
          throw new InvalidCacheLoadException("CacheLoader returned null for key " + key + ".");
        }
        statsCounter.recordLoadSuccess(loadingValueReference.elapsedNanos());
        storeLoadedValue(key, hash, loadingValueReference, value);
        return value;
      } finally {
        if (value == null) {
          statsCounter.recordLoadException(loadingValueReference.elapsedNanos());
          removeLoadingValue(key, hash, loadingValueReference);
        }
      }
    }

    V scheduleRefresh(
        ReferenceEntry<K, V> entry,
        K key,
        int hash,
        V oldValue,
        long now,
        CacheLoader<? super K, V> loader) {
      if (map.refreshes()
          && (now - entry.getWriteTime() > map.refreshNanos)
          && !entry.getValueReference().isLoading()) {
        V newValue = refresh(key, hash, loader, true);
        if (newValue != null) {
          return newValue;
        }
      }
      return oldValue;
    }

    /**
     * Refreshes the value associated with {@code key}, unless another thread is
     * already doing so.
     * Returns the newly refreshed value associated with {@code key} if it was
     * refreshed inline, or
     * {@code null} if another thread is performing the refresh or if an error
     * occurs during
     * refresh.
     */
    @CanIgnoreReturnValue
    @CheckForNull
    V refresh(K key, int hash, CacheLoader<? super K, V> loader, boolean checkTime) {
      final LoadingValueReference<K, V> loadingValueReference = insertLoadingValueReference(key, hash, checkTime);
      if (loadingValueReference == null) {
        return null;
      }

      ListenableFuture<V> result = loadAsync(key, hash, loadingValueReference, loader);
      if (result.isDone()) {
        try {
          return Uninterruptibles.getUninterruptibly(result);
        } catch (Throwable t) {
          // don't let refresh exceptions propagate; error was already logged
        }
      }
      return null;
    }

    /**
     * Returns a newly inserted {@code LoadingValueReference}, or null if the live
     * value reference
     * is already loading.
     */
    @CheckForNull
    LoadingValueReference<K, V> insertLoadingValueReference(
        final K key, final int hash, boolean checkTime) {
      ReferenceEntry<K, V> e = null;
      lock();
      try {
        long now = map.ticker.read();
        preWriteCleanup(now);

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        // Look for an existing entry.
        for (e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            // We found an existing entry.

            ValueReference<K, V> valueReference = e.getValueReference();
            if (valueReference.isLoading()
                || (checkTime && (now - e.getWriteTime() < map.refreshNanos))) {
              // refresh is a no-op if loading is pending
              // if checkTime, we want to check *after* acquiring the lock if refresh still
              // needs
              // to be scheduled
              return null;
            }

            // continue returning old value while loading
            ++modCount;
            LoadingValueReference<K, V> loadingValueReference = new LoadingValueReference<>(valueReference);
            e.setValueReference(loadingValueReference);
            return loadingValueReference;
          }
        }

        ++modCount;
        LoadingValueReference<K, V> loadingValueReference = new LoadingValueReference<>();
        e = newEntry(key, hash, first);
        e.setValueReference(loadingValueReference);
        table.set(index, e);
        return loadingValueReference;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    // reference queues, for garbage collection cleanup

    /** Cleanup collected entries when the lock is available. */
    void tryDrainReferenceQueues() {
      if (tryLock()) {
        try {
          drainReferenceQueues();
        } finally {
          unlock();
        }
      }
    }

    /**
     * Drain the key and value reference queues, cleaning up internal entries
     * containing garbage
     * collected keys or values.
     */
    @GuardedBy("this")
    void drainReferenceQueues() {
      if (map.usesKeyReferences()) {
        drainKeyReferenceQueue();
      }
      if (map.usesValueReferences()) {
        drainValueReferenceQueue();
      }
    }

    @GuardedBy("this")
    void drainKeyReferenceQueue() {
      // 用于存储从引用队列中取出的引用
      Reference<? extends K> ref;
      // 清理计数器
      int i = 0;

      // 不断从引用队列中获取并处理被GC回收的键引用
      while ((ref = keyReferenceQueue.poll()) != null) {
        // 由于Reference本身就是ReferenceEntry，所以可以直接转换
        // 这里的类型转换是安全的，因为我们知道放入队列的都是ReferenceEntry
        @SuppressWarnings("unchecked")
        ReferenceEntry<K, V> entry = (ReferenceEntry<K, V>) ref;

        // 通知map处理已经被回收的键对应的条目
        map.reclaimKey(entry);

        // 如果清理的数量达到上限DRAIN_MAX，则退出循环
        // 这是为了防止清理时间过长影响其他操作
        if (++i == DRAIN_MAX) {
          break;
        }
      }
    }

    @GuardedBy("this")
    void drainValueReferenceQueue() {
      Reference<? extends V> ref;
      int i = 0;
      while ((ref = valueReferenceQueue.poll()) != null) {
        @SuppressWarnings("unchecked")
        ValueReference<K, V> valueReference = (ValueReference<K, V>) ref;
        map.reclaimValue(valueReference);
        if (++i == DRAIN_MAX) {
          break;
        }
      }
    }

    /** Clears all entries from the key and value reference queues. */
    void clearReferenceQueues() {
      if (map.usesKeyReferences()) {
        clearKeyReferenceQueue();
      }
      if (map.usesValueReferences()) {
        clearValueReferenceQueue();
      }
    }

    void clearKeyReferenceQueue() {
      while (keyReferenceQueue.poll() != null) {
      }
    }

    void clearValueReferenceQueue() {
      while (valueReferenceQueue.poll() != null) {
      }
    }

    // recency queue, shared by expiration and eviction

    /**
     * Records the relative order in which this read was performed by adding
     * {@code entry} to the
     * recency queue. At write-time, or when the queue is full past the threshold,
     * the queue will be
     * drained and the entries therein processed.
     *
     * <p>
     * Note: locked reads should use {@link #recordLockedRead}.
     */
    void recordRead(ReferenceEntry<K, V> entry, long now) {
      if (map.recordsAccess()) {
        entry.setAccessTime(now);
      }
      recencyQueue.add(entry);
    }

    /**
     * Updates the eviction metadata that {@code entry} was just read. This
     * currently amounts to
     * adding {@code entry} to relevant eviction lists.
     *
     * <p>
     * Note: this method should only be called under lock, as it directly
     * manipulates the
     * eviction queues. Unlocked reads should use {@link #recordRead}.
     */
    @GuardedBy("this")
    void recordLockedRead(ReferenceEntry<K, V> entry, long now) {
      if (map.recordsAccess()) {
        entry.setAccessTime(now);
      }
      accessQueue.add(entry);
    }

    /**
     * Updates eviction metadata that {@code entry} was just written. This currently
     * amounts to
     * adding {@code entry} to relevant eviction lists.
     */
    @GuardedBy("this")
    void recordWrite(ReferenceEntry<K, V> entry, int weight, long now) {
      // we are already under lock, so drain the recency queue immediately
      drainRecencyQueue();
      totalWeight += weight;

      if (map.recordsAccess()) {
        entry.setAccessTime(now);
      }
      if (map.recordsWrite()) {
        entry.setWriteTime(now);
      }
      accessQueue.add(entry);
      writeQueue.add(entry);
    }

    /**
     * Drains the recency queue, updating eviction metadata that the entries therein
     * were read in
     * the specified relative order. This currently amounts to adding them to
     * relevant eviction
     * lists (accounting for the fact that they could have been removed from the map
     * since being
     * added to the recency queue).
     */
    @GuardedBy("this")
    void drainRecencyQueue() {
      ReferenceEntry<K, V> e;
      while ((e = recencyQueue.poll()) != null) {
        // An entry may be in the recency queue despite it being removed from
        // the map . This can occur when the entry was concurrently read while a
        // writer is removing it from the segment or after a clear has removed
        // all the segment's entries.
        if (accessQueue.contains(e)) {
          accessQueue.add(e);
        }
      }
    }

    // expiration

    /** Cleanup expired entries when the lock is available. */
    void tryExpireEntries(long now) {
      if (tryLock()) {
        try {
          expireEntries(now);
        } finally {
          unlock();
          // don't call postWriteCleanup as we're in a read
        }
      }
    }

    @GuardedBy("this")
    void expireEntries(long now) {
      drainRecencyQueue();

      ReferenceEntry<K, V> e;
      while ((e = writeQueue.peek()) != null && map.isExpired(e, now)) {
        if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
          throw new AssertionError();
        }
      }
      while ((e = accessQueue.peek()) != null && map.isExpired(e, now)) {
        if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
          throw new AssertionError();
        }
      }
    }

    // eviction

    @GuardedBy("this")
    void enqueueNotification(
        @CheckForNull K key, int hash, @CheckForNull V value, int weight, RemovalCause cause) {
      totalWeight -= weight;
      if (cause.wasEvicted()) {
        statsCounter.recordEviction();
      }
      if (map.removalNotificationQueue != DISCARDING_QUEUE) {
        RemovalNotification<K, V> notification = RemovalNotification.create(key, value, cause);
        map.removalNotificationQueue.offer(notification);
      }
    }

    /**
     * Performs eviction if the segment is over capacity. Avoids flushing the entire
     * cache if the
     * newest entry exceeds the maximum weight all on its own.
     *
     * @param newest the most recently added entry
     */
    @GuardedBy("this")
    void evictEntries(ReferenceEntry<K, V> newest) {
      if (!map.evictsBySize()) {
        return;
      }

      drainRecencyQueue();

      // If the newest entry by itself is too heavy for the segment, don't bother
      // evicting
      // anything else, just that
      if (newest.getValueReference().getWeight() > maxSegmentWeight) {
        if (!removeEntry(newest, newest.getHash(), RemovalCause.SIZE)) {
          throw new AssertionError();
        }
      }

      while (totalWeight > maxSegmentWeight) {
        ReferenceEntry<K, V> e = getNextEvictable();
        if (!removeEntry(e, e.getHash(), RemovalCause.SIZE)) {
          throw new AssertionError();
        }
      }
    }

    // TODO(fry): instead implement this with an eviction head
    @GuardedBy("this")
    ReferenceEntry<K, V> getNextEvictable() {
      for (ReferenceEntry<K, V> e : accessQueue) {
        int weight = e.getValueReference().getWeight();
        if (weight > 0) {
          return e;
        }
      }
      throw new AssertionError();
    }

    /** Returns first entry of bin for given hash. */
    ReferenceEntry<K, V> getFirst(int hash) {
      // read this volatile field only once
      AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
      return table.get(hash & (table.length() - 1));
    }

    // Specialized implementations of map methods

    @CheckForNull
    ReferenceEntry<K, V> getEntry(Object key, int hash) {
      for (ReferenceEntry<K, V> e = getFirst(hash); e != null; e = e.getNext()) {
        if (e.getHash() != hash) {
          continue;
        }

        K entryKey = e.getKey();
        if (entryKey == null) {
          tryDrainReferenceQueues();
          continue;
        }

        if (map.keyEquivalence.equivalent(key, entryKey)) {
          return e;
        }
      }

      return null;
    }

    @CheckForNull
    ReferenceEntry<K, V> getLiveEntry(Object key, int hash, long now) {
      ReferenceEntry<K, V> e = getEntry(key, hash);
      if (e == null) {
        return null;
      } else if (map.isExpired(e, now)) {
        tryExpireEntries(now);
        return null;
      }
      return e;
    }

    /**
     * Gets the value from an entry. Returns null if the entry is invalid,
     * partially-collected,
     * loading, or expired.
     */
    V getLiveValue(ReferenceEntry<K, V> entry, long now) {
      if (entry.getKey() == null) {
        tryDrainReferenceQueues();
        return null;
      }
      V value = entry.getValueReference().get();
      if (value == null) {
        tryDrainReferenceQueues();
        return null;
      }

      if (map.isExpired(entry, now)) {
        tryExpireEntries(now);
        return null;
      }
      return value;
    }

    boolean containsKey(Object key, int hash) {
      try {
        if (count != 0) { // read-volatile
          long now = map.ticker.read();
          ReferenceEntry<K, V> e = getLiveEntry(key, hash, now);
          if (e == null) {
            return false;
          }
          return e.getValueReference().get() != null;
        }

        return false;
      } finally {
        postReadCleanup();
      }
    }

    /**
     * This method is a convenience for testing. Code should call
     * {@link LocalCache#containsValue}
     * directly.
     */
    @VisibleForTesting
    boolean containsValue(Object value) {
      try {
        if (count != 0) { // read-volatile
          long now = map.ticker.read();
          AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
          int length = table.length();
          for (int i = 0; i < length; ++i) {
            for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
              V entryValue = getLiveValue(e, now);
              if (entryValue == null) {
                continue;
              }
              if (map.valueEquivalence.equivalent(value, entryValue)) {
                return true;
              }
            }
          }
        }

        return false;
      } finally {
        postReadCleanup();
      }
    }

    @CanIgnoreReturnValue
    @CheckForNull
    V put(K key, int hash, V value, boolean onlyIfAbsent) {
      lock();
      try {
        long now = map.ticker.read();
        preWriteCleanup(now);

        int newCount = this.count + 1;
        if (newCount > this.threshold) { // ensure capacity
          expand();
          newCount = this.count + 1;
        }

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        // Look for an existing entry.
        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            // We found an existing entry.

            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();

            if (entryValue == null) {
              ++modCount;
              if (valueReference.isActive()) {
                enqueueNotification(
                    key, hash, entryValue, valueReference.getWeight(), RemovalCause.COLLECTED);
                setValue(e, key, value, now);
                newCount = this.count; // count remains unchanged
              } else {
                setValue(e, key, value, now);
                newCount = this.count + 1;
              }
              this.count = newCount; // write-volatile
              evictEntries(e);
              return null;
            } else if (onlyIfAbsent) {
              // Mimic
              // "if (!map.containsKey(key)) ...
              // else return map.get(key);
              recordLockedRead(e, now);
              return entryValue;
            } else {
              // clobber existing entry, count remains unchanged
              ++modCount;
              enqueueNotification(
                  key, hash, entryValue, valueReference.getWeight(), RemovalCause.REPLACED);
              setValue(e, key, value, now);
              evictEntries(e);
              return entryValue;
            }
          }
        }

        // Create a new entry.
        ++modCount;
        ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
        setValue(newEntry, key, value, now);
        table.set(index, newEntry);
        newCount = this.count + 1;
        this.count = newCount; // write-volatile
        evictEntries(newEntry);
        return null;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    /** Expands the table if possible. */
    @GuardedBy("this")
    void expand() {
      AtomicReferenceArray<ReferenceEntry<K, V>> oldTable = table;
      int oldCapacity = oldTable.length();
      if (oldCapacity >= MAXIMUM_CAPACITY) {
        return;
      }

      /*
       * Reclassify nodes in each list to new Map. Because we are using power-of-two
       * expansion, the
       * elements from each bin must either stay at same index, or move with a power
       * of two offset.
       * We eliminate unnecessary node creation by catching cases where old nodes can
       * be reused
       * because their next fields won't change. Statistically, at the default
       * threshold, only about
       * one-sixth of them need cloning when a table doubles. The nodes they replace
       * will be garbage
       * collectable as soon as they are no longer referenced by any reader thread
       * that may be in
       * the midst of traversing table right now.
       */

      int newCount = count;
      AtomicReferenceArray<ReferenceEntry<K, V>> newTable = newEntryArray(oldCapacity << 1);
      threshold = newTable.length() * 3 / 4;
      int newMask = newTable.length() - 1;
      for (int oldIndex = 0; oldIndex < oldCapacity; ++oldIndex) {
        // We need to guarantee that any existing reads of old Map can
        // proceed. So we cannot yet null out each bin.
        ReferenceEntry<K, V> head = oldTable.get(oldIndex);

        if (head != null) {
          ReferenceEntry<K, V> next = head.getNext();
          int headIndex = head.getHash() & newMask;

          // Single node on list
          if (next == null) {
            newTable.set(headIndex, head);
          } else {
            // Reuse the consecutive sequence of nodes with the same target
            // index from the end of the list. tail points to the first
            // entry in the reusable list.
            ReferenceEntry<K, V> tail = head;
            int tailIndex = headIndex;
            for (ReferenceEntry<K, V> e = next; e != null; e = e.getNext()) {
              int newIndex = e.getHash() & newMask;
              if (newIndex != tailIndex) {
                // The index changed. We'll need to copy the previous entry.
                tailIndex = newIndex;
                tail = e;
              }
            }
            newTable.set(tailIndex, tail);

            // Clone nodes leading up to the tail.
            for (ReferenceEntry<K, V> e = head; e != tail; e = e.getNext()) {
              int newIndex = e.getHash() & newMask;
              ReferenceEntry<K, V> newNext = newTable.get(newIndex);
              ReferenceEntry<K, V> newFirst = copyEntry(e, newNext);
              if (newFirst != null) {
                newTable.set(newIndex, newFirst);
              } else {
                removeCollectedEntry(e);
                newCount--;
              }
            }
          }
        }
      }
      table = newTable;
      this.count = newCount;
    }

    boolean replace(K key, int hash, V oldValue, V newValue) {
      lock();
      try {
        long now = map.ticker.read();
        preWriteCleanup(now);

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();
            if (entryValue == null) {
              if (valueReference.isActive()) {
                // If the value disappeared, this entry is partially collected.
                int newCount = this.count - 1;
                ++modCount;
                ReferenceEntry<K, V> newFirst = removeValueFromChain(
                    first,
                    e,
                    entryKey,
                    hash,
                    entryValue,
                    valueReference,
                    RemovalCause.COLLECTED);
                newCount = this.count - 1;
                table.set(index, newFirst);
                this.count = newCount; // write-volatile
              }
              return false;
            }

            if (map.valueEquivalence.equivalent(oldValue, entryValue)) {
              ++modCount;
              enqueueNotification(
                  key, hash, entryValue, valueReference.getWeight(), RemovalCause.REPLACED);
              setValue(e, key, newValue, now);
              evictEntries(e);
              return true;
            } else {
              // Mimic
              // "if (map.containsKey(key) && map.get(key).equals(oldValue))..."
              recordLockedRead(e, now);
              return false;
            }
          }
        }

        return false;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    @CheckForNull
    V replace(K key, int hash, V newValue) {
      lock();
      try {
        long now = map.ticker.read();
        preWriteCleanup(now);

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();
            if (entryValue == null) {
              if (valueReference.isActive()) {
                // If the value disappeared, this entry is partially collected.
                int newCount = this.count - 1;
                ++modCount;
                ReferenceEntry<K, V> newFirst = removeValueFromChain(
                    first,
                    e,
                    entryKey,
                    hash,
                    entryValue,
                    valueReference,
                    RemovalCause.COLLECTED);
                newCount = this.count - 1;
                table.set(index, newFirst);
                this.count = newCount; // write-volatile
              }
              return null;
            }

            ++modCount;
            enqueueNotification(
                key, hash, entryValue, valueReference.getWeight(), RemovalCause.REPLACED);
            setValue(e, key, newValue, now);
            evictEntries(e);
            return entryValue;
          }
        }

        return null;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    @CheckForNull
    V remove(Object key, int hash) {
      lock();
      try {
        long now = map.ticker.read();
        preWriteCleanup(now);

        int newCount = this.count - 1;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();

            RemovalCause cause;
            if (entryValue != null) {
              cause = RemovalCause.EXPLICIT;
            } else if (valueReference.isActive()) {
              cause = RemovalCause.COLLECTED;
            } else {
              // currently loading
              return null;
            }

            ++modCount;
            ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, entryKey, hash, entryValue, valueReference,
                cause);
            newCount = this.count - 1;
            table.set(index, newFirst);
            this.count = newCount; // write-volatile
            return entryValue;
          }
        }

        return null;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    boolean remove(Object key, int hash, Object value) {
      lock();
      try {
        long now = map.ticker.read();
        preWriteCleanup(now);

        int newCount = this.count - 1;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();

            RemovalCause cause;
            if (map.valueEquivalence.equivalent(value, entryValue)) {
              cause = RemovalCause.EXPLICIT;
            } else if (entryValue == null && valueReference.isActive()) {
              cause = RemovalCause.COLLECTED;
            } else {
              // currently loading
              return false;
            }

            ++modCount;
            ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, entryKey, hash, entryValue, valueReference,
                cause);
            newCount = this.count - 1;
            table.set(index, newFirst);
            this.count = newCount; // write-volatile
            return (cause == RemovalCause.EXPLICIT);
          }
        }

        return false;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    @CanIgnoreReturnValue
    boolean storeLoadedValue(
        K key, int hash, LoadingValueReference<K, V> oldValueReference, V newValue) {
      lock();
      try {
        long now = map.ticker.read();
        preWriteCleanup(now);

        int newCount = this.count + 1;
        if (newCount > this.threshold) { // ensure capacity
          expand();
          newCount = this.count + 1;
        }

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();
            // replace the old LoadingValueReference if it's live, otherwise
            // perform a putIfAbsent
            if (oldValueReference == valueReference
                || (entryValue == null && valueReference != UNSET)) {
              ++modCount;
              if (oldValueReference.isActive()) {
                RemovalCause cause = (entryValue == null) ? RemovalCause.COLLECTED : RemovalCause.REPLACED;
                enqueueNotification(key, hash, entryValue, oldValueReference.getWeight(), cause);
                newCount--;
              }
              setValue(e, key, newValue, now);
              this.count = newCount; // write-volatile
              evictEntries(e);
              return true;
            }

            // the loaded value was already clobbered
            enqueueNotification(key, hash, newValue, 0, RemovalCause.REPLACED);
            return false;
          }
        }

        ++modCount;
        ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
        setValue(newEntry, key, newValue, now);
        table.set(index, newEntry);
        this.count = newCount; // write-volatile
        evictEntries(newEntry);
        return true;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    void clear() {
      if (count != 0) { // read-volatile
        lock();
        try {
          long now = map.ticker.read();
          preWriteCleanup(now);

          AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
          for (int i = 0; i < table.length(); ++i) {
            for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
              // Loading references aren't actually in the map yet.
              if (e.getValueReference().isActive()) {
                K key = e.getKey();
                V value = e.getValueReference().get();
                RemovalCause cause = (key == null || value == null) ? RemovalCause.COLLECTED : RemovalCause.EXPLICIT;
                enqueueNotification(
                    key, e.getHash(), value, e.getValueReference().getWeight(), cause);
              }
            }
          }
          for (int i = 0; i < table.length(); ++i) {
            table.set(i, null);
          }
          clearReferenceQueues();
          writeQueue.clear();
          accessQueue.clear();
          readCount.set(0);

          ++modCount;
          count = 0; // write-volatile
        } finally {
          unlock();
          postWriteCleanup();
        }
      }
    }

    /**
     * 从链表中移除指定条目的值引用
     * 该方法必须在持有段锁的情况下调用（由@GuardedBy("this")保证）
     *
     * @param first          链表的第一个节点
     * @param entry          要移除的条目
     * @param key            条目的键（可能为null）
     * @param hash           键的哈希值
     * @param value          条目的值
     * @param valueReference 值的引用
     * @param cause          移除的原因
     * @return 更新后的链表头节点
     */
    @GuardedBy("this")
    @CheckForNull
    ReferenceEntry<K, V> removeValueFromChain(
        ReferenceEntry<K, V> first,
        ReferenceEntry<K, V> entry,
        @CheckForNull K key,
        int hash,
        V value,
        ValueReference<K, V> valueReference,
        RemovalCause cause) {

      // 将移除事件加入通知队列，以便异步通知监听器
      // 包含：键、哈希值、值、权重和移除原因
      enqueueNotification(key, hash, value, valueReference.getWeight(), cause);

      // 从写入顺序队列中移除条目
      writeQueue.remove(entry);
      // 从访问顺序队列中移除条目
      accessQueue.remove(entry);

      // 检查值是否正在加载中
      if (valueReference.isLoading()) {
        // 如果值正在加载，通知加载过程该值已被设置为null
        valueReference.notifyNewValue(null);
        // 直接返回原链表头，不修改链表结构
        return first;
      } else {
        // 如果值不是正在加载，则从链表中移除整个条目
        return removeEntryFromChain(first, entry);
      }
    }

    /**
     * 从链表中移除指定的条目，并重新构建链表
     * 该方法会复制entry之前的所有有效节点，并将它们与entry之后的节点链接起来
     *
     * @param first 原链表的第一个节点
     * @param entry 要移除的目标节点
     * @return 新链表的第一个节点，如果所有节点都被回收则返回null
     */
    @GuardedBy("this")
    @CheckForNull
    ReferenceEntry<K, V> removeEntryFromChain(
        ReferenceEntry<K, V> first, ReferenceEntry<K, V> entry) {
      // 记录当前计数
      int newCount = count;
      // 获取要移除节点之后的第一个节点作为新的起始点
      ReferenceEntry<K, V> newFirst = entry.getNext();

      // 遍历entry之前的所有节点
      for (ReferenceEntry<K, V> e = first; e != entry; e = e.getNext()) {
        // 尝试复制当前节点，并将其next指向newFirst
        ReferenceEntry<K, V> next = copyEntry(e, newFirst);
        if (next != null) {
          // 如果复制成功，更新newFirst为新复制的节点
          newFirst = next;
        } else {
          // 如果复制失败（节点已被GC回收），移除该节点
          removeCollectedEntry(e);
          // 减少计数
          newCount--;
        }
      }
      // 更新段的计数值
      this.count = newCount;
      // 返回新的链表头
      return newFirst;
    }

    /**
     * 移除一个已被垃圾回收的条目
     * 该方法处理条目被回收的后续清理工作，包括：
     * 1. 发送移除通知
     * 2. 从写入队列移除
     * 3. 从访问队列移除
     *
     * @param entry 需要被移除的条目
     */
    @GuardedBy("this")
    void removeCollectedEntry(ReferenceEntry<K, V> entry) {
      enqueueNotification(
          entry.getKey(),
          entry.getHash(),
          entry.getValueReference().get(),
          entry.getValueReference().getWeight(),
          RemovalCause.COLLECTED);
      writeQueue.remove(entry);
      accessQueue.remove(entry);
    }

    /**
     * 移除一个键已被垃圾回收的条目
     * 该方法在确定键已被GC回收后调用，用于清理缓存中的无效条目
     *
     * @param entry 要移除的条目引用
     * @param hash  条目的哈希值
     * @return 如果成功移除返回true，如果条目不存在返回false
     */
    @CanIgnoreReturnValue // 表示返回值可以被调用者忽略
    boolean reclaimKey(ReferenceEntry<K, V> entry, int hash) {
      // 获取段锁，确保接下来的操作是线程安全的
      lock();
      try {
        // 预先计算移除一个元素后的新计数值
        int newCount = count - 1;

        // 获取当前段的哈希表引用
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        // 计算条目在哈希表中的索引位置（哈希值与表长度-1进行位与运算）
        int index = hash & (table.length() - 1);
        // 获取该桶中的第一个条目
        ReferenceEntry<K, V> first = table.get(index);

        // 遍历该桶中的条目链表
        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          // 如果找到了目标条目（引用相等）
          if (e == entry) {
            // 增加修改计数，用于快速失败机制
            ++modCount;

            // 从链表中移除该条目，并重新构建链表
            // 同时处理相关的值引用和触发移除通知
            ReferenceEntry<K, V> newFirst = removeValueFromChain(
                first, // 原链表头
                e, // 要移除的条目
                e.getKey(), // 条目的键
                hash, // 哈希值
                e.getValueReference().get(), // 条目的值
                e.getValueReference(), // 值的引用
                RemovalCause.COLLECTED // 移除原因：被GC收集
            );

            // 重新计算新的计数值
            newCount = this.count - 1;
            // 更新桶的头节点为新的链表头
            table.set(index, newFirst);
            // 更新计数值（volatile写，确保其他线程可见）
            this.count = newCount;

            return true; // 返回true表示成功找到并移除了条目
          }
        }

        return false; // 如果遍历完链表都没找到目标条目，返回false
      } finally {
        // 释放段锁
        unlock();
        // 执行写操作后的清理工作（如处理引用队列、维护统计信息等）
        postWriteCleanup();
      }
    }

    /**
     * 移除一个值已被垃圾回收的缓存条目
     * 
     * 该方法会在值引用被GC回收后调用，用于清理缓存中的无效条目。
     * 方法会在持有段锁的情况下执行，确保线程安全。
     *
     * @param key            要移除的条目的键
     * @param hash           键的哈希值
     * @param valueReference 被回收的值的引用
     * @return 如果找到并成功移除了条目返回true，否则返回false
     */
    @CanIgnoreReturnValue // 表示调用者可以忽略返回值
    boolean reclaimValue(K key, int hash, ValueReference<K, V> valueReference) {
      // 获取段锁，确保接下来的操作是线程安全的
      lock();
      try {
        // 预计的新计数值（当前计数-1）
        int newCount = this.count - 1;
        // 获取当前段的哈希表
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        // 计算目标条目在哈希表中的索引位置
        int index = hash & (table.length() - 1);
        // 获取该桶中的第一个条目
        ReferenceEntry<K, V> first = table.get(index);

        // 遍历该桶中的条目链表
        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          // 获取当前条目的键
          K entryKey = e.getKey();
          // 检查是否找到目标条目：
          // 1. 哈希值相等
          // 2. 键不为null
          // 3. 键等价（使用自定义的等价性比较器）
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            // 获取当前条目的值引用
            ValueReference<K, V> v = e.getValueReference();
            // 确认是否是我们要找的值引用
            if (v == valueReference) {
              // 增加修改计数，用于快速失败机制
              ++modCount;
              // 从链中移除该条目，并重新构建链表
              ReferenceEntry<K, V> newFirst = removeValueFromChain(
                  first, // 原链表头
                  e, // 要移除的条目
                  entryKey, // 条目的键
                  hash, // 哈希值
                  valueReference.get(), // 条目的值
                  valueReference, // 值引用
                  RemovalCause.COLLECTED // 移除原因：被GC收集
              );
              // 更新计数
              newCount = this.count - 1;
              // 更新桶的头节点
              table.set(index, newFirst);
              // 更新计数值（volatile写，确保可见性）
              this.count = newCount;
              return true; // 成功找到并移除条目
            }
            return false; // 找到键但值引用不匹配
          }
        }

        return false; // 未找到目标条目
      } finally {
        // 释放段锁
        unlock();
        // 如果当前线程没有持有锁（不是在put操作中），执行后续清理
        if (!isHeldByCurrentThread()) { // 不在put操作中才清理
          postWriteCleanup();
        }
      }
    }

    /**
     * 移除一个正在加载中的值
     * 
     * 该方法在并发加载场景下被调用，用于：
     * 1. 如果值正在加载中，将值引用恢复为旧值
     * 2. 如果值加载已结束但未成功，从缓存中移除该条目
     * 
     * @param key            要处理的键
     * @param hash           键的哈希值
     * @param valueReference 要移除的加载中的值引用
     * @return 如果找到并成功处理了条目返回true，否则返回false
     */
    @CanIgnoreReturnValue // 表示调用者可以忽略返回值
    boolean removeLoadingValue(K key, int hash, LoadingValueReference<K, V> valueReference) {
      // 获取段锁，确保线程安全
      lock();
      try {
        // 获取当前段的哈希表
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        // 计算桶索引：哈希值与(表长度-1)进行位与运算
        int index = hash & (table.length() - 1);
        // 获取桶中的第一个条目
        ReferenceEntry<K, V> first = table.get(index);

        // 遍历桶中的条目链表
        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          // 获取当前条目的键
          K entryKey = e.getKey();
          // 检查是否找到目标条目：
          // 1. 哈希值相等
          // 2. 键不为null
          // 3. 键等价（使用map的键等价性比较器）
          if (e.getHash() == hash
              && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            // 获取当前条目的值引用
            ValueReference<K, V> v = e.getValueReference();
            // 检查是否是目标值引用
            if (v == valueReference) {
              // 如果值引用仍在加载中（活动状态）
              if (valueReference.isActive()) {
                // 恢复为旧的值引用
                e.setValueReference(valueReference.getOldValue());
              } else {
                // 如果值引用已不活动，从链表中移除整个条目
                ReferenceEntry<K, V> newFirst = removeEntryFromChain(first, e);
                // 更新桶的头节点
                table.set(index, newFirst);
              }
              return true; // 成功处理了条目
            }
            return false; // 找到键但值引用不匹配
          }
        }

        return false; // 未找到目标条目
      } finally {
        // 释放段锁
        unlock();
        // 执行写操作后的清理工作（如处理引用队列等）
        postWriteCleanup();
      }
    }

    @VisibleForTesting
    @GuardedBy("this")
    @CanIgnoreReturnValue
    boolean removeEntry(ReferenceEntry<K, V> entry, int hash, RemovalCause cause) {
      int newCount = this.count - 1;
      AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
      int index = hash & (table.length() - 1);
      ReferenceEntry<K, V> first = table.get(index);

      for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
        if (e == entry) {
          ++modCount;
          ReferenceEntry<K, V> newFirst = removeValueFromChain(
              first,
              e,
              e.getKey(),
              hash,
              e.getValueReference().get(),
              e.getValueReference(),
              cause);
          newCount = this.count - 1;
          table.set(index, newFirst);
          this.count = newCount; // write-volatile
          return true;
        }
      }

      return false;
    }

    /**
     * Performs routine cleanup following a read. Normally cleanup happens during
     * writes. If cleanup
     * is not observed after a sufficient number of reads, try cleaning up from the
     * read thread.
     */
    void postReadCleanup() {
      if ((readCount.incrementAndGet() & DRAIN_THRESHOLD) == 0) {
        cleanUp();
      }
    }

    /**
     * Performs routine cleanup prior to executing a write. This should be called
     * every time a write
     * thread acquires the segment lock, immediately after acquiring the lock.
     *
     * <p>
     * Post-condition: expireEntries has been run.
     */
    @GuardedBy("this")
    void preWriteCleanup(long now) {
      runLockedCleanup(now);
    }

    /** Performs routine cleanup following a write. */
    void postWriteCleanup() {
      runUnlockedCleanup();
    }

    void cleanUp() {
      long now = map.ticker.read();
      runLockedCleanup(now);
      runUnlockedCleanup();
    }

    void runLockedCleanup(long now) {
      if (tryLock()) {
        try {
          drainReferenceQueues();
          expireEntries(now); // calls drainRecencyQueue
          readCount.set(0);
        } finally {
          unlock();
        }
      }
    }

    void runUnlockedCleanup() {
      // locked cleanup may generate notifications we can send unlocked
      if (!isHeldByCurrentThread()) {
        map.processPendingNotifications();
      }
    }
  }

  static class LoadingValueReference<K, V> implements ValueReference<K, V> {
    volatile ValueReference<K, V> oldValue;

    // TODO(fry): rename get, then extend AbstractFuture instead of containing
    // SettableFuture
    final SettableFuture<V> futureValue = SettableFuture.create();
    final Stopwatch stopwatch = Stopwatch.createUnstarted();

    public LoadingValueReference() {
      this(null);
    }

    public LoadingValueReference(@CheckForNull ValueReference<K, V> oldValue) {
      this.oldValue = (oldValue == null) ? LocalCache.unset() : oldValue;
    }

    @Override
    public boolean isLoading() {
      return true;
    }

    @Override
    public boolean isActive() {
      return oldValue.isActive();
    }

    @Override
    public int getWeight() {
      return oldValue.getWeight();
    }

    @CanIgnoreReturnValue
    public boolean set(@CheckForNull V newValue) {
      return futureValue.set(newValue);
    }

    @CanIgnoreReturnValue
    public boolean setException(Throwable t) {
      return futureValue.setException(t);
    }

    private ListenableFuture<V> fullyFailedFuture(Throwable t) {
      return immediateFailedFuture(t);
    }

    @Override
    public void notifyNewValue(@CheckForNull V newValue) {
      if (newValue != null) {
        // The pending load was clobbered by a manual write.
        // Unblock all pending gets, and have them return the new value.
        set(newValue);
      } else {
        // The pending load was removed. Delay notifications until loading completes.
        oldValue = unset();
      }

      // TODO(fry): could also cancel loading if we had a handle on its future
    }

    public ListenableFuture<V> loadFuture(K key, CacheLoader<? super K, V> loader) {
      try {
        stopwatch.start();
        V previousValue = oldValue.get();
        if (previousValue == null) {
          V newValue = loader.load(key);
          return set(newValue) ? futureValue : immediateFuture(newValue);
        }
        ListenableFuture<V> newValue = loader.reload(key, previousValue);
        if (newValue == null) {
          return immediateFuture(null);
        }
        // To avoid a race, make sure the refreshed value is set into
        // loadingValueReference
        // *before* returning newValue from the cache query.
        return transform(
            newValue,
            newResult -> {
              LoadingValueReference.this.set(newResult);
              return newResult;
            },
            directExecutor());
      } catch (Throwable t) {
        ListenableFuture<V> result = setException(t) ? futureValue : fullyFailedFuture(t);
        if (t instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        return result;
      }
    }

    @CheckForNull
    public V compute(
        K key, BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> function) {
      stopwatch.start();
      V previousValue;
      try {
        previousValue = oldValue.waitForValue();
      } catch (ExecutionException e) {
        previousValue = null;
      }
      V newValue;
      try {
        newValue = function.apply(key, previousValue);
      } catch (Throwable th) {
        this.setException(th);
        throw th;
      }
      this.set(newValue);
      return newValue;
    }

    public long elapsedNanos() {
      return stopwatch.elapsed(NANOSECONDS);
    }

    @Override
    public V waitForValue() throws ExecutionException {
      return getUninterruptibly(futureValue);
    }

    @Override
    public V get() {
      return oldValue.get();
    }

    public ValueReference<K, V> getOldValue() {
      return oldValue;
    }

    @Override
    public ReferenceEntry<K, V> getEntry() {
      return null;
    }

    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, @CheckForNull V value, ReferenceEntry<K, V> entry) {
      return this;
    }
  }

  static class ComputingValueReference<K, V> extends LoadingValueReference<K, V> {
    ComputingValueReference(ValueReference<K, V> oldValue) {
      super(oldValue);
    }

    @Override
    public boolean isLoading() {
      return false;
    }
  }

  // Queues

  /**
   * A custom queue for managing eviction order. Note that this is tightly
   * integrated with {@code
   * ReferenceEntry}, upon which it relies to perform its linking.
   *
   * <p>
   * Note that this entire implementation makes the assumption that all elements
   * which are in the
   * map are also in this queue, and that all elements not in the queue are not in
   * the map.
   *
   * <p>
   * The benefits of creating our own queue are that (1) we can replace elements
   * in the middle of
   * the queue as part of copyWriteEntry, and (2) the contains method is highly
   * optimized for the
   * current model.
   */
  /**
   * 自定义的写入顺序队列，用于管理缓存条目的驱逐顺序
   * 该队列与ReferenceEntry紧密集成，依赖ReferenceEntry来维护节点间的链接关系
   *
   * 实现假设：
   * 1. 所有在map中的元素都必须在这个队列中
   * 2. 所有不在队列中的元素都不在map中
   *
   * 自定义队列的优势：
   * 1. 可以在copyWriteEntry操作中替换队列中间的元素
   * 2. contains方法针对当前模型高度优化
   */
  static final class WriteQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
    /**
     * 队列的哨兵节点（头节点）
     * 使用匿名内部类实现，维护了一个双向循环链表结构
     */
    final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {
      // 返回最大时间戳，确保头节点总是在队列末尾
      @Override
      public long getWriteTime() {
        return Long.MAX_VALUE;
      }

      @Override
      public void setWriteTime(long time) {
        // 空实现，头节点时间戳不可修改
      }

      // 使用@Weak注解标记引用，防止内存泄漏
      @Weak
      ReferenceEntry<K, V> nextWrite = this; // 初始指向自身

      @Override
      public ReferenceEntry<K, V> getNextInWriteQueue() {
        return nextWrite;
      }

      @Override
      public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
        this.nextWrite = next;
      }

      @Weak
      ReferenceEntry<K, V> previousWrite = this; // 初始指向自身

      @Override
      public ReferenceEntry<K, V> getPreviousInWriteQueue() {
        return previousWrite;
      }

      @Override
      public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
        this.previousWrite = previous;
      }
    };

    // Queue接口实现

    /**
     * 将条目添加到队列尾部
     * 
     * @param entry 要添加的条目
     * @return 永远返回true
     */
    @Override
    public boolean offer(ReferenceEntry<K, V> entry) {
      // 断开entry原有的链接
      connectWriteOrder(entry.getPreviousInWriteQueue(), entry.getNextInWriteQueue());

      // 添加到队列尾部（head之前）
      connectWriteOrder(head.getPreviousInWriteQueue(), entry);
      connectWriteOrder(entry, head);

      return true;
    }

    /**
     * 查看队列头部的元素，但不移除
     * 
     * @return 队列头部元素，如果队列为空返回null
     */
    @CheckForNull
    @Override
    public ReferenceEntry<K, V> peek() {
      ReferenceEntry<K, V> next = head.getNextInWriteQueue();
      return (next == head) ? null : next; // 如果下一个是head，说明队列为空
    }

    /**
     * 移除并返回队列头部的元素
     * 
     * @return 队列头部元素，如果队列为空返回null
     */
    @CheckForNull
    @Override
    public ReferenceEntry<K, V> poll() {
      ReferenceEntry<K, V> next = head.getNextInWriteQueue();
      if (next == head) {
        return null; // 队列为空
      }

      remove(next); // 移除元素
      return next;
    }

    /**
     * 从队列中移除指定元素
     * 
     * @param o 要移除的元素
     * @return 如果元素存在且被移除返回true
     */
    @Override
    @SuppressWarnings("unchecked")
    @CanIgnoreReturnValue
    public boolean remove(Object o) {
      ReferenceEntry<K, V> e = (ReferenceEntry<K, V>) o;
      ReferenceEntry<K, V> previous = e.getPreviousInWriteQueue();
      ReferenceEntry<K, V> next = e.getNextInWriteQueue();
      connectWriteOrder(previous, next);
      nullifyWriteOrder(e);

      return next != NullEntry.INSTANCE;
    }

    /**
     * 检查元素是否在队列中
     * 通过检查元素的next引用是否为NullEntry来判断
     */
    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
      ReferenceEntry<K, V> e = (ReferenceEntry<K, V>) o;
      return e.getNextInWriteQueue() != NullEntry.INSTANCE;
    }

    /**
     * 检查队列是否为空
     * 通过检查head的next是否指向自身来判断
     */
    @Override
    public boolean isEmpty() {
      return head.getNextInWriteQueue() == head;
    }

    /**
     * 计算队列大小
     * 通过遍历整个队列来计数
     */
    @Override
    public int size() {
      int size = 0;
      for (ReferenceEntry<K, V> e = head.getNextInWriteQueue(); e != head; e = e.getNextInWriteQueue()) {
        size++;
      }
      return size;
    }

    /**
     * 清空队列
     * 清除所有节点的引用关系，最后重置head的指向
     */
    @Override
    public void clear() {
      ReferenceEntry<K, V> e = head.getNextInWriteQueue();
      while (e != head) {
        ReferenceEntry<K, V> next = e.getNextInWriteQueue();
        nullifyWriteOrder(e); // 清除节点的引用
        e = next;
      }

      // 重置head指向自身
      head.setNextInWriteQueue(head);
      head.setPreviousInWriteQueue(head);
    }

    /**
     * 返回队列的迭代器
     * 使用AbstractSequentialIterator简化迭代器实现
     */
    @Override
    public Iterator<ReferenceEntry<K, V>> iterator() {
      return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
        @CheckForNull
        @Override
        protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
          ReferenceEntry<K, V> next = previous.getNextInWriteQueue();
          return (next == head) ? null : next;
        }
      };
    }
  }

  /**
   * A custom queue for managing access order. Note that this is tightly
   * integrated with {@code
   * ReferenceEntry}, upon which it relies to perform its linking.
   *
   * <p>
   * Note that this entire implementation makes the assumption that all elements
   * which are in the
   * map are also in this queue, and that all elements not in the queue are not in
   * the map.
   *
   * <p>
   * The benefits of creating our own queue are that (1) we can replace elements
   * in the middle of
   * the queue as part of copyWriteEntry, and (2) the contains method is highly
   * optimized for the
   * current model.
   */
  static final class AccessQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
    final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {

      @Override
      public long getAccessTime() {
        return Long.MAX_VALUE;
      }

      @Override
      public void setAccessTime(long time) {
      }

      @Weak
      ReferenceEntry<K, V> nextAccess = this;

      @Override
      public ReferenceEntry<K, V> getNextInAccessQueue() {
        return nextAccess;
      }

      @Override
      public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
        this.nextAccess = next;
      }

      @Weak
      ReferenceEntry<K, V> previousAccess = this;

      @Override
      public ReferenceEntry<K, V> getPreviousInAccessQueue() {
        return previousAccess;
      }

      @Override
      public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
        this.previousAccess = previous;
      }
    };

    // implements Queue

    @Override
    public boolean offer(ReferenceEntry<K, V> entry) {
      // unlink
      connectAccessOrder(entry.getPreviousInAccessQueue(), entry.getNextInAccessQueue());

      // add to tail
      connectAccessOrder(head.getPreviousInAccessQueue(), entry);
      connectAccessOrder(entry, head);

      return true;
    }

    @CheckForNull
    @Override
    public ReferenceEntry<K, V> peek() {
      ReferenceEntry<K, V> next = head.getNextInAccessQueue();
      return (next == head) ? null : next;
    }

    @CheckForNull
    @Override
    public ReferenceEntry<K, V> poll() {
      ReferenceEntry<K, V> next = head.getNextInAccessQueue();
      if (next == head) {
        return null;
      }

      remove(next);
      return next;
    }

    @Override
    @SuppressWarnings("unchecked")
    @CanIgnoreReturnValue
    public boolean remove(Object o) {
      ReferenceEntry<K, V> e = (ReferenceEntry<K, V>) o;
      ReferenceEntry<K, V> previous = e.getPreviousInAccessQueue();
      ReferenceEntry<K, V> next = e.getNextInAccessQueue();
      connectAccessOrder(previous, next);
      nullifyAccessOrder(e);

      return next != NullEntry.INSTANCE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
      ReferenceEntry<K, V> e = (ReferenceEntry<K, V>) o;
      return e.getNextInAccessQueue() != NullEntry.INSTANCE;
    }

    @Override
    public boolean isEmpty() {
      return head.getNextInAccessQueue() == head;
    }

    @Override
    public int size() {
      int size = 0;
      for (ReferenceEntry<K, V> e = head.getNextInAccessQueue(); e != head; e = e.getNextInAccessQueue()) {
        size++;
      }
      return size;
    }

    @Override
    public void clear() {
      ReferenceEntry<K, V> e = head.getNextInAccessQueue();
      while (e != head) {
        ReferenceEntry<K, V> next = e.getNextInAccessQueue();
        nullifyAccessOrder(e);
        e = next;
      }

      head.setNextInAccessQueue(head);
      head.setPreviousInAccessQueue(head);
    }

    @Override
    public Iterator<ReferenceEntry<K, V>> iterator() {
      return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
        @CheckForNull
        @Override
        protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
          ReferenceEntry<K, V> next = previous.getNextInAccessQueue();
          return (next == head) ? null : next;
        }
      };
    }
  }

  // Cache support

  public void cleanUp() {
    for (Segment<?, ?> segment : segments) {
      segment.cleanUp();
    }
  }

  // ConcurrentMap methods

  @Override
  public boolean isEmpty() {
    /*
     * Sum per-segment modCounts to avoid mis-reporting when elements are
     * concurrently added and
     * removed in one segment while checking another, in which case the table was
     * never actually
     * empty at any point. (The sum ensures accuracy up through at least 1<<31
     * per-segment
     * modifications before recheck.) Method containsValue() uses similar
     * constructions for
     * stability checks.
     */
    long sum = 0L;
    Segment<K, V>[] segments = this.segments;
    for (Segment<K, V> segment : segments) {
      if (segment.count != 0) {
        return false;
      }
      sum += segment.modCount;
    }

    if (sum != 0L) { // recheck unless no modifications
      for (Segment<K, V> segment : segments) {
        if (segment.count != 0) {
          return false;
        }
        sum -= segment.modCount;
      }
      return sum == 0L;
    }
    return true;
  }

  long longSize() {
    Segment<K, V>[] segments = this.segments;
    long sum = 0;
    for (Segment<K, V> segment : segments) {
      sum += segment.count;
    }
    return sum;
  }

  @Override
  public int size() {
    return Ints.saturatedCast(longSize());
  }

  @CanIgnoreReturnValue // TODO(b/27479612): consider removing this
  @Override
  @CheckForNull
  public V get(@CheckForNull Object key) {
    if (key == null) {
      return null;
    }
    int hash = hash(key);
    return segmentFor(hash).get(key, hash);
  }

  @CanIgnoreReturnValue // TODO(b/27479612): consider removing this
  V get(K key, CacheLoader<? super K, V> loader) throws ExecutionException {
    int hash = hash(checkNotNull(key));
    return segmentFor(hash).get(key, hash, loader);
  }

  @CheckForNull
  public V getIfPresent(Object key) {
    int hash = hash(checkNotNull(key));
    V value = segmentFor(hash).get(key, hash);
    if (value == null) {
      globalStatsCounter.recordMisses(1);
    } else {
      globalStatsCounter.recordHits(1);
    }
    return value;
  }

  @Override
  @CheckForNull
  public V getOrDefault(@CheckForNull Object key, @CheckForNull V defaultValue) {
    V result = get(key);
    return (result != null) ? result : defaultValue;
  }

  V getOrLoad(K key) throws ExecutionException {
    return get(key, defaultLoader);
  }

  ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
    int hits = 0;
    int misses = 0;

    ImmutableMap.Builder<K, V> result = ImmutableMap.builder();
    for (Object key : keys) {
      V value = get(key);
      if (value == null) {
        misses++;
      } else {
        // TODO(fry): store entry key instead of query key
        @SuppressWarnings("unchecked")
        K castKey = (K) key;
        result.put(castKey, value);
        hits++;
      }
    }
    globalStatsCounter.recordHits(hits);
    globalStatsCounter.recordMisses(misses);
    return result.buildKeepingLast();
  }

  ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
    int hits = 0;
    int misses = 0;

    Map<K, V> result = Maps.newLinkedHashMap();
    Set<K> keysToLoad = Sets.newLinkedHashSet();
    for (K key : keys) {
      V value = get(key);
      if (!result.containsKey(key)) {
        result.put(key, value);
        if (value == null) {
          misses++;
          keysToLoad.add(key);
        } else {
          hits++;
        }
      }
    }

    try {
      if (!keysToLoad.isEmpty()) {
        try {
          Map<K, V> newEntries = loadAll(unmodifiableSet(keysToLoad), defaultLoader);
          for (K key : keysToLoad) {
            V value = newEntries.get(key);
            if (value == null) {
              throw new InvalidCacheLoadException("loadAll failed to return a value for " + key);
            }
            result.put(key, value);
          }
        } catch (UnsupportedLoadingOperationException e) {
          // loadAll not implemented, fallback to load
          for (K key : keysToLoad) {
            misses--; // get will count this miss
            result.put(key, get(key, defaultLoader));
          }
        }
      }
      return ImmutableMap.copyOf(result);
    } finally {
      globalStatsCounter.recordHits(hits);
      globalStatsCounter.recordMisses(misses);
    }
  }

  /**
   * Returns the result of calling {@link CacheLoader#loadAll}, or null if
   * {@code loader} doesn't
   * implement {@code loadAll}.
   */
  @CheckForNull
  Map<K, V> loadAll(Set<? extends K> keys, CacheLoader<? super K, V> loader)
      throws ExecutionException {
    checkNotNull(loader);
    checkNotNull(keys);
    Stopwatch stopwatch = Stopwatch.createStarted();
    Map<K, V> result;
    boolean success = false;
    try {
      @SuppressWarnings("unchecked") // safe since all keys extend K
      Map<K, V> map = (Map<K, V>) loader.loadAll(keys);
      result = map;
      success = true;
    } catch (UnsupportedLoadingOperationException e) {
      success = true;
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExecutionException(e);
    } catch (RuntimeException e) {
      throw new UncheckedExecutionException(e);
    } catch (Exception e) {
      throw new ExecutionException(e);
    } catch (Error e) {
      throw new ExecutionError(e);
    } finally {
      if (!success) {
        globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
      }
    }

    if (result == null) {
      globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
      throw new InvalidCacheLoadException(loader + " returned null map from loadAll");
    }

    stopwatch.stop();
    // TODO(fry): batch by segment
    boolean nullsPresent = false;
    for (Entry<K, V> entry : result.entrySet()) {
      K key = entry.getKey();
      V value = entry.getValue();
      if (key == null || value == null) {
        // delay failure until non-null entries are stored
        nullsPresent = true;
      } else {
        put(key, value);
      }
    }

    if (nullsPresent) {
      globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
      throw new InvalidCacheLoadException(loader + " returned null keys or values from loadAll");
    }

    // TODO(fry): record count of loaded entries
    globalStatsCounter.recordLoadSuccess(stopwatch.elapsed(NANOSECONDS));
    return result;
  }

  /**
   * Returns the internal entry for the specified key. The entry may be loading,
   * expired, or
   * partially collected.
   */
  @CheckForNull
  ReferenceEntry<K, V> getEntry(@CheckForNull Object key) {
    // does not impact recency ordering
    if (key == null) {
      return null;
    }
    int hash = hash(key);
    return segmentFor(hash).getEntry(key, hash);
  }

  void refresh(K key) {
    int hash = hash(checkNotNull(key));
    segmentFor(hash).refresh(key, hash, defaultLoader, false);
  }

  @Override
  public boolean containsKey(@CheckForNull Object key) {
    // does not impact recency ordering
    if (key == null) {
      return false;
    }
    int hash = hash(key);
    return segmentFor(hash).containsKey(key, hash);
  }

  @Override
  public boolean containsValue(@CheckForNull Object value) {
    // does not impact recency ordering
    if (value == null) {
      return false;
    }

    // This implementation is patterned after ConcurrentHashMap, but without the
    // only way for it to return a false negative would be for the target value to
    // jump
    // around in the map
    // such that none of the subsequent iterations observed it, despite the fact
    // that at every point
    // in time it was present somewhere int the map. This becomes increasingly
    // unlikely as
    // CONTAINS_VALUE_RETRIES increases, though without locking it is theoretically
    // possible.
    long now = ticker.read();
    final Segment<K, V>[] segments = this.segments;
    long last = -1L;
    for (int i = 0; i < CONTAINS_VALUE_RETRIES; i++) {
      long sum = 0L;
      for (Segment<K, V> segment : segments) {
        // ensure visibility of most recent completed write
        int unused = segment.count; // read-volatile

        AtomicReferenceArray<ReferenceEntry<K, V>> table = segment.table;
        for (int j = 0; j < table.length(); j++) {
          for (ReferenceEntry<K, V> e = table.get(j); e != null; e = e.getNext()) {
            V v = segment.getLiveValue(e, now);
            if (v != null && valueEquivalence.equivalent(value, v)) {
              return true;
            }
          }
        }
        sum += segment.modCount;
      }
      if (sum == last) {
        break;
      }
      last = sum;
    }
    return false;
  }

  @CheckForNull
  @CanIgnoreReturnValue
  @Override
  public V put(K key, V value) {
    checkNotNull(key);
    checkNotNull(value);
    int hash = hash(key);
    return segmentFor(hash).put(key, hash, value, false);
  }

  @CheckForNull
  @Override
  public V putIfAbsent(K key, V value) {
    checkNotNull(key);
    checkNotNull(value);
    int hash = hash(key);
    return segmentFor(hash).put(key, hash, value, true);
  }

  @Override
  @CheckForNull
  public V compute(
      K key, BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> function) {
    checkNotNull(key);
    checkNotNull(function);
    int hash = hash(key);
    return segmentFor(hash).compute(key, hash, function);
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
    checkNotNull(key);
    checkNotNull(function);
    return compute(key, (k, oldValue) -> (oldValue == null) ? function.apply(key) : oldValue);
  }

  @Override
  @CheckForNull
  public V computeIfPresent(
      K key, BiFunction<? super K, ? super V, ? extends @Nullable V> function) {
    checkNotNull(key);
    checkNotNull(function);
    return compute(key, (k, oldValue) -> (oldValue == null) ? null : function.apply(k, oldValue));
  }

  @Override
  @CheckForNull
  public V merge(
      K key, V newValue, BiFunction<? super V, ? super V, ? extends @Nullable V> function) {
    checkNotNull(key);
    checkNotNull(newValue);
    checkNotNull(function);
    return compute(
        key, (k, oldValue) -> (oldValue == null) ? newValue : function.apply(oldValue, newValue));
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    for (Entry<? extends K, ? extends V> e : m.entrySet()) {
      put(e.getKey(), e.getValue());
    }
  }

  @CheckForNull
  @CanIgnoreReturnValue
  @Override
  public V remove(@CheckForNull Object key) {
    if (key == null) {
      return null;
    }
    int hash = hash(key);
    return segmentFor(hash).remove(key, hash);
  }

  @CanIgnoreReturnValue
  @Override
  public boolean remove(@CheckForNull Object key, @CheckForNull Object value) {
    if (key == null || value == null) {
      return false;
    }
    int hash = hash(key);
    return segmentFor(hash).remove(key, hash, value);
  }

  @CanIgnoreReturnValue
  @Override
  public boolean replace(K key, @CheckForNull V oldValue, V newValue) {
    checkNotNull(key);
    checkNotNull(newValue);
    if (oldValue == null) {
      return false;
    }
    int hash = hash(key);
    return segmentFor(hash).replace(key, hash, oldValue, newValue);
  }

  @CheckForNull
  @CanIgnoreReturnValue
  @Override
  public V replace(K key, V value) {
    checkNotNull(key);
    checkNotNull(value);
    int hash = hash(key);
    return segmentFor(hash).replace(key, hash, value);
  }

  @Override
  public void clear() {
    for (Segment<K, V> segment : segments) {
      segment.clear();
    }
  }

  void invalidateAll(Iterable<?> keys) {
    // TODO(fry): batch by segment
    for (Object key : keys) {
      remove(key);
    }
  }

  @LazyInit
  @RetainedWith
  @CheckForNull
  Set<K> keySet;

  @Override
  public Set<K> keySet() {
    // does not impact recency ordering
    Set<K> ks = keySet;
    return (ks != null) ? ks : (keySet = new KeySet());
  }

  @LazyInit
  @RetainedWith
  @CheckForNull
  Collection<V> values;

  @Override
  public Collection<V> values() {
    // does not impact recency ordering
    Collection<V> vs = values;
    return (vs != null) ? vs : (values = new Values());
  }

  @LazyInit
  @RetainedWith
  @CheckForNull
  Set<Entry<K, V>> entrySet;

  @Override
  @GwtIncompatible // Not supported.
  public Set<Entry<K, V>> entrySet() {
    // does not impact recency ordering
    Set<Entry<K, V>> es = entrySet;
    return (es != null) ? es : (entrySet = new EntrySet());
  }

  // Iterator Support

  abstract class HashIterator<T> implements Iterator<T> {

    int nextSegmentIndex;
    int nextTableIndex;
    @CheckForNull
    Segment<K, V> currentSegment;
    @CheckForNull
    AtomicReferenceArray<ReferenceEntry<K, V>> currentTable;
    @CheckForNull
    ReferenceEntry<K, V> nextEntry;
    @CheckForNull
    WriteThroughEntry nextExternal;
    @CheckForNull
    WriteThroughEntry lastReturned;

    HashIterator() {
      nextSegmentIndex = segments.length - 1;
      nextTableIndex = -1;
      advance();
    }

    @Override
    public abstract T next();

    final void advance() {
      nextExternal = null;

      if (nextInChain()) {
        return;
      }

      if (nextInTable()) {
        return;
      }

      while (nextSegmentIndex >= 0) {
        currentSegment = segments[nextSegmentIndex--];
        if (currentSegment.count != 0) {
          currentTable = currentSegment.table;
          nextTableIndex = currentTable.length() - 1;
          if (nextInTable()) {
            return;
          }
        }
      }
    }

    /**
     * Finds the next entry in the current chain. Returns true if an entry was
     * found.
     */
    boolean nextInChain() {
      if (nextEntry != null) {
        for (nextEntry = nextEntry.getNext(); nextEntry != null; nextEntry = nextEntry.getNext()) {
          if (advanceTo(nextEntry)) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Finds the next entry in the current table. Returns true if an entry was
     * found.
     */
    boolean nextInTable() {
      while (nextTableIndex >= 0) {
        if ((nextEntry = currentTable.get(nextTableIndex--)) != null) {
          if (advanceTo(nextEntry) || nextInChain()) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Advances to the given entry. Returns true if the entry was valid, false if it
     * should be
     * skipped.
     */
    boolean advanceTo(ReferenceEntry<K, V> entry) {
      try {
        long now = ticker.read();
        K key = entry.getKey();
        V value = getLiveValue(entry, now);
        if (value != null) {
          nextExternal = new WriteThroughEntry(key, value);
          return true;
        } else {
          // Skip stale entry.
          return false;
        }
      } finally {
        currentSegment.postReadCleanup();
      }
    }

    @Override
    public boolean hasNext() {
      return nextExternal != null;
    }

    WriteThroughEntry nextEntry() {
      if (nextExternal == null) {
        throw new NoSuchElementException();
      }
      lastReturned = nextExternal;
      advance();
      return lastReturned;
    }

    @Override
    public void remove() {
      checkState(lastReturned != null);
      LocalCache.this.remove(lastReturned.getKey());
      lastReturned = null;
    }
  }

  final class KeyIterator extends HashIterator<K> {

    @Override
    public K next() {
      return nextEntry().getKey();
    }
  }

  final class ValueIterator extends HashIterator<V> {

    @Override
    public V next() {
      return nextEntry().getValue();
    }
  }

  /**
   * Custom Entry class used by EntryIterator.next(), that relays setValue changes
   * to the underlying
   * map.
   */
  final class WriteThroughEntry implements Entry<K, V> {
    final K key; // non-null
    V value; // non-null

    WriteThroughEntry(K key, V value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public K getKey() {
      return key;
    }

    @Override
    public V getValue() {
      return value;
    }

    @Override
    public boolean equals(@CheckForNull Object object) {
      // Cannot use key and value equivalence
      if (object instanceof Entry) {
        Entry<?, ?> that = (Entry<?, ?>) object;
        return key.equals(that.getKey()) && value.equals(that.getValue());
      }
      return false;
    }

    @Override
    public int hashCode() {
      // Cannot use key and value equivalence
      return key.hashCode() ^ value.hashCode();
    }

    @Override
    public V setValue(V newValue) {
      V oldValue = put(key, newValue);
      value = newValue; // only if put succeeds
      return oldValue;
    }

    @Override
    public String toString() {
      return getKey() + "=" + getValue();
    }
  }

  final class EntryIterator extends HashIterator<Entry<K, V>> {

    @Override
    public Entry<K, V> next() {
      return nextEntry();
    }
  }

  abstract class AbstractCacheSet<T> extends AbstractSet<T> {
    @Override
    public int size() {
      return LocalCache.this.size();
    }

    @Override
    public boolean isEmpty() {
      return LocalCache.this.isEmpty();
    }

    @Override
    public void clear() {
      LocalCache.this.clear();
    }
  }

  boolean removeIf(BiPredicate<? super K, ? super V> filter) {
    checkNotNull(filter);
    boolean changed = false;
    for (K key : keySet()) {
      while (true) {
        V value = get(key);
        if (value == null || !filter.test(key, value)) {
          break;
        } else if (LocalCache.this.remove(key, value)) {
          changed = true;
          break;
        }
      }
    }
    return changed;
  }

  final class KeySet extends AbstractCacheSet<K> {

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator();
    }

    @Override
    public boolean contains(Object o) {
      return LocalCache.this.containsKey(o);
    }

    @Override
    public boolean remove(Object o) {
      return LocalCache.this.remove(o) != null;
    }
  }

  final class Values extends AbstractCollection<V> {
    @Override
    public int size() {
      return LocalCache.this.size();
    }

    @Override
    public boolean isEmpty() {
      return LocalCache.this.isEmpty();
    }

    @Override
    public void clear() {
      LocalCache.this.clear();
    }

    @Override
    public Iterator<V> iterator() {
      return new ValueIterator();
    }

    @Override
    public boolean removeIf(Predicate<? super V> filter) {
      checkNotNull(filter);
      return LocalCache.this.removeIf((k, v) -> filter.test(v));
    }

    @Override
    public boolean contains(Object o) {
      return LocalCache.this.containsValue(o);
    }
  }

  final class EntrySet extends AbstractCacheSet<Entry<K, V>> {

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator();
    }

    @Override
    public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
      checkNotNull(filter);
      return LocalCache.this.removeIf((k, v) -> filter.test(Maps.immutableEntry(k, v)));
    }

    @Override
    public boolean contains(Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }
      Entry<?, ?> e = (Entry<?, ?>) o;
      Object key = e.getKey();
      if (key == null) {
        return false;
      }
      V v = LocalCache.this.get(key);

      return v != null && valueEquivalence.equivalent(e.getValue(), v);
    }

    @Override
    public boolean remove(Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }
      Entry<?, ?> e = (Entry<?, ?>) o;
      Object key = e.getKey();
      return key != null && LocalCache.this.remove(key, e.getValue());
    }
  }

  // Serialization Support

  /**
   * Serializes the configuration of a LocalCache, reconstituting it as a Cache
   * using CacheBuilder
   * upon deserialization. An instance of this class is fit for use by the
   * writeReplace of
   * LocalManualCache.
   *
   * <p>
   * Unfortunately, readResolve() doesn't get called when a circular dependency is
   * present, so
   * the proxy must be able to behave as the cache itself.
   */
  static class ManualSerializationProxy<K, V> extends ForwardingCache<K, V>
      implements Serializable {
    private static final long serialVersionUID = 1;

    final Strength keyStrength;
    final Strength valueStrength;
    final Equivalence<Object> keyEquivalence;
    final Equivalence<Object> valueEquivalence;
    final long expireAfterWriteNanos;
    final long expireAfterAccessNanos;
    final long maxWeight;
    final Weigher<K, V> weigher;
    final int concurrencyLevel;
    final RemovalListener<? super K, ? super V> removalListener;
    @CheckForNull
    final Ticker ticker;
    final CacheLoader<? super K, V> loader;

    @CheckForNull
    transient Cache<K, V> delegate;

    ManualSerializationProxy(LocalCache<K, V> cache) {
      this(
          cache.keyStrength,
          cache.valueStrength,
          cache.keyEquivalence,
          cache.valueEquivalence,
          cache.expireAfterWriteNanos,
          cache.expireAfterAccessNanos,
          cache.maxWeight,
          cache.weigher,
          cache.concurrencyLevel,
          cache.removalListener,
          cache.ticker,
          cache.defaultLoader);
    }

    private ManualSerializationProxy(
        Strength keyStrength,
        Strength valueStrength,
        Equivalence<Object> keyEquivalence,
        Equivalence<Object> valueEquivalence,
        long expireAfterWriteNanos,
        long expireAfterAccessNanos,
        long maxWeight,
        Weigher<K, V> weigher,
        int concurrencyLevel,
        RemovalListener<? super K, ? super V> removalListener,
        Ticker ticker,
        CacheLoader<? super K, V> loader) {
      this.keyStrength = keyStrength;
      this.valueStrength = valueStrength;
      this.keyEquivalence = keyEquivalence;
      this.valueEquivalence = valueEquivalence;
      this.expireAfterWriteNanos = expireAfterWriteNanos;
      this.expireAfterAccessNanos = expireAfterAccessNanos;
      this.maxWeight = maxWeight;
      this.weigher = weigher;
      this.concurrencyLevel = concurrencyLevel;
      this.removalListener = removalListener;
      this.ticker = (ticker == Ticker.systemTicker() || ticker == NULL_TICKER) ? null : ticker;
      this.loader = loader;
    }

    CacheBuilder<K, V> recreateCacheBuilder() {
      CacheBuilder<K, V> builder = CacheBuilder.newBuilder()
          .setKeyStrength(keyStrength)
          .setValueStrength(valueStrength)
          .keyEquivalence(keyEquivalence)
          .valueEquivalence(valueEquivalence)
          .concurrencyLevel(concurrencyLevel)
          .removalListener(removalListener);
      builder.strictParsing = false;
      if (expireAfterWriteNanos > 0) {
        builder.expireAfterWrite(expireAfterWriteNanos, NANOSECONDS);
      }
      if (expireAfterAccessNanos > 0) {
        builder.expireAfterAccess(expireAfterAccessNanos, NANOSECONDS);
      }
      if (weigher != OneWeigher.INSTANCE) {
        Object unused = builder.weigher(weigher);
        if (maxWeight != UNSET_INT) {
          builder.maximumWeight(maxWeight);
        }
      } else {
        if (maxWeight != UNSET_INT) {
          builder.maximumSize(maxWeight);
        }
      }
      if (ticker != null) {
        builder.ticker(ticker);
      }
      return builder;
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
      in.defaultReadObject();
      CacheBuilder<K, V> builder = recreateCacheBuilder();
      this.delegate = builder.build();
    }

    private Object readResolve() {
      return delegate;
    }

    @Override
    protected Cache<K, V> delegate() {
      return delegate;
    }
  }

  /**
   * Serializes the configuration of a LocalCache, reconstituting it as an
   * LoadingCache using
   * CacheBuilder upon deserialization. An instance of this class is fit for use
   * by the writeReplace
   * of LocalLoadingCache.
   *
   * <p>
   * Unfortunately, readResolve() doesn't get called when a circular dependency is
   * present, so
   * the proxy must be able to behave as the cache itself.
   */
  static final class LoadingSerializationProxy<K, V> extends ManualSerializationProxy<K, V>
      implements LoadingCache<K, V>, Serializable {
    private static final long serialVersionUID = 1;

    @CheckForNull
    transient LoadingCache<K, V> autoDelegate;

    LoadingSerializationProxy(LocalCache<K, V> cache) {
      super(cache);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
      in.defaultReadObject();
      CacheBuilder<K, V> builder = recreateCacheBuilder();
      this.autoDelegate = builder.build(loader);
    }

    @Override
    public V get(K key) throws ExecutionException {
      return autoDelegate.get(key);
    }

    @Override
    public V getUnchecked(K key) {
      return autoDelegate.getUnchecked(key);
    }

    @Override
    public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
      return autoDelegate.getAll(keys);
    }

    @Override
    public V apply(K key) {
      return autoDelegate.apply(key);
    }

    @Override
    public void refresh(K key) {
      autoDelegate.refresh(key);
    }

    private Object readResolve() {
      return autoDelegate;
    }
  }

  static class LocalManualCache<K, V> implements Cache<K, V>, Serializable {
    final LocalCache<K, V> localCache;

    LocalManualCache(CacheBuilder<? super K, ? super V> builder) {
      this(new LocalCache<>(builder, null));
    }

    private LocalManualCache(LocalCache<K, V> localCache) {
      this.localCache = localCache;
    }

    // Cache methods

    @Override
    @CheckForNull
    public V getIfPresent(Object key) {
      return localCache.getIfPresent(key);
    }

    @Override
    public V get(K key, final Callable<? extends V> valueLoader) throws ExecutionException {
      checkNotNull(valueLoader);
      return localCache.get(
          key,
          new CacheLoader<Object, V>() {
            @Override
            public V load(Object key) throws Exception {
              return valueLoader.call();
            }
          });
    }

    @Override
    public ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
      return localCache.getAllPresent(keys);
    }

    @Override
    public void put(K key, V value) {
      localCache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
      localCache.putAll(m);
    }

    @Override
    public void invalidate(Object key) {
      checkNotNull(key);
      localCache.remove(key);
    }

    @Override
    public void invalidateAll(Iterable<?> keys) {
      localCache.invalidateAll(keys);
    }

    @Override
    public void invalidateAll() {
      localCache.clear();
    }

    @Override
    public long size() {
      return localCache.longSize();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      return localCache;
    }

    @Override
    public CacheStats stats() {
      SimpleStatsCounter aggregator = new SimpleStatsCounter();
      aggregator.incrementBy(localCache.globalStatsCounter);
      for (Segment<K, V> segment : localCache.segments) {
        aggregator.incrementBy(segment.statsCounter);
      }
      return aggregator.snapshot();
    }

    @Override
    public void cleanUp() {
      localCache.cleanUp();
    }

    // Serialization Support

    private static final long serialVersionUID = 1;

    Object writeReplace() {
      return new ManualSerializationProxy<>(localCache);
    }

    private void readObject(ObjectInputStream in) throws InvalidObjectException {
      throw new InvalidObjectException("Use ManualSerializationProxy");
    }
  }

  static class LocalLoadingCache<K, V> extends LocalManualCache<K, V>
      implements LoadingCache<K, V> {

    LocalLoadingCache(
        CacheBuilder<? super K, ? super V> builder, CacheLoader<? super K, V> loader) {
      super(new LocalCache<>(builder, checkNotNull(loader)));
    }

    // LoadingCache methods

    @Override
    public V get(K key) throws ExecutionException {
      return localCache.getOrLoad(key);
    }

    @CanIgnoreReturnValue // TODO(b/27479612): consider removing this
    @Override
    public V getUnchecked(K key) {
      try {
        return get(key);
      } catch (ExecutionException e) {
        throw new UncheckedExecutionException(e.getCause());
      }
    }

    @Override
    public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
      return localCache.getAll(keys);
    }

    @Override
    public void refresh(K key) {
      localCache.refresh(key);
    }

    @Override
    public final V apply(K key) {
      return getUnchecked(key);
    }

    // Serialization Support

    private static final long serialVersionUID = 1;

    @Override
    Object writeReplace() {
      return new LoadingSerializationProxy<>(localCache);
    }

    private void readObject(ObjectInputStream in) throws InvalidObjectException {
      throw new InvalidObjectException("Use LoadingSerializationProxy");
    }
  }
}
