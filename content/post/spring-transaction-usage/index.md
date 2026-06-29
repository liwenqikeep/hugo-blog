---
title: "Spring 事务（一）：@Transactional 使用、传播行为与隔离级别"
date: 2018-06-08
draft: false
categories: ["Java"]
tags: ["Spring", "事务", "@Transactional", "传播行为", "隔离级别", "TransactionTemplate"]
toc: true
---

## 前言

事务管理是 Spring 框架中最常用的功能之一。Spring 提供了声明式事务（`@Transactional`）和编程式事务（`TransactionTemplate`）两种方式，底层统一通过 `PlatformTransactionManager` 抽象屏蔽了不同事务 API 的差异。

本文从使用出发，全面覆盖 Spring 事务的核心概念、7 种传播行为、5 种隔离级别、常见配置和踩坑点，为后续的源码分析做准备。

<!--more-->

## 一、声明式事务

### 1.1 快速开始

```java
// 1. 启用事务（Spring Boot 自动配置，无需手动开启）
// 非 Spring Boot 项目需要：
@Configuration
@EnableTransactionManagement  // 启用声明式事务
public class TransactionConfig {
    
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}

// 2. 在方法上使用
@Service
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    @Transactional  // 声明事务
    public void createUser(User user) {
        userRepository.save(user);
        // 如果这里抛出 RuntimeException，事务自动回滚
    }
}
```

### 1.2 @Transactional 核心属性

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Transactional {

    @AliasFor("transactionManager")
    String value() default "";
    
    @AliasFor("value")
    String transactionManager() default "";  // 指定事务管理器
    
    String[] propagation() default {};      // 传播行为（实际上是 Propagation 枚举）
    Isolation isolation() default Isolation.DEFAULT;  // 隔离级别
    int timeout() default -1;               // 超时时间（秒）
    boolean readOnly() default false;        // 是否只读
    Class<? extends Throwable>[] rollbackFor() default {};    // 触发回滚的异常
    String[] rollbackForClassName() default {};
    Class<? extends Throwable>[] noRollbackFor() default {};  // 不触发回滚的异常
    String[] noRollbackForClassName() default {};
}
```

**常用配置示例：**

```java
// 指定异常回滚（默认只回滚 RuntimeException 和 Error）
@Transactional(rollbackFor = Exception.class)

// 只读事务（优化查询性能）
@Transactional(readOnly = true)

// 设置超时
@Transactional(timeout = 30)

// 指定事务管理器（多数据源时）
@Transactional(transactionManager = "orderTransactionManager")

// 指定传播行为
@Transactional(propagation = Propagation.REQUIRES_NEW)

// 指定隔离级别
@Transactional(isolation = Isolation.REPEATABLE_READ)
```

### 1.3 类级别与方法级别

```java
// 类级别定义默认事务行为
@Transactional(rollbackFor = Exception.class, timeout = 30)
@Service
public class UserService {
    
    // 方法级别覆盖类级别配置
    @Transactional(readOnly = true)
    public User findById(Long id) { ... }
    
    // 继承类级别的 rollbackFor 和 timeout
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createUser(User user) { ... }
    
    // 没有 @Transactional → 类级别的配置对此方法不生效
    public void noTransaction() { ... }
}
```

> **注意：** 类级别的 `@Transactional` 对类中所有方法生效，但方法自己标注的注解会覆盖类级别的配置。

## 二、编程式事务

### 2.1 TransactionTemplate

```java
@Service
public class TransactionService {
    
    @Autowired
    private TransactionTemplate transactionTemplate;
    
    public void doInTransaction() {
        // 正常执行
        transactionTemplate.execute(status -> {
            // 数据库操作
            User user = userRepository.save(new User("Tom"));
            Order order = orderRepository.save(new Order(user.getId()));
            return order;
        });
    }
    
    public void doWithRollback() {
        // 手动回滚
        transactionTemplate.execute(status -> {
            try {
                // 操作
                userRepository.save(new User("Tom"));
            } catch (Exception e) {
                status.setRollbackOnly();  // 标记回滚
                throw e;
            }
            return null;
        });
    }
}
```

### 2.2 PlatformTransactionManager 直接使用

```java
@Service
public class TransactionService {
    
    @Autowired
    private PlatformTransactionManager transactionManager;
    
    public void manualTransaction() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        def.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            // 业务操作
            userRepository.save(new User("Tom"));
            
