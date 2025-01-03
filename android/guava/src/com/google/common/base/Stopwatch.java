/*
 * Copyright (C) 2008 The Guava Authors
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

package com.google.common.base;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.J2ktIncompatible;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.j2objc.annotations.J2ObjCIncompatible;

/**
 * 一个精确测量<i>流逝时间</i>的对象：用于测量同一进程中两次连续"当前时间"读数之间的持续时间。
 * An object that accurately measures <i>elapsed time</i>: the measured duration
 * between two
 * successive readings of "now" in the same process.
 * 
 * <p>
 * 与之相对的，<i>墙上时间</i>是通过{@link System#currentTimeMillis()}这样的方法获得的"当前时间"读数，
 * 最适合用{@link java.time.Instant}表示。这些值<i>可以</i>通过相减得到一个{@code Duration}
 * （比如使用{@code Duration.between}），但这样做<i>不能</i>得到可靠的流逝时间测量，
 * 因为墙上时间的读数本质上是近似的，会定期受到时钟校正的影响。
 * 由于本类（默认）使用{@link System#nanoTime}，因此不受这些变化的影响。
 *
 * <p>
 * 使用此类而不是直接调用{@link System#nanoTime}有两个原因：
 * <ul>
 * <li>{@code nanoTime}返回的原始{@code long}值没有实际意义，
 * 除了在{@code Stopwatch}中的使用方式外，其他使用方式都是不安全的。
 * <li>可以替换为其他纳秒级计时源，例如为了测试或性能原因，
 * 而不会影响你的大部分代码。
 * </ul>
 *
 * <p>
 * {@code Stopwatch}相对于{@link System#nanoTime()}的一个缺点是，
 * {@code Stopwatch}需要进行对象分配和额外的方法调用，这可能会降低报告的流逝时间的准确性。
 * 对于只需要合理准确值的日志记录和指标统计来说，{@code Stopwatch}仍然是合适的。
 * 在极少数需要最大化准确性的情况下，请直接使用{@code System.nanoTime()}。
 *
 * <p>
 * 基本用法：
 * 
 * <pre>{@code
 * Stopwatch stopwatch = Stopwatch.createStarted(); // 创建并启动秒表
 * doSomething(); // 执行某些操作
 * stopwatch.stop(); // 停止秒表（可选）
 *
 * Duration duration = stopwatch.elapsed(); // 获取流逝的时间
 *
 * log.info("time: " + stopwatch); // 输出格式化字符串，如 "12.3 ms"
 * }</pre>
 *
 * <p>
 * 改变状态的方法不是幂等的；对已经处于目标状态的秒表进行启动或停止是错误的。
 *
 * <p>
 * 在测试使用此类的代码时，使用{@link #createUnstarted(Ticker)}或
 * {@link #createStarted(Ticker)}来提供一个模拟的计时器。
 * 这样可以模拟秒表的任何有效行为。
 *
 * <p>
 * <b>注意：</b>此类不是线程安全的。
 *
 * <p>
 * <b>Android用户警告：</b>使用默认行为的秒表在设备休眠时可能不会继续计时。
 * 建议使用以下方式创建：
 *
 * <pre>{@code
 * Stopwatch.createStarted(
 *     new Ticker() {
 *       public long read() {
 *         // 使用Android特定的计时方法（需要API Level 17）
 *         return android.os.SystemClock.elapsedRealtimeNanos();
 *       }
 *     });
 * }</pre>
 *
 * @author Kevin Bourrillion
 * @since 10.0 版本引入
 */
@GwtCompatible(emulated = true)
@SuppressWarnings("GoodTime") // lots of violations
public final class Stopwatch {
  private final Ticker ticker;
  private boolean isRunning;
  private long elapsedNanos;
  private long startTick;

  /**
   * Creates (but does not start) a new stopwatch using {@link System#nanoTime} as
   * its time source.
   *
   * @since 15.0
   */
  public static Stopwatch createUnstarted() {
    return new Stopwatch();
  }

  /**
   * Creates (but does not start) a new stopwatch, using the specified time
   * source.
   *
   * @since 15.0
   */
  public static Stopwatch createUnstarted(Ticker ticker) {
    return new Stopwatch(ticker);
  }

  /**
   * Creates (and starts) a new stopwatch using {@link System#nanoTime} as its
   * time source.
   *
   * @since 15.0
   */
  public static Stopwatch createStarted() {
    return new Stopwatch().start();
  }

  /**
   * Creates (and starts) a new stopwatch, using the specified time source.
   *
   * @since 15.0
   */
  public static Stopwatch createStarted(Ticker ticker) {
    return new Stopwatch(ticker).start();
  }

  Stopwatch() {
    this.ticker = Ticker.systemTicker();
  }

  Stopwatch(Ticker ticker) {
    this.ticker = checkNotNull(ticker, "ticker");
  }

