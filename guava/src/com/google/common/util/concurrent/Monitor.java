/*
 * Copyright (C) 2010 The Guava Authors
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Internal.toNanosSaturated;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.J2ktIncompatible;
import com.google.common.primitives.Longs;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.j2objc.annotations.Weak;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import javax.annotation.CheckForNull;

/** 同步抽象类,提供了比Reentrantlock更高级的同步机制
 * A synchronization abstraction supporting waiting on arbitrary boolean conditions.
 *
 * <p>This class is intended as a replacement for {@link ReentrantLock}. Code using {@code Monitor}
 * is less error-prone and more readable than code using {@code ReentrantLock}, without significant
 * performance loss. {@code Monitor} even has the potential for performance gain by optimizing the
 * evaluation and signaling of conditions. Signaling is entirely <a
 * href="http://en.wikipedia.org/wiki/Monitor_(synchronization)#Implicit_signaling">implicit</a>. By
 * eliminating explicit signaling, this class can guarantee that only one thread is awakened when a
 * condition becomes true (no "signaling storms" due to use of {@link
 * java.util.concurrent.locks.Condition#signalAll Condition.signalAll}) and that no signals are lost
 * (no "hangs" due to incorrect use of {@link java.util.concurrent.locks.Condition#signal
 * Condition.signal}).
 *
 * <p>A thread is said to <i>occupy</i> a monitor if it has <i>entered</i> the monitor but not yet
 * <i>left</i>. Only one thread may occupy a given monitor at any moment. A monitor is also
 * reentrant, so a thread may enter a monitor any number of times, and then must leave the same
 * number of times. The <i>enter</i> and <i>leave</i> operations have the same synchronization
 * semantics as the built-in Java language synchronization primitives.
 *
 * <p>A call to any of the <i>enter</i> methods with <b>void</b> return type should always be
 * followed immediately by a <i>try/finally</i> block to ensure that the current thread leaves the
 * monitor cleanly:
 *
 * <pre>{@code
 * monitor.enter();
 * try {
 *   // do things while occupying the monitor
 * } finally {
 *   monitor.leave();
 * }
 * }</pre>
 *
 * <p>A call to any of the <i>enter</i> methods with <b>boolean</b> return type should always appear
 * as the condition of an <i>if</i> statement containing a <i>try/finally</i> block to ensure that
 * the current thread leaves the monitor cleanly:
 *
 * <pre>{@code
 * if (monitor.tryEnter()) {
 *   try {
 *     // do things while occupying the monitor
 *   } finally {
 *     monitor.leave();
 *   }
 * } else {
 *   // do other things since the monitor was not available
 * }
 * }</pre>
 *
 * <h2>Comparison with {@code synchronized} and {@code ReentrantLock}</h2>
 *
 * <p>The following examples show a simple threadsafe holder expressed using {@code synchronized},
 * {@link ReentrantLock}, and {@code Monitor}.
 *
 * <h3>{@code synchronized}</h3>
 *
 * <p>This version is the fewest lines of code, largely because the synchronization mechanism used
 * is built into the language and runtime. But the programmer has to remember to avoid a couple of
 * common bugs: The {@code wait()} must be inside a {@code while} instead of an {@code if}, and
 * {@code notifyAll()} must be used instead of {@code notify()} because there are two different
 * logical conditions being awaited.
 *
 * <pre>{@code
 * public class SafeBox<V> {
 *   private V value;
 *
 *   public synchronized V get() throws InterruptedException {
 *     while (value == null) {
 *       wait();
 *     }
 *     V result = value;
 *     value = null;
 *     notifyAll();
 *     return result;
 *   }
 *
 *   public synchronized void set(V newValue) throws InterruptedException {
 *     while (value != null) {
 *       wait();
 *     }
 *     value = newValue;
 *     notifyAll();
 *   }
 * }
 * }</pre>
 *
 * <h3>{@code ReentrantLock}</h3>
 *
 * <p>This version is much more verbose than the {@code synchronized} version, and still suffers
 * from the need for the programmer to remember to use {@code while} instead of {@code if}. However,
 * one advantage is that we can introduce two separate {@code Condition} objects, which allows us to
 * use {@code signal()} instead of {@code signalAll()}, which may be a performance benefit.
 *
 * <pre>{@code
 * public class SafeBox<V> {
 *   private V value;
 *   private final ReentrantLock lock = new ReentrantLock();
 *   private final Condition valuePresent = lock.newCondition();
 *   private final Condition valueAbsent = lock.newCondition();
 *
 *   public V get() throws InterruptedException {
 *     lock.lock();
 *     try {
 *       while (value == null) {
 *         valuePresent.await();
 *       }
 *       V result = value;
 *       value = null;
 *       valueAbsent.signal();
 *       return result;
 *     } finally {
 *       lock.unlock();
 *     }
 *   }
 *
 *   public void set(V newValue) throws InterruptedException {
 *     lock.lock();
 *     try {
 *       while (value != null) {
 *         valueAbsent.await();
 *       }
 *       value = newValue;
 *       valuePresent.signal();
 *     } finally {
 *       lock.unlock();
 *     }
 *   }
 * }
 * }</pre>
 *
 * <h3>{@code Monitor}</h3>
 *
 * <p>This version adds some verbosity around the {@code Guard} objects, but removes that same
 * verbosity, and more, from the {@code get} and {@code set} methods. {@code Monitor} implements the
 * same efficient signaling as we had to hand-code in the {@code ReentrantLock} version above.
 * Finally, the programmer no longer has to hand-code the wait loop, and therefore doesn't have to
 * remember to use {@code while} instead of {@code if}.
 *
 * <pre>{@code
 * public class SafeBox<V> {
 *   private V value;
 *   private final Monitor monitor = new Monitor();
 *   private final Monitor.Guard valuePresent = monitor.newGuard(() -> value != null);
 *   private final Monitor.Guard valueAbsent = monitor.newGuard(() -> value == null);
 *
 *   public V get() throws InterruptedException {
 *     monitor.enterWhen(valuePresent);
 *     try {
 *       V result = value;
 *       value = null;
 *       return result;
 *     } finally {
 *       monitor.leave();
 *     }
 *   }
 *
 *   public void set(V newValue) throws InterruptedException {
 *     monitor.enterWhen(valueAbsent);
 *     try {
 *       value = newValue;
 *     } finally {
 *       monitor.leave();
 *     }
 *   }
 * }
 * }</pre>
 *
 * @author Justin T. Sampson
 * @author Martin Buchholz
 * @since 10.0
 */
