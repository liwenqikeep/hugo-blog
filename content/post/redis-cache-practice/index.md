---
title: "Redis（五）：缓存设计与实战"
date: 2018-08-01
draft: false
categories: ["Redis"]
tags: ["Redis", "缓存设计", "分布式锁", "缓存一致性", "热点缓存", "Spring Boot"]
toc: true
---

## 前言

前面四篇文章覆盖了 Redis 的数据结构、持久化、集群和淘汰策略。这篇文章将所有知识落地到实战——在真实的项目中，**如何设计合理的缓存方案**？如何处理**缓存一致性**、**分布式锁**、**热点缓存**等常见问题？

<!--more-->

## 一、Spring Boot 整合 Redis

### 1.1 依赖与配置

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>  <!-- 连接池 -->
</dependency>
```

```yaml
spring:
  redis:
    host: 192.168.1.100
    port: 6379
    password: yourpassword
    timeout: 3000ms
    lettuce:                     # Spring Boot 2.x 默认使用 Lettuce
      pool:
        max-active: 16           # 最大连接数
        max-idle: 8              # 最大空闲连接
        min-idle: 4              # 最小空闲连接
        max-wait: -1ms           # 获取连接的最大等待时间（-1 = 无限）
```

### 1.2 RedisTemplate 配置

```java
@Configuration
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory) {
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // 使用 Jackson 序列化 Value
        Jackson2JsonRedisSerializer<Object> serializer = 
                new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);
        serializer.setObjectMapper(mapper);
        
        // Key 用 String 序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        
        return template;
    }
}
```

### 1.3 封装缓存工具类

```java
@Component
public class CacheService {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // 缓存查询：Cache Aside 模式
    public <T> T get(String key, Class<T> type, 
                     Supplier<T> dbLoader, long expire) {
        // 1. 查缓存
        Object value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            return type.cast(value);
        }
        
        // 2. 缓存未命中 → 查数据库（分布式锁保护）
        String lockKey = "lock:" + key;
        String lockValue = UUID.randomUUID().toString();
        
        if (tryLock(lockKey, lockValue, 10)) {
            try {
                // 双重检查（double check）
                value = redisTemplate.opsForValue().get(key);
                if (value != null) {
                    return type.cast(value);
                }
                
                // 查数据库
                T result = dbLoader.get();
                if (result != null) {
                    redisTemplate.opsForValue().set(key, result, expire, TimeUnit.SECONDS);
                } else {
                    // 缓存空值，防止穿透
                    redisTemplate.opsForValue().set(key, null, 60, TimeUnit.SECONDS);
                }
                return result;
            } finally {
                unlock(lockKey, lockValue);
            }
        }
        
        // 等待重试（其他线程正在查数据库）
        sleep(50);
        return get(key, type, dbLoader, expire);
    }
    
    // 更新缓存：先更新数据库，再删除缓存
    public void evict(String key) {
        redisTemplate.delete(key);
    }
}
```

---

## 二、缓存模式

### 2.1 Cache Aside（旁路缓存）

```
读：
  1. 读缓存 → 命中则返回
  2. 未命中 → 读数据库
  3. 写入缓存 → 返回

写：
  1. 更新数据库
  2. 删除缓存（不是更新缓存）
  
为什么写操作要删除缓存而不是更新缓存？
  - 更新缓存是写操作（写入新值），成本高（可能需要序列化）
  - 删除缓存等下次读取时再加载（惰性加载）
  - 避免并发写操作的缓存不一致问题
```

```java
public class CacheAsidePattern {
    
    // 读
    public User getUser(Long id) {
        String key = "user:" + id;
        
        // 1. 读缓存
        User user = redis.get(key);
        if (user != null) return user;
        
        // 2. 读数据库
        user = userDao.findById(id);
        
        // 3. 写缓存
        if (user != null) {
            redis.set(key, user, 3600);
        } else {
            redis.set(key, null, 60);  // 缓存空值
        }
        
        return user;
    }
    
