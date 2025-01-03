/*
 * Copyright (C) 2015 The Guava Authors
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

import com.google.common.annotations.GwtCompatible;
import java.util.concurrent.Future;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * 一个可能异步计算值的接口。类似于 Callable，但返回 ListenableFuture 结果。
 * 
 * 主要特点：
 * 1. 支持异步计算
 * 2. 返回 ListenableFuture 类型
 * 3. 可用于异步派生操作
 * 
 * 使用场景：
 * - 异步任务链式调用
 * - 复杂的异步计算
 * - 需要监听结果的异步操作
 * 
 * 示例用法参见：{@link Futures.FutureCombiner#callAsync(AsyncCallable, java.util.concurrent.Executor)}
 *
 * @param <V> 计算结果的类型，允许为 null
 * @since 20.0
 */
@FunctionalInterface
@GwtCompatible
public interface AsyncCallable<V extends @Nullable Object> {
  /**
   * 执行异步计算并返回一个 Future 结果。
   * 
   * 重要特性：
   * 1. 返回的 Future 无需立即完成（isDone 可能为 false）
   * 2. 适用于异步派生场景
   * 3. 抛出异常等价于返回失败的 Future
   * 
   * 实现注意事项：
   * - 可以返回尚未完成的 Future
   * - 异常处理：方法可以抛出异常，会被转换为失败的 Future
   * - 建议实现异步操作以提高性能
   * 
   * @return 包含计算结果的 ListenableFuture
   * @throws Exception 如果计算过程中发生错误
   */
  ListenableFuture<V> call() throws Exception;
}
