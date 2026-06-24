---
title: "Java序列化：JDK 序列化源码深度剖析"
date: 2018-04-02
draft: false
categories: ["Java"]
tags: ["序列化", "源码分析", "ObjectOutputStream", "ObjectInputStream"]
series: ["Java 序列化深度剖析"]
weight: 1
---

# 庖丁解牛：JDK 序列化源码深度剖析

> 如果你了解序列化的基本用法，但想深入理解其底层原理，这篇文章将带你逐行分析 JDK 序列化的核心源码。

## 1. 序列化的本质是什么？

在 Java 中，序列化是将对象状态转换为字节流的过程，反序列化则是将字节流恢复为对象的过程。

**为什么要序列化？**

- **网络传输**：对象需要通过网络发送给其他进程或服务器
- **持久化存储**：将对象保存到磁盘或数据库
- **进程间通信**：跨 JVM 的对象传递

```java
// 序列化的基本用法
public class SerializationDemo {
    public static void main(String[] args) throws Exception {
        User user = new User("Alice", 25);
        
        // 序列化
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(user);
        byte[] bytes = baos.toByteArray();
        
        // 反序列化
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(bais);
        User restored = (User) ois.readObject();
    }
}

class User implements Serializable {
    private String name;
    private int age;
    
    // 需要无参构造函数用于反序列化
    public User() {}
    public User(String name, int age) {
        this.name = name;
        this.age = age;
    }
}
```

## 2. ObjectOutputStream 源码解析

让我们从 `ObjectOutputStream` 开始，逐行分析序列化是如何工作的。

### 2.1 writeObject 方法调用链

```java
// java.io.ObjectOutputStream
public class ObjectOutputStream extends OutputStream implements ObjectOutput, ObjectStreamConstants {
    
    // 公开的入口方法
    public final void writeObject(Object obj) throws IOException {
        // 检查对象是否可序列化
        if (enableOverride) {
            writeObjectOverride(obj);
            return;
        }
        
        try {
            // 检查对象是否可序列化
            writeObject0(obj, false);
        } finally {
            // 清除雪崩效应相关状态
            if (extendedDebugInfo) {
                debugInfoStack.clear();
            }
        }
    }
    
    // 核心序列化逻辑
    private void writeObject0(Object obj, boolean unshared) throws IOException {
        // 1. 处理 null 对象
        if (obj == null) {
            writeNull();
            return;
        }
        
        // 2. 检查是否已经序列化过（处理循环引用）
        Object objStub = objectsTable.lookup(obj);
        if (objStub != null) {
            writeHandle(objStub);
            return;
        }
        
        // 3. 获取对象的实际类型
        Class<?> cl = obj.getClass();
        ObjectStreamClass descriptor = ObjectStreamClass.lookup(cl, true);
        
        // 4. 检查是否是 Java 基本类型或 String
        if (obj instanceof String) {
            writeString((String) obj, unshared);
        } else if (cl.isArray()) {
            writeArray(obj, descriptor, unshared);
        } else if (obj instanceof Enum) {
            writeEnum((Enum<?>) obj, descriptor, unshared);
        } else if (obj instanceof Serializable) {
            // 5. 调用自定义的 writeObject 方法
            writeOrdinaryObject(obj, descriptor, unshared);
        } else {
            throw new NotSerializableException(cl.getName());
        }
    }
}
```

### 2.2 writeOrdinaryObject 深入分析

```java
private void writeOrdinaryObject(Object obj, ObjectStreamClass desc, 
                                  boolean unshared) throws IOException {
    // 1. 写入 Object Stream 协议头部（魔数 + 版本）
    bout.writeByte(TC_OBJECT);
    
    // 2. 写入类描述信息
    writeClassDesc(desc, !unshared);
    
    // 3. 是否需要写入对象数据
    if (desc.isMarshalable()) {
        // 自定义 Marshalable 对象
        writeMarshalable(obj, desc, !unshared);
    } else if (desc.isSerializable()) {
        // 4. 标准可序列化对象走这条路
        writeSerializableObject(obj, desc, !unshared);
    } else {
        throw new NotSerializableException("not serializable: " + desc.getName());
    }
}

private void writeSerializableObject(Object obj, ObjectStreamClass desc, 
                                     boolean unshared) throws IOException {
    // 开启引用替换机制
    serializeFields(obj, desc, !unshared);
}

private void serializeFields(Object obj, ObjectStreamClass desc, 
                              boolean unshared) throws IOException {
    // 获取字段信息
    ObjectStreamField[] fields = desc.getFields();
    
    if (gotFields) {
        // 字段已解析，使用缓存
        defaultWriteFields(obj, desc, objHandle);
    } else {
        // 首次写入，写入字段值
        defaultWriteFields(obj, desc, objHandle);
    }
}
```

### 2.3 反射调用自定义 writeObject

