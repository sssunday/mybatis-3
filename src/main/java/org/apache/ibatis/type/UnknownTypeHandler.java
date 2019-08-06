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
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.io.Resources;

/**
 **未知的 TypeHandler 实现类。通过获取对应的 TypeHandler ，进行处理。
 * @author Clinton Begin
 */
public class UnknownTypeHandler extends BaseTypeHandler<Object> {

  /**
   * ObjectTypeHandler 单例
   */
  private static final ObjectTypeHandler OBJECT_TYPE_HANDLER = new ObjectTypeHandler();

  /**
   * TypeHandler 注册表
   */
  private TypeHandlerRegistry typeHandlerRegistry;

  /**
   * 有参构造方法，传入TypeHandler 注册表
   * @param typeHandlerRegistry
   */
  public UnknownTypeHandler(TypeHandlerRegistry typeHandlerRegistry) {
    this.typeHandlerRegistry = typeHandlerRegistry;
  }

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
      throws SQLException {
	// 获得参数对应的处理器
    TypeHandler handler = resolveTypeHandler(parameter, jdbcType);
    //// 使用 handler 设置参数
    handler.setParameter(ps, i, parameter, jdbcType);
  }

  @Override
  public Object getNullableResult(ResultSet rs, String columnName)
      throws SQLException {
	// 获得参数对应的处理器
    TypeHandler<?> handler = resolveTypeHandler(rs, columnName);
 // 使用 handler 获得值
    return handler.getResult(rs, columnName);
  }

  @Override
  public Object getNullableResult(ResultSet rs, int columnIndex)
      throws SQLException {
    TypeHandler<?> handler = resolveTypeHandler(rs.getMetaData(), columnIndex);
    if (handler == null || handler instanceof UnknownTypeHandler) {
      handler = OBJECT_TYPE_HANDLER;
    }
    return handler.getResult(rs, columnIndex);
  }

  @Override
  public Object getNullableResult(CallableStatement cs, int columnIndex)
      throws SQLException {
    return cs.getObject(columnIndex);
  }

  /**
   *  根据parameter.Class 获得对应的处理器
   * @param parameter
   * @param jdbcType
   * @return
   */
  private TypeHandler<?> resolveTypeHandler(Object parameter, JdbcType jdbcType) {
    TypeHandler<?> handler;
    if (parameter == null) {
      //parameter 为空， 默认为ObjectTypeHandler
      handler = OBJECT_TYPE_HANDLER;
    } else {
      //根据parameter.Class 获得对应的处理器
      handler = typeHandlerRegistry.getTypeHandler(parameter.getClass(), jdbcType);
      // check if handler is null (issue #270)
      if (handler == null || handler instanceof UnknownTypeHandler) {
    	//如果处理器不存在或者是未知类型的处理器，  默认为ObjectTypeHandler
        handler = OBJECT_TYPE_HANDLER;
      }
    }
    return handler;
  }

  /**
   * 根据字段名和结果集， 解析TypeHandler
   * <br> 实际上是找到column的index，然后通过ResultSet和columnIndex解析
   * @param rs
   * @param column
   * @return
   */
  private TypeHandler<?> resolveTypeHandler(ResultSet rs, String column) {
    try {
      Map<String,Integer> columnIndexLookup;
      columnIndexLookup = new HashMap<>();
      ResultSetMetaData rsmd = rs.getMetaData();
      int count = rsmd.getColumnCount();
      for (int i = 1; i <= count; i++) {
        String name = rsmd.getColumnName(i);
        columnIndexLookup.put(name,i);
      }
      Integer columnIndex = columnIndexLookup.get(column);
      TypeHandler<?> handler = null;
      if (columnIndex != null) {
        handler = resolveTypeHandler(rsmd, columnIndex);
      }
      if (handler == null || handler instanceof UnknownTypeHandler) {
        handler = OBJECT_TYPE_HANDLER;
      }
      return handler;
    } catch (SQLException e) {
      throw new TypeException("Error determining JDBC type for column " + column + ".  Cause: " + e, e);
    }
  }

  /**
   * 解析TypeHandler
   * @param rsmd ResultSetMetaData
   * @param columnIndex column的index
   * @return
   */
  private TypeHandler<?> resolveTypeHandler(ResultSetMetaData rsmd, Integer columnIndex) {
    TypeHandler<?> handler = null;
    
    //获得jdbcType
    JdbcType jdbcType = safeGetJdbcTypeForColumn(rsmd, columnIndex);
    
    //获得javaType
    Class<?> javaType = safeGetClassForColumn(rsmd, columnIndex);
    
    //根据jdbcType和javaType到TypeHandler注册表//获得对应的 TypeHandler 对象
    if (javaType != null && jdbcType != null) {
      handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
    } else if (javaType != null) {
      handler = typeHandlerRegistry.getTypeHandler(javaType);
    } else if (jdbcType != null) {
      handler = typeHandlerRegistry.getTypeHandler(jdbcType);
    }
    return handler;
  }

  private JdbcType safeGetJdbcTypeForColumn(ResultSetMetaData rsmd, Integer columnIndex) {
    try {
      // 从 ResultSetMetaData 中，获得字段类型
      // 获得 JDBC Type
      return JdbcType.forCode(rsmd.getColumnType(columnIndex));
    } catch (Exception e) {
      return null;
    }
  }

  private Class<?> safeGetClassForColumn(ResultSetMetaData rsmd, Integer columnIndex) {
    try {
      // 从 ResultSetMetaData 中，获得字段类型
      // 获得 Java Type
      return Resources.classForName(rsmd.getColumnClassName(columnIndex));
    } catch (Exception e) {
      return null;
    }
  }
}
