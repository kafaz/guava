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

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.google.common.annotations.GwtIncompatible;

/**
 * 提供与{@code java.util.concurrent.atomic}包中的类相关的静态工具方法。
 * Static utility methods pertaining to classes in the
 * {@code java.util.concurrent.atomic} package.
 *
 * @author Kurt Alfred Kluever
 * @since 10.0
 */
@GwtIncompatible
public final class Atomics {
  /**
   * 私有构造函数，防止实例化
   * Private constructor to prevent instantiation
   */
  private Atomics() {
  }

  /**
   * 创建一个没有初始值的{@code AtomicReference}实例。
   * Creates an {@code AtomicReference} instance with no initial value.
   *
   * @return 一个新的没有初始值的{@code AtomicReference}
   *         a new {@code AtomicReference} with no initial value
   */
  public static <V> AtomicReference<@Nullable V> newReference() {
    return new AtomicReference<>();
  }

  /**
   * 创建一个具有给定初始值的{@code AtomicReference}实例。
   * Creates an {@code AtomicReference} instance with the given initial value.
   *
   * @param initialValue 初始值
   *                     the initial value
   * @return 一个具有给定初始值的新的{@code AtomicReference}
   *         a new {@code AtomicReference} with the given initial value
   */
  public static <V extends @Nullable Object> AtomicReference<V> newReference(
      @ParametricNullness V initialValue) {
    return new AtomicReference<>(initialValue);
  }

  /**
   * 创建一个指定长度的{@code AtomicReferenceArray}实例。
   * Creates an {@code AtomicReferenceArray} instance of given length.
   *
   * @param length 数组的长度
   *               the length of the array
   * @return 一个具有指定长度的新的{@code AtomicReferenceArray}
   *         a new {@code AtomicReferenceArray} with the given length
   */
  public static <E> AtomicReferenceArray<@Nullable E> newReferenceArray(int length) {
    return new AtomicReferenceArray<>(length);
  }

  /**
   * 创建一个{@code AtomicReferenceArray}实例，其长度与给定数组相同，并复制其所有元素。
   * Creates an {@code AtomicReferenceArray} instance with the same length as, and
   * all elements
   * copied from, the given array.
   *
   * @param array 要复制元素的源数组
   *              the array to copy elements from
   * @return 一个从给定数组复制的新的{@code AtomicReferenceArray}
   *         a new {@code AtomicReferenceArray} copied from the given array
   */
  public static <E extends @Nullable Object> AtomicReferenceArray<E> newReferenceArray(E[] array) {
    return new AtomicReferenceArray<>(array);
  }
}
