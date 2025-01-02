/*
 * Copyright (C) 2012 The Guava Authors
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

package com.google.common.util.concurrent;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Internal.toNanosSaturated;
import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;

import com.google.common.annotations.Beta;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.J2ktIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.SmoothRateLimiter.SmoothBursty;
import com.google.common.util.concurrent.SmoothRateLimiter.SmoothWarmingUp;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * 速率限制器。从概念上讲，速率限制器以可配置的速率分发许可证。每次调用{@link #acquire()}时，
 * 如果需要则阻塞直到有许可证可用，然后获取该许可证。一旦获取，许可证无需释放。
 * A rate limiter. Conceptually, a rate limiter distributes permits at a
 * configurable rate. Each
 * {@link #acquire()} blocks if necessary until a permit is available, and then
 * takes it. Once
 * acquired, permits need not be released.
 *
 * <p>
 * {@code RateLimiter}是线程安全的：它会限制来自所有线程的总调用速率。
 * 但需要注意的是，它不保证公平性。
 * <p>
 * {@code RateLimiter} is safe for concurrent use: It will restrict the total
 * rate of calls from
 * all threads. Note, however, that it does not guarantee fairness.
 *
 * <p>
 * 速率限制器通常用于限制对某些物理或逻辑资源的访问速率。这与{@link java.util.concurrent.Semaphore}
 * 不同，后者限制的是并发访问的数量而不是速率（注意并发性和速率是密切相关的，
 * 例如参见<a href="http://en.wikipedia.org/wiki/Little%27s_law">利特尔法则</a>）。
 *
 * <p>
 * 速率限制器主要由发放许可证的速率来定义。在没有额外配置的情况下，许可证将以固定速率分发，
 * 该速率以每秒许可证数量来定义。许可证将平滑分发，通过调整个别许可证之间的延迟来确保维持配置的速率。
 *
 * <p>
 * 可以配置{@code RateLimiter}具有预热期，在此期间每秒发放的许可证数量稳步增加，
 * 直到达到稳定速率。
 *
 * <p>
 * 举例来说，假设我们有一个要执行的任务列表，但我们不想每秒提交超过2个任务：
 * 
 * <pre>{@code
 * final RateLimiter rateLimiter = RateLimiter.create(2.0); // 速率是"每秒2个许可证"
 * 
 * void submitTasks(List<Runnable> tasks, Executor executor) {
 *   for (Runnable task : tasks) {
 *     rateLimiter.acquire(); // 可能会等待
 *     executor.execute(task);
 *   }
 * }
 * }</pre>
 *
 * <p>
 * 再举一个例子，假设我们产生一个数据流，我们想将其限制在每秒5KB。
 * 这可以通过要求每字节一个许可证，并指定每秒5000个许可证的速率来实现：
 * 
 * <pre>{@code
 * final RateLimiter rateLimiter = RateLimiter.create(5000.0); // 速率 = 每秒5000个许可证
 * 
 * void submitPacket(byte[] packet) {
 *   rateLimiter.acquire(packet.length);
 *   networkService.send(packet);
 * }
 * }</pre>
 *
 * <p>
 * 需要注意的是，请求的许可证数量<i>永远不会</i>影响请求本身的节流
 * （调用{@code acquire(1)}和调用{@code acquire(1000)}会导致完全相同的节流效果，如果有的话），
 * 但它会影响<i>下一个</i>请求的节流。也就是说，如果一个耗时的任务到达空闲的RateLimiter，
 * 它将立即被授予许可，但是<i>下一个</i>请求将经历额外的节流，从而为耗时任务的成本付出代价。
 *
 * @author Dimitris Andreou
 * @since 13.0
 */
