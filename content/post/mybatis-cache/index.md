---
title: "MyBatis（四）：缓存机制与源码解析"
date: 2018-06-26
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "一级缓存", "二级缓存", "SqlSession", "Cache", "源码分析"]
toc: true
---

## 前言

MyBatis 提供了两级缓存来减少数据库访问次数：**一级缓存（SqlSession 级别）**和**二级缓存（Mapper 级别）**。理解缓存机制有助于避免常见的缓存问题（如脏读、缓存未命中），同时也能在需要时合理利用缓存提升性能。

本文从使用到源码，完整覆盖 MyBatis 的缓存实现。

<!--more-->

> **源码版本：** MyBatis 3.5.x

## 一、缓存整体架构

```
SqlSession（会话）
    │
    ├── 一级缓存（BaseExecutor.localCache）
    │     └── PerpetualCache（HashMap）
    │     生命周期 = SqlSession 生命周期
    │
    ├── 二级缓存（CachingExecutor 装饰器）
    │     └── MappedStatement.cache（Cache 对象）
    │     生命周期 = Mapper 命名空间级别
    │
    └── Executor.query()
```

**两级缓存的协作流程：**

```
Executor.query()
    │
    ├── 1. 查询二级缓存
    │     （CachingExecutor 负责）
    │     ├── 命中 → 返回
    │     └── 未命中 → 继续
    │
    ├── 2. 查询一级缓存
    │     （BaseExecutor 负责）
    │     ├── 命中 → 返回
    │     └── 未命中 → 查询数据库
    │
    ├── 3. 查询数据库
    │     （doQuery() 执行 SQL）
    │
    └── 4. 更新缓存
          ├── 存入一级缓存
          └── 存入二级缓存
```

---

## 二、一级缓存

### 2.1 特性

| 特性 | 说明 |
|------|------|
| 作用范围 | SqlSession 级别 |
| 存储结构 | HashMap（PerpetualCache）|
| 默认开启 | 是（无法关闭，但可设置为 STATEMENT 级）|
| 缓存失效 | SQLSession 关闭、执行 update/insert/delete、手动 clearCache() |
| 作用域 | 同一 SqlSession 内的相同查询 |

### 2.2 一级缓存的实现

一级缓存在 `BaseExecutor` 中实现——所有 Executor 子类都继承自它：

```java
// BaseExecutor.java
public abstract class BaseExecutor implements Executor {
    
    // ★ 一级缓存（PerpetualCache 本质是 HashMap）
    protected PerpetualCache localCache;
    protected PerpetualCache localOutputParameterCache;  // 存储过程输出参数
    
    // 本地缓存作用域
    protected LocalCacheScope localCacheScope = LocalCacheScope.SESSION;
    
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, 
                              RowBounds rowBounds, ResultHandler resultHandler) {
        // 1. 获取 BoundSql
        BoundSql boundSql = ms.getBoundSql(parameter);
        
        // 2. ★ 生成缓存 Key
        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
        
        // 3. ★ 调用 query 的重载方法（实际执行）
        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter,
                              RowBounds rowBounds, ResultHandler resultHandler,
                              CacheKey key, BoundSql boundSql) {
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            // ★ 如果 <select flushCache="true">，清空一级缓存
            clearLocalCache();
        }
        
        List<E> list;
        try {
            queryStack++;
            
            // ★ 4. 从一级缓存中查询
            list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
            
            if (list != null) {
                // ★ 一级缓存命中
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            } else {
                // ★ 缓存未命中 → 查询数据库
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            queryStack--;
        }
        
        if (queryStack == 0) {
            // ★ 如果作用域是 STATEMENT，执行完后清空缓存
            // 即：每次查询后都清空，相当于仅对单次查询缓存
            if (localCacheScope == LocalCacheScope.STATEMENT) {
                clearLocalCache();
            }
        }
        
        return list;
    }
    
    // 数据库查询
    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter,
                                           RowBounds rowBounds, ResultHandler resultHandler,
                                           CacheKey key, BoundSql boundSql) {
        List<E> list;
        localCache.putObject(key, EXECUTION_PLACEHOLDER);  // 占位符，防递归
        try {
            // ★ 调用子类的 doQuery()
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            localCache.removeObject(key);
        }
        
        // ★ 查询结果写入一级缓存
        localCache.putObject(key, list);
        return list;
    }
    
    // ★ 更新操作后清空一级缓存
    @Override
    public int update(MappedStatement ms, Object parameter) {
        ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
        
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        
        // ★ 任何 insert/update/delete 都会清除一级缓存
        clearLocalCache();
        return doUpdate(ms, parameter);
    }
    
    @Override
    public void clearLocalCache() {
        if (!closed) {
            // ★ 清空一级缓存
            localCache.clear();
            localOutputParameterCache.clear();
        }
    }
}
```

