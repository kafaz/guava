/*
 * Copyright (C) 2011 The Guava Authors
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

import com.google.common.annotations.GwtCompatible;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * 表示缓存条目被移除的原因。
 * 缓存条目可能因为多种原因被移除，包括：手动移除、被替换、垃圾回收、过期和容量限制。
 *
 * @author Charles Fry
 * @since 10.0
 */
@GwtCompatible
public enum RemovalCause {
  /**
   * 表示条目被用户手动移除。
   * 这种情况可能由以下操作触发：
   * - {@link Cache#invalidate} 单个失效
   * - {@link Cache#invalidateAll(Iterable)} 批量失效
   * - {@link Cache#invalidateAll()} 全部失效
   * - {@link Map#remove} Map接口的移除
   * - {@link ConcurrentMap#remove} 并发Map的移除
   * - {@link Iterator#remove} 迭代器的移除
   */
  EXPLICIT {
    @Override
    boolean wasEvicted() {
      return false;
    }
  },

  /**
   * 表示条目的值被用户替换。
   * 注意：此时条目本身并未被移除，只是值被更新。
   * 这种情况可能由以下操作触发：
   * - {@link Cache#put} 放入新值
   * - {@link LoadingCache#refresh} 刷新值
   * - {@link Map#put} Map接口的放入
   * - {@link Map#putAll} Map接口的批量放入
   * - {@link ConcurrentMap#replace(Object, Object)} 并发Map的替换
   * - {@link ConcurrentMap#replace(Object, Object, Object)} 并发Map的条件替换
   */
  REPLACED {
    @Override
    boolean wasEvicted() {
      return false;
    }
  },

  /**
   * 表示条目因其键或值被垃圾回收而被移除。
   * 这种情况发生在使用以下特性时：
   * - {@link CacheBuilder#weakKeys} 弱引用键
   * - {@link CacheBuilder#weakValues} 弱引用值
   * - {@link CacheBuilder#softValues} 软引用值
   */
  COLLECTED {
    @Override
    boolean wasEvicted() {
      return true;
    }
  },

  /**
   * 表示条目因为超过过期时间而被移除。
   * 这种情况发生在使用以下特性时：
   * - {@link CacheBuilder#expireAfterWrite} 写入后过期
   * - {@link CacheBuilder#expireAfterAccess} 访问后过期
   */
  EXPIRED {
    @Override
    boolean wasEvicted() {
      return true;
    }
  },

  /**
   * 表示条目因为缓存大小限制而被驱逐。
   * 这种情况发生在使用以下特性时：
   * - {@link CacheBuilder#maximumSize} 最大条目数限制
   * - {@link CacheBuilder#maximumWeight} 最大权重限制
   */
  SIZE {
    @Override
    boolean wasEvicted() {
      return true;
    }
  };

  /**
   * 判断是否为自动驱逐导致的移除。
   * 
   * 返回true的情况：
   * - COLLECTED (垃圾回收)
   * - EXPIRED (过期)
   * - SIZE (大小限制)
   * 
   * 返回false的情况：
   * - EXPLICIT (手动移除)
   * - REPLACED (值替换)
   *
   * @return 如果是自动驱逐（非手动移除也非替换）返回true
   */
  abstract boolean wasEvicted();
}