            transactionManager.commit(status);  // 提交
        } catch (Exception e) {
            transactionManager.rollback(status);  // 回滚
            throw e;
        }
    }
}
```

### 2.3 声明式 vs 编程式

| 维度 | 声明式 `@Transactional` | 编程式 `TransactionTemplate` |
|------|------------------------|----------------------------|
| 使用复杂度 | 低（一行注解） | 中（模板方法） |
| 灵活性 | 低（编译期确定） | 高（运行时决定回滚/提交）|
| 细粒度控制 | 有限 | 完全控制 |
| 适合场景 | 大多数业务场景 | 需要精细控制事务边界 |

## 三、传播行为（Propagation）

### 3.1 7 种传播行为

| 传播行为 | 含义 | 使用场景 |
|---------|------|---------|
| `REQUIRED`（默认）| 当前有事务则加入，没有则新建 | 最常用的默认行为 |
| `SUPPORTS` | 当前有事务则加入，没有则以非事务方式执行 | 查询方法 |
| `MANDATORY` | 当前必须有事务，否则抛异常 | 强依赖事务的方法 |
| `REQUIRES_NEW` | 挂起当前事务，新建一个事务执行 | 独立操作（如审计日志）|
| `NOT_SUPPORTED` | 挂起当前事务，以非事务方式执行 | 内部包含非事务操作 |
| `NEVER` | 当前不能有事务，否则抛异常 | 明确不应该在事务中的操作 |
| `NESTED` | 嵌套事务（Savepoint 机制） | 部分回滚 |

### 3.2 REQUIRED（默认）

```java
@Service
public class OuterService {
    
    @Autowired
    private InnerService innerService;
    
    @Transactional
    public void outer() {
        userRepository.save(new User("Tom"));
        innerService.inner();  // 加入当前事务
        // 任何一个抛出异常 → 整个事务全部回滚
    }
}

@Service
public class InnerService {
    
    @Transactional(propagation = Propagation.REQUIRED)  // 默认行为
    public void inner() {
        orderRepository.save(new Order());
    }
}
```

```
outer() 开始事务 T1
  ├── save User
  ├── inner() → 加入 T1
  │     └── save Order
  └── 异常 → T1 全部回滚（User 和 Order 都回滚）
```

### 3.3 REQUIRES_NEW

```java
@Service
public class AuditService {
    
    // 不管外部有没有事务，自己开启新事务
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAudit(AuditLog log) {
        auditRepository.save(log);
        // 即使外部事务回滚，这条审计日志也提交了
    }
}
```

```
outer() 开始事务 T1
  ├── save User
  ├── logAudit()
  │     └── 挂起 T1，开始 T2
  │           └── save AuditLog → 提交 T2
  │     → 恢复 T1
  └── 异常 → T1 回滚（User 回滚，但 AuditLog 已提交）
```

### 3.4 NESTED（嵌套事务）

```java
@Service
public class SubService {
    
    @Transactional(propagation = Propagation.NESTED)
    public void subOperation() {
        // 在外部事务的 Savepoint 中执行
        orderRepository.save(new Order());
        // 如果这里抛出异常 → 只回滚到 Savepoint，不影响外部事务
    }
}
```

```
outer() 开始事务 T1
  ├── save User
  ├── subOperation()
  │     └── 设置 Savepoint (T1 内部)
  │           └── save Order
  │     └── 异常 → 回滚到 Savepoint，T1 继续
  └── save Another → T1 提交
```

### 3.5 传播行为对比

| 传播行为 | 外部有事务 | 外部无事务 |
|---------|-----------|-----------|
| REQUIRED | 加入外部事务 | 新建事务 |
| SUPPORTS | 加入外部事务 | 非事务 |
| MANDATORY | 加入外部事务 | 抛异常 |
| REQUIRES_NEW | 挂起外部，新建 | 新建事务 |
| NOT_SUPPORTED | 挂起外部，非事务 | 非事务 |
| NEVER | 抛异常 | 非事务 |
| NESTED | 嵌套事务（Savepoint）| 新建事务 |

## 四、隔离级别（Isolation）

### 4.1 读问题

| 问题 | 描述 | 发生条件 |
|------|------|---------|
| **脏读** | 读到另一个事务未提交的数据 | 隔离级别过低 |
| **不可重复读** | 同一事务两次读取同一条记录，结果不同 | 别的事务修改并提交了 |
| **幻读** | 同一事务两次范围查询，结果集不同 | 别的事务插入了新数据 |

### 4.2 Spring 支持的隔离级别

```java
public enum Isolation {
    DEFAULT(-1),           // 使用数据库默认级别
    READ_UNCOMMITTED(1),   // 读未提交（可以读到未提交的数据）
    READ_COMMITTED(2),     // 读已提交（MySQL 默认，解决脏读）
    REPEATABLE_READ(4),    // 可重复读（MySQL InnoDB 默认，解决脏读 + 不可重复读）
    SERIALIZABLE(8)        // 串行化（解决所有并发问题，性能最差）
}
```

| 隔离级别 | 脏读 | 不可重复读 | 幻读 |
|---------|:----:|:---------:|:----:|
| READ_UNCOMMITTED | ✅ 可能 | ✅ 可能 | ✅ 可能 |
| READ_COMMITTED | ❌ 避免 | ✅ 可能 | ✅ 可能 |
| REPEATABLE_READ | ❌ 避免 | ❌ 避免 | ✅ 可能（InnoDB 通过间隙锁避免）|
| SERIALIZABLE | ❌ 避免 | ❌ 避免 | ❌ 避免 |

### 4.3 隔离级别配置

```java
// 推荐：使用数据库默认级别
@Transactional(isolation = Isolation.DEFAULT)