### 2.3 CacheKey 的生成

```java
// BaseExecutor.java
@Override
public CacheKey createCacheKey(MappedStatement ms, Object parameterObject,
                                RowBounds rowBounds, BoundSql boundSql) {
    if (closed) {
        throw new ExecutorException("Executor was closed.");
    }
    
    CacheKey cacheKey = new CacheKey();
    
    // 1. MappedStatement ID（mapper 全限定名 + 方法名）
    cacheKey.update(ms.getId());
    
    // 2. 分页偏移量和限制
    cacheKey.update(rowBounds.getOffset());
    cacheKey.update(rowBounds.getLimit());
    
    // 3. 实际 SQL 语句（已替换 #{} 为 ?）
    cacheKey.update(boundSql.getSql());
    
    // 4. 参数值
    for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
        String property = parameterMapping.getProperty();
        if (boundSql.hasAdditionalParameter(property)) {
            cacheKey.update(boundSql.getAdditionalParameter(property));
        } else {
            // 从参数对象中获取值
            Object value = MetaObject.DEFAULT_META_OBJECT.getValue(property, parameterObject);
            cacheKey.update(value);
        }
    }
    
    return cacheKey;
}
```

### 2.4 一级缓存失效的场景

```java
// 失效场景 1：不同 SqlSession
SqlSession session1 = factory.openSession();
SqlSession session2 = factory.openSession();

User user1 = session1.selectOne("findById", 1);  // 查数据库
User user2 = session2.selectOne("findById", 1);  // 查数据库（不同 session）

// 失效场景 2：执行了写操作
User user = session.selectOne("findById", 1);   // 查数据库
session.update("updateUser", new User(1, "Tom")); // 清空一级缓存
User user2 = session.selectOne("findById", 1);   // 又查数据库

// 失效场景 3：手动清空
session.clearCache();  // 清空一级缓存

// 失效场景 4：设置 STATEMENT 作用域
// mybatis-config.xml
// <setting name="localCacheScope" value="STATEMENT"/>
// 每次查询后自动清空
```

---

## 三、二级缓存

### 3.1 特性

| 特性 | 说明 |
|------|------|
| 作用范围 | Mapper 命名空间级别（跨 SqlSession）|
| 默认开启 | 否（需配置 `<cache/>`）|
| 存储结构 | 可插拔（默认 PerpetualCache，可替换 Redis、Memcached 等）|
| 实现方式 | CachingExecutor 装饰器模式 |
| 缓存对象 | 需实现 Serializable |
| 脏读风险 | 多表联合查询时可能出现 |

### 3.2 配置二级缓存

```xml
<!-- Mapper XML 中配置 -->
<mapper namespace="com.example.mapper.UserMapper">
    
    <!-- 开启二级缓存 -->
    <cache
        eviction="LRU"           <!-- 淘汰策略：LRU/FIFO/SOFT/WEAK -->
        flushInterval="60000"    <!-- 刷新间隔（毫秒）-->
        size="512"               <!-- 最大引用数量 -->
        readOnly="true"          <!-- 是否只读 -->
    />
    
    <!-- 某个查询单独控制缓存 -->
    <select id="findById" resultType="User" useCache="true">
        SELECT * FROM users WHERE id = #{id}
    </select>
    
    <!-- 更新后刷新缓存 -->
    <insert id="insert" flushCache="true">...</insert>
    
</mapper>
```

**淘汰策略说明：**

| 策略 | 说明 |
|------|------|
| `LRU` | 最近最少使用（默认）|
| `FIFO` | 先进先出 |
| `SOFT` | 软引用（基于 GC 状态）|
| `WEAK` | 弱引用（GC 时直接回收）|

### 3.3 CachingExecutor——二级缓存的实现

二级缓存通过装饰器模式实现——`CachingExecutor` 包装了实际的 Executor：

