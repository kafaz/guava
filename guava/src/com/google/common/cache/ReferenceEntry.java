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

import javax.annotation.CheckForNull;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.cache.LocalCache.ValueReference;

/**
 * An entry in a reference map.
 * 引用映射中的一个条目。
 *
 * Entries in the map can be in the following states:
 * 映射中的条目可能处于以下状态：
 *
 * Valid:
 * 有效状态：
 *
 * <ul>
 * <li>Live: valid key/value are set
 * 活跃：已设置有效的键值对
 * 
 * <li>Loading: loading is pending
 * 加载中：正在等待加载
 * </ul>
 *
 * Invalid:
 * 无效状态：
 *
 * <ul>
 * <li>Expired: time expired (key/value may still be set)
 * 过期：时间已过期（键值对可能仍然存在）
 * 
 * <li>Collected: key/value was partially collected, but not yet cleaned up
 * 已回收：键值对已被部分回收，但尚未清理
 * 
 * <li>Unset: marked as unset, awaiting cleanup or reuse
 * 未设置：标记为未设置，等待清理或重用
 * </ul>
 */
@GwtIncompatible
interface ReferenceEntry<K, V> {
  /** Returns the value reference from this entry. */
  @CheckForNull
  ValueReference<K, V> getValueReference();

  /** Sets the value reference for this entry. */
  void setValueReference(ValueReference<K, V> valueReference);

  /** Returns the next entry in the chain. */
  @CheckForNull
  ReferenceEntry<K, V> getNext();

  /** Returns the entry's hash. */
  int getHash();

  /** Returns the key for this entry. */
  @CheckForNull
  K getKey();

  /*
   * Used by entries that use access order. Access entries are maintained in a
   * doubly-linked list.
   * 用于使用访问顺序的条目。访问顺序的条目通过双向链表维护。
   * 
   * New entries are added at the tail of the list at write time;
   * 新的条目在写入时被添加到链表尾部；
   * 
   * stale entries are expired from the head of the list.
   * 过期的条目从链表头部移除。
   */

  /** Returns the time that this entry was last accessed, in ns. */
  @SuppressWarnings("GoodTime")
  long getAccessTime();

  /** Sets the entry access time in ns. */
  @SuppressWarnings("GoodTime") // b/122668874
  void setAccessTime(long time);

  /** Returns the next entry in the access queue. */
  ReferenceEntry<K, V> getNextInAccessQueue();

  /** Sets the next entry in the access queue. */
  void setNextInAccessQueue(ReferenceEntry<K, V> next);

  /** Returns the previous entry in the access queue. */
  ReferenceEntry<K, V> getPreviousInAccessQueue();

  /** Sets the previous entry in the access queue. */
  void setPreviousInAccessQueue(ReferenceEntry<K, V> previous);

  /*
   * Implemented by entries that use write order. Write entries are maintained in
   * a doubly-linked
   * list. New entries are added at the tail of the list at write time and stale
   * entries are
   * expired from the head of the list.
   */

  @SuppressWarnings("GoodTime")
  /** Returns the time that this entry was last written, in ns. */
  long getWriteTime();

  /** Sets the entry write time in ns. */
  @SuppressWarnings("GoodTime") // b/122668874
  void setWriteTime(long time);

  /** Returns the next entry in the write queue. */
  ReferenceEntry<K, V> getNextInWriteQueue();

  /** Sets the next entry in the write queue. */
  void setNextInWriteQueue(ReferenceEntry<K, V> next);

  /** Returns the previous entry in the write queue. */
  ReferenceEntry<K, V> getPreviousInWriteQueue();

  /** Sets the previous entry in the write queue. */
  void setPreviousInWriteQueue(ReferenceEntry<K, V> previous);
}
