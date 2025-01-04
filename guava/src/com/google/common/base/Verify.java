/*
 * Copyright (C) 2013 The Guava Authors
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

import static com.google.common.base.Strings.lenientFormat;

import com.google.common.annotations.GwtCompatible;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import javax.annotation.CheckForNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * 提供与 Java 语言断言类似功能的静态便利方法，但这些方法始终处于启用状态。当检查可能在"实际环境"中失败时，
 * 应使用这些方法来替代 Java 断言。
 * Static convenience methods that serve the same purpose as Java language assertions, except that 
 * they are always enabled. These methods should be used instead of Java assertions whenever there 
 * is a chance the check may fail "in real life".
 *
 * 示例 Example:
 * <pre>{@code
 * Bill bill = remoteService.getLastUnpaidBill();
 *
 * // 如果 bug 12345 再次发生，我们宁愿直接终止程序
 * // In case bug 12345 happens again we'd rather just die
 * Verify.verify(bill.status() == Status.UNPAID,
 *     "Unexpected bill status: %s", bill.status());
 * }</pre>
 *
 * <h3>与其他方案的比较 Comparison to alternatives</h3>
 *
 * 注意：在某些情况下，下面解释的差异可能很微妙。当不确定使用哪种方法时，不用太担心，
 * 选择一个看起来合理的方案就可以。
 * Note: In some cases the differences explained below can be subtle. When it's unclear which 
 * approach to use, don't worry too much about it; just pick something that seems reasonable 
 * and it will be fine.
 *
 * <ul>
 *   <li>如果要检查调用者是否违反了你的方法或构造函数的约定（例如传入无效参数），
 *       应使用 Preconditions 类的工具方法。
 *       If checking whether the caller has violated your method or constructor's contract, 
 *       use the utilities of the Preconditions class instead.
 *
 *   <li>如果检查不可能发生的情况（除非你自己的类或其受信任的依赖项严重损坏，否则不会发生），
 *       这就是普通 Java 断言的用途。注意，断言默认是禁用的，本质上被视为"已编译的注释"。
 *       If checking an impossible condition, this is what ordinary Java assertions are for. 
 *       Note that assertions are not enabled by default.
 *
 *   <li>显式的 if/throw 始终是可接受的；我们仍建议使用 VerifyException 异常类型。
 *       不建议抛出普通的 RuntimeException。
 *       An explicit if/throw is always acceptable; we recommend using VerifyException. 
 *       Throwing plain RuntimeException is frowned upon.
 *
 *   <li>通常不建议使用 Objects.requireNonNull()，因为 verifyNotNull() 和 
 *       Preconditions.checkNotNull() 可以更清晰地执行相同的功能。
 *       Use of Objects.requireNonNull() is generally discouraged, since verifyNotNull() 
 *       and Preconditions.checkNotNull() perform the same function with more clarity.
 * </ul>
 *
 * <h3>性能警告 Warning about performance</h3>
 *
 * 请记住，消息构造的参数值都必须立即计算，即使验证成功且消息最终未使用，也可能发生自动装箱和
 * 可变参数数组创建。对性能敏感的验证检查应继续使用常规形式：
 * Remember that parameter values for message construction must all be computed eagerly, and
 * autoboxing and varargs array creation may happen as well, even when the verification succeeds
 * and the message ends up unneeded. Performance-sensitive verification checks should continue
 * to use usual form:
 *
 * <pre>{@code
 * Bill bill = remoteService.getLastUnpaidBill();
 * if (bill.status() != Status.UNPAID) {
 *   throw new VerifyException("Unexpected bill status: " + bill.status());
 * }
 * }</pre>
 *
 * <h3>仅支持 %s Only %s is supported</h3>
 *
 * 与 Preconditions 一样，Verify 使用 Strings.lenientFormat 来格式化错误消息模板字符串。
 * 这只支持 "%s" 说明符，不支持 java.util.Formatter 的全部说明符。但是，即使参数数量与格式
 * 字符串中 "%s" 的出现次数不匹配，Verify 仍会按预期运行，并在错误消息中包含所有参数值。
 * As with Preconditions, Verify uses Strings.lenientFormat to format error message templates.
 * This only supports the "%s" specifier, not the full range of Formatter specifiers.
 *
 * @since 17.0
 */
