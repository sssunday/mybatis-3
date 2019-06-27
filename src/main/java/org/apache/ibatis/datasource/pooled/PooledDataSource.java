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

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * 池化DataSource（数据库连接池）
 * POOLED这种数据源的实现利用“池”的概念将 JDBC 连接对象组织起来，避免了创建新的连接实例时所必需的初始化和认证时间。 这是一种使得并发 Web 应用快速响应请求的流行处理方式
 * This is a simple, synchronous, thread-safe database connection pool.
 *
 * @author Clinton Begin
 */
public class PooledDataSource implements DataSource {
	/*
	在popConnection的时候：
	1.如果池中有idle的，返回之
	2.如果没有，并且池中的active连接数小于配置的最大连接数，新建一个连接返回
	3.如果没有idle并且连接数已经创建到最大，就不创建新连接。从acitve connection列表中返回一个最老的值state.activeConnections.get(0)，看这个连接被取出的时间（check out时间，表示从连接开始使用到目前还未close）是不是超过poolMaximumCheckoutTime（配置项，默认是20秒），如果超过，使这个连接失效，并且使用这个连接返回做下一个操作
	4.如果这个连接check out时间还未到poolMaximumCheckoutTime，调用state对象的wait函数：state.wait(poolTimeToWait);等待被唤醒（在连接close的时候会调用pushConnection函数，这里会调用state对象的notifyAll,唤醒之后重新进入循环取连接）
	*/
  private static final Log log = LogFactory.getLog(PooledDataSource.class);

  /**
   * 池化状态
   */
  private final PoolState state = new PoolState(this);

  /**
   * UnpooledDataSource 对象
   * 真正的连接逻辑在UnpooledDataSource中实现，PooledDataSource中实现的主要是pool的管理
   */
  private final UnpooledDataSource dataSource;

  // OPTIONAL CONFIGURATION FIELDS
  /**
   * <br>最大活动连接数
   * <br>在任意时间可以存在的活动（也就是正在使用）连接数量
   * <br>Active 活动的
   */
  protected int poolMaximumActiveConnections = 10;
  
  /**
   * <br>最大闲置连接数
   * <br>任意时间可能存在的空闲连接数
   * <br>Idle 闲置的
   */
  protected int poolMaximumIdleConnections = 5;
  
  /**
   * <br>最大可回收时间
   * <br>即当达到最大活动链接数时，此时如果有程序获取连接，则检查最先使用的连接，看其是否超出了该时间，如果超出了该时间，则可以回收该连接。（默认20s）
   * <br>在被强制返回之前，池中连接被检出（checked out）时间。单位：毫秒
   */
  protected int poolMaximumCheckoutTime = 20000;
  
  /**
   * <br>没有连接时，重尝试获取连接以及打印日志的时间间隔（默认20s）
   * <br>没有连接，最早的连接也未超出poolMaximumCheckoutTime， 则等待poolTimeToWait，等待被唤醒
   * <br>这是一个底层设置，如果获取连接花费了相当长的时间，连接池会打印状态日志并重新尝试获取一个连接（避免在误配置的情况下一直安静的失败）。单位：毫秒
   */
  protected int poolTimeToWait = 20000;
  
  /**
   * <br>这是一个关于坏连接容忍度的底层设置， 作用于每一个尝试从缓存池获取连接的线程。 如果这个线程获取到的是一个坏的连接，那么这个数据源允许这个线程尝试重新获取一个新的连接，但是这个重新尝试的次数不应该超过 poolMaximumIdleConnections 与 poolMaximumLocalBadConnectionTolerance 之和。 默认值：3 （新增于 3.4.5）
   * <br>BadConnection  坏连接：失效连接，不可用
   * <br>eg：此处设置的3，idle数为5，则获取到8个坏连接之后，再次获取到坏连接会抛出异常
   */
  protected int poolMaximumLocalBadConnectionTolerance = 3;
  
  /**
   * <br>发送到数据库的侦测查询，用来检验连接是否正常工作并准备接受请求。
   */
  protected String poolPingQuery = "NO PING QUERY SET";
  
  /**
   * <br>是否启用侦测查询。若开启，需要设置 poolPingQuery 属性为一个可执行的 SQL 语句（最好是一个速度非常快的 SQL 语句）
   */
  protected boolean poolPingEnabled;
  