/**
 * 一个支持在任意布尔条件上等待的同步抽象类。
 *
 * <p>本类旨在替代 {@link ReentrantLock}。使用 {@code Monitor} 的代码相比使用 
 * {@code ReentrantLock} 更不容易出错且更易读,并且不会带来明显的性能损失。
 * {@code Monitor} 甚至可能通过优化条件的评估和信号传递来提高性能。信号传递完全是
 * <a href="http://en.wikipedia.org/wiki/Monitor_(synchronization)#Implicit_signaling">隐式的</a>。
 * 通过消除显式信号传递,本类可以保证当条件变为true时只唤醒一个线程(避免由于使用 
 * {@link java.util.concurrent.locks.Condition#signalAll Condition.signalAll} 导致的"信号风暴"),
 * 且不会丢失信号(避免由于错误使用 {@link java.util.concurrent.locks.Condition#signal Condition.signal} 导致的"挂起")。
 *
 * <p>如果一个线程已经"进入"监视器但尚未"离开",则称该线程"占用"该监视器。任何时刻只能有一个线程占用给定的监视器。
 * 监视器也是可重入的,因此线程可以多次进入监视器,但必须离开相同的次数。"进入"和"离开"操作具有与Java语言内置
 * 同步原语相同的同步语义。
 *
 * <p>对任何返回类型为 <b>void</b> 的"进入"方法的调用,应该始终紧跟一个 try/finally 块,
 * 以确保当前线程能够干净地离开监视器:
 *
 * <pre>{@code
 * monitor.enter();
 * try {
 *   // 在占用监视器时执行操作
 * } finally {
 *   monitor.leave();
 * }
 * }</pre>
 *
 * <p>对任何返回类型为 <b>boolean</b> 的"进入"方法的调用,应该始终作为 if 语句的条件,
 * 该语句包含一个 try/finally 块,以确保当前线程能够干净地离开监视器:
 *
 * <pre>{@code
 * if (monitor.tryEnter()) {
 *   try {
 *     // 在占用监视器时执行操作
 *   } finally {
 *     monitor.leave();
 *   }
 * } else {
 *   // 由于监视器不可用而执行其他操作
 * }
 * }</pre>
 *
 * <h2>与 {@code synchronized} 和 {@code ReentrantLock} 的比较</h2>
 *
 * <p>以下示例展示了使用 {@code synchronized}、{@link ReentrantLock} 和 {@code Monitor} 
 * 实现的简单线程安全容器。
 *
 * <h3>{@code synchronized} 实现</h3>
 *
 * <p>这个版本的代码最少,主要因为同步机制内置于语言和运行时中。但程序员必须注意避免两个常见错误:
 * {@code wait()} 必须在 {@code while} 而不是 {@code if} 中使用,且必须使用 {@code notifyAll()} 
 * 而不是 {@code notify()},因为有两个不同的逻辑条件在等待。
 *
 * <pre>{@code
 * public class SafeBox<V> {
 *   private V value;
 *
 *   public synchronized V get() throws InterruptedException {
 *     while (value == null) { // 等待直到值存在
 *       wait();
 *     }
 *     V result = value;
 *     value = null;
 *     notifyAll(); // 通知其他等待的线程
 *     return result;
 *   }
 *
 *   public synchronized void set(V newValue) throws InterruptedException {
 *     while (value != null) { // 等待直到值为空
 *       wait();
 *     }
 *     value = newValue;
 *     notifyAll(); // 通知其他等待的线程
 *   }
 * }
 * }</pre>
 *
 * <h3>{@code ReentrantLock} 实现</h3>
 *
 * <p>这个版本比 {@code synchronized} 版本更冗长,仍然需要程序员记住使用 {@code while} 而不是 {@code if}。
 * 然而,一个优点是我们可以引入两个独立的 {@code Condition} 对象,这允许我们使用 {@code signal()} 
 * 而不是 {@code signalAll()},可能带来性能收益。
 *
 * <h3>{@code Monitor} 实现</h3>
 *
 * <p>这个版本在 {@code Guard} 对象方面增加了一些代码,但在 {@code get} 和 {@code set} 方法中
 * 减少了更多的代码。{@code Monitor} 实现了与我们在 {@code ReentrantLock} 版本中手动编码的相同的
 * 高效信号机制。最后,程序员不再需要手动编码等待循环,因此不用记住使用 {@code while} 而不是 {@code if}。
 *
 * @author Justin T. Sampson
 * @author Martin Buchholz
 * @since 10.0
 */
/**
 * Monitor类 - 一个支持任意布尔条件等待的同步抽象类。
 *
 * <p>该类的设计目的是作为{@link ReentrantLock}的替代品。使用{@code Monitor}的代码
 * 比使用{@code ReentrantLock}更不容易出错且更易读，同时不会带来明显的性能损失。
 * {@code Monitor}通过优化条件评估和信号机制，甚至可能获得性能提升。信号机制完全是
 * <a href="http://en.wikipedia.org/wiki/Monitor_(synchronization)#Implicit_signaling">隐式的</a>。
 * 通过消除显式信号，该类可以保证：
 * 1. 当条件变为true时只唤醒一个线程(避免使用signalAll导致的"信号风暴")
 * 2. 不会丢失信号(避免由于不正确使用signal导致的"挂起")
 *
 * <p>线程状态说明:
 * - 当一个线程进入(entered)但尚未离开(left)监视器时，称该线程占用(occupy)了监视器
 * - 任何时刻只能有一个线程占用监视器
 * - 监视器是可重入的，所以一个线程可以多次进入监视器，但必须相同次数地离开
 * - enter和leave操作具有与Java内置同步原语相同的同步语义
 */

/**
 * 基本使用模式1 - void返回类型的enter方法：
 * 必须立即跟随try/finally块以确保线程正确离开监视器
 */