// TODO(user): switch to nano precision. A natural unit of cost is "bytes", and
// a micro precision
// would mean a maximum rate of "1MB/s", which might be small in some cases.
@Beta
@J2ktIncompatible
@GwtIncompatible
public abstract class RateLimiter {
  /**
   * 创建一个具有指定稳定吞吐量的{@code RateLimiter}，吞吐量以"每秒许可证数"为单位
   * （通常称为<i>QPS</i>，即每秒查询数）。
   * Creates a {@code RateLimiter} with the specified stable throughput, given as
   * "permits per second" (commonly referred to as <i>QPS</i>, queries per
   * second).
   *
   * <p>
   * 返回的{@code RateLimiter}确保在任何给定的一秒内平均发放的许可证数不超过
   * {@code permitsPerSecond}，持续的请求会被平滑地分布在每一秒内。当传入请求率超过
   * {@code permitsPerSecond}时，速率限制器将每{@code (1.0 / permitsPerSecond)}秒
   * 发放一个许可证。当速率限制器处于未使用状态时，最多允许{@code permitsPerSecond}
   * 个许可证的突发请求，随后的请求将被平滑限制在{@code permitsPerSecond}的稳定速率。
   * <p>
   * The returned {@code RateLimiter} ensures that on average no more than
   * {@code permitsPerSecond} are issued during any given second, with sustained
   * requests
   * being smoothly spread over each second. When the incoming request rate
   * exceeds
   * {@code permitsPerSecond} the rate limiter will release one permit every
   * {@code (1.0 / permitsPerSecond)} seconds. When the rate limiter is unused,
   * bursts of up to {@code permitsPerSecond} permits will be allowed, with
   * subsequent requests being smoothly limited at the stable rate of
   * {@code permitsPerSecond}.
   *
   * @param permitsPerSecond 返回的{@code RateLimiter}的速率，以每秒可用的许可证数量计量
   *                         the rate of the returned {@code RateLimiter},
   *                         measured in
   *                         how many permits become available per second
   * @throws IllegalArgumentException 如果{@code permitsPerSecond}为负数或零
   *                                  if {@code permitsPerSecond} is negative or
   *                                  zero
   */
  // TODO(user): "This is equivalent to
  // {@code createWithCapacity(permitsPerSecond, 1, TimeUnit.SECONDS)}".
  public static RateLimiter create(double permitsPerSecond) {
    /*
     * The default RateLimiter configuration can save the unused permits of up to
     * one second. This
     * is to avoid unnecessary stalls in situations like this: A RateLimiter of
     * 1qps, and 4 threads,
     * all calling acquire() at these moments:
     *
     * T0 at 0 seconds
     * T1 at 1.05 seconds
     * T2 at 2 seconds
     * T3 at 3 seconds
     *
     * Due to the slight delay of T1, T2 would have to sleep till 2.05 seconds, and
     * T3 would also
     * have to sleep till 3.05 seconds.
     */
    return create(permitsPerSecond, SleepingStopwatch.createFromSystemTimer());
  }

  @VisibleForTesting
  static RateLimiter create(double permitsPerSecond, SleepingStopwatch stopwatch) {
    RateLimiter rateLimiter = new SmoothBursty(stopwatch, 1.0 /* maxBurstSeconds */);
    rateLimiter.setRate(permitsPerSecond);
    return rateLimiter;
  }

