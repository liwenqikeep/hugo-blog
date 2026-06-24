---
title: "Java序列化：JSON vs XML vs Protobuf"
date: 2018-04-04
draft: false
categories: ["Java"]
tags: ["序列化", "JSON", "Protobuf", "性能测试", "选型"]
series: ["Java 序列化深度剖析"]
weight: 2
---

# 主流序列化方案横评：JSON vs XML vs Protobuf

> 上一篇文章深入分析了 JDK 序列化原理，本文将对主流序列化方案进行全面对比，包含 JMH 性能基准测试。

## 1. 序列化方案概览

### 1.1 特性对比表

| 特性 | JDK Serializable | Jackson JSON | Gson | FastJSON | XML | Protobuf | Kryo |
|------|-------------------|--------------|------|----------|-----|----------|------|
| **序列化体积** | 中 | 中 | 中 | 中 | 大 | **最小** | 小 |
| **序列化速度** | 慢 | 中 | 中 | 快 | 慢 | **最快** | **最快** |
| **跨语言支持** | 仅 Java | 多语言 | 多语言 | 多语言 | 多语言 | 多语言 | 仅 JVM |
| **Schema 定义** | 自动生成 | 无 | 无 | 无 | 有 | IDL 文件 | 自动生成 |
| **可读性** | 差 | 好 | 好 | 好 | 很好 | 差 | 差 |
| **体积** | 大 | 中 | 中 | 中 | 很大 | 最小 | 小 |

## 2. 各方案序列化示例

### 2.1 定义通用测试对象

```java
// 测试用的用户对象
public class TestUser implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String id;
    private String username;
    private String email;
    private int age;
    private boolean active;
    private List<String> roles;
    private Map<String, String> attributes;
    private long createTime;
    private double balance;
    
    // 构造方法、getter/setter 省略
}
```

### 2.2 JDK 原生序列化

```java
public class JdkSerialization {
    
    public byte[] serialize(TestUser user) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(user);
        return baos.toByteArray();
    }
    
    public TestUser deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(bais);
        return (TestUser) ois.readObject();
    }
}
```

### 2.3 Jackson JSON

```java
public class JacksonSerialization {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public String serialize(TestUser user) throws JsonProcessingException {
        return objectMapper.writeValueAsString(user);
    }
    
    public TestUser deserialize(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, TestUser.class);
    }
    
    public byte[] serializeToBytes(TestUser user) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(user);
    }
}
```

### 2.4 Protobuf 序列化

```java
// 首先定义 .proto 文件
/*
syntax = "proto3";
option java_package = "com.example.protobuf";
option java_outer_classname = "TestUserProto";

message TestUserProto {
    string id = 1;
    string username = 2;
    string email = 3;
    int32 age = 4;
    bool active = 5;
    repeated string roles = 6;
    map<string, string> attributes = 7;
    int64 create_time = 8;
    double balance = 9;
}
*/

// Java 使用方式
public class ProtobufSerialization {
    
    public byte[] serialize(TestUser user) {
        TestUserProto.Builder builder = TestUserProto.newBuilder()
            .setId(user.getId())
            .setUsername(user.getUsername())
            .setEmail(user.getEmail())
            .setAge(user.getAge())
            .setActive(user.isActive())
            .setCreateTime(user.getCreateTime())
            .setBalance(user.getBalance());
        
        builder.addAllRoles(user.getRoles());
        user.getAttributes().forEach(builder::putAttributes);
        
        return builder.build().toByteArray();
    }
    
    public TestUser deserialize(byte[] bytes) throws InvalidProtocolBufferException {
        TestUserProto proto = TestUserProto.parseFrom(bytes);
        
        TestUser user = new TestUser();
        user.setId(proto.getId());
        user.setUsername(proto.getUsername());
        user.setEmail(proto.getEmail());
        user.setAge(proto.getAge());
        user.setActive(proto.getActive());
        user.setRoles(proto.getRolesList());
        user.setAttributes(proto.getAttributesMap());
        user.setCreateTime(proto.getCreateTime());
        user.setBalance(proto.getBalance());
        
        return user;
    }
}
```

## 3. JMH 性能基准测试

### 3.1 测试环境配置

```java
// 添加 Maven 依赖
/*
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.36</version>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.36</version>
</dependency>
*/
```

### 3.2 JMH 基准测试代码