```java
// CachingExecutor.java
public class CachingExecutor implements Executor {
    
    private final Executor delegate;  // 被装饰的实际 Executor（SimpleExecutor 等）
    
    public CachingExecutor(Executor delegate) {
        this.delegate = delegate;
        // 在 Configuration.newExecutor() 中创建
        // executor = new CachingExecutor(executor);
    }
    
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter,
                              RowBounds rowBounds, ResultHandler resultHandler) {
        // 1. 获取 BoundSql
        BoundSql boundSql = ms.getBoundSql(parameter);
        // 2. 生成缓存 Key
        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
        // ★ 3. 走二级缓存
        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }
    
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter,
                              RowBounds rowBounds, ResultHandler resultHandler,
                              CacheKey key, BoundSql boundSql) {
        
        // ★ 获取 MappedStatement 上配置的二级缓存
        Cache cache = ms.getCache();
        
        if (cache != null) {
            // ★ 判断是否需要刷新缓存
            if (ms.isFlushCacheRequired()) {
                // insert/update/delete → 清空二级缓存
                cache.clear();
            }
            
            if (ms.isUseCache() && resultHandler == null) {
                // 确保返回类型实现了 Serializable
                ensureNoOutParams(ms, boundSql);
                
                // ★ 从二级缓存获取
                List<E> list = (List<E>) cache.getObject(key);
                if (list == null) {
                    // ★ 二级缓存未命中 → 委托给实际的 Executor（走一级缓存或数据库）
                    list = delegate.query(ms, parameter, rowBounds, resultHandler, key, boundSql);
                    // ★ 结果写入二级缓存
                    cache.putObject(key, list);
                }
                return list;
            }
        }
        
        // 没有配置二级缓存 → 直接委托给实际 Executor
        return delegate.query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }
    
    @Override
    public int update(MappedStatement ms, Object parameter) {
        // ★ 先刷新二级缓存
        flushCacheIfRequired(ms);
        // ★ 委托给实际 Executor（它也会清空一级缓存）
        return delegate.update(ms, parameter);
    }
    
    private void flushCacheIfRequired(MappedStatement ms) {
        Cache cache = ms.getCache();
        if (cache != null && ms.isFlushCacheRequired()) {
            cache.clear();  // ★ 清空二级缓存
        }
    }
}
```

### 3.4 二级缓存的装饰器链

MyBatis 的缓存使用了经典的装饰器模式——一个 `Cache` 可以被多个装饰器层层包装：

```java
// Mapper 配置：
// <cache eviction="LRU" flushInterval="60000" size="512" readOnly="true"/>

// 生成的缓存对象装饰器链：
new SynchronizedCache(                           // 线程安全
    new LoggingCache(                             // 日志记录（命中率）
        new ScheduledCache(                       // 定时刷新
            new LruCache(                         // LRU 淘汰
                new PerpetualCache("namespace")   // 底层 HashMap
            , 512)                                // LRU 大小
        , 60000)                                  // 刷新间隔
    )
)
```

**Cache 接口：**

```java
public interface Cache {
    String getId();                          // 缓存 ID（命名空间）
    void putObject(Object key, Object value); // 存入缓存
    Object getObject(Object key);             // 获取缓存
    Object removeObject(Object key);          // 移除
    void clear();                             // 清空
    int getSize();                            // 大小
}
```

**CacheBuilder 构建缓存链：**

```java
// CacheBuilder.java
public Cache build() {
    setDefaultImplementations();
    
    // 1. 创建底层缓存（PerpetualCache）
    Cache cache = newBaseCacheInstance(implementation, id);
    setCacheProperties(cache);
    
    // 2. 如果配置了装饰器，应用装饰器
    if (PerpetualCache.class.equals(cache.getClass())) {
        for (Class<? extends Cache> decorator : decorators) {
            // ★ 逐个应用装饰器
            cache = newCacheDecoratorInstance(decorator, cache);
            setCacheProperties(cache);
        }
        // 3. 应用标准装饰器：SynchronizedCache + LoggingCache
        cache = setStandardDecorators(cache);
    }
    
    return cache;
}

private Cache setStandardDecorators(Cache cache) {
    try {
        // 1. 定时刷新
        if (flushInterval != null) {
            cache = new ScheduledCache(cache);
            ((ScheduledCache) cache).setClearInterval(flushInterval);
        }
        // 2. 淘汰策略（LRU/FIFO/SOFT/WEAK）
        if (eviction != null) {
            cache = newCacheDecoratorInstance(eviction, cache);
        }
        // 3. 日志
        cache = new LoggingCache(cache);
        // 4. 线程安全
        cache = new SynchronizedCache(cache);
        return cache;
    } catch (...) { ... }
}
```

---

## 四、多表联合查询的缓存问题

### 4.1 脏读问题

```java
// 问题场景：
// namespace: com.example.mapper.UserMapper
// namespace: com.example.mapper.RoleMapper

// UserMapper 查询用户+角色信息（联合查询）
@Select("SELECT u.*, r.name FROM users u JOIN roles r ON u.role_id = r.id")
// → 结果缓存到 UserMapper 的二级缓存中

// RoleMapper 更新角色信息
@Update("UPDATE roles SET name = 'admin' WHERE id = 1")
// → 刷新的是 RoleMapper 的二级缓存

// 问题：UserMapper 的缓存没有被清空，仍然返回旧数据
```