@J2ktIncompatible
@GwtIncompatible
@SuppressWarnings("GuardedBy") // TODO(b/35466881): Fix or suppress.
public final class Monitor {
  // TODO(user): Use raw LockSupport or AbstractQueuedSynchronizer instead of
  // ReentrantLock.
  // TODO(user): "Port" jsr166 tests for ReentrantLock.
  //
  // TODO(user): Change API to make it impossible to use a Guard with the "wrong"
  // monitor,
  // by making the monitor implicit, and to eliminate other sources of IMSE.
  // Imagine:
  // guard.lock();
  // try { /* monitor locked and guard satisfied here */ }
  // finally { guard.unlock(); }
  // Here are Justin's design notes about this:
  //
  // This idea has come up from time to time, and I think one of my
  // earlier versions of Monitor even did something like this. I ended
  // up strongly favoring the current interface.
  //
  // I probably can't remember all the reasons (it's possible you
  // could find them in the code review archives), but here are a few:
  //
  // 1. What about leaving/unlocking? Are you going to do
  // guard.enter() paired with monitor.leave()? That might get
  // confusing. It's nice for the finally block to look as close as
  // possible to the thing right before the try. You could have
  // guard.leave(), but that's a little odd as well because the
  // guard doesn't have anything to do with leaving. You can't
  // really enforce that the guard you're leaving is the same one
  // you entered with, and it doesn't actually matter.
  //
  // 2. Since you can enter the monitor without a guard at all, some
  // places you'll have monitor.enter()/monitor.leave() and other
  // places you'll have guard.enter()/guard.leave() even though
  // it's the same lock being acquired underneath. Always using
  // monitor.enterXXX()/monitor.leave() will make it really clear
  // which lock is held at any point in the code.
  //
  // 3. I think "enterWhen(notEmpty)" reads better than "notEmpty.enter()".
  //
  // TODO(user): Implement ReentrantLock features:
  // - toString() method
  // - getOwner() method
  // - getQueuedThreads() method
  // - getWaitingThreads(Guard) method
  // - implement Serializable
  // - redo the API to be as close to identical to ReentrantLock as possible,
  // since, after all, this class is also a reentrant mutual exclusion lock!?

  // TODO(user): 使用原始的 LockSupport 或 AbstractQueuedSynchronizer 来替代 ReentrantLock
  // TODO(user): 移植 jsr166 中的 ReentrantLock 测试用例
  //
  // TODO(user): 修改 API 以避免 Guard 与"错误"的 monitor 一起使用
  // 通过使 monitor 隐式化,并消除其他 IMSE(IllegalMonitorStateException) 的来源
  // 设想如下用法:
  // guard.lock();
  // try { /* 此处 monitor 已锁定且 guard 条件已满足 */ }
  // finally { guard.unlock(); }
  // 以下是 Justin 关于这个设计的说明:
  //
  // 这个想法不时出现,我早期版本的 Monitor 实现中可能就有类似的做法。
  // 但最终我还是强烈倾向于现在的接口设计。
  //
  // 我可能记不住所有的原因(你也许能在代码评审归档中找到),
  // 但这里列出几个主要原因:
  //
  // 1. 关于 leaving/unlocking 怎么处理? 你是要用 guard.enter() 
  //    搭配 monitor.leave() 吗? 这可能会造成混淆。try 语句前的代码
  //    和 finally 块中的代码看起来越接近越好。你可以使用 guard.leave(),
  //    但这也有点奇怪,因为 guard 实际上与离开操作无关。你无法真正确保
  //    你离开时用的 guard 就是进入时用的那个,而且这实际上并不重要。
  //
  // 2. 由于你可以不使用 guard 就进入 monitor,这样有些地方你会用
  //    monitor.enter()/monitor.leave(),而其他地方又用
  //    guard.enter()/guard.leave(),尽管底层使用的是同一个锁。
  //    始终使用 monitor.enterXXX()/monitor.leave() 可以让代码
  //    更清晰地展示在任何时候持有的是哪个锁。
  //
  // 3. "enterWhen(notEmpty)" 比 "notEmpty.enter()" 更易读。
  //
  // TODO(user): 实现 ReentrantLock 的特性:
  // - toString() 方法
  // - getOwner() 方法
  // - getQueuedThreads() 方法
  // - getWaitingThreads(Guard) 方法
  // - 实现 Serializable 接口
  // - 重新设计 API 使其尽可能接近 ReentrantLock,
  //   毕竟,这个类本质上也是一个可重入的互斥锁!
  /*
   * One of the key challenges of this class is to prevent lost signals, while
   * trying hard to
   * minimize unnecessary signals. One simple and correct algorithm is to signal
   * some other waiter
   * with a satisfied guard (if one exists) whenever any thread occupying the
   * monitor exits the
   * monitor, either by unlocking all of its held locks, or by starting to wait
   * for a guard. This
   * includes exceptional exits, so all control paths involving signalling must be
   * protected by a
   * finally block.
   *
   * Further optimizations of this algorithm become increasingly subtle. A wait
   * that terminates
   * without the guard being satisfied (due to timeout, but not interrupt) can
   * then immediately exit
   * the monitor without signalling. If it timed out without being signalled, it
   * does not need to
   * "pass on" the signal to another thread. If it *was* signalled, then its guard
   * must have been
   * satisfied at the time of signal, and has since been modified by some other
   * thread to be
   * non-satisfied before reacquiring the lock, and that other thread takes over
   * the responsibility
   * of signaling the next waiter.
   *
   * Unlike the underlying Condition, if we are not careful, an interrupt *can*
   * cause a signal to be
   * lost, because the signal may be sent to a condition whose sole waiter has
   * just been
   * interrupted.
   *
   * Imagine a monitor with multiple guards. A thread enters the monitor,
   * satisfies all the guards,
   * and leaves, calling signalNextWaiter. With traditional locks and conditions,
   * all the conditions
   * need to be signalled because it is not known which if any of them have
   * waiters (and hasWaiters
   * can't be used reliably because of a check-then-act race). With our Monitor
   * guards, we only
   * signal the first active guard that is satisfied. But the corresponding thread
   * may have already
   * been interrupted and is waiting to reacquire the lock while still registered
   * in activeGuards,
   * in which case the signal is a no-op, and the bigger-picture signal is lost
   * unless interrupted
   * threads take special action by participating in the signal-passing game.
   */
    /*
   * Monitor类的关键技术挑战之一是防止信号丢失,同时尽量减少不必要的信号传递。
   * 一个简单且正确的算法是:当任何占用monitor的线程退出monitor时(无论是通过解锁
   * 所有持有的锁,还是开始等待一个guard),都要向其他具有已满足guard条件的等待者
   * (如果存在)发送信号。这包括异常退出的情况,所以所有涉及发信号的控制路径都必须
   * 被finally块保护。
   *
   * 对这个算法的进一步优化变得越来越微妙:
   * - 如果等待因超时(非中断)终止且guard条件未满足,则可以直接退出monitor而无需发信号
   * - 如果超时前未收到信号,就不需要将信号"传递"给其他线程
   * - 如果收到了信号,则说明发信号时guard条件是满足的,但在重新获取锁之前已被其他
   *   线程修改为不满足状态。此时由该其他线程负责向下一个等待者发送信号
   *
   * 与底层的Condition不同,如果处理不当,中断可能导致信号丢失。这是因为信号可能被
   * 发送到一个其唯一等待者刚刚被中断的条件上。
   *
   * 考虑一个具有多个guards的monitor场景:
   * 1. 一个线程进入monitor,满足了所有guards条件,然后离开,调用signalNextWaiter
   * 2. 使用传统的锁和条件时,需要对所有条件发信号,因为:
   *    - 不知道哪些条件有等待者
   *    - 由于检查-操作竞争,hasWaiters不能可靠使用
   * 3. 使用Monitor guards时:
   *    - 我们只对第一个满足条件的活动guard发信号
   *    - 但相应线程可能已被中断,正在等待重新获取锁(仍在activeGuards中注册)
   *    - 这种情况下信号会无效
   *    - 除非被中断的线程通过参与信号传递来采取特殊措施,否则更大范围的信号就会丢失
   */
  /*
   * Timeout handling is intricate, especially given our ambitious goals:
   * - Avoid underflow and overflow of timeout values when specified timeouts are
   * close to
   * Long.MIN_VALUE or Long.MAX_VALUE.
   * - Favor responding to interrupts over timeouts.
   * - System.nanoTime() is expensive enough that we want to call it the minimum
   * required number of
   * times, typically once before invoking a blocking method. This often requires
   * keeping track of
   * the first time in a method that nanoTime() has been invoked, for which the
   * special value 0L
   * is reserved to mean "uninitialized". If timeout is non-positive, then
   * nanoTime need never be
   * called.
   * - Keep behavior of fair and non-fair instances consistent.
   */
    /*
   * 超时处理十分复杂,我们有以下几个目标要求:
   *
   * 1. 溢出防护:
   *    - 避免在指定的超时值接近 Long.MIN_VALUE 或 Long.MAX_VALUE 时发生下溢或上溢
   *
   * 2. 优先级处理:
   *    - 优先响应中断而不是超时
   *
   * 3. 性能优化:
   *    - System.nanoTime() 调用开销较大,需要将调用次数降至最少
   *    - 通常在调用阻塞方法前只调用一次
   *    - 需要跟踪方法中首次调用 nanoTime() 的时间
   *    - 使用特殊值 0L 表示"未初始化"状态
   *    - 如果超时值为非正数,则无需调用 nanoTime
   *
   * 4. 一致性保证:
   *    - 保持公平和非公平实例的行为一致性
   */