  /**
   * <br>配置 poolPingQuery 的频率。可以被设置为和数据库连接超时时间一样，来避免不必要的侦测，默认值：0（即所有连接每一时刻都被侦测 — 当然仅当 poolPingEnabled 为 true 时适用）
   */
  protected int poolPingConnectionsNotUsedFor;

  /**
   * <br>期望 Connection 的类型编码，通过 {@link #assembleConnectionTypeCode(String, String, String)} 计算。
   * <br>("" + url + username + password).hashCode()
   */
  private int expectedConnectionTypeCode;

  public PooledDataSource() {
    dataSource = new UnpooledDataSource();
  }

  public PooledDataSource(UnpooledDataSource dataSource) {
    this.dataSource = dataSource;
  }

  public PooledDataSource(String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
	//创建UnpooledDataSource对象
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, driverProperties);
    //计算expectedConnectionTypeCode的值
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  /**
   * 获取连接
   * return 池化连接代理
   */
  @Override
  public Connection getConnection() throws SQLException {
    return popConnection(dataSource.getUsername(), dataSource.getPassword()).getProxyConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return popConnection(username, password).getProxyConnection();
  }

  @Override
  public void setLoginTimeout(int loginTimeout) {
    DriverManager.setLoginTimeout(loginTimeout);
  }

  @Override
  public int getLoginTimeout() {
    return DriverManager.getLoginTimeout();
  }

  @Override
  public void setLogWriter(PrintWriter logWriter) {
    DriverManager.setLogWriter(logWriter);
  }

  @Override
  public PrintWriter getLogWriter() {
    return DriverManager.getLogWriter();
  }

  public void setDriver(String driver) {
    dataSource.setDriver(driver);
    forceCloseAll();
  }

  public void setUrl(String url) {
    dataSource.setUrl(url);
    forceCloseAll();
  }

  public void setUsername(String username) {
    dataSource.setUsername(username);
    forceCloseAll();
  }

  public void setPassword(String password) {
    dataSource.setPassword(password);
    forceCloseAll();
  }

  public void setDefaultAutoCommit(boolean defaultAutoCommit) {
    dataSource.setAutoCommit(defaultAutoCommit);
    forceCloseAll();
  }

