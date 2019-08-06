package org.apache.ibatis.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MyInvocationHandaler implements InvocationHandler {

  Class clz;

  public MyInvocationHandaler(Class bizClassClass) {
    this.clz = bizClassClass;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    System.out.println("doBefore");
    Object result = method.invoke(proxy, args);
    System.out.println("doAfter");
    return result;
  }

  public Object getProxy() {
    try {
      ProxyClass proxyClass = new ProxyClass(this, clz.newInstance());
      return proxyClass;
    } catch (Exception e) {
      System.out.println(e.getMessage());
    }
    return null;
  }

  private static class ProxyClass extends BizClass{
    MyInvocationHandaler handler;
    Object proxy;
    public ProxyClass(MyInvocationHandaler handler, Object proxy) {
      this.handler = handler;
      this.proxy = proxy;
    }

    @Override
    public void doSth() {
      this.doSth(new Object[]{});
    }

    public Object doSth(Object[] args) {
      Object result = null;
      List<Class<?>> clzList = new ArrayList<>();
      if(args != null && args.length > 0){
        clzList.addAll(Arrays.stream(args).map(e -> e.getClass()).collect(Collectors.toList()));
      }
      try {
        result = handler.invoke(proxy, handler.clz.getMethod("doSth", clzList.toArray(new Class[]{})), args);
      } catch (Throwable throwable) {
        throwable.printStackTrace();
      }
      return result;
    }
  }
}