// 显式指定（MySQL InnoDB 默认是 REPEATABLE_READ）
@Transactional(isolation = Isolation.READ_COMMITTED)

// 注意：隔离级别是全局事务配置，不是每个方法都适合不同隔离级别
// 最好的做法是大部分方法使用 DEFAULT，少数关键业务单独设置
```

## 五、常见配置场景

### 5.1 多数据源事务

```java
// 配置两个事务管理器
@Configuration
public class MultiDataSourceConfig {
    
    @Bean
    @Primary
    public DataSource primaryDataSource() { ... }
    
    @Bean
    public DataSource secondaryDataSource() { ... }
    
    @Bean
    @Primary
    public PlatformTransactionManager primaryTransactionManager() {
        return new DataSourceTransactionManager(primaryDataSource());
    }
    
    @Bean
    public PlatformTransactionManager secondaryTransactionManager() {
        return new DataSourceTransactionManager(secondaryDataSource());
    }
}

// 使用时指定事务管理器
@Service
public class UserService {
    
    @Transactional("primaryTransactionManager")
    public void doPrimary() { ... }
    
    @Transactional("secondaryTransactionManager")
    public void doSecondary() { ... }
}
```

### 5.2 自调用事务失效

```java
@Service
public class UserService {
    
    @Transactional
    public void outer() {
        // 自调用 → this.inner() 不经过代理 → @Transactional 失效
        inner();
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() {
        // 不会在新事务中执行！直接在原有事务中执行
    }
}
```

**解决方案：**

```java
// 方式一：注入自身（Spring Boot 允许循环引用时）
@Service
public class UserService {
    @Autowired
    private UserService self;  // 注入代理对象
    
    @Transactional
    public void outer() {
        self.inner();  // 通过代理调用，事务生效
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() { ... }
}

// 方式二：提取到另一个 Service（推荐）
@Service
public class InnerService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() { ... }
}
```

### 5.3 @Transactional 回滚规则

```java
// 默认：只回滚 RuntimeException 和 Error
@Transactional
public void defaultRollback() {
    throw new RuntimeException();  // 回滚
}

@Transactional
public void noRollback() throws SQLException {
    throw new SQLException();  // 不回滚！SQLException 是 checked exception
}

// 修复：指定 rollbackFor
@Transactional(rollbackFor = Exception.class)
public void fixedRollback() throws SQLException {
    throw new SQLException();  // 回滚
}

// 排除特定异常
@Transactional(noRollbackFor = IllegalArgumentException.class)
public void excludeRollback() {
    throw new IllegalArgumentException();  // 不回滚
    throw new NullPointerException();      // 回滚（RuntimeException 子类）
}
```

## 六、常见踩坑

### 6.1 @Transactional 只对 public 方法生效

```java
@Transactional  // ✅ 生效
public void publicMethod() { ... }

@Transactional  // ❌ 不生效！protected/private 不会创建事务
protected void protectedMethod() { ... }
```

### 6.2 @Transactional 的异常不要 try-catch

```java
@Transactional
public void wrong() {
    try {
        userRepository.save(user);
        throw new RuntimeException();  // 异常被 catch 了
    } catch (Exception e) {
        // 异常被吃掉 → 事务不会回滚！数据已提交
    }
}

@Transactional
public void correct() {
    userRepository.save(user);
    throw new RuntimeException();  // 让事务拦截器感知异常并回滚
}
```

### 6.3 事务超时

```java
@Transactional(timeout = 5)  // 5 秒超时
public void longRunning() {
    // 方法执行超过 5 秒 → TransactionTimedOutException
}
```

## 七、总结

### @Transactional 属性速查

| 属性 | 默认值 | 说明 |
|------|--------|------|
| propagation | REQUIRED | 传播行为 |
| isolation | DEFAULT | 隔离级别 |
| timeout | -1（永不过期）| 事务超时秒数 |
| readOnly | false | 是否只读事务 |
| rollbackFor | RuntimeException | 触发回滚的异常 |

### 传播行为速记

```
REQUIRED       → 有则加入，无则新建
REQUIRES_NEW   → 新建，挂起当前
NESTED         → 嵌套于当前事务（Savepoint）
MANDATORY      → 必须有事务
SUPPORTS       → 有则加入，无则非事务
NEVER          → 不能有事务
NOT_SUPPORTED  → 挂起当前，非事务
```

### 事务最佳实践

1. `@Transactional` 只放 public 方法
2. 不要 try-catch 吃掉异常
3. 自调用要小心事务失效
4. 默认只回滚 `RuntimeException`，checked exception 需指定 `rollbackFor`
5. 只读查询用 `@Transactional(readOnly = true)` 优化
6. 多数据源时指定 `transactionManager`

---

**下一篇：** [Spring 事务（二）：声明式事务源码深度解析]({{< relref "post/spring-transaction-source-code" >}})