  public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
    dataSource.setDefaultTransactionIsolationLevel(defaultTransactionIsolationLevel);
    forceCloseAll();
  }

  public void setDriverProperties(Properties driverProps) {
    dataSource.setDriverProperties(driverProps);
    forceCloseAll();
  }

  /**
   * Sets the default network timeout value to wait for the database operation to complete. See {@link Connection#setNetworkTimeout(java.util.concurrent.Executor, int)}
   * 
   * @param milliseconds
   *          The time in milliseconds to wait for the database operation to complete.
   * @since 3.5.2
   */
  public void setDefaultNetworkTimeout(Integer milliseconds) {
    dataSource.setDefaultNetworkTimeout(milliseconds);
    forceCloseAll();
  }

  /**
   * The maximum number of active connections.
   *
   * @param poolMaximumActiveConnections The maximum number of active connections
   */
  public void setPoolMaximumActiveConnections(int poolMaximumActiveConnections) {
    this.poolMaximumActiveConnections = poolMaximumActiveConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of idle connections.
   *
   * @param poolMaximumIdleConnections The maximum number of idle connections
   */
  public void setPoolMaximumIdleConnections(int poolMaximumIdleConnections) {
    this.poolMaximumIdleConnections = poolMaximumIdleConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of tolerance for bad connection happens in one thread
   * which are applying for new {@link PooledConnection}.
   *
   * @param poolMaximumLocalBadConnectionTolerance
   * max tolerance for bad connection happens in one thread
   *
   * @since 3.4.5
   */
  public void setPoolMaximumLocalBadConnectionTolerance(
      int poolMaximumLocalBadConnectionTolerance) {
    this.poolMaximumLocalBadConnectionTolerance = poolMaximumLocalBadConnectionTolerance;
  }

  /**
   * The maximum time a connection can be used before it *may* be
   * given away again.
   *
   * @param poolMaximumCheckoutTime The maximum time
   */
  public void setPoolMaximumCheckoutTime(int poolMaximumCheckoutTime) {
    this.poolMaximumCheckoutTime = poolMaximumCheckoutTime;
    forceCloseAll();
  }

  /**
   * The time to wait before retrying to get a connection.
   *
   * @param poolTimeToWait The time to wait
   */
  public void setPoolTimeToWait(int poolTimeToWait) {
    this.poolTimeToWait = poolTimeToWait;
    forceCloseAll();
  }

  /**
   * The query to be used to check a connection.
   *
   * @param poolPingQuery The query
   */
  public void setPoolPingQuery(String poolPingQuery) {
    this.poolPingQuery = poolPingQuery;
    forceCloseAll();
  }

  /**
   * Determines if the ping query should be used.
   *
   * @param poolPingEnabled True if we need to check a connection before using it
   */
  public void setPoolPingEnabled(boolean poolPingEnabled) {
    this.poolPingEnabled = poolPingEnabled;
    forceCloseAll();
  }

  /**
   * If a connection has not been used in this many milliseconds, ping the
   * database to make sure the connection is still good.
   *
   * @param milliseconds the number of milliseconds of inactivity that will trigger a ping
   */
  public void setPoolPingConnectionsNotUsedFor(int milliseconds) {
    this.poolPingConnectionsNotUsedFor = milliseconds;
    forceCloseAll();
  }

  public String getDriver() {
    return dataSource.getDriver();
  }

  public String getUrl() {
    return dataSource.getUrl();
  }

  public String getUsername() {
    return dataSource.getUsername();
  }

  public String getPassword() {
    return dataSource.getPassword();
  }

  public boolean isAutoCommit() {
    return dataSource.isAutoCommit();
  }

  public Integer getDefaultTransactionIsolationLevel() {
    return dataSource.getDefaultTransactionIsolationLevel();
  }

  public Properties getDriverProperties() {
    return dataSource.getDriverProperties();
  }

  /**
   * @since 3.5.2
   */
  public Integer getDefaultNetworkTimeout() {
    return dataSource.getDefaultNetworkTimeout();
  }

  public int getPoolMaximumActiveConnections() {
    return poolMaximumActiveConnections;
  }

  public int getPoolMaximumIdleConnections() {
    return poolMaximumIdleConnections;
  }

  public int getPoolMaximumLocalBadConnectionTolerance() {
    return poolMaximumLocalBadConnectionTolerance;
  }

  public int getPoolMaximumCheckoutTime() {
    return poolMaximumCheckoutTime;
  }

  public int getPoolTimeToWait() {
    return poolTimeToWait;
  }

  public String getPoolPingQuery() {
    return poolPingQuery;
  }

  public boolean isPoolPingEnabled() {
    return poolPingEnabled;
  }

  public int getPoolPingConnectionsNotUsedFor() {
    return poolPingConnectionsNotUsedFor;
  }

  /**
   ** 关闭所有active和idle的连接，在finalize中使用
   * Closes all active and idle connections in the pool.
   */
  public void forceCloseAll() {
    synchronized (state) {
      expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
      //遍历活动连接池
      for (int i = state.activeConnections.size(); i > 0; i--) {
        try {
          //移除池化连接
          PooledConnection conn = state.activeConnections.remove(i - 1);
          //池化连接置为不可用
          conn.invalidate();

          //获取池化连接真实连接
          Connection realConn = conn.getRealConnection();
          if (!realConn.getAutoCommit()) {
        	//事务回滚
            realConn.rollback();
          }
          //真实连接关闭
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
    //遍历空闲连接池
      for (int i = state.idleConnections.size(); i > 0; i--) {
        try {
          PooledConnection conn = state.idleConnections.remove(i - 1);//移除池化连接
          conn.invalidate();//池化连接置为不可用

          Connection realConn = conn.getRealConnection();//获取池化连接真实连接
          if (!realConn.getAutoCommit()) {
            realConn.rollback();//事务回滚
          }
          realConn.close();//真实连接关闭
        } catch (Exception e) {
          // ignore
        }
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("PooledDataSource forcefully closed/removed all connections.");
    }
  }

  public PoolState getPoolState() {
    return state;
  }

  /**
   * 计算Connection类型编码
   * @param url
   * @param username
   * @param password
   * @return
   */
  private int assembleConnectionTypeCode(String url, String username, String password) {
    return ("" + url + username + password).hashCode();
  }

  /**
   * <br>归还池化连接
   * <br>将使用完的连接，添加回连接池中。
   * <br>PooledConnection 池化连接
   * <br>Connection： 真实连接，PooledConnection.realConnection
   * @param conn
   * @throws SQLException
   */
  protected void pushConnection(PooledConnection conn) throws SQLException {

    synchronized (state) {
      //从活动连接池中移除此连接
      state.activeConnections.remove(conn);
      //校验连接是否可用（包含ping检测）
      if (conn.isValid()) {//可用
        if (state.idleConnections.size() < poolMaximumIdleConnections && conn.getConnectionTypeCode() == expectedConnectionTypeCode) {
          //当前空闲连接数小于最大空闲连接数，并且和当前连接池的标识匹配，回收到空闲连接池
          //（判断是否超过空闲连接上限）
          
          // 统计连接使用时长
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          if (!conn.getRealConnection().getAutoCommit()) {
        	// 回滚事务，防止连接存在未提交或未回滚的事务
            conn.getRealConnection().rollback();
          }
          //创建新的池化连接，装入的此连接的真实连接connection，将新的池化连接装入空闲连接池,  老连接置为不可用
          PooledConnection newConn = new PooledConnection(conn.getRealConnection(), this);
          state.idleConnections.add(newConn);//将新的池化连接装入空闲连接池
          //继承CreatedTime和LastUsedTime属性
          newConn.setCreatedTimestamp(conn.getCreatedTimestamp());
          newConn.setLastUsedTimestamp(conn.getLastUsedTimestamp());
          conn.invalidate();//老连接置为不可用
          if (log.isDebugEnabled()) {
            log.debug("Returned connection " + newConn.getRealHashCode() + " to pool.");
          }
          //唤醒正在等待连接的线程
          state.notifyAll();
        } else {
          //空闲连接数已满，直接关闭真实连接
        	
          // 统计连接使用时长
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          //关闭真实连接
          conn.getRealConnection().close();
          if (log.isDebugEnabled()) {
            log.debug("Closed connection " + conn.getRealHashCode() + ".");
          }
          //连接置为不可用
          conn.invalidate();
        }
      } else {
        if (log.isDebugEnabled()) {
          log.debug("A bad connection (" + conn.getRealHashCode() + ") attempted to return to the pool, discarding connection.");
        }
        // 统计获取到坏的连接的次数
        state.badConnectionCount++;
      }
    }
  }

  /**
   * <br>获取池化连接
   * <br>PooledConnection 池化连接
   * <br>Connection： 真实连接，PooledConnection.realConnection
   * @param username
   * @param password
   * @return
   * @throws SQLException
   */
  private PooledConnection popConnection(String username, String password) throws SQLException {
    boolean countedWait = false;//标记，获取连接时，是否进行了等待
    PooledConnection conn = null;//最终获取的连接对象
    long t = System.currentTimeMillis();
    int localBadConnectionCount = 0;//获取坏连接的次数

    //循环获取可用的连接
    while (conn == null) {
      synchronized (state) {
        if (!state.idleConnections.isEmpty()) {//1.1.空闲连接非空
          // Pool has available connection
          ////通过移除的方式，获取首个空闲连接
          conn = state.idleConnections.remove(0);
          if (log.isDebugEnabled()) {
            log.debug("Checked out connection " + conn.getRealHashCode() + " from pool.");
          }
        } else {//1.2.空闲连接非空
          // Pool does not have available connection
          if (state.activeConnections.size() < poolMaximumActiveConnections) {//2.1.活动连接数未达到最大值
            // Can create new connection
        	//创建新的 PooledConnection 连接对象，装入新的真实连接 
            conn = new PooledConnection(dataSource.getConnection(), this);
            if (log.isDebugEnabled()) {
              log.debug("Created connection " + conn.getRealHashCode() + ".");
            }
          } else {//2.2.活动连接数已达到最大值
            // Cannot create new connection
        	//获取最早的连接
            PooledConnection oldestActiveConnection = state.activeConnections.get(0);
            //获取此连接的检出时间cheoutTime（也就是所有连接中最长的检出时间）
            long longestCheckoutTime = oldestActiveConnection.getCheckoutTime();
            if (longestCheckoutTime > poolMaximumCheckoutTime) {//3.1 cheoutTime超时 
              // Can claim overdue connection
              //对连接超时的时间的统计
              state.claimedOverdueConnectionCount++;
              state.accumulatedCheckoutTimeOfOverdueConnections += longestCheckoutTime;
              state.accumulatedCheckoutTime += longestCheckoutTime;
              //从活跃连接集合中移除此连接（从活动连接池中移除）
              state.activeConnections.remove(oldestActiveConnection);
              if (!oldestActiveConnection.getRealConnection().getAutoCommit()) {
                try {
                  //如果事务不是自动提交，回滚事务
                  oldestActiveConnection.getRealConnection().rollback();
                } catch (SQLException e) {
                  /*
                     Just log a message for debug and continue to execute the following
                     statement like nothing happened.
                     Wrap the bad connection with a new PooledConnection, this will help
                     to not interrupt current executing thread and give current thread a
                     chance to join the next competition for another valid/good database
                     connection. At the end of this loop, bad {@link @conn} will be set as null.
                   */
                  log.debug("Bad connection. Could not roll back");
                }
              }
              // 创建新的 PooledConnection 连接对象，装入的此连接的真实连接connection
              conn = new PooledConnection(oldestActiveConnection.getRealConnection(), this);
              //继承oldestActiveConnection的CreatedTime和LastUsedTime， 在确定连接可用之后，会重置这两个时间，并返回，防止再次获取本连接时出现等待
              conn.setCreatedTimestamp(oldestActiveConnection.getCreatedTimestamp());
              conn.setLastUsedTimestamp(oldestActiveConnection.getLastUsedTimestamp());
              // 设置 oldestActiveConnection 为无效，这样，如果目前正在使用该连接的调用方，如果在发起数据库操作，将可以抛出异常。
          //此处旧的PooledConnection设置为无效，其真实连接RealConnection被装载到一个新的PooledConnection，新旧连接都持有此真实连接，新的PooledConnection是可用状态
              oldestActiveConnection.invalidate();
              if (log.isDebugEnabled()) {
                log.debug("Claimed overdue connection " + conn.getRealHashCode() + ".");
              }
            } else {//3.2 cheoutTime未超时 ，等待
              // Must wait
              try {
                if (!countedWait) {
                  state.hadToWaitCount++;
                  countedWait = true;
                }
                if (log.isDebugEnabled()) {
                  log.debug("Waiting as long as " + poolTimeToWait + " milliseconds for connection.");
                }
                long wt = System.currentTimeMillis();
                state.wait(poolTimeToWait);//阻塞等待
                state.accumulatedWaitTime += System.currentTimeMillis() - wt;//被唤醒或者等待时间超时
              } catch (InterruptedException e) {
                break;
              }
            }
          }
        }
        if (conn != null) {
          // ping to server and check the connection is valid or not
          //conn.isValid() 校验连接是否有效，同时校验连接的valid值和真实连接是否能ping通数据库（这里的ping指的是，如果开启poolPingEnabled，执行poolPingQuery语句）
          if (conn.isValid()) {//获取到的连接是有效的
            if (!conn.getRealConnection().getAutoCommit()) {
              conn.getRealConnection().rollback();
            }
            //将弹出的连接数据进行重置，然后再装入activeConnections（装入活动连接池）
            conn.setConnectionTypeCode(assembleConnectionTypeCode(dataSource.getUrl(), username, password));
            conn.setCheckoutTimestamp(System.currentTimeMillis());//检出时间
            conn.setLastUsedTimestamp(System.currentTimeMillis());//上次使用时间
            state.activeConnections.add(conn);
            state.requestCount++;
            state.accumulatedRequestTime += System.currentTimeMillis() - t;
          } else {//获取到的连接是无效的，（不可用）
            if (log.isDebugEnabled()) {
              log.debug("A bad connection (" + conn.getRealHashCode() + ") was returned from the pool, getting another connection.");
            }
            state.badConnectionCount++;
            localBadConnectionCount++;
            conn = null;//连接置为空，循环再次到连接池获取连接
            if (localBadConnectionCount > (poolMaximumIdleConnections + poolMaximumLocalBadConnectionTolerance)) {
              //如果获取到坏连接的次数超过限制，抛出异常，限制为poolMaximumIdleConnections + poolMaximumLocalBadConnectionTolerance
              //TODO 很奇怪
              if (log.isDebugEnabled()) {
                log.debug("PooledDataSource: Could not get a good connection to the database.");
              }
              throw new SQLException("PooledDataSource: Could not get a good connection to the database.");
            }
          }
        }
      }

    }

    //校验conn是否为空
    //TODO 此处校验不会执行，conn不会为空，除非异常，抛异常也不会执行这一段
    if (conn == null) {
      if (log.isDebugEnabled()) {
        log.debug("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
      }
      throw new SQLException("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
    }

    return conn;
  }

  /**
   ** 判断判断连接是否能够连到数据库
   * Method to check to see if a connection is still usable
   *
   * @param conn - the connection to check
   * @return True if the connection is still usable
   */
  protected boolean pingConnection(PooledConnection conn) {
	//记录能否ping成功
    boolean result = true;

    try {
      //真实连接是否已经关闭，关闭意味着ping肯定是失败的，result = false
      result = !conn.getRealConnection().isClosed();
    } catch (SQLException e) {
      if (log.isDebugEnabled()) {
        log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
      }
      result = false;
    }

    if (result) {
      // 是否启用侦测查询
      if (poolPingEnabled) {
    	//判断是否长时间未使用。若是，才需要发起 ping。
    	//只有距离上次使用的时间超过poolPingConnectionsNotUsedFor，才会发起ping
        if (poolPingConnectionsNotUsedFor >= 0 && conn.getTimeElapsedSinceLastUse() > poolPingConnectionsNotUsedFor) {
          try {
            if (log.isDebugEnabled()) {
              log.debug("Testing connection " + conn.getRealHashCode() + " ...");
            }
            // 通过执行 poolPingQuery 语句来发起 ping
            Connection realConn = conn.getRealConnection();
            try (Statement statement = realConn.createStatement()) {
              statement.executeQuery(poolPingQuery).close();
            }
            if (!realConn.getAutoCommit()) {
              realConn.rollback();
            }
            // 标记执行成功
            result = true;
            if (log.isDebugEnabled()) {
              log.debug("Connection " + conn.getRealHashCode() + " is GOOD!");
            }
          } catch (Exception e) {
        	//ping 失败 ，关闭数据库真实的连接，并置结果为false
            log.warn("Execution of ping query '" + poolPingQuery + "' failed: " + e.getMessage());
            try {
              conn.getRealConnection().close();
            } catch (Exception e2) {
              //ignore
            }
            result = false;
            if (log.isDebugEnabled()) {
              log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
            }
          }
        }
      }
    }
    return result;
  }

  /**
   ** 获取真实数据库连接
   * Unwraps a pooled connection to get to the 'real' connection
   *
   * @param conn - the pooled connection to unwrap
   * @return The 'real' connection
   */
  public static Connection unwrapConnection(Connection conn) {
    if (Proxy.isProxyClass(conn.getClass())) {//如果传入的是被代理的连接
      // 获取 InvocationHandler 对象
      InvocationHandler handler = Proxy.getInvocationHandler(conn);
      if (handler instanceof PooledConnection) {
    	// 如果是 PooledConnection 对象，则获取真实的连接
        return ((PooledConnection) handler).getRealConnection();
      }
    }
    return conn;
  }

  /**
   * <br> gc时资源回收
   * <br>当某个类重载finalize()方法后，该类的实例对象在没被引用而被GC清理时会执行finalize()方法。
   * <br>防止新建线程池之后，在无引用的情况下一直占用连接（pool = new Pool(); pool = new Pool(); 第一个Pool不可达）
   */
  protected void finalize() throws Throwable {
	//关闭连接池所有连接
    forceCloseAll();
    //销毁对象
    super.finalize();
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException(getClass().getName() + " is not a wrapper.");
  }

  public boolean isWrapperFor(Class<?> iface) {
    return false;
  }

  public Logger getParentLogger() {
    return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
  }

}
