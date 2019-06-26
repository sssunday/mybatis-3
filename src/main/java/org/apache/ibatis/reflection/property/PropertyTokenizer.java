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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * <br>属性分词器，支持迭代的访问方式
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {
  /**
   *当前字符串
   */
  private String name;
  
  /**
   * 带索引的属性名
   */
  private final String indexedName;
  
  /**
   * <br>索引
   *<br> 对于数组 name[0] ，则 index = 0
   * <br>对于 Map map[key] ，则 index = key
   */
  private String index;
  
  /**
   * 剩余字符串
   */
  private final String children;

  /**
   * 分词：name[index].children
   * @param fullname
   */
  public PropertyTokenizer(String fullname) {
    int delim = fullname.indexOf('.');//以.号分割，name.children
    if (delim > -1) {
      name = fullname.substring(0, delim);
      children = fullname.substring(delim + 1);
    } else {
      name = fullname;
      children = null;
    }
    indexedName = name;//另存name
    delim = name.indexOf('[');//以[ 分割name ， 并修改name， ->  name[index]
    if (delim > -1) {
      index = name.substring(delim + 1, name.length() - 1);
      name = name.substring(0, delim);
    }
  }

  public static void main(String[] args) {
	  PropertyTokenizer p = new PropertyTokenizer("array[1].obj.test");
	  System.out.println(p);//[name=array, indexedName=array[1], index=1, children=obj.test]
	  System.out.println(p.hasNext());//true
	  System.out.println(p = p.next());//[name=obj, indexedName=obj, index=null, children=test]
	  System.out.println(p.hasNext());//true
	  System.out.println(p = p.next());//[name=test, indexedName=test, index=null, children=null]
	  System.out.println(p.hasNext());//false
}
  
  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  @Override
  public boolean hasNext() {
    return children != null;
  }

  @Override
  public PropertyTokenizer next() {
    return new PropertyTokenizer(children);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }

  @Override
  public String toString() {
	return "PropertyTokenizer [name=" + name + ", indexedName=" + indexedName + ", index=" + index + ", children="
			+ children + "]";
  }
  
}
