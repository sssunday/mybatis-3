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
package org.apache.ibatis.datasource.pooled;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * <br>池化的 Connection 对象
 * <br>实现 InvocationHandler 接口
 * @author Clinton Begin
 */
class PooledConnection implements InvocationHandler {

  /**
   * 关闭Connection的方法名
   * 释放PooledConnection
   */
  private static final String CLOSE = "close";
  
  /**
   * JDK Proxy 的接口
   */
  private static final Class<?>[] IFACES = new Class<?>[] { Connection.class };

  /**
   * <br>对象的标识，基于 {@link #realConnection} 求 hashCode
   * <br>realConnection.hashCode
   */
  private final int hashCode;
  /**
   * 所属的PooledDataSource
   */
  private final PooledDataSource dataSource;
  
  /**
   * 真实的 Connection 连接
   */
  private final Connection realConnection;
  
  /**
   * 代理的 Connection 连接，即 {@link PooledConnection} 这个动态代理的 Connection 对象
   */
  private final Connection proxyConnection;
  
  /**
   * 从连接池中，获取走的时间戳（检出时刻的时间）
   */
  private long checkoutTimestamp;
  
  /**
   * 对象创建时间
   */
  private long createdTimestamp;
  
  /**
   * 最后使用时间（最后更新时间）
   */
  private long lastUsedTimestamp;
  
  /**
   * 连接的标识，即 {@link PooledDataSource#expectedConnectionTypeCode}
   */
  private int connectionTypeCode;
  
  /**
   * 是否有效
   */
  private boolean valid;

