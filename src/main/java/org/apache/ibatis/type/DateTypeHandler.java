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
import java.sql.Timestamp;
import java.util.Date;

/**
 ** 日期类型处理器 - 日期时间都处理2019-06-28 18:33:21
 *<br>java.util.Date <--> java.sql.Timestamp
 * @author Clinton Begin
 */
public class DateTypeHandler extends BaseTypeHandler<Date> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, Date parameter, JdbcType jdbcType)
      throws SQLException {
	// 将 Date 转换成 Timestamp 类型,   然后设置到 ps 中
    // parameter: java.util.Date -> java.sql.Timestamp
    ps.setTimestamp(i, new Timestamp(parameter.getTime()));
  }

  @Override
  public Date getNullableResult(ResultSet rs, String columnName)
      throws SQLException {
	//获取Timestamp类型结果
    Timestamp sqlTimestamp = rs.getTimestamp(columnName);
    
    //java.sql.Timestamp -> java.util.Date
    if (sqlTimestamp != null) {
      return new Date(sqlTimestamp.getTime());
    }
    return null;
  }

  @Override
  public Date getNullableResult(ResultSet rs, int columnIndex)
      throws SQLException {
    Timestamp sqlTimestamp = rs.getTimestamp(columnIndex);
    if (sqlTimestamp != null) {
      return new Date(sqlTimestamp.getTime());
    }
    return null;
  }

  @Override
  public Date getNullableResult(CallableStatement cs, int columnIndex)
      throws SQLException {
    Timestamp sqlTimestamp = cs.getTimestamp(columnIndex);
    if (sqlTimestamp != null) {
      return new Date(sqlTimestamp.getTime());
    }
    return null;
  }
}