  /**
   * A boolean condition for which a thread may wait. A {@code Guard} is
   * associated with a single
   * {@code Monitor}. The monitor may check the guard at arbitrary times from any
   * thread occupying
   * the monitor, so code should not be written to rely on how often a guard might
   * or might not be
   * checked.
   *
   * <p>
   * If a {@code Guard} is passed into any method of a {@code Monitor} other than
   * the one it is
   * associated with, an {@link IllegalMonitorStateException} is thrown.
   * question: Guard 怎么保证调用时使用的函数是线程安全的?
   * @since 10.0
   */
  public abstract static class Guard {

    @Weak
    final Monitor monitor;
    final Condition condition;

    @GuardedBy("monitor.lock")
    int waiterCount = 0;

    /** The next active guard */
    @GuardedBy("monitor.lock")
    @CheckForNull
    Guard next;

    protected Guard(Monitor monitor) {
      this.monitor = checkNotNull(monitor, "monitor");
      this.condition = monitor.lock.newCondition();
    }

    /**
     * Evaluates this guard's boolean condition. This method is always called with
     * the associated
     * monitor already occupied. Implementations of this method must depend only on
     * state protected
     * by the associated monitor, and must not modify that state.
     */
    public abstract boolean isSatisfied();
  }

  /** Whether this monitor is fair. */
  private final boolean fair;

  /** The lock underlying this monitor. */
  private final ReentrantLock lock;

  /**
   * The guards associated with this monitor that currently have waiters
   * ({@code waiterCount > 0}).
   * A linked list threaded through the Guard.next field.
   */
  @GuardedBy("lock")
  @CheckForNull
  private Guard activeGuards = null;

  /**
   * Creates a monitor with a non-fair (but fast) ordering policy. Equivalent to
   * {@code
   * Monitor(false)}.
   */
  public Monitor() {
    this(false);
  }

  /**
   * Creates a monitor with the given ordering policy.
   *
   * @param fair whether this monitor should use a fair ordering policy rather
   *             than a non-fair (but
   *             fast) one
   */
  public Monitor(boolean fair) {
    this.fair = fair;
    this.lock = new ReentrantLock(fair);
  }

  /**
   * 为Monitor创建一个新的Guard(守卫)条件。Guard用于控制线程的等待条件。
   * 
   * <p>Guard在实现上采用了模板方法模式:
   * 1. 抽象父类定义基础框架
   * 2. 子类实现具体的条件判断逻辑
   *
   * <p>使用示例:
   * <pre>{@code
   * Monitor monitor = new Monitor();
   * // 创建一个等待队列非空的Guard
   * Guard queueNotEmpty = monitor.newGuard(() -> !queue.isEmpty());
   * 
   * // 等待直到队列有数据
   * monitor.enterWhen(queueNotEmpty); 
   * try {
   *   // 处理队列数据
   * } finally {
   *   monitor.leave();
   * }
   * }</pre>
   *
   * @param isSatisfied 一个返回布尔值的函数,用于判断Guard条件是否满足
   * @return 一个新的Guard实例,绑定到当前Monitor
   * @throws NullPointerException 如果isSatisfied为null
   * @since 21.0 (Android 33.4.0开始支持)
   */
  public Guard newGuard(final BooleanSupplier isSatisfied) {
      checkNotNull(isSatisfied, "isSatisfied");
      return new Guard(this) {
          @Override
          public boolean isSatisfied() {
              return isSatisfied.getAsBoolean(); // 委托给传入的条件判断函数
          }
      };
  }

  /** Enters this monitor. Blocks indefinitely. */
  public void enter() {
    lock.lock();
  }

