/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;

/**
 ** 基于 java.lang.ref.WeakReference 的 Cache 实现类（弱引用）
 *<br>
 *<br> 如果一个对象只具有弱引用，无论内存充足与否，Java GC后对象如果只有弱引用将会被自动回收。
 *<br>
 *<br>四种引用类型 https://juejin.im/post/5a5129f5f265da3e317dfc08<br>
 * Weak Reference cache decorator.
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * @author Clinton Begin
 */
public class WeakCache implements Cache {
  /**
   * <br>强引用的键的队列,防止对象被回收
   * <br>此队列保证缓存中的元素在被访问过一次之后，可以防止被GC，有大小限制，超过限制，则最老的元素会被抛弃，存在被GC的可能
   */
  private final Deque<Object> hardLinksToAvoidGarbageCollection;
  
  /**
   * 被 GC 回收的 WeakEntry 集合
   * <br>ReferenceQueue  引用队列
   */
  private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
  
  /**
   * 装饰的 Cache 对象
   */
  private final Cache delegate;
  
  /**
   * {@link #hardLinksToAvoidGarbageCollection} 的大小
   */
  private int numberOfHardLinks;

  public WeakCache(Cache delegate) {
    this.delegate = delegate;
    this.numberOfHardLinks = 256;
    this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
    this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
	// 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.numberOfHardLinks = size;
  }

  /**
   * 添加缓存，实际上是将对象的弱引用缓存了起来
   */
  @Override
  public void putObject(Object key, Object value) {
	// 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    // 将对象的WeakEntry实例添加到 delegate 中
    delegate.putObject(key, new WeakEntry(key, value, queueOfGarbageCollectedEntries));
  }

  /**
   * 获取缓存的值
   */
  @Override
  public Object getObject(Object key) {
    Object result = null;
    @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
    //获取到key对应的WeakEntry（也是WeakReference实例）
    WeakReference<Object> weakReference = (WeakReference<Object>) delegate.getObject(key);
    if (weakReference != null) {//如果引用不为空，（此处跟GC无关，判断缓存中是否存有此key的值）
      result = weakReference.get();//获取引用指向的对象
      if (result == null) {//如果对象为空（为空的原因是，意味着已经被 GC 回收）
        delegate.removeObject(key);//从 delegate 中移除 缓存的弱引用
      } else {
    	// 非空，添加到 hardLinksToAvoidGarbageCollection 中，避免被 GC
    	//TODO 可能存在重复添加，如果获取相同的键
        hardLinksToAvoidGarbageCollection.addFirst(result);
        if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
          // 强引用队列的大小超过上限，移除 hardLinksToAvoidGarbageCollection 的队尾。此时队尾元素并没有从缓存中移除，GC前还能get到
          hardLinksToAvoidGarbageCollection.removeLast();
        }
      }
    }
    return result;
  }

  @Override
  public Object removeObject(Object key) {
	// 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    hardLinksToAvoidGarbageCollection.clear();
    // 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  private void removeGarbageCollectedItems() {
    WeakEntry sv;
    while ((sv = (WeakEntry) queueOfGarbageCollectedEntries.poll()) != null) {
      delegate.removeObject(sv.key);
    }
  }

  /**
   * 弱引用entry
   *
   */
  private static class WeakEntry extends WeakReference<Object> {
	/**
	 * 键
	 */
    private final Object key;

    /**
     *<br>调用了WeakReference的构造方法
     *<br>value在被GC的时候，其引用会被添加到garbageCollectionQueue中
     */
    private WeakEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
      
      super(value, garbageCollectionQueue);
      this.key = key;
    }
  }

}
