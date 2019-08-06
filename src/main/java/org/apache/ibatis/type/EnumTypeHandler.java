/**
 *    Copyright 2009-2017 the original author or authors.
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
import java.sql.SQLException;

/**
 ** Enum 类型的 TypeHandler 实现类
 * @author Clinton Begin
 */
public class EnumTypeHandler<E extends Enum<E>> extends BaseTypeHandler<E> {

  /*
   	因为数据库不存在枚举类型，所以讲枚举类型持久化到数据库有两种方式，Enum.name <=> String 和 Enum.ordinal <=> int
   */
	
  /**
   * 枚举的类型
   */
  private final Class<E> type;

  /**
   * 只有带参数的构造器， 没有默认构造器
   * 必需传入枚举的类型
   * @param type
   */
  public EnumTypeHandler(Class<E> type) {
    if (type == null) {
      throw new IllegalArgumentException("Type argument cannot be null");
    }
    this.type = type;
  }

  /**
   * 设置参数， 将 Enum 转换成 String 类型
   */
  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, E parameter, JdbcType jdbcType) throws SQLException {
	// 
	// 获取Enum的name， 将 Enum 转换成 String 类型
	if (jdbcType == null) {
	  // 如果jdbcType为空， 按String类型处理
      ps.setString(i, parameter.name());
    } else {
      // 按jdbcType处理，传入的参数任然是String类型
      ps.setObject(i, parameter.name(), jdbcType.TYPE_CODE); // see r3589
    }
  }

  /**
   * 将 String 转换成 Enum 类型
   */
  @Override
  public E getNullableResult(ResultSet rs, String columnName) throws SQLException {
    String s = rs.getString(columnName);
    return s == null ? null : Enum.valueOf(type, s);
  }

  @Override
  public E getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    String s = rs.getString(columnIndex);
    return s == null ? null : Enum.valueOf(type, s);
  }

  @Override
  public E getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    String s = cs.getString(columnIndex);
    return s == null ? null : Enum.valueOf(type, s);
  }
}