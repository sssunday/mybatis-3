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

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;

/**
 *<br>基于先进先出的淘汰机制的 Cache 实现类
 *<br>问题
 *<br>1.如果重复添加一个缓存，那么在 keyList 里会存储两个，占用了缓存上限的两个名额。
 *<br>2.在移除指定缓存时，不会移除 keyList 里占用的一个名额。
 * FIFO (first in, first out) cache decorator.
 *
 * @author Clinton Begin
 */
public class FifoCache implements Cache {

  /**
   * 装饰的 Cache 对象
   */
  private final Cache delegate;
  
  /**
   * 双端队列，记录缓存键的添加
   */
  private final Deque<Object> keyList;
  
  /**
   * 队列上限
   */
  private int size;

  public FifoCache(Cache delegate) {
    this.delegate = delegate;
    this.keyList = new LinkedList<>();
    this.size = 1024;//默认1024
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.size = size;
  }

  @Override
  public void putObject(Object key, Object value) {
	//循环key列表
    cycleKeyList(key);
    delegate.putObject(key, value);
  }

  @Override
  public Object getObject(Object key) {
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
	//只移除缓存，没有移除keyList中的key
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyList.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  /**
   * 循环keyList
   * @param key
   */
  private void cycleKeyList(Object key) {
	//在尾部添加key
    keyList.addLast(key);
    if (keyList.size() > size) {
      //如果key队列的大小超过限制，移除头部的key（最老的key），并移除相应的缓存数据
      Object oldestKey = keyList.removeFirst();
      delegate.removeObject(oldestKey);
    }
  }

}