  /**
   * Returns {@code true} if {@link #start()} has been called on this stopwatch,
   * and {@link #stop()}
   * has not been called since the last call to {@code start()}.
   */
  public boolean isRunning() {
    return isRunning;
  }

  /**
   * Starts the stopwatch.
   *
   * @return this {@code Stopwatch} instance
   * @throws IllegalStateException if the stopwatch is already running.
   */
  @CanIgnoreReturnValue
  public Stopwatch start() {
    checkState(!isRunning, "This stopwatch is already running.");
    isRunning = true;
    startTick = ticker.read();
    return this;
  }

  /**
   * Stops the stopwatch. Future reads will return the fixed duration that had
   * elapsed up to this
   * point.
   *
   * @return this {@code Stopwatch} instance
   * @throws IllegalStateException if the stopwatch is already stopped.
   */
  @CanIgnoreReturnValue
  public Stopwatch stop() {
    long tick = ticker.read();
    checkState(isRunning, "This stopwatch is already stopped.");
    isRunning = false;
    elapsedNanos += tick - startTick;
    return this;
  }

  /**
   * Sets the elapsed time for this stopwatch to zero, and places it in a stopped
   * state.
   *
   * @return this {@code Stopwatch} instance
   */
  @CanIgnoreReturnValue
  public Stopwatch reset() {
    elapsedNanos = 0;
    isRunning = false;
    return this;
  }

  private long elapsedNanos() {
    return isRunning ? ticker.read() - startTick + elapsedNanos : elapsedNanos;
  }

  /**
   * Returns the current elapsed time shown on this stopwatch, expressed in the
   * desired time unit,
   * with any fraction rounded down.
   *
   * <p>
   * <b>Note:</b> the overhead of measurement can be more than a microsecond, so
   * it is generally
   * not useful to specify {@link TimeUnit#NANOSECONDS} precision here.
   *
   * <p>
   * It is generally not a good idea to use an ambiguous, unitless {@code long} to
   * represent
   * elapsed time. Therefore, we recommend using {@link #elapsed()} instead, which
   * returns a
   * strongly-typed {@code Duration} instance.
   *
   * @since 14.0 (since 10.0 as {@code elapsedTime()})
   */
  public long elapsed(TimeUnit desiredUnit) {
    return desiredUnit.convert(elapsedNanos(), NANOSECONDS);
  }

  /**
   * Returns the current elapsed time shown on this stopwatch as a
   * {@link Duration}. Unlike {@link
   * #elapsed(TimeUnit)}, this method does not lose any precision due to rounding.
   *
   * <p>
   * <b>Warning:</b> do not call this method from Android code unless you are on
   * Android API
   * level 26+ or you <a
   * href=
   * "https://developer.android.com/studio/write/java11-default-support-table">opt
   * in to
   * library desugaring</a>.
   *
   * @since 33.4.0 (but since 22.0 in the JRE flavor)
   */
  @SuppressWarnings("Java7ApiChecker")
  // If users use this when they shouldn't, we hope that NewApi will catch
  // subsequent Duration calls
  @IgnoreJRERequirement
  @J2ktIncompatible
  @GwtIncompatible
  @J2ObjCIncompatible
  public Duration elapsed() {
    return Duration.ofNanos(elapsedNanos());
  }

  /** Returns a string representation of the current elapsed time. */
  @Override
  public String toString() {
    long nanos = elapsedNanos();

    TimeUnit unit = chooseUnit(nanos);
    double value = (double) nanos / NANOSECONDS.convert(1, unit);

    // Too bad this functionality is not exposed as a regular method call
    return Platform.formatCompact4Digits(value) + " " + abbreviate(unit);
  }

  private static TimeUnit chooseUnit(long nanos) {
    if (DAYS.convert(nanos, NANOSECONDS) > 0) {
      return DAYS;
    }
    if (HOURS.convert(nanos, NANOSECONDS) > 0) {
      return HOURS;
    }
    if (MINUTES.convert(nanos, NANOSECONDS) > 0) {
      return MINUTES;
    }
    if (SECONDS.convert(nanos, NANOSECONDS) > 0) {
      return SECONDS;
    }
    if (MILLISECONDS.convert(nanos, NANOSECONDS) > 0) {
      return MILLISECONDS;
    }
    if (MICROSECONDS.convert(nanos, NANOSECONDS) > 0) {
      return MICROSECONDS;
    }
    return NANOSECONDS;
  }

  private static String abbreviate(TimeUnit unit) {
    switch (unit) {
      case NANOSECONDS:
        return "ns";
      case MICROSECONDS:
        return "\u03bcs"; // μs
      case MILLISECONDS:
        return "ms";
      case SECONDS:
        return "s";
      case MINUTES:
        return "min";
      case HOURS:
        return "h";
      case DAYS:
        return "d";
    }
    throw new AssertionError();
  }
}