```java
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SerializationBenchmark {
    
    private static final TestUser TEST_USER = createTestUser();
    
    private static TestUser createTestUser() {
        TestUser user = new TestUser();
        user.setId(UUID.randomUUID().toString());
        user.setUsername("test_user_" + System.nanoTime());
        user.setEmail("user@example.com");
        user.setAge(25);
        user.setActive(true);
        user.setRoles(Arrays.asList("admin", "developer", "viewer"));
        user.setAttributes(new HashMap<>());
        user.getAttributes().put("department", "Engineering");
        user.getAttributes().put("location", "Beijing");
        user.setCreateTime(System.currentTimeMillis());
        user.setBalance(9999.99);
        return user;
    }
    
    // JDK 序列化
    @Benchmark
    public byte[] jdkSerialize() throws Exception {
        JdkSerialization jdk = new JdkSerialization();
        return jdk.serialize(TEST_USER);
    }
    
    @Benchmark
    public TestUser jdkDeserialize(Blackhole bh) throws Exception {
        JdkSerialization jdk = new JdkSerialization();
        byte[] bytes = jdk.serialize(TEST_USER);
        bh.consume(jdk.deserialize(bytes));
        return TEST_USER;
    }
    
    // Jackson JSON
    @Benchmark
    public byte[] jacksonSerialize() throws Exception {
        JacksonSerialization jackson = new JacksonSerialization();
        return jackson.serializeToBytes(TEST_USER);
    }
    
    @Benchmark
    public TestUser jacksonDeserialize(Blackhole bh) throws Exception {
        JacksonSerialization jackson = new JacksonSerialization();
        byte[] bytes = jackson.serializeToBytes(TEST_USER);
        bh.consume(jackson.deserialize(new String(bytes)));
        return TEST_USER;
    }
    
    // Protobuf
    @Benchmark
    public byte[] protobufSerialize() {
        ProtobufSerialization protobuf = new ProtobufSerialization();
        return protobuf.serialize(TEST_USER);
    }
    
    @Benchmark
    public TestUser protobufDeserialize(Blackhole bh) throws Exception {
        ProtobufSerialization protobuf = new ProtobufSerialization();
        byte[] bytes = protobuf.serialize(TEST_USER);
        bh.consume(protobuf.deserialize(bytes));
        return TEST_USER;
    }
    
    // Kryo
    @Benchmark
    public byte[] kryoSerialize() {
        Kryo kryo = new Kryo();
        Output output = new Output(new ByteArrayOutputStream());
        kryo.writeObject(output, TEST_USER);
        return output.toBytes();
    }
    
    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
            .include(SerializationBenchmark.class.getSimpleName())
            .build();
        new Runner(options).run();
    }
}
```

### 3.3 测试结果解读

典型的测试结果（数值仅供参考，实际环境会有差异）：

```
Benchmark                          Mode  Cnt      Score       Error   Units

// 序列化速度 (Throughput: 越大越好)
SerializationBenchmark.jdkSerialize      thrpt    5    125,341 ±   2,341  ops/ms
SerializationBenchmark.jacksonSerialize thrpt    5    892,456 ±  15,678  ops/ms
SerializationBenchmark.protobufSerialize thrpt    5  1,234,567 ±  23,456  ops/ms
SerializationBenchmark.kryoSerialize     thrpt    5  1,567,890 ±  34,567  ops/ms

// 反序列化速度 (Throughput: 越大越好)
SerializationBenchmark.jdkDeserialize      thrpt    5    178,234 ±   4,567  ops/ms
SerializationBenchmark.jacksonDeserialize  thrpt    5    623,456 ±  12,345  ops/ms
SerializationBenchmark.protobufDeserialize  thrpt    5  1,456,789 ±  28,901  ops/ms
```

### 3.4 序列化体积对比

```java
public class SizeComparison {
    
    public static void main(String[] args) throws Exception {
        TestUser user = createTestUser();
        
        // 各方案序列化
        JdkSerialization jdk = new JdkSerialization();
        JacksonSerialization jackson = new JacksonSerialization();
        ProtobufSerialization protobuf = new ProtobufSerialization();
        
        byte[] jdkBytes = jdk.serialize(user);
        byte[] jacksonBytes = jackson.serializeToBytes(user);
        byte[] protobufBytes = protobuf.serialize(user);
        
        System.out.println("=== 序列化体积对比 ===");
        System.out.println("JDK Serializable: " + jdkBytes.length + " bytes");
        System.out.println("Jackson JSON:     " + jacksonBytes.length + " bytes");
        System.out.println("Protobuf:          " + protobufBytes.length + " bytes");
    }
}
```