  /**
   * Creates a {@code RateLimiter} with the specified stable throughput, given as
   * "permits per
   * second" (commonly referred to as <i>QPS</i>, queries per second), and a
   * <i>warmup period</i>,
   * during which the {@code RateLimiter} smoothly ramps up its rate, until it
   * reaches its maximum
   * rate at the end of the period (as long as there are enough requests to
   * saturate it). Similarly,
   * if the {@code RateLimiter} is left <i>unused</i> for a duration of
   * {@code warmupPeriod}, it
   * will gradually return to its "cold" state, i.e. it will go through the same
   * warming up process
   * as when it was first created.
   *
   * <p>
   * The returned {@code RateLimiter} is intended for cases where the resource
   * that actually
   * fulfills the requests (e.g., a remote server) needs "warmup" time, rather
   * than being
   * immediately accessed at the stable (maximum) rate.
   *
   * <p>
   * The returned {@code RateLimiter} starts in a "cold" state (i.e. the warmup
   * period will
   * follow), and if it is left unused for long enough, it will return to that
   * state.
   *
   * @param permitsPerSecond the rate of the returned {@code RateLimiter},
   *                         measured in how many
   *                         permits become available per second
   * @param warmupPeriod     the duration of the period where the
   *                         {@code RateLimiter} ramps up its rate,
   *                         before reaching its stable (maximum) rate
   * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or
   *                                  zero or {@code
   *     warmupPeriod}             is negative
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public static RateLimiter create(double permitsPerSecond, Duration warmupPeriod) {
    return create(permitsPerSecond, toNanosSaturated(warmupPeriod), TimeUnit.NANOSECONDS);
  }

  /**
   * Creates a {@code RateLimiter} with the specified stable throughput, given as
   * "permits per
   * second" (commonly referred to as <i>QPS</i>, queries per second), and a
   * <i>warmup period</i>,
   * during which the {@code RateLimiter} smoothly ramps up its rate, until it
   * reaches its maximum
   * rate at the end of the period (as long as there are enough requests to
   * saturate it). Similarly,
   * if the {@code RateLimiter} is left <i>unused</i> for a duration of
   * {@code warmupPeriod}, it
   * will gradually return to its "cold" state, i.e. it will go through the same
   * warming up process
   * as when it was first created.
   *
   * <p>
   * The returned {@code RateLimiter} is intended for cases where the resource
   * that actually
   * fulfills the requests (e.g., a remote server) needs "warmup" time, rather
   * than being
   * immediately accessed at the stable (maximum) rate.
   *
   * <p>
   * The returned {@code RateLimiter} starts in a "cold" state (i.e. the warmup
   * period will
   * follow), and if it is left unused for long enough, it will return to that
   * state.
   *
   * @param permitsPerSecond the rate of the returned {@code RateLimiter},
   *                         measured in how many
   *                         permits become available per second
   * @param warmupPeriod     the duration of the period where the
   *                         {@code RateLimiter} ramps up its rate,
   *                         before reaching its stable (maximum) rate
   * @param unit             the time unit of the warmupPeriod argument
   * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or
   *                                  zero or {@code
   *     warmupPeriod}             is negative
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public static RateLimiter create(double permitsPerSecond, long warmupPeriod, TimeUnit unit) {
    checkArgument(warmupPeriod >= 0, "warmupPeriod must not be negative: %s", warmupPeriod);
    return create(
        permitsPerSecond, warmupPeriod, unit, 3.0, SleepingStopwatch.createFromSystemTimer());
  }

  @VisibleForTesting
  static RateLimiter create(
      double permitsPerSecond,
      long warmupPeriod,
      TimeUnit unit,
      double coldFactor,
      SleepingStopwatch stopwatch) {
    RateLimiter rateLimiter = new SmoothWarmingUp(stopwatch, warmupPeriod, unit, coldFactor);
    rateLimiter.setRate(permitsPerSecond);
    return rateLimiter;
  }

  /**
   * The underlying timer; used both to measure elapsed time and sleep as
   * necessary. A separate
   * object to facilitate testing.
   */
  private final SleepingStopwatch stopwatch;

  // Can't be initialized in the constructor because mocks don't call the
  // constructor.
  @CheckForNull
  private volatile Object mutexDoNotUseDirectly;

  private Object mutex() {
    Object mutex = mutexDoNotUseDirectly;
    if (mutex == null) {
      synchronized (this) {
        mutex = mutexDoNotUseDirectly;
        if (mutex == null) {
          mutexDoNotUseDirectly = mutex = new Object();
        }
      }
    }
    return mutex;
  }

  RateLimiter(SleepingStopwatch stopwatch) {
    this.stopwatch = checkNotNull(stopwatch);
  }

  /**
   * Updates the stable rate of this {@code RateLimiter}, that is, the
   * {@code permitsPerSecond}
   * argument provided in the factory method that constructed the
   * {@code RateLimiter}. Currently
   * throttled threads will <b>not</b> be awakened as a result of this invocation,
   * thus they do not
   * observe the new rate; only subsequent requests will.
   *
   * <p>
   * Note though that, since each request repays (by waiting, if necessary) the
   * cost of the
   * <i>previous</i> request, this means that the very next request after an
   * invocation to {@code
   * setRate} will not be affected by the new rate; it will pay the cost of the
   * previous request,
   * which is in terms of the previous rate.
   *
   * <p>
   * The behavior of the {@code RateLimiter} is not modified in any other way,
   * e.g. if the {@code
   * RateLimiter} was configured with a warmup period of 20 seconds, it still has
   * a warmup period of
   * 20 seconds after this method invocation.
   *
   * @param permitsPerSecond the new stable rate of this {@code RateLimiter}
   * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or
   *                                  zero
   */
  public final void setRate(double permitsPerSecond) {
    checkArgument(permitsPerSecond > 0.0, "rate must be positive");
    synchronized (mutex()) {
      doSetRate(permitsPerSecond, stopwatch.readMicros());
    }
  }

  abstract void doSetRate(double permitsPerSecond, long nowMicros);