```java
private void writeSerialData(Object obj, ObjectStreamClass desc) 
    throws IOException {
    // 获取类的序列化描述符
    Class<?> cl = desc.forClass();
    
    // 检查类是否有自定义的 writeObject 方法
    if (desc.hasWriteObjectMethod()) {
        // 如果有，使用阻塞式写入
        BlockDataOutputStream dos = bout;
        bout.defaultWriteData(obj);
        
        // 通过反射调用类的 writeObject 方法
        try {
            desc.getMethod("writeObject").invoke(obj, (Object) dos);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("writeObject failed", t);
        } catch (IllegalAccessException e) {
            throw new IOException("writeObject method inaccessible", e);
        } finally {
            dos = bout;
        }
        
        dos.writeByte(TC_ENDBLOCKDATA);
    } else {
        // 如果没有，使用默认序列化
        defaultWriteFields(obj, desc, objHandle);
    }
}
```

## 3. serialVersionUID 的作用

### 3.1 什么是 serialVersionUID？

每个可序列化的类都有一个唯一的版本标识符，用于在反序列化时验证发送方和接收方的类是否兼容。

```java
class User implements Serializable {
    // 显式声明版本号
    private static final long serialVersionUID = 1L;
    
    private String name;
    private int age;
}
```

### 3.2 版本验证源码解析

```java
// ObjectInputStream.resolveClass 中
private ObjectStreamClass readClassDesc(boolean naccept) throws IOException {
    byte tc = bin.peekByte();
    
    switch (tc) {
        case TC_CLASSDESC:
            // 从流中读取类描述
            return readClassDesc0(false);
        // ...
    }
}

private ObjectStreamClass readClassDesc0(boolean unshared) throws IOException {
    // 读取类的名称
    String name = readString(false);
    
    // 读取 serialVersionUID
    long suid = Long.reverseBytes(readLong());
    
    // 创建类描述符
    ObjectStreamClass desc = ObjectStreamClass.lookup(name, true);
    
    // 关键验证：比较本地类和流中的 serialVersionUID
    if (desc != null && desc.getLocalDesc() != null) {
        if (suid != desc.getLocalDesc().getSerialVersionUID()) {
            throw new InvalidClassException(
                desc.getLocalDesc().getName(),
                "Local class not compatible: " +
                "stream serialVersionUID = " + suid +
                " local class serialVersionUID = " + desc.getLocalDesc().getSerialVersionUID()
            );
        }
    }
}
```

### 3.3 如何生成 serialVersionUID？

```java
public class SerialVersionUIDGenerator {
    
    /**
     * 计算类的 serialVersionUID
     * 基于类名、修饰符、接口、字段和方法计算
     */
    public static long calculateSUID(Class<?> cl) throws IOException {
        // 1. 类名（UTF格式）
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        
        dos.writeUTF(cl.getName());
        
        // 2. 类的修饰符（public, abstract, class等）
        dos.writeInt(cl.getModifiers());
        
        // 3. 实现的接口（按名称排序）
        Class<?>[] interfaces = cl.getInterfaces();
        Arrays.sort(interfaces, Comparator.comparing(Class::getName));
        dos.writeInt(interfaces.length);
        for (Class<?> iface : interfaces) {
            dos.writeUTF(iface.getName());
        }
        
        // 4. 字段（按名称和类型排序）
        Field[] fields = cl.getDeclaredFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        dos.writeInt(fields.length);
        for (Field field : fields) {
            dos.writeUTF(field.getName());
            dos.writeUTF(getTypeSignature(field.getType()));
        }
        
        // 5. 方法（按名称和签名排序）
        Method[] methods = cl.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        dos.writeInt(methods.length);
        for (Method method : methods) {
            dos.writeUTF(method.getName());
            dos.writeUTF(getTypeSignature(method));
        }
        
        // 6. 计算 SHA/Digest
        MessageDigest md = MessageDigest.getInstance("SHA");
        md.update(baos.toByteArray());
        byte[] hash = md.digest();
        
        // 7. 取前 8 字节作为 serialVersionUID
        long suid = 0;
        for (int i = Math.min(hash.length, 8) - 1; i >= 0; i--) {
            suid = (suid << 8) | (hash[i] & 0xFF);
        }
        return suid;
    }
}
```

## 4. readResolve 和 writeReplace 深度应用

### 4.1 writeReplace - 替换序列化对象

```java
public class WriteReplaceDemo {
    
    public static void main(String[] args) throws Exception {
        UserV2 user = new UserV2("Alice", 25);
        
        // 序列化时会输出 ProxyUser，而不是 UserV2
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(user);
        
        byte[] bytes = baos.toByteArray();
        
        // 反序列化
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(bais);
        Object obj = ois.readObject();
        
        System.out.println("Original class: " + user.getClass().getName());
        System.out.println("Serialized to: " + ProxyUser.class.getName());
        System.out.println("Deserialized to: " + obj.getClass().getName());
        System.out.println("Data: " + obj);
    }
}

class UserV2 implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private int age;
    
    public UserV2(String name, int age) {
        this.name = name;
        this.age = age;
    }
    
    // 序列化时替换对象
    private Object writeReplace() {
        System.out.println("writeReplace called");
        return new ProxyUser(name, age);
    }
}

class ProxyUser implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private int age;
    
    public ProxyUser(String name, int age) {
        this.name = name;
        this.age = age;
    }
    
    // 反序列化时恢复原对象
    private Object readResolve() {
        System.out.println("readResolve called");
        return new UserV2(name, age);
    }
    
    @Override
    public String toString() {
        return "ProxyUser{name='" + name + "', age=" + age + "}";
    }
}
```

