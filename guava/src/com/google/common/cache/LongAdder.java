/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

/*
 * Source:
 * http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/src/jsr166e/LongAdder.java?revision=1.17
 */

package com.google.common.cache;

import com.google.common.annotations.GwtCompatible;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One or more variables that together maintain an initially zero {@code long} sum. When updates
 * (method {@link #add}) are contended across threads, the set of variables may grow dynamically to
 * reduce contention. Method {@link #sum} (or, equivalently, {@link #longValue}) returns the current
 * total combined across the variables maintaining the sum.
 *
 * <p>This class is usually preferable to {@link AtomicLong} when multiple threads update a common
 * sum that is used for purposes such as collecting statistics, not for fine-grained synchronization
 * control. Under low update contention, the two classes have similar characteristics. But under
 * high contention, expected throughput of this class is significantly higher, at the expense of
 * higher space consumption.
 *
 * <p>This class extends {@link Number}, but does <em>not</em> define methods such as {@code
 * equals}, {@code hashCode} and {@code compareTo} because instances are expected to be mutated, and
 * so are not useful as collection keys.
 *
 * <p><em>jsr166e note: This class is targeted to be placed in java.util.concurrent.atomic.</em>
 *
 * @since 1.8
 * @author Doug Lea
 */
/**
 * 一个高效的累加器实现，用于维护一个初始值为零的 long 类型的总和。
 * 当多线程并发更新时，内部会动态地扩展变量集合以减少竞争。
 * 
 * 主要特点：
 * 1. 分散竞争：当多线程更新发生冲突时，会动态创建新的 Cell 来分散竞争
 * 2. 高吞吐量：在高并发场景下，性能显著优于 AtomicLong
 * 3. 非精确统计：sum() 方法返回的是非原子性的快照值
 * 
 * 适用场景：
 * - 适合用于统计数据收集等对精确性要求不高的场景
 * - 不适合用于细粒度的同步控制
 * - 在低竞争情况下，性能与 AtomicLong 相似
 * - 在高竞争情况下，以额外的空间换取更高的吞吐量
 *
 * 注意事项：
 * - 继承自 Number 类，但不实现 equals、hashCode 和 compareTo 方法
 * - 因为实例值会被修改，所以不适合作为集合的键
 * - 此类计划被放置在 java.util.concurrent.atomic 包中
 *
 * 示例用法：
 * <pre>{@code
 * LongAdder adder = new LongAdder();
 * adder.add(10);      // 添加值
 * adder.increment();  // 加1
 * adder.decrement();  // 减1
 * long sum = adder.sum();  // 获取总和
 * }</pre>
 *
 * @since 1.8
 * @author Doug Lea
 */
@GwtCompatible(emulated = true)
final class LongAdder extends Striped64 implements Serializable, LongAddable {
  private static final long serialVersionUID = 7249069246863182397L;

  /** Version of plus for use in retryUpdate */
  @Override
  final long fn(long v, long x) {
    return v + x;
  }

  /** Creates a new adder with initial sum of zero. */
  public LongAdder() {}

  /**
   * 将指定值添加到累加器。这是一个高度优化的方法，用于处理高并发场景下的累加操作。
   * 
   * 实现策略：
   * 1. 优先尝试更新base值，这是最快的路径
   * 2. 如果base更新失败，说明存在竞争，则尝试更新Cell数组中的某个单元
   * 3. 如果Cell更新也失败，则进入完整的重试逻辑
   * 
   * 性能考虑：
   * - 采用无锁设计，全程使用CAS操作
   * - 通过Cell数组分散竞争
   * - 使用线程哈希值来选择Cell，减少冲突
   *
   * @param x 要添加的值
   */
  @Override
  public void add(long x) {
    Cell[] as;      // cells数组的引用
    long b, v;      // b用于base值，v用于Cell的当前值
    int[] hc;       // 线程的哈希码数组
    Cell a;         // 当前选中的Cell
    int n;          // cells数组的长度

    // 快速路径：尝试直接更新base值
    // 如果cells已初始化或CAS更新base失败，则进入慢速路径
    if ((as = cells) != null || !casBase(b = base, b + x)) {
        // 标记是否存在竞争，初始假设无竞争
        boolean uncontended = true;
        
        // 以下任一条件成立则需要进入重试逻辑：
        if ((hc = threadHashCode.get()) == null     // 1. 线程哈希码未初始化
            || as == null                           // 2. cells数组未初始化
            || (n = as.length) < 1                  // 3. cells数组为空
            || (a = as[(n - 1) & hc[0]]) == null   // 4. 目标Cell未初始化
            || !(uncontended =                      // 5. CAS更新Cell失败
                a.cas(v = a.value, v + x))) {
            // 进入完整的重试逻辑，可能会初始化或扩容cells数组
            retryUpdate(x, hc, uncontended);
        }
    }
  }

  /** Equivalent to {@code add(1)}. */
  @Override
  public void increment() {
    add(1L);
  }

  /** Equivalent to {@code add(-1)}. */
  public void decrement() {
    add(-1L);
  }

  /**
   * Returns the current sum. The returned value is <em>NOT</em> an atomic snapshot; invocation in
   * the absence of concurrent updates returns an accurate result, but concurrent updates that occur
   * while the sum is being calculated might not be incorporated.
   *
   * @return the sum
   */
  @Override
  public long sum() {
    long sum = base;
    Cell[] as = cells;
    if (as != null) {
      int n = as.length;
      for (int i = 0; i < n; ++i) {
        Cell a = as[i];
        if (a != null) sum += a.value;
      }
    }
    return sum;
  }

  /**
   * Resets variables maintaining the sum to zero. This method may be a useful alternative to
   * creating a new adder, but is only effective if there are no concurrent updates. Because this
   * method is intrinsically racy, it should only be used when it is known that no threads are
   * concurrently updating.
   */
  public void reset() {
    internalReset(0L);
  }

  /**
   * Equivalent in effect to {@link #sum} followed by {@link #reset}. This method may apply for
   * example during quiescent points between multithreaded computations. If there are updates
   * concurrent with this method, the returned value is <em>not</em> guaranteed to be the final
   * value occurring before the reset.
   *
   * @return the sum
   */
  public long sumThenReset() {
    long sum = base;
    Cell[] as = cells;
    base = 0L;
    if (as != null) {
      int n = as.length;
      for (int i = 0; i < n; ++i) {
        Cell a = as[i];
        if (a != null) {
          sum += a.value;
          a.value = 0L;
        }
      }
    }
    return sum;
  }

  /**
   * Returns the String representation of the {@link #sum}.
   *
   * @return the String representation of the {@link #sum}
   */
  @Override
  public String toString() {
    return Long.toString(sum());
  }

  /**
   * Equivalent to {@link #sum}.
   *
   * @return the sum
   */
  @Override
  public long longValue() {
    return sum();
  }

  /** Returns the {@link #sum} as an {@code int} after a narrowing primitive conversion. */
  @Override
  public int intValue() {
    return (int) sum();
  }

  /** Returns the {@link #sum} as a {@code float} after a widening primitive conversion. */
  @Override
  public float floatValue() {
    return (float) sum();
  }

  /** Returns the {@link #sum} as a {@code double} after a widening primitive conversion. */
  @Override
  public double doubleValue() {
    return (double) sum();
  }

  private void writeObject(ObjectOutputStream s) throws IOException {
    s.defaultWriteObject();
    s.writeLong(sum());
  }

  private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
    s.defaultReadObject();
    busy = 0;
    cells = null;
    base = s.readLong();
  }
}