@GwtCompatible
public final class Verify {
  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with no
   * message otherwise.
   *
   * @throws VerifyException if {@code expression} is {@code false}
   * @see Preconditions#checkState Preconditions.checkState()
   */
  public static void verify(boolean expression) {
    if (!expression) {
      throw new VerifyException();
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * @param expression a boolean expression
   * @param errorMessageTemplate a template for the exception message should the check fail. The
   *     message is formed by replacing each {@code %s} placeholder in the template with an
   *     argument. These are matched by position - the first {@code %s} gets {@code
   *     errorMessageArgs[0]}, etc. Unmatched arguments will be appended to the formatted message in
   *     square braces. Unmatched placeholders will be left as-is.
   * @param errorMessageArgs the arguments to be substituted into the message template. Arguments
   *     are converted to strings using {@link String#valueOf(Object)}.
   * @throws VerifyException if {@code expression} is {@code false}
   * @see Preconditions#checkState Preconditions.checkState()
   */
  public static void verify(
      boolean expression,
      String errorMessageTemplate,
      @CheckForNull @Nullable Object... errorMessageArgs) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, errorMessageArgs));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, char p1) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, int p1) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, long p1) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression, String errorMessageTemplate, @CheckForNull Object p1) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, char p1, char p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, int p1, char p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, long p1, char p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression, String errorMessageTemplate, @CheckForNull Object p1, char p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, char p1, int p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, int p1, int p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, long p1, int p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression, String errorMessageTemplate, @CheckForNull Object p1, int p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, char p1, long p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, int p1, long p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(boolean expression, String errorMessageTemplate, long p1, long p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression, String errorMessageTemplate, @CheckForNull Object p1, long p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression, String errorMessageTemplate, char p1, @CheckForNull Object p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression, String errorMessageTemplate, int p1, @CheckForNull Object p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression, String errorMessageTemplate, long p1, @CheckForNull Object p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression,
      String errorMessageTemplate,
      @CheckForNull Object p1,
      @CheckForNull Object p2) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression,
      String errorMessageTemplate,
      @CheckForNull Object p1,
      @CheckForNull Object p2,
      @CheckForNull Object p3) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2, p3));
    }
  }

  /**
   * Ensures that {@code expression} is {@code true}, throwing a {@code VerifyException} with a
   * custom message otherwise.
   *
   * <p>See {@link #verify(boolean, String, Object...)} for details.
   *
   * @since 23.1 (varargs overload since 17.0)
   */
  public static void verify(
      boolean expression,
      String errorMessageTemplate,
      @CheckForNull Object p1,
      @CheckForNull Object p2,
      @CheckForNull Object p3,
      @CheckForNull Object p4) {
    if (!expression) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, p1, p2, p3, p4));
    }
  }

  /*
   * For a discussion of the signature of verifyNotNull, see the discussion above
   * Preconditions.checkNotNull.
   *
   * (verifyNotNull has many fewer "problem" callers, so we could try to be stricter. On the other
   * hand, verifyNotNull arguably has more reason to accept nullable arguments in the first
   * place....)
   */

  /**
   * Ensures that {@code reference} is non-null, throwing a {@code VerifyException} with a default
   * message otherwise.
   *
   * @return {@code reference}, guaranteed to be non-null, for convenience
   * @throws VerifyException if {@code reference} is {@code null}
   * @see Preconditions#checkNotNull Preconditions.checkNotNull()
   */
  @CanIgnoreReturnValue
  public static <T> T verifyNotNull(@CheckForNull T reference) {
    return verifyNotNull(reference, "expected a non-null reference");
  }

  /**
   * Ensures that {@code reference} is non-null, throwing a {@code VerifyException} with a custom
   * message otherwise.
   *
   * @param errorMessageTemplate a template for the exception message should the check fail. The
   *     message is formed by replacing each {@code %s} placeholder in the template with an
   *     argument. These are matched by position - the first {@code %s} gets {@code
   *     errorMessageArgs[0]}, etc. Unmatched arguments will be appended to the formatted message in
   *     square braces. Unmatched placeholders will be left as-is.
   * @param errorMessageArgs the arguments to be substituted into the message template. Arguments
   *     are converted to strings using {@link String#valueOf(Object)}.
   * @return {@code reference}, guaranteed to be non-null, for convenience
   * @throws VerifyException if {@code reference} is {@code null}
   * @see Preconditions#checkNotNull Preconditions.checkNotNull()
   */
  @CanIgnoreReturnValue
  public static <T> T verifyNotNull(
      @CheckForNull T reference,
      String errorMessageTemplate,
      @CheckForNull @Nullable Object... errorMessageArgs) {
    if (reference == null) {
      throw new VerifyException(lenientFormat(errorMessageTemplate, errorMessageArgs));
    }
    return reference;
  }

  // TODO(kevinb): consider <T> T verifySingleton(Iterable<T>) to take over for
  // Iterables.getOnlyElement()

  private Verify() {}
}