    // 写：先更新数据库，再删除缓存
    public void updateUser(User user) {
        userDao.update(user);         // 1. 更新数据库
        redis.del("user:" + user.getId()); // 2. 删除缓存
    }
}
```

### 2.2 延迟双删

```java
// 处理"先更新数据库 → 删除缓存 → 其他线程读旧数据写缓存"的并发问题

public void updateUser(User user) {
    redis.del("user:" + user.getId());  // 1. 先删缓存
    userDao.update(user);               // 2. 更新数据库
    
    // 3. 延迟一会儿再删一次
    executor.schedule(() -> {
        redis.del("user:" + user.getId());
    }, 500, TimeUnit.MILLISECONDS);
}
```

### 2.3 逻辑过期（热点数据永不过期）

```java
// 对热点数据，缓存不过期，由后台任务定期刷新

// 缓存数据包含逻辑过期时间
public class CacheData<T> {
    private T data;
    private long expireTime;     // 逻辑过期时间
    private long lastUpdate;     // 最后更新时间
}

// 读
public User getUser(Long id) {
    String key = "user:" + id;
    CacheData<User> cacheData = redis.get(key);
    
    if (cacheData == null) {
        return loadFromDB(key, id);  // 缓存都没有，查数据库
    }
    
    if (cacheData.isExpired()) {
        // 缓存逻辑过期 → 异步刷新缓存
        executor.submit(() -> {
            User user = userDao.findById(id);
            redis.set(key, new CacheData<>(user, 3600));
        });
    }
    
    return cacheData.getData();  // 返回旧数据，不阻塞
}

// 写：直接更新数据库 + 缓存
public void updateUser(User user) {
    userDao.update(user);
    redis.set("user:" + user.getId(), new CacheData<>(user, 3600));
}
```

---

## 三、分布式锁

### 3.1 基于 SETNX 的实现

```java
// 最常用的 Redis 分布式锁

public class RedisDistributedLock {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    // 加锁
    public boolean tryLock(String key, String value, long expireSeconds) {
        // SET key value NX EX seconds
        // NX：key 不存在时才设置成功
        // EX：设置过期时间（自动释放锁，防止死锁）
        return redisTemplate.opsForValue()
                .setIfAbsent(key, value, expireSeconds, TimeUnit.SECONDS);
    }
    
    // 解锁（Lua 脚本保证原子性）
    public boolean unlock(String key, String value) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] " +
                        "then return redis.call('del', KEYS[1]) " +
                        "else return 0 end";
        // 只删除自己加的锁（防止误删别人的锁）
        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                List.of(key), value);
        return result != null && result > 0;
    }
}
```

### 3.2 Redisson 框架

```java
// 更完善的分布式锁实现（Redisson）
// 支持：自动续期、可重入、公平锁、读写锁、红锁

@Configuration
public class RedissonConfig {
    
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
              .setAddress("redis://192.168.1.100:6379")
              .setPassword("yourpassword");
        return Redisson.create(config);
    }
}

@Service
public class OrderService {
    
    @Autowired
    private RedissonClient redisson;
    
    public void createOrder(Long orderId) {
        RLock lock = redisson.getLock("order:lock:" + orderId);
        
        try {
            // 尝试加锁，最多等 10 秒，锁 30 秒自动释放
            if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
                // 处理订单（自动续期：watch dog 机制）
                doCreateOrder(orderId);
            }
        } finally {
            lock.unlock();  // 释放锁
        }
    }
}
```

**Watch Dog 机制：**

```
Redisson 的锁默认 30 秒过期
如果业务逻辑还没执行完，Watch Dog 每 10 秒自动续期一次
防止业务没执行完锁就过期了
```

### 3.3 分布式锁注意事项

```
1. 设置过期时间（防止死锁）
   使用 SET NX EX（setIfAbsent）原子操作

2. 解锁时校验 value（防止误删别人的锁）
   用 Lua 脚本检查 value 匹配后才 DELETE

3. 锁超时与业务时长
   业务耗时 > 锁超时 → 用 Redisson 的 Watch Dog

4. 可重入
   同一线程可重复获取同一把锁（Redisson 支持）