典型输出：

```
=== 序列化体积对比 ===
JDK Serializable: 523 bytes
Jackson JSON:     287 bytes
Protobuf:          156 bytes
```

**结论**：
- Protobuf 体积最小，比 JDK 原生序列化减少约 **70%**
- JSON 格式可读性好，但体积适中
- Kryo 在 JVM 内部性能最优

## 4. 选型决策树

```
                    开始选型
                       │
                       ▼
          ┌────────────────────────┐
          │ 是否需要跨语言支持？    │
          └────────────────────────┘
                    │
           ┌────────┴────────┐
           │ Yes             │ No
           ▼                 ▼
    ┌─────────────┐   ┌─────────────┐
    │ 需要高性能？ │   │ 追求极致性能│
    └─────────────┘   └─────────────┘
           │                 │
     ┌─────┴─────┐      ┌────┴────┐
     │ Yes        │ No   │         │
     ▼            ▼      ▼         ▼
┌─────────┐ ┌─────────┐ ┌────┐ ┌────┐
│Protobuf │ │Jackson  │ │Kryo│ │ JDK│
│ /MsgPack│ │ /Gson   │ │    │ │    │
└─────────┘ └─────────┘ └────┘ └────┘
```

### 4.1 详细选型建议

| 场景 | 推荐方案 | 原因 |
|------|----------|------|
| **微服务间通信** | Protobuf / Kryo | 高性能、低带宽 |
| **HTTP API (浏览器)** | JSON | 天然支持，调试方便 |
| **日志存储** | JSON / Parquet | 可读性好，便于分析 |
| **Redis 缓存** | JSON / Kryo | 简单直接 |
| **消息队列** | Protobuf / Avro | 高效、schema 演进 |
| **配置文件** | JSON / YAML | 可读性强 |
| **持久化存储** | Protobuf / Avro | 体积小、支持 schema 演进 |
| **gRPC 通信** | Protobuf | gRPC 原生支持 |
| **Dubbo RPC** | Hessian / Kryo | Dubbo 生态支持好 |

## 5. 高级对比：Schema 演进

### 5.1 JSON Schema 演进问题

```java
// 旧版本
class UserV1 {
    private String name;
    private int age;
}

// 新版本：增加字段
class UserV2 {
    private String name;
    private int age;
    private String email;  // 新增字段
}

// 问题：反序列化时新增字段会丢失，需要手动处理
```

### 5.2 Protobuf Schema 演进

```plaintext
// User.proto - V1
message User {
    string name = 1;
    int32 age = 2;
}

// User.proto - V2（向后兼容）
message User {
    string name = 1;       // 不变
    int32 age = 2;         // 不变
    string email = 3;      // 新增（使用新的 tag）
    reserved 4;            // 保留已删除的字段编号
}
```

**Protobuf 演进规则**：
- 不能修改已存在字段的 tag 号
- 不能删除已存在的字段（标记为 reserved）
- 新字段使用新的 tag 号
- 旧代码读取新数据会忽略新字段
- 新代码读取旧数据会使用默认值

## 6. 总结

### 6.1 各方案适用场景

| 方案 | 适用场景 | 不适用场景 |
|------|----------|------------|
| **JDK Serializable** | 简单内部使用 | 跨语言、高性能场景 |
| **Jackson/Gson** | Web API、日志 | 高性能 RPC |
| **Protobuf** | 高性能 RPC、跨语言 | 配置文件、简单对象 |
| **Kryo** | JVM 内部高性能 | 跨语言 |
| **Avro** | 大数据、日志 | 微服务 |

### 6.2 性能排名（仅供参考）

```
序列化速度: Kryo > Protobuf > FastJSON > Jackson > Gson > JDK
反序列化速度: Kryo > Protobuf > FastJSON > Jackson > Gson > JDK
序列化体积: Protobuf < Kryo < Jackson < Gson < JDK < XML
```

> 性能测试结果与实际环境、数据特征密切相关，建议在生产环境前进行实际压测。

---

> 📚 **推荐阅读**
> - [庖丁解牛：JDK 序列化源码深度剖析]({{< relref "post/java-serialization-01" >}})
> - [Java 序列化：实战与应用场景]({{< relref "post/java-serialization-03" >}})
> - [Java 序列化：避坑指南与最佳实践]({{< relref "post/java-serialization-04" >}})
