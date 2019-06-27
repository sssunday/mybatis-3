/*
 * package org.apache.ibatis.transaction.spring;
 * 
 * import static org.springframework.util.Assert.notNull;
 * 
 * import java.sql.Connection; import java.sql.SQLException;
 * 
 * import javax.sql.DataSource;
 * 
 * import org.apache.ibatis.logging.Log; import
 * org.apache.ibatis.logging.LogFactory; import
 * org.apache.ibatis.transaction.Transaction; import
 * org.springframework.jdbc.datasource.DataSourceUtils;
 * 
 *//**
 *基于 Spring 管理的事务实现类。实际真正在使用的
	 * {@code SpringManagedTransaction} handles the lifecycle of a JDBC connection.
	 * It retrieves a connection from Spring's transaction manager and returns it
	 * back to it when it is no longer needed.
	 * <p>
	 * If Spring's transaction handling is active it will no-op all
	 * commit/rollback/close calls assuming that the Spring transaction manager will
	 * do the job.
	 * <p>
	 * If it is not it will behave like {@code JdbcTransaction}.
	 *
	 * @author Hunter Presnall
	 * @author Eduardo Macarron
	 * 
	 * @version $Id$
	 */
/*
 * public class SpringManagedTransaction implements Transaction {
 * 
 * private static final Log logger =
 * LogFactory.getLog(SpringManagedTransaction.class);
 * 
 * private final DataSource dataSource;
 * 
 * private Connection connection;
 * 
 * private boolean isConnectionTransactional;
 * 
 * private boolean autoCommit;
 * 
 * public SpringManagedTransaction(DataSource dataSource) { notNull(dataSource,
 * "No DataSource specified"); this.dataSource = dataSource; }
 * 
 *//**
	 * {@inheritDoc}
	 */
/*
 * public Connection getConnection() throws SQLException { if (this.connection
 * == null) { openConnection(); } return this.connection; }
 * 
 *//**
	 * Gets a connection from Spring transaction manager and discovers if this
	 * {@code Transaction} should manage connection or let it to Spring.
	 * <p>
	 * It also reads autocommit setting because when using Spring Transaction
	 * MyBatis thinks that autocommit is always false and will always call
	 * commit/rollback so we need to no-op that calls.
	 */
/*
 * private void openConnection() throws SQLException { this.connection =
 * DataSourceUtils.getConnection(this.dataSource); this.autoCommit =
 * this.connection.getAutoCommit(); this.isConnectionTransactional =
 * DataSourceUtils.isConnectionTransactional(this.connection, this.dataSource);
 * 
 * if (logger.isDebugEnabled()) { logger.debug( "JDBC Connection [" +
 * this.connection + "] will" + (this.isConnectionTransactional ? " " : " not ")
 * + "be managed by Spring"); } }
 * 
 *//**
	 * {@inheritDoc}
	 */
/*
 * public void commit() throws SQLException { if (this.connection != null &&
 * !this.isConnectionTransactional && !this.autoCommit) { if
 * (logger.isDebugEnabled()) { logger.debug("Committing JDBC Connection [" +
 * this.connection + "]"); } this.connection.commit(); } }
 * 
 *//**
	 * {@inheritDoc}
	 */
/*
 * public void rollback() throws SQLException { if (this.connection != null &&
 * !this.isConnectionTransactional && !this.autoCommit) { if
 * (logger.isDebugEnabled()) { logger.debug("Rolling back JDBC Connection [" +
 * this.connection + "]"); } this.connection.rollback(); } }
 * 
 *//**
	 * {@inheritDoc}
	 *//*
		 * public void close() throws SQLException {
		 * DataSourceUtils.releaseConnection(this.connection, this.dataSource); }
		 * 
		 * }
		 * 
		 */