  /**
   * Enters this monitor. Blocks at most the given time.
   *
   * @return whether the monitor was entered
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean enter(Duration time) {
    return enter(toNanosSaturated(time), TimeUnit.NANOSECONDS);
  }

  /**
   * Enters this monitor. Blocks at most the given time.
   *
   * @return whether the monitor was entered
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean enter(long time, TimeUnit unit) {
    final long timeoutNanos = toSafeNanos(time, unit);
    final ReentrantLock lock = this.lock;
    if (!fair && lock.tryLock()) {
      return true;
    }
    boolean interrupted = Thread.interrupted();
    try {
      final long startTime = System.nanoTime();
      for (long remainingNanos = timeoutNanos;;) {
        try {
          return lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupt) {
          interrupted = true;
          remainingNanos = remainingNanos(startTime, timeoutNanos);
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Enters this monitor. Blocks indefinitely, but may be interrupted.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  public void enterInterruptibly() throws InterruptedException {
    lock.lockInterruptibly();
  }

  /**
   * Enters this monitor. Blocks at most the given time, and may be interrupted.
   *
   * @return whether the monitor was entered
   * @throws InterruptedException if interrupted while waiting
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean enterInterruptibly(Duration time) throws InterruptedException {
    return enterInterruptibly(toNanosSaturated(time), TimeUnit.NANOSECONDS);
  }

  /**
   * Enters this monitor. Blocks at most the given time, and may be interrupted.
   *
   * @return whether the monitor was entered
   * @throws InterruptedException if interrupted while waiting
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean enterInterruptibly(long time, TimeUnit unit) throws InterruptedException {
    return lock.tryLock(time, unit);
  }

  /**
   * Enters this monitor if it is possible to do so immediately. Does not block.
   *
   * <p>
   * <b>Note:</b> This method disregards the fairness setting of this monitor.
   *
   * @return whether the monitor was entered
   */
  public boolean tryEnter() {
    return lock.tryLock();
  }

  /**
   * 当Guard条件满足时进入Monitor。无限期阻塞,但可被中断。
   *
   * @throws InterruptedException 如果在等待时被中断
   */
  public void enterWhen(Guard guard) throws InterruptedException {
      // 验证Guard是否属于当前Monitor
      if (guard.monitor != this) {
          throw new IllegalMonitorStateException();
      }
      
      final ReentrantLock lock = this.lock;
      // 检查当前线程是否已持有锁,用于后续信号处理
      boolean signalBeforeWaiting = lock.isHeldByCurrentThread();
      // 获取锁,支持中断
      lock.lockInterruptibly();
  
      boolean satisfied = false;
      try {
          if (!guard.isSatisfied()) {
              // 条件不满足,进入等待,可能发送信号
              await(guard, signalBeforeWaiting);
          }
          satisfied = true;
      } finally {
          // 如果条件未满足(可能由于中断),释放锁
          if (!satisfied) {
              leave();
          }
      }
  }

  /**
   * Enters this monitor when the guard is satisfied. Blocks at most the given
   * time, including both
   * the time to acquire the lock and the time to wait for the guard to be
   * satisfied, and may be
   * interrupted.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   * @throws InterruptedException if interrupted while waiting
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean enterWhen(Guard guard, Duration time) throws InterruptedException {
    return enterWhen(guard, toNanosSaturated(time), TimeUnit.NANOSECONDS);
  }

  /**
   * 当Guard条件满足时进入Monitor,带超时限制。
   * 阻塞时间包含获取锁的时间和等待Guard条件满足的时间。
   * 支持中断。
   *
   * @return 是否成功进入Monitor(同时意味着Guard条件满足)
   * @throws InterruptedException 如果等待过程中被中断
   */
  public boolean enterWhen(Guard guard, long time, TimeUnit unit) throws InterruptedException {
      // 将时间转换为纳秒,并进行安全性处理
      final long timeoutNanos = toSafeNanos(time, unit);
      
      // 验证Guard归属
      if (guard.monitor != this) {
          throw new IllegalMonitorStateException();
      }
  
      final ReentrantLock lock = this.lock;
      // 检查是否重入
      boolean reentrant = lock.isHeldByCurrentThread();
      // 记录开始时间
      long startTime = 0L;

      //note: 代码块标签, 有什么好处吗?
      locked: {
          if (!fair) {
              // 非公平锁模式:先检查中断状态,保持与公平模式行为一致
              if (Thread.interrupted()) {
                  throw new InterruptedException();
              }
              // 尝试立即获取锁
              if (lock.tryLock()) {
                  break locked;
              }
          }
          // 记录开始时间并尝试在限定时间内获取锁
          startTime = initNanoTime(timeoutNanos);
          if (!lock.tryLock(time, unit)) {
              return false;  // 获取锁超时
          }
      }
  
      boolean satisfied = false;
      boolean threw = true;  // 用于追踪是否发生异常
      try {
          // 检查Guard条件是否满足,或在剩余时间内等待
          satisfied = guard.isSatisfied()
              || awaitNanos(
                  guard,
                  (startTime == 0L) ? timeoutNanos : remainingNanos(startTime, timeoutNanos),
                  reentrant);
          threw = false;
          return satisfied;
      } finally {
          if (!satisfied) {
              try {
                  // 仅在发生异常且非重入时发送信号
                  if (threw && !reentrant) {
                      signalNextWaiter();
                  }
              } finally {
                  lock.unlock();
              }
          }
      }
  }

  /** Enters this monitor when the guard is satisfied. Blocks indefinitely. */
  public void enterWhenUninterruptibly(Guard guard) {
    if (guard.monitor != this) {
      throw new IllegalMonitorStateException();
    }
    final ReentrantLock lock = this.lock;
    boolean signalBeforeWaiting = lock.isHeldByCurrentThread();
    lock.lock();

    boolean satisfied = false;
    try {
      if (!guard.isSatisfied()) {
        awaitUninterruptibly(guard, signalBeforeWaiting);
      }
      satisfied = true;
    } finally {
      if (!satisfied) {
        leave();
      }
    }
  }