### 4.2 readResolve - 单例模式序列化问题

```java
public class SingletonSerialization implements Serializable {
    // 单例实例
    private static final SingletonSerialization INSTANCE = new SingletonSerialization();
    
    private int value = 42;
    
    private SingletonSerialization() {}
    
    public static SingletonSerialization getInstance() {
        return INSTANCE;
    }
    
    // 关键：添加 readResolve 方法保证单例
    private Object readResolve() {
        // 返回已有的单例实例，而不是反序列化创建的新实例
        return INSTANCE;
    }
}

// 测试
public class SingletonTest {
    public static void main(String[] args) throws Exception {
        SingletonSerialization s1 = SingletonSerialization.getInstance();
        
        // 序列化
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(s1);
        
        // 反序列化
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        SingletonSerialization s2 = (SingletonSerialization) ois.readObject();
        
        // 验证是同一个实例
        System.out.println("s1 == s2: " + (s1 == s2));  // true
    }
}
```

## 5. 循环引用处理机制

JDK 序列化使用对象表来追踪已序列化的对象，解决循环引用问题：

```java
public class CircularReferenceDemo {
    
    public static void main(String[] args) throws Exception {
        // 创建循环引用
        Node a = new Node("A");
        Node b = new Node("B");
        a.next = b;
        b.next = a;
        
        // 序列化
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(a);
        
        // 反序列化
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        Node restoredA = (Node) ois.readObject();
        
        // 验证循环引用被正确恢复
        System.out.println("restoredA.next.next == restoredA: " + 
            (restoredA.next.next == restoredA));  // true
    }
}

class Node implements Serializable {
    private static final long serialVersionUID = 1L;
    String name;
    Node next;
    
    public Node(String name) {
        this.name = name;
    }
}
```

**循环引用处理流程：**

```
序列化过程：
1. 序列化对象 A，生成新的 handle (假设 #1)
2. 序列化 A.next = B，生成新的 handle (假设 #2)
3. 序列化 B.next = A，发现 A 已经在表中，使用已存在的 handle #1

结果：字节流中 A 出现两次，但引用指向同一个对象
```

## 6. 常见序列化问题与解决方案

### 6.1 transient 字段处理

```java
public class TransientFieldDemo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String name;
    private transient String password;      // 不被序列化
    private transient SecretKey sessionKey; // 不被序列化
    private static String staticField;       // 不被序列化（类级别）
    
    public TransientFieldDemo(String name, String password) {
        this.name = name;
        this.password = password;
    }
    
    // 自定义序列化，手动处理 transient 字段
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        // 手动序列化 password（可以加密后存储）
        out.writeObject(encrypt(password));
    }
    
    private void readObject(ObjectInputStream in) 
        throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // 手动反序列化 password（可以解密）
        password = decrypt((String) in.readObject());
    }
    
    private String encrypt(String s) { /* 加密逻辑 */ return s; }
    private String decrypt(String s) { /* 解密逻辑 */ return s; }
}
```

### 6.2 父类序列化问题

```java
// 解决方案 1：父类也实现 Serializable
class Parent implements Serializable {}
class Child extends Parent {}

// 解决方案 2：手动序列化父类字段
class Child extends Parent {
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        // 手动写出父类字段
        out.writeInt(this.parentField);
    }
    
    private void readObject(ObjectInputStream in) 
        throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // 手动读取父类字段
        setParentField(in.readInt());
    }
}
```

## 7. 总结

本文深入分析了 JDK 序列化的核心源码：

1. **writeObject 调用链**：从公开 API 到反射调用的完整流程
2. **serialVersionUID**：版本验证机制和自动生成算法
3. **writeReplace/readResolve**：对象替换和反序列化后处理的高级用法
4. **循环引用**：通过对象表实现的引用追踪机制
5. **常见问题**：transient 字段和父类序列化问题

理解这些底层原理，对于后续学习其他序列化方案（JSON、Protobuf）至关重要——你会发现，很多概念是相通的。

---

> 📚 **推荐阅读**
> - [Java 序列化：主流方案横评]({{< relref "post/java-serialization-02" >}})
> - [Java 序列化：实战与应用场景]({{< relref "post/java-serialization-03" >}})
> - [Java 序列化：避坑指南与最佳实践]({{< relref "post/java-serialization-04" >}})
