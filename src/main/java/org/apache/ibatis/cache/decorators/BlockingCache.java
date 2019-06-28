/**
 *    Copyright 2009-2018 the original author or authors.
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 **阻塞的 Cache 实现类
 * Simple blocking decorator
 *
 * Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * @author Eduardo Macarron
 *
 */
public class BlockingCache implements Cache {

  /**
   * 阻塞等待超时时间
   */
  private long timeout;
  
  /**
   * 装饰的 Cache 对象
   */
  private final Cache delegate;
  
  /**
   * 缓存键与 ReentrantLock 对象的映射
   */
  private final ConcurrentHashMap<Object, ReentrantLock> locks;

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  /**
   * 添加缓存，如果当前线程持有锁，则释放锁
   */
  @Override
  public void putObject(Object key, Object value) {
    try {
      delegate.putObject(key, value);
    } finally {
      //释放锁
      releaseLock(key);
    }
  }

  /**
   * <br>获取缓存的时候会对key加锁，如果缓存不为空，则释放锁并返回value，如果缓存为空，则不释放锁
   * <br>配合putObject使用，添加缓存时，才会释放锁，这样被阻塞等待的其它线程就不会重复添加缓存了。
   * <br>一般的缓存的使用方式是， 1.获取缓存  2.若缓存不为空，返回缓存结果， 3.若缓存为空，查DB，将结果放入缓存，返回结果 
   * <br>此处在获取缓存处加锁，1. 可以只让一个请求通过，然后查DB设缓存，然后唤醒其他阻塞的请求线程，防止同一时刻流量冲击DB， 2. 此处需要配合putObject一起使用，否则会导致key一直被锁住
   */
  @Override
  public Object getObject(Object key) {
	//获取锁
    acquireLock(key);
    //获得缓存值
    Object value = delegate.getObject(key);
    if (value != null) {
      //如果缓存值不为空，释放锁
      releaseLock(key);
    }
    return value;
  }

  /**
   * 不会移除缓存，只会释放key上的锁
   */
  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
	//清缓存
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  /**
   * 获得 ReentrantLock 对象。如果不存在，进行添加
   * @param key
   * @return
   */
  private ReentrantLock getLockForKey(Object key) {
    return locks.computeIfAbsent(key, k -> new ReentrantLock());
  }

  /**
   * 获取锁
   * @param key
   */
  private void acquireLock(Object key) {
    Lock lock = getLockForKey(key);
    if (timeout > 0) {
      try {
    	// 获得锁，直到超时
        boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
        if (!acquired) {
          throw new CacheException("Couldn't get a lock in " + timeout + " for the key " +  key + " at the cache " + delegate.getId());
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    } else {//没有超时时间
      //无条件锁，阻塞直到拿到锁
      lock.lock();
    }
  }

  /**
   * 释放锁
   * @param key
   */
  private void releaseLock(Object key) {
	//获取锁对象
    ReentrantLock lock = locks.get(key);
    if (lock.isHeldByCurrentThread()) {
      //如果锁被当前线程持有，则释放锁
      lock.unlock();
    }
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }
}