  /**
   * Enters this monitor when the guard is satisfied. Blocks at most the given
   * time, including both
   * the time to acquire the lock and the time to wait for the guard to be
   * satisfied.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean enterWhenUninterruptibly(Guard guard, Duration time) {
    return enterWhenUninterruptibly(guard, toNanosSaturated(time), TimeUnit.NANOSECONDS);
  }

  /**
   * Enters this monitor when the guard is satisfied. Blocks at most the given
   * time, including both
   * the time to acquire the lock and the time to wait for the guard to be
   * satisfied.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean enterWhenUninterruptibly(Guard guard, long time, TimeUnit unit) {
    final long timeoutNanos = toSafeNanos(time, unit);
    if (guard.monitor != this) {
      throw new IllegalMonitorStateException();
    }
    final ReentrantLock lock = this.lock;
    long startTime = 0L;
    boolean signalBeforeWaiting = lock.isHeldByCurrentThread();
    boolean interrupted = Thread.interrupted();
    try {
      if (fair || !lock.tryLock()) {
        startTime = initNanoTime(timeoutNanos);
        for (long remainingNanos = timeoutNanos;;) {
          try {
            if (lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS)) {
              break;
            } else {
              return false;
            }
          } catch (InterruptedException interrupt) {
            interrupted = true;
            remainingNanos = remainingNanos(startTime, timeoutNanos);
          }
        }
      }

      boolean satisfied = false;
      try {
        while (true) {
          try {
            if (guard.isSatisfied()) {
              satisfied = true;
            } else {
              final long remainingNanos;
              if (startTime == 0L) {
                startTime = initNanoTime(timeoutNanos);
                remainingNanos = timeoutNanos;
              } else {
                remainingNanos = remainingNanos(startTime, timeoutNanos);
              }
              satisfied = awaitNanos(guard, remainingNanos, signalBeforeWaiting);
            }
            return satisfied;
          } catch (InterruptedException interrupt) {
            interrupted = true;
            signalBeforeWaiting = false;
          }
        }
      } finally {
        if (!satisfied) {
          lock.unlock(); // No need to signal if timed out
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * 尝试进入Monitor并立即检查Guard条件。
   * 会阻塞等待获取锁,但不会等待Guard条件满足。
   * 如果条件不满足会立即释放锁并返回false。
   *
   * @param guard Guard条件
   * @return 是否成功进入Monitor(Guard条件满足)
   */
  public boolean enterIf(Guard guard) {
      // 校验Guard必须属于当前Monitor
      if (guard.monitor != this) {
          throw new IllegalMonitorStateException();
      }
      
      final ReentrantLock lock = this.lock;
      lock.lock();  // 获取锁,可能阻塞
  
      boolean satisfied = false;
      try {
          // 检查Guard条件并记录结果
          return satisfied = guard.isSatisfied();
      } finally {
          if (!satisfied) {
              // 条件不满足时释放锁
              lock.unlock();
          }
          // 条件满足时保持锁定
      }
  }

  /**
   * Enters this monitor if the guard is satisfied. Blocks at most the given time
   * acquiring the
   * lock, but does not wait for the guard to be satisfied.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean enterIf(Guard guard, Duration time) {
    return enterIf(guard, toNanosSaturated(time), TimeUnit.NANOSECONDS);
  }

  /**
   * Enters this monitor if the guard is satisfied. Blocks at most the given time
   * acquiring the
   * lock, but does not wait for the guard to be satisfied.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean enterIf(Guard guard, long time, TimeUnit unit) {
    if (guard.monitor != this) {
      throw new IllegalMonitorStateException();
    }
    if (!enter(time, unit)) {
      return false;
    }

    boolean satisfied = false;
    try {
      return satisfied = guard.isSatisfied();
    } finally {
      if (!satisfied) {
        lock.unlock();
      }
    }
  }

  /**
   * Enters this monitor if the guard is satisfied. Blocks indefinitely acquiring
   * the lock, but does
   * not wait for the guard to be satisfied, and may be interrupted.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   * @throws InterruptedException if interrupted while waiting
   */
  public boolean enterIfInterruptibly(Guard guard) throws InterruptedException {
    if (guard.monitor != this) {
      throw new IllegalMonitorStateException();
    }
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();

    boolean satisfied = false;
    try {
      return satisfied = guard.isSatisfied();
    } finally {
      if (!satisfied) {
        lock.unlock();
      }
    }
  }

  /**
   * Enters this monitor if the guard is satisfied. Blocks at most the given time
   * acquiring the
   * lock, but does not wait for the guard to be satisfied, and may be
   * interrupted.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean enterIfInterruptibly(Guard guard, Duration time) throws InterruptedException {
    return enterIfInterruptibly(guard, toNanosSaturated(time), TimeUnit.NANOSECONDS);
  }

  /**
   * Enters this monitor if the guard is satisfied. Blocks at most the given time
   * acquiring the
   * lock, but does not wait for the guard to be satisfied, and may be
   * interrupted.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean enterIfInterruptibly(Guard guard, long time, TimeUnit unit)
      throws InterruptedException {
    if (guard.monitor != this) {
      throw new IllegalMonitorStateException();
    }
    final ReentrantLock lock = this.lock;
    if (!lock.tryLock(time, unit)) {
      return false;
    }

    boolean satisfied = false;
    try {
      return satisfied = guard.isSatisfied();
    } finally {
      if (!satisfied) {
        lock.unlock();
      }
    }
  }

  /**
   * Enters this monitor if it is possible to do so immediately and the guard is
   * satisfied. Does not
   * block acquiring the lock and does not wait for the guard to be satisfied.
   *
   * <p>
   * <b>Note:</b> This method disregards the fairness setting of this monitor.
   *
   * @return whether the monitor was entered, which guarantees that the guard is
   *         now satisfied
   */
  public boolean tryEnterIf(Guard guard) {
    if (guard.monitor != this) {
      throw new IllegalMonitorStateException();
    }
    final ReentrantLock lock = this.lock;
    if (!lock.tryLock()) {
      return false;
    }

    boolean satisfied = false;
    try {
      return satisfied = guard.isSatisfied();
    } finally {
      if (!satisfied) {
        lock.unlock();
      }
    }
  }

  /**
   * Waits for the guard to be satisfied. Waits indefinitely, but may be
   * interrupted. May be called
   * only by a thread currently occupying this monitor.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  public void waitFor(Guard guard) throws InterruptedException {
    if (!((guard.monitor == this) && lock.isHeldByCurrentThread())) {
      throw new IllegalMonitorStateException();
    }
    if (!guard.isSatisfied()) {
      await(guard, true);
    }
  }

  /**
   * Waits for the guard to be satisfied. Waits at most the given time, and may be
   * interrupted. May
   * be called only by a thread currently occupying this monitor.
   *
   * @return whether the guard is now satisfied
   * @throws InterruptedException if interrupted while waiting
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean waitFor(Guard guard, Duration time) throws InterruptedException {
    return waitFor(guard, toNanosSaturated(time), TimeUnit.NANOSECONDS);
  }

  /**
   * Waits for the guard to be satisfied. Waits at most the given time, and may be
   * interrupted. May
   * be called only by a thread currently occupying this monitor.
   *
   * @return whether the guard is now satisfied
   * @throws InterruptedException if interrupted while waiting
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean waitFor(Guard guard, long time, TimeUnit unit) throws InterruptedException {
    final long timeoutNanos = toSafeNanos(time, unit);
    if (!((guard.monitor == this) && lock.isHeldByCurrentThread())) {
      throw new IllegalMonitorStateException();
    }
    if (guard.isSatisfied()) {
      return true;
    }
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    return awaitNanos(guard, timeoutNanos, true);
  }

  /**
   * Waits for the guard to be satisfied. Waits indefinitely. May be called only
   * by a thread
   * currently occupying this monitor.
   */
  public void waitForUninterruptibly(Guard guard) {
    if (!((guard.monitor == this) && lock.isHeldByCurrentThread())) {
      throw new IllegalMonitorStateException();
    }
    if (!guard.isSatisfied()) {
      awaitUninterruptibly(guard, true);
    }
  }