  /**
   * Constructor for SimplePooledConnection that uses the Connection and PooledDataSource passed in.
   *
   * @param connection - the connection that is to be presented as a pooled connection
   * @param dataSource - the dataSource that the connection is from
   */
  public PooledConnection(Connection connection, PooledDataSource dataSource) {
    this.hashCode = connection.hashCode();
    this.realConnection = connection;
    this.dataSource = dataSource;
    this.createdTimestamp = System.currentTimeMillis();
    this.lastUsedTimestamp = System.currentTimeMillis();
    this.valid = true;
    
    //基于 JDK Proxy 创建 Connection 对象
    //并且 handler 对象就是 this ，也就是自己。那意味着什么？后续对 proxyConnection 的所有方法调用，都会委托给 PooledConnection#invoke(Object proxy, Method method, Object[] args) 方法
    this.proxyConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), IFACES, this);
    
    /*
     *Object newProxyInstance(ClassLoader loader,  Class<?>[] interfaces, InvocationHandler h)
     *参数：
     *	loader： 类加载器， 
     *	interfaces 代理的目标接口
     *	h 实现InvocationHandler接口， 代理的实际执行实现为h.invoke()
     *Object：代理对象（即代理本身），可执行interfaces中定义的方法，可进行类型转化，转化为interfaces中的Class
     *eg: 上面的proxyConnection为Connection类型的引用，可执行Connection的所有方法，执行的流程会进入到 this.invoke()中 即 PooledConnection.invoke()
     *@PooledConnection#invoke
     */
  }

  /**
   ** 将连接设置为无效
   * Invalidates the connection.
   */
  public void invalidate() {
    valid = false;
  }

  /**
   * * 校验连接是否有效
   * Method to see if the connection is usable.
   *
   * @return True if the connection is usable
   */
  public boolean isValid() {
    return valid && realConnection != null && dataSource.pingConnection(this);
  }

  /**
   * Getter for the *real* connection that this wraps.
   *
   * @return The connection
   */
  public Connection getRealConnection() {
    return realConnection;
  }

  /**
   * Getter for the proxy for the connection.
   *
   * @return The proxy
   */
  public Connection getProxyConnection() {
    return proxyConnection;
  }

  /**
   * Gets the hashcode of the real connection (or 0 if it is null).
   *
   * @return The hashcode of the real connection (or 0 if it is null)
   */
  public int getRealHashCode() {
    return realConnection == null ? 0 : realConnection.hashCode();
  }

  /**
   * Getter for the connection type (based on url + user + password).
   *
   * @return The connection type
   */
  public int getConnectionTypeCode() {
    return connectionTypeCode;
  }

  /**
   * Setter for the connection type.
   *
   * @param connectionTypeCode - the connection type
   */
  public void setConnectionTypeCode(int connectionTypeCode) {
    this.connectionTypeCode = connectionTypeCode;
  }

  /**
   * Getter for the time that the connection was created.
   *
   * @return The creation timestamp
   */
  public long getCreatedTimestamp() {
    return createdTimestamp;
  }

  /**
   * Setter for the time that the connection was created.
   *
   * @param createdTimestamp - the timestamp
   */
  public void setCreatedTimestamp(long createdTimestamp) {
    this.createdTimestamp = createdTimestamp;
  }

  /**
   * Getter for the time that the connection was last used.
   *
   * @return - the timestamp
   */
  public long getLastUsedTimestamp() {
    return lastUsedTimestamp;
  }

  /**
   * Setter for the time that the connection was last used.
   *
   * @param lastUsedTimestamp - the timestamp
   */
  public void setLastUsedTimestamp(long lastUsedTimestamp) {
    this.lastUsedTimestamp = lastUsedTimestamp;
  }

  /**
   ** 距离上次连接使用的时间
   * <br>Getter for the time since this connection was last used.
   *
   * @return - the time since the last use
   */
  public long getTimeElapsedSinceLastUse() {
    return System.currentTimeMillis() - lastUsedTimestamp;
  }

  /**
   * Getter for the age of the connection.
   *
   * @return the age
   */
  public long getAge() {
    return System.currentTimeMillis() - createdTimestamp;
  }

  /**
   * Getter for the timestamp that this connection was checked out.
   *
   * @return the timestamp
   */
  public long getCheckoutTimestamp() {
    return checkoutTimestamp;
  }

  /**
   * Setter for the timestamp that this connection was checked out.
   *
   * @param timestamp the timestamp
   */
  public void setCheckoutTimestamp(long timestamp) {
    this.checkoutTimestamp = timestamp;
  }

  /**
   * Getter for the time that this connection has been checked out.
   *
   * @return the time
   */
  public long getCheckoutTime() {
    return System.currentTimeMillis() - checkoutTimestamp;
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  /**
   ** 比较方法
   * Allows comparing this connection to another.
   *
   * @param obj - the other connection to test for equality
   * @see Object#equals(Object)
   */
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof PooledConnection) {
      //调用真实连接的equal
      return realConnection.hashCode() == ((PooledConnection) obj).realConnection.hashCode();
    } else if (obj instanceof Connection) {
      return hashCode == obj.hashCode();
    } else {
      return false;
    }
  }

  /**
   ** 代理调用方法
   * Required for InvocationHandler implementation.
   *
   * @param proxy  - not used
   * @param method - the method to be executed
   * @param args   - the parameters to be passed to the method
   * @see java.lang.reflect.InvocationHandler#invoke(Object, java.lang.reflect.Method, Object[])
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
	//代理执行的方法名
    String methodName = method.getName();
    
    
    if (CLOSE.hashCode() == methodName.hashCode() && CLOSE.equals(methodName)) {
      //如果是close方法，则调用PooledDataSource的pushConnection方法， 释放连接，从而避免真实连接被关闭。
      dataSource.pushConnection(this);
      return null;
    }
    try {
      //method.getDeclaringClass()  定义method的Class
      //如果不是Object类的方法，则检查池化连接是否为可用状态
      if (!Object.class.equals(method.getDeclaringClass())) {
        // issue #579 toString() should never fail
        // throw an SQLException instead of a Runtime
        checkConnection();//检测池化连接是否为可用状态，即valid的值是否为true，检测不通过抛出异常
      }
      //反射调用对应方法
      //操作对象为真实连接，由真是连接执行操作
      return method.invoke(realConnection, args);
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }

  }

  /**
   * 检测池化连接是否为可用状态，即valid的值是否为true，检测不通过抛出异常
   * @throws SQLException
   */
  private void checkConnection() throws SQLException {
    if (!valid) {
      throw new SQLException("Error accessing PooledConnection. Connection is invalid.");
    }
  }

}
