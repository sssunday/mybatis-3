package org.apache.ibatis.proxy;

public class BizClass {

  public void doSth(){
    System.out.println("doSth");
  }

  public static void main(String[] args) {

    MyInvocationHandaler handaler = new MyInvocationHandaler(BizClass.class);
    BizClass biz = (BizClass)handaler.getProxy();
    biz.doSth();
  }
}
