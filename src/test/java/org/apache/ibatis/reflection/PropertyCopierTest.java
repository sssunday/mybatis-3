package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.property.PropertyCopier;

public class PropertyCopierTest {

	public static void main(String[] args) {
		Clz clz = new Clz();
		clz.setObj(new Object());
		clz.setStr("123");
		Clz clz2 = new Clz();
		PropertyCopier.copyBeanProperties(Clz.class, clz, clz2);
		System.out.println(clz.getObj() == clz2.getObj());//true
	}
	
	static class Clz {
		private Object obj;
		String str;
		public Object getObj() {
			return obj;
		}
		public void setObj(Object obj) {
			this.obj = obj;
		}
		public String getStr() {
			return str;
		}
		public void setStr(String str) {
			this.str = str;
		}
	}
}
