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
package org.apache.ibatis.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * @author Clinton Begin
 */
public class MetaClass {

  private final ReflectorFactory reflectorFactory;
  private final Reflector reflector;

  /**
   * 构造方法，每个MetaClass对应一个类
   * @param type
   * @param reflectorFactory
   */
  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    this.reflector = reflectorFactory.findForClass(type);
  }

  /**
   * 静态方法创建MetaClass
   * @param type
   * @param reflectorFactory
   * @return
   */
  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

  /**
   * 创建指定属性类的MetaClass
   * @param name
   * @return
   */
  public MetaClass metaClassForProperty(String name) {
    Class<?> propType = reflector.getGetterType(name);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 获取属性，不区分大小写的字符串  -> 属性名称（驼峰式）
   * @param name
   * @return
   */
  public String findProperty(String name) {
    StringBuilder prop = buildProperty(name, new StringBuilder());
    return prop.length() > 0 ? prop.toString() : null;
  }

  /**
   * 获取属性（去掉下划线转驼峰）
   * @param name
   * @param useCamelCaseMapping
   * @return
   */
  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      name = name.replace("_", "");
    }
    return findProperty(name);
  }

  //获取所有可读属性
  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

  //获取所有可写属性
  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

  //获取set方法的入参type，会递归到属性的最深层次
  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop.getName());
      return metaProp.getSetterType(prop.getChildren());
    } else {
      return reflector.getSetterType(prop.getName());
    }
  }

  /**
   * 获取get方法的返参type，会递归到属性的最深层次
   * @param name
   * @return
   */
  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);//分词
    if (prop.hasNext()) {//若有子表达式，递归子表达式
      MetaClass metaProp = metaClassForProperty(prop);
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    return getGetterType(prop);
  }

  /**
   * PropertyTokenizer条件下构建MetaClass
   * @param prop
   * @return
   */
  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    Class<?> propType = getGetterType(prop);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * PropertyTokenizer条件下获取get方法的返参type
   * *处理集合类型
   * @param prop
   * @return
   */
  private Class<?> getGetterType(PropertyTokenizer prop) {
	//以List<Sring>为例说明
    Class<?> type = reflector.getGetterType(prop.getName());//或许属性的get方法的返回type
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {//type为集合类型
      Type returnType = getGenericGetterType(prop.getName());
      if (returnType instanceof ParameterizedType) {
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();//泛型T实际类型 [class java.lang.String]
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          returnType = actualTypeArguments[0];//class java.lang.String
          if (returnType instanceof Class) {
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }

  /**
   * 获取getType 处理泛型
   * @param propertyName
   * @return
   */
  private Type getGenericGetterType(String propertyName) {
    try {
      //从Reflector.getMethods中获取属性的get方法Invoker对象
      Invoker invoker = reflector.getGetInvoker(propertyName);
      if (invoker instanceof MethodInvoker) {//如果 MethodInvoker 对象，则说明是 getting 方法，解析方法返回类型
        Field _method = MethodInvoker.class.getDeclaredField("method");
        _method.setAccessible(true);
        Method method = (Method) _method.get(invoker);
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      } else if (invoker instanceof GetFieldInvoker) {//如果 GetFieldInvoker 对象，则说明是 field ，直接访问
    	//此处的_field是GetFieldInvoker这个Class的Field,仍然是GetFieldInvoker反射维度的Field
    	//类型java.lang.reflect.Field 属性名称org.apache.ibatis.reflection.invoker.GetFieldInvoker.field
        Field _field = GetFieldInvoker.class.getDeclaredField("field");
        _field.setAccessible(true);
        //_field.get(invoker) 获取invoker对象中field属性的值，此值为已存储的Reflector反射维度的Field
        //类型java.util.List 属性名称org.apache.ibatis.domain.misc.RichType.richStrList
        Field field = (Field) _field.get(invoker);
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException | IllegalAccessException ignored) {
    }
    return null;
  }

  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasSetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop.getName());
        return metaProp.hasSetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasSetter(prop.getName());
    }
  }

  public boolean hasGetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasGetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop);
        return metaProp.hasGetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasGetter(prop.getName());
    }
  }

  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }

  private StringBuilder buildProperty(String name, StringBuilder builder) {
	//创建PropertyTokenizer对象，对name进行分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //有子表达式
    if (prop.hasNext()) {
      //从Reflector获取属性名
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        builder.append(propertyName);//拼接到builder中
        builder.append(".");
        MetaClass metaProp = metaClassForProperty(propertyName);//创建属性的MetaClass对象
        metaProp.buildProperty(prop.getChildren(), builder);//递归解析子表达式 children ，并将结果添加到 builder 中
      }
    } else {//无子表达式
      //获得属性名，并添加到 builder 中
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