**解决方案：**

```xml
<!-- 使用 <cache-ref> 引用同一缓存 -->
<mapper namespace="com.example.mapper.UserMapper">
    <cache-ref namespace="com.example.mapper.RoleMapper"/>
</mapper>
```

即多个 Mapper 共享同一个命名空间的缓存，任何一个执行更新都会清空共享缓存。

---

## 五、自定义缓存

### 5.1 实现自定义缓存

```java
// 实现 Cache 接口
public class RedisCache implements Cache {
    
    private final String id;
    private final RedisTemplate<String, Object> redisTemplate;
    
    public RedisCache(String id) {
        this.id = id;
        // 获取 RedisTemplate
        ApplicationContext context = SpringContextHolder.getApplicationContext();
        this.redisTemplate = context.getBean("redisTemplate", RedisTemplate.class);
    }
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public void putObject(Object key, Object value) {
        redisTemplate.opsForValue().set(id + ":" + key.toString(), value);
    }
    
    @Override
    public Object getObject(Object key) {
        return redisTemplate.opsForValue().get(id + ":" + key.toString());
    }
    
    @Override
    public Object removeObject(Object key) {
        redisTemplate.delete(id + ":" + key.toString());
        return null;
    }
    
    @Override
    public void clear() {
        Set<String> keys = redisTemplate.keys(id + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
    
    @Override
    public int getSize() {
        // ...
        return 0;
    }
}
```

```xml
<!-- 使用自定义缓存 -->
<mapper namespace="com.example.mapper.UserMapper">
    <cache type="com.example.cache.RedisCache"/>
</mapper>
```

### 5.2 二级缓存的使用建议

| 场景 | 建议 |
|------|------|
| 单表查询 | ✅ 适合使用二级缓存 |
| 联合查询频繁的表 | ⚠️ 注意脏读问题，用 `<cache-ref>` 共享 |
| 更新频繁的表 | ❌ 缓存意义不大，反而增加维护成本 |
| 字典表、配置表 | ✅ 非常适合缓存 |
| 集群部署 | ✅ 建议使用 Redis 等分布式缓存 |

---

## 六、总结

### 两级缓存对比

| 维度 | 一级缓存 | 二级缓存 |
|------|---------|---------|
| 范围 | SqlSession 级别 | Mapper 命名空间级别 |
| 默认开启 | 是 | 否（需 `<cache/>`）|
| 存储 | PerpetualCache（HashMap）| PerpetualCache / 自定义 |
| 跨 Session | 否 | 是 |
| 失效 | update/commit/close/clearCache | insert/update/delete/flushCache |
| 事务 | 提交后才生效 | 提交后才生效 |
| 线程安全 | 同一 Session 内单线程 | SynchronizedCache 保证 |
| 序列化 | 不需要 | 需要（可能反序列化回不同对象）|

### 缓存查找顺序

```
CacheKey → 二级缓存 → 一级缓存 → 数据库 → 写入一级缓存 → 写入二级缓存
```

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `BaseExecutor` | 一级缓存的实现（localCache）|
| `PerpetualCache` | 底层 HashMap 缓存存储 |
| `CacheKey` | 缓存 Key 的生成（SQL + 参数 + RowBounds）|
| `CachingExecutor` | 二级缓存装饰器 |
| `CacheBuilder` | 构建 Cache 装饰器链 |
| `LoggingCache` | 缓存命中率日志装饰器 |
| `SynchronizedCache` | 线程安全装饰器 |
| `ScheduledCache` | 定时刷新装饰器 |
| `LruCache` | LRU 淘汰策略装饰器 |
| `FifoCache` | FIFO 淘汰策略装饰器 |

---

**上一篇：** [MyBatis（三）：动态 SQL 深度解析]({{< relref "post/mybatis-dynamic-sql" >}})

**系列索引：**
- [MyBatis（一）：核心架构与配置]({{< relref "post/mybatis-architecture" >}})
- [MyBatis（二）：Mapper 代理原理与 SQL 执行流程]({{< relref "post/mybatis-mapper-proxy-execution" >}})
- [MyBatis（三）：动态 SQL 深度解析]({{< relref "post/mybatis-dynamic-sql" >}})
- [MyBatis（四）：缓存机制与源码解析]({{< relref "post/mybatis-cache" >}})
