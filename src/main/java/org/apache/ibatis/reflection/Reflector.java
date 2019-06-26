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
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  /**
   **对应的类
   */
  private final Class<?> type;
  
  /**
   **可读属性名称数组
   */
  private final String[] readablePropertyNames;
  
  /**
   **可写属性名称数组
   */
  private final String[] writablePropertyNames;
  
  /**
   **属性对应的 setting 方法的映射。
   *
   **key 为属性名称
   **value 为 Invoker 对象
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();
  
  /**
   **属性对应的 getting 方法的映射。
   *
   **key 为属性名称
   **value 为 Invoker 对象
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();
  
  /**
   ** 属性对应的 setting 方法的方法参数类型的映射。{@link #setMethods}
   *
   **key 为属性名称
   **value 为方法参数类型
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  
  /**
   **属性对应的 getting 方法的返回值类型的映射。{@link #getMethods}
   *
   **key 为属性名称
   **value 为返回值的类型
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  /**
   **默认构造方法
   */
  private Constructor<?> defaultConstructor;

  /**
   **不区分大小写的属性集合
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
	//设置对应的类
    type = clazz;
    //初始化defaultConstructor（无参构造器）
    addDefaultConstructor(clazz);
    //初始化 getMethods 和 getTypes ，通过遍历 getting 方法 和 is方法
    addGetMethods(clazz);
    //初始化 setMethods 和 setTypes ，通过遍历 setting 方法
    addSetMethods(clazz);
    
    //初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性。
    //没有get和set方法的属性，直接通过反射设置或获取field的值
    addFields(clazz);
    
    //初始化 readablePropertyNames、writeablePropertyNames、caseInsensitivePropertyMap 属性
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    writablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  //添加无参构造器
  private void addDefaultConstructor(Class<?> clazz) {
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    for (Constructor<?> constructor : consts) {
      if (constructor.getParameterTypes().length == 0) {
        this.defaultConstructor = constructor;
      }
    }
  }

  //添加get方法
  private void addGetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      if (method.getParameterTypes().length > 0) {
        continue;
      }
      String name = method.getName();
      if ((name.startsWith("get") && name.length() > 3)
          || (name.startsWith("is") && name.length() > 2)) {
    	//从方法名中获取属性名(截取)
        name = PropertyNamer.methodToProperty(name);
        addMethodConflict(conflictingGetters, name, method);
      }
    }
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * <br>处理get方法冲突，同一个属性只保留一个get方法
   * @param conflictingGetters
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
	// 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 getting 方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;//竞争获胜的方法
      String propName = entry.getKey();  //属性
      for (Method candidate : entry.getValue()) {//属性对应的方法列表 处理列表保留优先级最高的
        if (winner == null) {
          winner = candidate;
          continue;
        }
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        if (candidateType.equals(winnerType)) {
          if (!boolean.class.equals(candidateType)) {//boolean类型的get方法可以是getXXX也可以是isXXX
        	/*
        	 * JavaBeans specification allows follow：
        	 * public boolean isBool() {return true;}
        	 * public boolean getBool() {return false;}
        	 */
            throw new ReflectionException(
                "Illegal overloaded getter method with ambiguous type for property "
                    + propName + " in class " + winner.getDeclaringClass()
                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
          } else if (candidate.getName().startsWith("is")) {//优先取isXXX
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) {//A isAssignableFrom B:  AB相同或A是B的超类|超接口
          // OK getter type is descendant
        } else if (winnerType.isAssignableFrom(candidateType)) {//取子孙类,缩小范围
          /*
                public class A {
				    List<String> getXXXX();
				}
				
				public class B extends B {
				    ArrayList<String> getXXXX(); // 选择它
				}
           */
          winner = candidate;
        } else {
          //同一个属性不能有完全不相关的两个返回类型的get方法
          throw new ReflectionException(
              "Illegal overloaded getter method with ambiguous type for property "
                  + propName + " in class " + winner.getDeclaringClass()
                  + ". This breaks the JavaBeans specification and can cause unpredictable results.");
        }
      }
      addGetMethod(propName, winner);
    }
  }

  /**
   ** 添加get方法（单个）
   * @param name
   * @param method
   */
  private void addGetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      //解析真实返回类型
      Type returnType = TypeParameterResolver.resolveReturnType(method, type);
      getTypes.put(name, typeToClass(returnType));
    }
  }

  private void addSetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      String name = method.getName();
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {//过滤出所有参数个数为1的方法
          name = PropertyNamer.methodToProperty(name);
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    resolveSetterConflicts(conflictingSetters);
  }

  /**
   ** 统计所有方法
   *<br>包括冲突的方法:因为子类可以修改放大返回值. 
   *<br>例如,父类的一个方法的返回值为List,子类对该方法的返回值可以覆写为 ArrayList,此处两个方法都会保存
   *<br>
   *  key: 属性名
   *  value: 方法列表
   * @param conflictingMethods
   * @param name
   * @param method
   */
  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
    list.add(method);
  }

  /**
   * <br>处理set方法冲突，同一个属性只保留一个set方法
   * @param conflictingSetters
   */
  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      Class<?> getterType = getTypes.get(propName);//获取get方法的返回值类型
      Method match = null;
      ReflectionException exception = null;
      for (Method setter : setters) {
    	//取出参数，参数只会有一个（上一步已过滤）
        Class<?> paramType = setter.getParameterTypes()[0];
        if (paramType.equals(getterType)) {
          //若set与get的类型完全一致，则次set方法为最该属性匹配的方法
          // should be the best match
          match = setter;
          break;
        }
        if (exception == null) {
          try {
        	//选择更匹配的方法
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      if (match == null) {
        throw exception;
      } else {
        addSetMethod(propName, match);
      }
    }
  }

  /**
   *<br>同get解决冲突逻辑类似，判断入参类型，取最底层的类型
   * @param setter1
   * @param setter2
   * @param property
   * @return
   */
  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  /**
   * <br>添加set方法（单个）
   * @param name
   * @param method
   */
  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      //解析真实入参类型
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  /**
   * 寻找type真正的类
   * @param src
   * @return
   */
  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {// 普通类型，直接使用类
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {// 泛型类型，使用泛型
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {// 泛型数组，获得具体类
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {// 普通类型
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);// 递归该方法，返回类
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  /**
   * <br>添加field
   * <br>其实也是统计getMethods + getTypes 和 setMethods + setTypes
   * <br>通过遍历field的方式，找到没有get或set方法的属性，通过反射的方式进行取值或赋值，从而达到没有声明get或set方法也可以操作值的效果
   * @param clazz
   */
  private void addFields(Class<?> clazz) {
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {//不处理final和static
          //处理无set方法的filed，添加SetFieldInvoker来对值进行set操作
          addSetField(field);
        }
      }
      if (!getMethods.containsKey(field.getName())) {
    	//处理无set方法的filed，添加GetFieldInvoker来对值进行set操作
        addGetField(field);
      }
    }
    //往上递归
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  /**
   * 处理没有定义set方法的属性
   * @param field
   */
  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   * 处理没有定义get方法的属性
   * @param field
   */
  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   ** 校验属性是否合法:
   *<br>不能是$开头, 不统计serialVersionUID和class属性
   * @param name
   * @return
   */
  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   ** 统计所有方法(非桥接)
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler <code>Class.getMethods()</code>,
   * because we want to look for private methods as well.
   *
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = cls;
    //统计本类中的方法,接口
    while (currentClass != null && currentClass != Object.class) {
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }
      //向上统计父类
      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[methods.size()]);
  }

  /**
   * *统计
   * @param uniqueMethods
   * @param methods
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {//所有非桥接类的方法
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 每次方法的统计从最底层类开始,然后再统计superClass
        // 此处保留底层方法,确保出现重写的情况下,保存的方法为子类重写过的方法
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   ** 返参+方法名+入参: 标识一个方法
   * ${returnType}#${methodName}:${parameterTypeName1},${parameterTypeName2}...
   * @param method
   * @return
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * *判断是否能获取属性的控制权（安全策略）
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  /**
   * 忽略大小写的方式获取属性名
   * @param name
   * @return
   */
  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