  /**
   * Returns the stable rate (as {@code permits per seconds}) with which this
   * {@code RateLimiter} is
   * configured with. The initial value of this is the same as the
   * {@code permitsPerSecond} argument
   * passed in the factory method that produced this {@code RateLimiter}, and it
   * is only updated
   * after invocations to {@linkplain #setRate}.
   */
  public final double getRate() {
    synchronized (mutex()) {
      return doGetRate();
    }
  }

  abstract double doGetRate();

  /**
   * Acquires a single permit from this {@code RateLimiter}, blocking until the
   * request can be
   * granted. Tells the amount of time slept, if any.
   *
   * <p>
   * This method is equivalent to {@code acquire(1)}.
   *
   * @return time spent sleeping to enforce rate, in seconds; 0.0 if not
   *         rate-limited
   * @since 16.0 (present in 13.0 with {@code void} return type})
   */
  @CanIgnoreReturnValue
  public double acquire() {
    return acquire(1);
  }

  /**
   * Acquires the given number of permits from this {@code RateLimiter}, blocking
   * until the request
   * can be granted. Tells the amount of time slept, if any.
   *
   * @param permits the number of permits to acquire
   * @return time spent sleeping to enforce rate, in seconds; 0.0 if not
   *         rate-limited
   * @throws IllegalArgumentException if the requested number of permits is
   *                                  negative or zero
   * @since 16.0 (present in 13.0 with {@code void} return type})
   */
  @CanIgnoreReturnValue
  public double acquire(int permits) {
    long microsToWait = reserve(permits);
    stopwatch.sleepMicrosUninterruptibly(microsToWait);
    return 1.0 * microsToWait / SECONDS.toMicros(1L);
  }

  /**
   * Reserves the given number of permits from this {@code RateLimiter} for future
   * use, returning
   * the number of microseconds until the reservation can be consumed.
   *
   * @return time in microseconds to wait until the resource can be acquired,
   *         never negative
   */
  final long reserve(int permits) {
    checkPermits(permits);
    synchronized (mutex()) {
      return reserveAndGetWaitLength(permits, stopwatch.readMicros());
    }
  }

  /**
   * Acquires a permit from this {@code RateLimiter} if it can be obtained without
   * exceeding the
   * specified {@code timeout}, or returns {@code false} immediately (without
   * waiting) if the permit
   * would not have been granted before the timeout expired.
   *
   * <p>
   * This method is equivalent to {@code tryAcquire(1, timeout)}.
   *
   * @param timeout the maximum time to wait for the permit. Negative values are
   *                treated as zero.
   * @return {@code true} if the permit was acquired, {@code false} otherwise
   * @throws IllegalArgumentException if the requested number of permits is
   *                                  negative or zero
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean tryAcquire(Duration timeout) {
    return tryAcquire(1, toNanosSaturated(timeout), TimeUnit.NANOSECONDS);
  }

  /**
   * Acquires a permit from this {@code RateLimiter} if it can be obtained without
   * exceeding the
   * specified {@code timeout}, or returns {@code false} immediately (without
   * waiting) if the permit
   * would not have been granted before the timeout expired.
   *
   * <p>
   * This method is equivalent to {@code tryAcquire(1, timeout, unit)}.
   *
   * @param timeout the maximum time to wait for the permit. Negative values are
   *                treated as zero.
   * @param unit    the time unit of the timeout argument
   * @return {@code true} if the permit was acquired, {@code false} otherwise
   * @throws IllegalArgumentException if the requested number of permits is
   *                                  negative or zero
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean tryAcquire(long timeout, TimeUnit unit) {
    return tryAcquire(1, timeout, unit);
  }

  /**
   * Acquires permits from this {@link RateLimiter} if it can be acquired
   * immediately without delay.
   *
   * <p>
   * This method is equivalent to {@code tryAcquire(permits, 0, anyUnit)}.
   *
   * @param permits the number of permits to acquire
   * @return {@code true} if the permits were acquired, {@code false} otherwise
   * @throws IllegalArgumentException if the requested number of permits is
   *                                  negative or zero
   * @since 14.0
   */
  public boolean tryAcquire(int permits) {
    return tryAcquire(permits, 0, MICROSECONDS);
  }

  /**
   * Acquires a permit from this {@link RateLimiter} if it can be acquired
   * immediately without
   * delay.
   *
   * <p>
   * This method is equivalent to {@code tryAcquire(1)}.
   *
   * @return {@code true} if the permit was acquired, {@code false} otherwise
   * @since 14.0
   */
  public boolean tryAcquire() {
    return tryAcquire(1, 0, MICROSECONDS);
  }