5. 主从切换问题
   SET NX 到主节点 → 同步到从节点前主节点宕机
   → 新主节点没有这条锁记录 → 其他线程可以获取同名的锁
   → 解决方案：RedLock（红锁），需要大多数节点同意
```

---

## 四、热点缓存

### 4.1 热点探测

```java
// 使用 Redis 的 Hotkey 探测
// 配合 Redis 4.0+ 的 LFU 淘汰策略

// 方式 1：monitor 命令（不推荐，压力大）
redis-cli monitor | grep "hot_key"

// 方式 2：使用 redis-faina (Redis 自带的分析工具)
redis-cli -p 6379 --hotkeys
```

### 4.2 本地缓存 + Redis 多级缓存

```java
// 解决热点缓存问题：本地缓存（Caffeine）作为第一级

@Component
public class MultiLevelCache {
    
    // 一级缓存：本地（Caffeine）
    private final Cache<String, Object> localCache = Caffeine.newBuilder()
            .maximumSize(10000)          // 最多 10000 条
            .expireAfterWrite(10, TimeUnit.SECONDS)  // 10 秒过期
            .build();
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    public Object get(String key) {
        // 1. 查本地缓存
        Object value = localCache.getIfPresent(key);
        if (value != null) return value;
        
        // 2. 查 Redis
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            value = deserialize(json);
            localCache.put(key, value);  // 写入本地缓存
            return value;
        }
        
        return null;
    }
}
```

---

## 五、缓存一致性方案总结

### 5.1 方案对比

| 方案 | 一致性 | 复杂度 | 适用场景 |
|------|--------|:------:|---------|
| Cache Aside | 最终一致 | 低 | 大部分场景 |
| 延迟双删 | 最终一致 | 中 | 并发写冲突多的场景 |
| 逻辑过期 + 异步更新 | 弱一致 | 中 | 热点数据 |
| Canal 订阅 binlog | 强一致 | 高 | 严格一致要求 |

### 5.2 Canal 订阅 binlog 方案

```java
// 通过监听 MySQL binlog 实时更新缓存

// Canal 模拟 MySQL 从库，读取 binlog
// 解析出数据变更 → 更新 Redis 缓存

// 流程：
// MySQL binlog → Canal → MQ → 缓存更新服务 → Redis

// 优点：与业务代码解耦，不侵入原有写操作
// 缺点：引入 Canal + MQ，运维复杂

// 适合场景：强一致性要求 + 团队有能力维护
```

---

## 六、总结

### 缓存设计原则

```
1. 缓存什么？        → 读多写少的数据、热点数据
2. 多大缓存？        → 根据数据的访问热度预估容量
3. 过期多久？        → 根据数据更新频率 + 允许的脏读时间
4. 淘汰策略？        → allkeys-lru（缓存场景）
5. 一致性怎么保证？   → 先更新数据库，再删除缓存
6. 并发问题怎么处理？  → 分布式锁防止缓存击穿
```

### 常见场景的缓存策略

| 场景 | 过期时间 | 策略 | 说明 |
|------|---------|------|------|
| 用户信息 | 1 小时 | Cache Aside | 读多写少 |
| 商品详情 | 逻辑过期 | 热点永不过期 + 异步刷新 | 避免缓存击穿 |
| 配置项 | 10 分钟 | 定时刷新 | 低频变更 |
| 验证码 | 5 分钟 | SETEX 直接过期 | 精确控制有效期 |

---

**上一篇：** [Redis（四）：过期策略与淘汰机制]({{< relref "post/redis-expiration-eviction" >}})

**系列索引：**
- [Redis（一）：数据结构与使用]({{< relref "post/redis-data-structures" >}})
- [Redis（二）：持久化机制]({{< relref "post/redis-persistence" >}})
- [Redis（三）：集群与高可用]({{< relref "post/redis-cluster" >}})
- [Redis（四）：过期策略与淘汰机制]({{< relref "post/redis-expiration-eviction" >}})
- [Redis（五）：缓存设计与实战]({{< relref "post/redis-cache-practice" >}})