  /**
   * Waits for the guard to be satisfied. Waits at most the given time. May be
   * called only by a
   * thread currently occupying this monitor.
   *
   * @return whether the guard is now satisfied
   * @since 28.0 (but only since 33.4.0 in the Android flavor)
   */
  public boolean waitForUninterruptibly(Guard guard, Duration time) {
    return waitForUninterruptibly(guard, toNanosSaturated(time), TimeUnit.NANOSECONDS);
  }

  /**
   * Waits for the guard to be satisfied. Waits at most the given time. May be
   * called only by a
   * thread currently occupying this monitor.
   *
   * @return whether the guard is now satisfied
   */
  @SuppressWarnings("GoodTime") // should accept a java.time.Duration
  public boolean waitForUninterruptibly(Guard guard, long time, TimeUnit unit) {
    final long timeoutNanos = toSafeNanos(time, unit);
    if (!((guard.monitor == this) && lock.isHeldByCurrentThread())) {
      throw new IllegalMonitorStateException();
    }
    if (guard.isSatisfied()) {
      return true;
    }
    boolean signalBeforeWaiting = true;
    final long startTime = initNanoTime(timeoutNanos);
    boolean interrupted = Thread.interrupted();
    try {
      for (long remainingNanos = timeoutNanos;;) {
        try {
          return awaitNanos(guard, remainingNanos, signalBeforeWaiting);
        } catch (InterruptedException interrupt) {
          interrupted = true;
          if (guard.isSatisfied()) {
            return true;
          }
          signalBeforeWaiting = false;
          remainingNanos = remainingNanos(startTime, timeoutNanos);
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Leaves this monitor. May be called only by a thread currently occupying this
   * monitor.
   */
  public void leave() {
    final ReentrantLock lock = this.lock;
    try {
      // No need to signal if we will still be holding the lock when we return
      if (lock.getHoldCount() == 1) {
        signalNextWaiter();
      }
    } finally {
      lock.unlock(); // Will throw IllegalMonitorStateException if not held
    }
  }

  /** Returns whether this monitor is using a fair ordering policy. */
  public boolean isFair() {
    return fair;
  }

  /**
   * Returns whether this monitor is occupied by any thread. This method is
   * designed for use in
   * monitoring of the system state, not for synchronization control.
   */
  public boolean isOccupied() {
    return lock.isLocked();
  }

  /**
   * Returns whether the current thread is occupying this monitor (has entered
   * more times than it
   * has left).
   */
  public boolean isOccupiedByCurrentThread() {
    return lock.isHeldByCurrentThread();
  }

  /**
   * Returns the number of times the current thread has entered this monitor in
   * excess of the number
   * of times it has left. Returns 0 if the current thread is not occupying this
   * monitor.
   */
  public int getOccupiedDepth() {
    return lock.getHoldCount();
  }

  /**
   * Returns an estimate of the number of threads waiting to enter this monitor.
   * The value is only
   * an estimate because the number of threads may change dynamically while this
   * method traverses
   * internal data structures. This method is designed for use in monitoring of
   * the system state,
   * not for synchronization control.
   */
  public int getQueueLength() {
    return lock.getQueueLength();
  }

  /**
   * Returns whether any threads are waiting to enter this monitor. Note that
   * because cancellations
   * may occur at any time, a {@code true} return does not guarantee that any
   * other thread will ever
   * enter this monitor. This method is designed primarily for use in monitoring
   * of the system
   * state.
   */
  public boolean hasQueuedThreads() {
    return lock.hasQueuedThreads();
  }

  /**
   * Queries whether the given thread is waiting to enter this monitor. Note that
   * because
   * cancellations may occur at any time, a {@code true} return does not guarantee
   * that this thread
   * will ever enter this monitor. This method is designed primarily for use in
   * monitoring of the
   * system state.
   */
  public boolean hasQueuedThread(Thread thread) {
    return lock.hasQueuedThread(thread);
  }

  /**
   * Queries whether any threads are waiting for the given guard to become
   * satisfied. Note that
   * because timeouts and interrupts may occur at any time, a {@code true} return
   * does not guarantee
   * that the guard becoming satisfied in the future will awaken any threads. This
   * method is
   * designed primarily for use in monitoring of the system state.
   */
  public boolean hasWaiters(Guard guard) {
    return getWaitQueueLength(guard) > 0;
  }

  /**
   * Returns an estimate of the number of threads waiting for the given guard to
   * become satisfied.
   * Note that because timeouts and interrupts may occur at any time, the estimate
   * serves only as an
   * upper bound on the actual number of waiters. This method is designed for use
   * in monitoring of
   * the system state, not for synchronization control.
   */
  public int getWaitQueueLength(Guard guard) {
    if (guard.monitor != this) {
      throw new IllegalMonitorStateException();
    }
    lock.lock();
    try {
      return guard.waiterCount;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns unit.toNanos(time), additionally ensuring the returned value is not
   * at risk of
   * overflowing or underflowing, by bounding the value between 0 and
   * (Long.MAX_VALUE / 4) * 3.
   * Actually waiting for more than 219 years is not supported!
   */
  private static long toSafeNanos(long time, TimeUnit unit) {
    long timeoutNanos = unit.toNanos(time);
    return Longs.constrainToRange(timeoutNanos, 0L, (Long.MAX_VALUE / 4) * 3);
  }

  /**
   * Returns System.nanoTime() unless the timeout has already elapsed. Returns 0L
   * if and only if the
   * timeout has already elapsed.
   */
  private static long initNanoTime(long timeoutNanos) {
    if (timeoutNanos <= 0L) {
      return 0L;
    } else {
      long startTime = System.nanoTime();
      return (startTime == 0L) ? 1L : startTime;
    }
  }

  /**
   * Returns the remaining nanos until the given timeout, or 0L if the timeout has
   * already elapsed.
   * Caller must have previously sanitized timeoutNanos using toSafeNanos.
   */
  private static long remainingNanos(long startTime, long timeoutNanos) {
    // assert timeoutNanos == 0L || startTime != 0L;

    // TODO : NOT CORRECT, BUT TESTS PASS ANYWAYS!
    // if (true) return timeoutNanos;
    // ONLY 2 TESTS FAIL IF WE DO:
    // if (true) return 0;

    return (timeoutNanos <= 0L) ? 0L : timeoutNanos - (System.nanoTime() - startTime);
  }

  /**
   * Signals some other thread waiting on a satisfied guard, if one exists.
   *
   * <p>
   * We manage calls to this method carefully, to signal only when necessary, but
   * never losing a
   * signal, which is the classic problem of this kind of concurrency construct.
   * We must signal if
   * the current thread is about to relinquish the lock and may have changed the
   * state protected by
   * the monitor, thereby causing some guard to be satisfied.
   *
   * <p>
   * In addition, any thread that has been signalled when its guard was satisfied
   * acquires the
   * responsibility of signalling the next thread when it again relinquishes the
   * lock. Unlike a
   * normal Condition, there is no guarantee that an interrupted thread has not
   * been signalled,
   * since the concurrency control must manage multiple Conditions. So this method
   * must generally be
   * called when waits are interrupted.
   *
   * <p>
   * On the other hand, if a signalled thread wakes up to discover that its guard
   * is still not
   * satisfied, it does *not* need to call this method before returning to wait.
   * This can only
   * happen due to spurious wakeup (ignorable) or another thread acquiring the
   * lock before the
   * current thread can and returning the guard to the unsatisfied state. In the
   * latter case the
   * other thread (last thread modifying the state protected by the monitor) takes
   * over the
   * responsibility of signalling the next waiter.
   *
   * <p>
   * This method must not be called from within a beginWaitingFor/endWaitingFor
   * block, or else
   * the current thread's guard might be mistakenly signalled, leading to a lost
   * signal.
   */
  @GuardedBy("lock")
  private void signalNextWaiter() {
    for (Guard guard = activeGuards; guard != null; guard = guard.next) {
      if (isSatisfied(guard)) {
        guard.condition.signal();
        break;
      }
    }
  }

  /**
   * Exactly like signalNextWaiter, but caller guarantees that guardToSkip need
   * not be considered,
   * because caller has previously checked that guardToSkip.isSatisfied() returned
   * false. An
   * optimization for the case that guardToSkip.isSatisfied() may be expensive.
   *
   * <p>
   * We decided against using this method, since in practice, isSatisfied() is
   * likely to be very
   * cheap (typically one field read). Resurrect this method if you find that not
   * to be true.
   */
  // @GuardedBy("lock")
  // private void signalNextWaiterSkipping(Guard guardToSkip) {
  // for (Guard guard = activeGuards; guard != null; guard = guard.next) {
  // if (guard != guardToSkip && isSatisfied(guard)) {
  // guard.condition.signal();
  // break;
  // }
  // }
  // }

  /**
   * Exactly like guard.isSatisfied(), but in addition signals all waiting threads
   * in the (hopefully
   * unlikely) event that isSatisfied() throws.
   */
  @GuardedBy("lock")
  private boolean isSatisfied(Guard guard) {
    try {
      return guard.isSatisfied();
    } catch (Throwable throwable) {
      // Any Exception is either a RuntimeException or sneaky checked exception.
      signalAllWaiters();
      throw throwable;
    }
  }

  /** Signals all threads waiting on guards. */
  @GuardedBy("lock")
  private void signalAllWaiters() {
    for (Guard guard = activeGuards; guard != null; guard = guard.next) {
      guard.condition.signalAll();
    }
  }

  /** Records that the current thread is about to wait on the specified guard. */
  @GuardedBy("lock")
  private void beginWaitingFor(Guard guard) {
    int waiters = guard.waiterCount++;
    if (waiters == 0) {
      // push guard onto activeGuards
      guard.next = activeGuards;
      activeGuards = guard;
    }
  }

  /**
   * Records that the current thread is no longer waiting on the specified guard.
   */
  @GuardedBy("lock")
  private void endWaitingFor(Guard guard) {
    int waiters = --guard.waiterCount;
    if (waiters == 0) {
      // unlink guard from activeGuards
      for (Guard p = activeGuards, pred = null;; pred = p, p = p.next) {
        if (p == guard) {
          if (pred == null) {
            activeGuards = p.next;
          } else {
            pred.next = p.next;
          }
          p.next = null; // help GC
          break;
        }
      }
    }
  }

  /*
   * 等待Guard条件满足的核心方法。
   * 该方法会循环等待直到Guard条件满足,同时记录等待状态以便其他线程可以检查并发送信号。
   * 调用方负责确保调用时Guard条件未满足。
   */
  @GuardedBy("lock")
  private void await(Guard guard, boolean signalBeforeWaiting) throws InterruptedException {
      // 如果需要,在等待前发送信号给其他等待者
      if (signalBeforeWaiting) {
          signalNextWaiter();
      }
      
      // 记录开始等待状态
      beginWaitingFor(guard);
      try {
          do {
              // 在Guard关联的Condition上等待
              guard.condition.await();
          } while (!guard.isSatisfied());  // 循环直到条件满足
      } finally {
          // 清理等待状态
          endWaitingFor(guard);
      }
  }
  }

  @GuardedBy("lock")
  private void awaitUninterruptibly(Guard guard, boolean signalBeforeWaiting) {
    if (signalBeforeWaiting) {
      signalNextWaiter();
    }
    beginWaitingFor(guard);
    try {
      do {
        guard.condition.awaitUninterruptibly();
      } while (!guard.isSatisfied());
    } finally {
      endWaitingFor(guard);
    }
  }

  /** Caller should check before calling that guard is not satisfied. */
  @GuardedBy("lock")
  private boolean awaitNanos(Guard guard, long nanos, boolean signalBeforeWaiting)
      throws InterruptedException {
    boolean firstTime = true;
    try {
      do {
        if (nanos <= 0L) {
          return false;
        }
        if (firstTime) {
          if (signalBeforeWaiting) {
            signalNextWaiter();
          }
          beginWaitingFor(guard);
          firstTime = false;
        }
        nanos = guard.condition.awaitNanos(nanos);
      } while (!guard.isSatisfied());
      return true;
    } finally {
      if (!firstTime) {
        endWaitingFor(guard);
      }
    }
  }
}