  /**
   * Acquires the given number of permits from this {@code RateLimiter} if it can
   * be obtained
   * without exceeding the specified {@code timeout}, or returns {@code false}
   * immediately (without
   * waiting) if the permits would not have been granted before the timeout
   * expired.
   *
   * @param permits the number of permits to acquire
   * @param timeout the maximum time to wait for the permits. Negative values are
   *                treated as zero.
   * @return {@code true} if the permits were acquired, {@code false} otherwise
   * @throws IllegalArgumentException if the requested number of permits is
   *                                  negative or zero
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean tryAcquire(int permits, Duration timeout) {
    return tryAcquire(permits, toNanosSaturated(timeout), TimeUnit.NANOSECONDS);
  }

  /**
   * Acquires the given number of permits from this {@code RateLimiter} if it can
   * be obtained
   * without exceeding the specified {@code timeout}, or returns {@code false}
   * immediately (without
   * waiting) if the permits would not have been granted before the timeout
   * expired.
   *
   * @param permits the number of permits to acquire
   * @param timeout the maximum time to wait for the permits. Negative values are
   *                treated as zero.
   * @param unit    the time unit of the timeout argument
   * @return {@code true} if the permits were acquired, {@code false} otherwise
   * @throws IllegalArgumentException if the requested number of permits is
   *                                  negative or zero
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
    long timeoutMicros = max(unit.toMicros(timeout), 0);
    checkPermits(permits);
    long microsToWait;
    synchronized (mutex()) {
      long nowMicros = stopwatch.readMicros();
      if (!canAcquire(nowMicros, timeoutMicros)) {
        return false;
      } else {
        microsToWait = reserveAndGetWaitLength(permits, nowMicros);
      }
    }
    stopwatch.sleepMicrosUninterruptibly(microsToWait);
    return true;
  }

  private boolean canAcquire(long nowMicros, long timeoutMicros) {
    return queryEarliestAvailable(nowMicros) - timeoutMicros <= nowMicros;
  }

  /**
   * Reserves next ticket and returns the wait time that the caller must wait for.
   *
   * @return the required wait time, never negative
   */
  final long reserveAndGetWaitLength(int permits, long nowMicros) {
    long momentAvailable = reserveEarliestAvailable(permits, nowMicros);
    return max(momentAvailable - nowMicros, 0);
  }

  /**
   * Returns the earliest time that permits are available (with one caveat).
   *
   * @return the time that permits are available, or, if permits are available
   *         immediately, an
   *         arbitrary past or present time
   */
  abstract long queryEarliestAvailable(long nowMicros);

  /**
   * Reserves the requested number of permits and returns the time that those
   * permits can be used
   * (with one caveat).
   *
   * @return the time that the permits may be used, or, if the permits may be used
   *         immediately, an
   *         arbitrary past or present time
   */
  abstract long reserveEarliestAvailable(int permits, long nowMicros);

  @Override
  public String toString() {
    return String.format(Locale.ROOT, "RateLimiter[stableRate=%3.1fqps]", getRate());
  }

  abstract static class SleepingStopwatch {
    /** Constructor for use by subclasses. */
    protected SleepingStopwatch() {
    }

    /*
     * We always hold the mutex when calling this. TODO(cpovirk): Is that important?
     * Perhaps we need
     * to guarantee that each call to reserveEarliestAvailable, etc. sees a value >=
     * the previous?
     * Also, is it OK that we don't hold the mutex when sleeping?
     */
    protected abstract long readMicros();

    protected abstract void sleepMicrosUninterruptibly(long micros);

    public static SleepingStopwatch createFromSystemTimer() {
      return new SleepingStopwatch() {
        final Stopwatch stopwatch = Stopwatch.createStarted();

        @Override
        protected long readMicros() {
          return stopwatch.elapsed(MICROSECONDS);
        }

        @Override
        protected void sleepMicrosUninterruptibly(long micros) {
          if (micros > 0) {
            Uninterruptibles.sleepUninterruptibly(micros, MICROSECONDS);
          }
        }
      };
    }
  }

  private static void checkPermits(int permits) {
    checkArgument(permits > 0, "Requested permits (%s) must be positive", permits);
  }
}
