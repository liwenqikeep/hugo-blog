---
title: "Spring 事务（二）：声明式事务源码深度解析"
date: 2018-06-10
draft: false
categories: ["Java"]
tags: ["Spring", "事务", "@Transactional", "TransactionInterceptor", "AOP", "源码分析", "PlatformTransactionManager"]
toc: true
---

## 前言

上一篇全面覆盖了 `@Transactional` 的使用和配置。这篇文章深入底层，追踪**一个 `@Transactional` 注解如何最终控制数据库事务的提交和回滚**的完整链路。

核心思路：Spring 声明式事务 = **AOP 拦截** + **事务拦截器** + **PlatformTransactionManager 抽象**。

<!--more-->

> **源码版本：** Spring Framework 5.x

## 一、@EnableTransactionManagement 入口

### 1.1 导入链

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(TransactionManagementConfigurationSelector.class)
public @interface EnableTransactionManagement {
    boolean proxyTargetClass() default false;
    AdviceMode mode() default AdviceMode.PROXY;  // 默认 PROXY，可选 ASPECTJ
    int order() default Ordered.LOWEST_PRECEDENCE;
}
```

**TransactionManagementConfigurationSelector 选择配置类：**

```java
// TransactionManagementConfigurationSelector.java
public class TransactionManagementConfigurationSelector extends AdviceModeImportSelector<EnableTransactionManagement> {
    
    @Override
    protected String[] selectImports(AdviceMode adviceMode) {
        switch (adviceMode) {
            case PROXY:
                // ★ 注册两个关键的配置类
                return new String[] {
                    AutoProxyRegistrar.class.getName(),          // 注册代理创建器
                    ProxyTransactionManagementConfiguration.class.getName()  // 注入事务基础设施
                };
            case ASPECTJ:
                return new String[] { ... };
        }
    }
}
```

### 1.2 注册的核心组件

`ProxyTransactionManagementConfiguration` 注册了事务所需的所有基础设施 Bean：

```java
// ProxyTransactionManagementConfiguration.java
@Configuration
public class ProxyTransactionManagementConfiguration {
    
    // 1. ★ 事务拦截器（核心）
    @Bean(name = TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public BeanFactoryTransactionAttributeSourceAdvisor transactionAdvisor() {
        BeanFactoryTransactionAttributeSourceAdvisor advisor = new BeanFactoryTransactionAttributeSourceAdvisor();
        // 设置 @Transactional 属性解析器
        advisor.setTransactionAttributeSource(transactionAttributeSource());
        // 设置事务拦截器
        advisor.setAdvice(transactionInterceptor());
        return advisor;
    }
    
    // 2. 解析 @Transactional 注解的属性
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public TransactionAttributeSource transactionAttributeSource() {
        return new AnnotationTransactionAttributeSource();
    }
    
    // 3. ★ 事务拦截器
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public TransactionInterceptor transactionInterceptor() {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionAttributeSource(transactionAttributeSource());
        return interceptor;
    }
}
```

## 二、事务的 AOP 代理链

### 2.1 Advisor 的构成

`BeanFactoryTransactionAttributeSourceAdvisor` 是一个 `PointcutAdvisor`，它的执行链路与 AOP 章节中分析的完全一致：

```
BeanFactoryTransactionAttributeSourceAdvisor
  ├── Pointcut: TransactionAttributeSourcePointcut
  │     └── matches() 检查方法/类上有没有 @Transactional 注解
  │
  └── Advice: TransactionInterceptor
        └── MethodInterceptor.invoke() → 事务的开启、提交、回滚
```

### 2.2 TransactionAttributeSourcePointcut 的匹配

```java
// TransactionAttributeSourcePointcut.java — 判断是否匹配
class TransactionAttributeSourcePointcut extends StaticMethodMatcherPointcut {
    
    private TransactionAttributeSource transactionAttributeSource;
    
    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        // ★ 检查目标方法或类上是否存在 @Transactional 注解
        TransactionAttributeSource tas = getTransactionAttributeSource();
        return (tas == null || tas.getTransactionAttribute(method, targetClass) != null);
    }
}
```

### 2.3 @Transactional 注解属性的解析

`AnnotationTransactionAttributeSource` 负责解析 `@Transactional` 的属性：

```java
// AnnotationTransactionAttributeSource.java
public class AnnotationTransactionAttributeSource extends AbstractFallbackTransactionAttributeSource {
    
    // 解析 @Transactional 的方法
    @Override
    protected TransactionAttribute findTransactionAttribute(Class<?> clazz) {
        return determineTransactionAttribute(clazz);
    }
    
    @Override
    protected TransactionAttribute findTransactionAttribute(Method method) {
        return determineTransactionAttribute(method);
    }
    
    private TransactionAttribute determineTransactionAttribute(AnnotatedElement element) {
        // 1. 查找 @Transactional 注解
        Transactional annotation = AnnotatedElementUtils.findMergedAnnotation(
                element, Transactional.class);
        if (annotation == null) return null;
        
        // 2. 将注解属性转为 TransactionAttribute
        TransactionAttribute ta = new RuleBasedTransactionAttribute();
        ta.setPropagationBehavior(annotation.propagation());
        ta.setIsolationLevel(annotation.isolation());
        ta.setTimeout(annotation.timeout());
        ta.setReadOnly(annotation.readOnly());
        
        // 3. 设置回滚规则（rollbackFor / noRollbackFor）
        List<RollbackRuleAttribute> rollbackRules = new ArrayList<>();
        for (Class<?> rbRule : annotation.rollbackFor()) {
            rollbackRules.add(new RollbackRuleAttribute(rbRule));
        }
        for (String rbRule : annotation.rollbackForClassName()) {
            rollbackRules.add(new RollbackRuleAttribute(rbRule));
        }
        // noRollbackFor 同理
        ((RuleBasedTransactionAttribute) ta).setRollbackRules(rollbackRules);
        
        return ta;
    }
}
```

## 三、TransactionInterceptor——事务的核心

`TransactionInterceptor` 是一个 `MethodInterceptor`，在代理方法调用时触发事务逻辑。

### 3.1 invoke() 入口

```java
// TransactionInterceptor.java
public class TransactionInterceptor extends TransactionAspectSupport
        implements MethodInterceptor, Serializable {
    
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Class<?> targetClass = (invocation.getThis() != null 
                ? AopUtils.getTargetClass(invocation.getThis()) : null);
        
        // ★ 委托给父类的模板方法
        return invokeWithinTransaction(invocation.getMethod(), targetClass, 
                invocation::proceed, invocation.getThis());
    }
}
```

### 3.2 invokeWithinTransaction——事务模板方法

这是声明式事务的**核心方法**，定义了一个事务方法的完整生命周期：

```java
// TransactionAspectSupport.java
protected Object invokeWithinTransaction(Method method, Class<?> targetClass,
                                          InvocationCallback invocation, Object target) {
    
    // 1. 获取 @Transactional 属性
    TransactionAttributeSource tas = getTransactionAttributeSource();
    TransactionAttribute txAttr = (tas != null ? tas.getTransactionAttribute(method, targetClass) : null);
    
    // 2. 获取 PlatformTransactionManager
    PlatformTransactionManager tm = determineTransactionManager(txAttr);
    
    // 3. 构建方法唯一标识（用于 TransactionSynchronization）
    String joinpointIdentification = methodIdentification(method, targetClass, txAttr);
    
    // ★ 4. 根据响应式/常规选择执行路径
    if (txAttr == null || !(tm instanceof CallbackPreferringPlatformTransactionManager)) {
        // ★ 常规路径（大部分情况）
        // 4a. 创建事务（如果当前需要事务）
        TransactionInfo txInfo = createTransactionIfNecessary(tm, txAttr, joinpointIdentification);
        
        Object retVal;
        try {
            // 4b. ★ 执行目标方法
            retVal = invocation.proceedWithInvocation();
        } catch (Throwable ex) {
            // 4c. ★ 异常时回滚
            completeTransactionAfterThrowing(txInfo, ex);
            throw ex;
        } finally {
            // 4d. 清理事务信息（恢复现场）
            cleanupTransactionInfo(txInfo);
        }
        
        // 4e. ★ 正常提交
        commitTransactionAfterReturning(txInfo);
        
        return retVal;
    } else {
        // 响应式事务路径（略）
    }
}
```

**核心流程：**

```
invokeWithinTransaction()
    │
    ├── 1. 获取 @Transactional 属性
    ├── 2. 确定 PlatformTransactionManager
    ├── 3. createTransactionIfNecessary()  → 开启事务
    │
    ├── 4. invocation.proceedWithInvocation()  → 执行目标方法
    │     ├── 正常返回 → commitTransactionAfterReturning() → 提交
    │     └── 抛出异常 → completeTransactionAfterThrowing() → 回滚
    │
    └── 5. cleanupTransactionInfo()  → 恢复事务上下文
```

## 四、创建事务

### 4.1 createTransactionIfNecessary

```java
// TransactionAspectSupport.java
protected TransactionInfo createTransactionIfNecessary(PlatformTransactionManager tm,
                                                        TransactionAttribute txAttr,
                                                        String joinpointIdentification) {
    // 1. 没有 @Transactional 属性 → 不需要事务
    if (txAttr != null && txAttr.getName() == null) {
        txAttr = new DelegatingTransactionAttribute(txAttr) {
            @Override
            public String getName() {
                return joinpointIdentification;
            }
        };
    }
    
    TransactionStatus status = null;
    if (txAttr != null) {
        if (tm != null) {
            // ★ 2. 调用 PlatformTransactionManager 获取事务
            status = tm.getTransaction(txAttr);
        }
    }
    
    // 3. 包装为 TransactionInfo（存储事务上下文，用于恢复）
    return prepareTransactionInfo(tm, txAttr, joinpointIdentification, status);
}
```

### 4.2 AbstractPlatformTransactionManager.getTransaction()

这是处理事务传播行为的关键方法：

```java
// AbstractPlatformTransactionManager.java
@Override
public final TransactionStatus getTransaction(TransactionDefinition definition) {
    // 1. 尝试获取当前事务（查找当前线程是否存在事务）
    Object transaction = doGetTransaction();
    
    // 2. 判断当前有没有事务
    if (isExistingTransaction(transaction)) {
        // ★ 当前有事务 → 根据传播行为处理
        return handleExistingTransaction(definition, transaction, debugEnabled);
    }
    
    // 3. 当前没有事务 → 检查传播行为
    if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_MANDATORY) {
        // MANDATORY 要求有事务 → 抛异常
        throw new IllegalTransactionStateException("No existing transaction found ...");
    } else if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED
            || definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW
            || definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
        // REQUIRED/REQUIRES_NEW/NESTED → 新建事务
        SuspendedResourcesHolder suspendedResources = suspend(null);  // 没有需要挂起的
        // ★ 真正创建事务
        boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
        DefaultTransactionStatus status = newTransactionStatus(
                definition, transaction, true, newSynchronization, debugEnabled, suspendedResources);
        // 调用 doBegin 开启事务（获取连接、设置隔离级别等）
        doBegin(transaction, definition);
        prepareSynchronization(status, definition);
        return status;
    } else {
        // SUPPORTS/NOT_SUPPORTED/NEVER → 非事务执行
        // ...
    }
}
```

### 4.3 handleExistingTransaction——传播行为的处理

```java
// AbstractPlatformTransactionManager.java
private TransactionStatus handleExistingTransaction(TransactionDefinition definition,
                                                     Object transaction, boolean debugEnabled) {
    
    // 1. NEVER → 抛异常
    if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NEVER) {
        throw new IllegalTransactionStateException("Existing transaction found ...");
    }
    
    // 2. NOT_SUPPORTED → 挂起当前事务
    if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
        Object suspendedResources = suspend(transaction);
        // 返回一个没有事务的状态
        return newTransactionStatus(...);
    }
    
    // 3. REQUIRES_NEW → 挂起当前事务，新建
    if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
        Object suspendedResources = suspend(transaction);  // ← 挂起当前事务
        // ★ 创建全新的独立事务
        DefaultTransactionStatus status = newTransactionStatus(...);
        doBegin(transaction, definition);  // ← 新连接、新事务
        prepareSynchronization(status, definition);
        return status;
    }
    
    // 4. NESTED → 使用 Savepoint
    if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
        if (useSavepointForNestedTransaction()) {
            // ★ 通过 Savepoint 实现嵌套事务
            DefaultTransactionStatus status = newTransactionStatus(...);
            status.createAndHoldSavepoint();  // ← 创建 Savepoint
            return status;
        } else {
            // 不支持 Savepoint → 退化为 REQUIRES_NEW
            // ...
        }
    }
    
    // 5. REQUIRED / SUPPORTS / MANDATORY → 加入当前事务
    return newTransactionStatus(...);
}
```

### 4.4 doBegin——真正的数据库事务开始

以 `DataSourceTransactionManager` 为例：

```java
// DataSourceTransactionManager.java
@Override
protected void doBegin(Object transaction, TransactionDefinition definition) {
    DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
    Connection con = null;
    
    try {
        // 1. 从数据源获取连接
        con = txObject.getConnectionHolder().getConnection();
        
        // 2. 设置隔离级别
        if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
            int previousIsolationLevel = con.getTransactionIsolation();
            txObject.setPreviousIsolationLevel(previousIsolationLevel);
            con.setTransactionIsolation(definition.getIsolationLevel());
        }
        
        // 3. 关闭自动提交（开启事务的关键！）
        if (con.getAutoCommit()) {
            txObject.setMustRestoreAutoCommit(true);
            con.setAutoCommit(false);  // ★ 关闭自动提交 = 开启事务
        }
        
        // 4. 设置只读提示
        if (definition.isReadOnly()) {
            con.setReadOnly(true);
        }
        
        // 5. 将 ConnectionHolder 绑定到当前线程
        txObject.getConnectionHolder().setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(
                obtainDataSource(), txObject.getConnectionHolder());
        
    } catch (Exception ex) {
        // 异常处理
    }
}
```

**关键理解：** `con.setAutoCommit(false)` 就是开启数据库事务。之后的所有 SQL 操作都在同一个数据库连接的事务中执行，直到 `commit()` 或 `rollback()`。

## 五、提交与回滚

### 5.1 提交

```java
// TransactionAspectSupport.java
protected void commitTransactionAfterReturning(TransactionInfo txInfo) {
    if (txInfo != null && txInfo.getTransactionStatus() != null) {
        // ★ 提交事务
        txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
    }
}
```

```java
// AbstractPlatformTransactionManager.java
@Override
public final void commit(TransactionStatus status) {
    if (status.isCompleted()) {
        throw new IllegalTransactionStateException("Transaction is already completed ...");
    }
    
    // 局部回滚（如 TransactionTemplate 标记了 setRollbackOnly）
    if (status.isRollbackOnly()) {
        // ★ 标记了回滚 → 执行回滚而不是提交
        rollback(status);
        return;
    }
    
    // 正常提交
    processCommit(defStatus);
}
```

### 5.2 回滚

```java
// TransactionAspectSupport.java
protected void completeTransactionAfterThrowing(TransactionInfo txInfo, Throwable ex) {
    if (txInfo != null && txInfo.getTransactionStatus() != null) {
        // ★ 检查异常类型是否匹配 rollbackFor
        if (txInfo.transactionAttribute != null 
                && txInfo.transactionAttribute.rollbackOn(ex)) {
            // → 回滚
            txInfo.getTransactionManager().rollback(txInfo.getTransactionStatus());
        } else {
            // → 提交（异常不在 rollbackFor 范围内）
            txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
        }
    }
}
```

**rollbackOn 的逻辑：**

```java
// DefaultTransactionAttribute.java
@Override
public boolean rollbackOn(Throwable ex) {
    // 默认规则：
    // RuntimeException / Error → true（回滚）
    // Checked Exception → false（提交）
    return (ex instanceof RuntimeException || ex instanceof Error);
}

// RuleBasedTransactionAttribute.java
@Override
public boolean rollbackOn(Throwable ex) {
    // 如果有 rollbackFor / noRollbackFor 配置，按规则匹配
    RollbackRuleAttribute winner = getRollbackRule(ex);
    if (winner != null) {
        return winner instanceof RollbackRuleAttribute;  // rollbackFor → true, noRollbackFor → false
    }
    // 没有配置 → 使用默认规则
    return super.rollbackOn(ex);
}
```

### 5.3 DataSourceTransactionManager 的 doCommit / doRollback

```java
// DataSourceTransactionManager.java
@Override
protected void doCommit(DefaultTransactionStatus status) {
    DataSourceTransactionObject txObject = (DataSourceTransactionObject) status.getTransaction();
    Connection con = txObject.getConnectionHolder().getConnection();
    
    if (status.isDebug()) {
        logger.debug("Committing JDBC transaction on Connection [" + con + "]");
    }
    
    try {
        // ★ 真正的 JDBC 提交
        con.commit();
    } catch (SQLException ex) {
        throw new TransactionSystemException("Could not commit JDBC transaction", ex);
    }
}

@Override
protected void doRollback(DefaultTransactionStatus status) {
    DataSourceTransactionObject txObject = (DataSourceTransactionObject) status.getTransaction();
    Connection con = txObject.getConnectionHolder().getConnection();
    
    try {
        // ★ 真正的 JDBC 回滚
        con.rollback();
    } catch (SQLException ex) {
        throw new TransactionSystemException("Could not roll back JDBC transaction", ex);
    }
}
```

## 六、NESTED 传播行为的 Savepoint 实现

```java
// AbstractPlatformTransactionManager.java — NESTED 的实现
// 在 handleExistingTransaction 中：
if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
    if (useSavepointForNestedTransaction()) {
        // ★ 创建 JDBC Savepoint（JDBC 3.0 支持）
        DefaultTransactionStatus status = prepareTransactionStatus(...);
        status.createAndHoldSavepoint();
        return status;
    }
}

// 回滚时只回滚到 Savepoint
@Override
protected void doRollback(DefaultTransactionStatus status) {
    if (status.hasSavepoint()) {
        // ★ NESTED 回滚 → 回滚到 Savepoint，不中断外部事务
        status.rollbackToHeldSavepoint();
    } else {
        // 常规回滚
        doRollback(status);
    }
}

// 提交时 NESTED 只是释放 Savepoint
@Override
protected void doCommit(DefaultTransactionStatus status) {
    if (status.hasSavepoint()) {
        // ★ NESTED 提交 → 释放 Savepoint
        status.releaseHeldSavepoint();
    } else {
        doCommit(status);
    }
}
```

## 七、事务同步管理

Spring 通过 `TransactionSynchronizationManager` 管理每个线程的事务资源：

```java
// TransactionSynchronizationManager.java
public abstract class TransactionSynchronizationManager {
    
    // 当前线程绑定的数据源连接
    private static final ThreadLocal<Map<Object, Object>> resources =
            new NamedThreadLocal<>("Transactional resources");
    
    // 当前线程的事务同步器
    private static final ThreadLocal<Set<TransactionSynchronization>> synchronizations =
            new NamedThreadLocal<>("Transaction synchronizations");
    
    // 当前事务的名称
    private static final ThreadLocal<String> currentTransactionName =
            new NamedThreadLocal<>("Current transaction name");
    
    // 当前事务是否是只读
    private static final ThreadLocal<Boolean> currentTransactionReadOnly =
            new NamedThreadLocal<>("Current transaction read-only flag");
    
    // 当前事务的隔离级别
    private static final ThreadLocal<Integer> currentTransactionIsolationLevel =
            new NamedThreadLocal<>("Current transaction isolation level");
    
    // 当前事务是否是新建的
    private static final ThreadLocal<Boolean> actualTransactionActive =
            new NamedThreadLocal<>("Actual transaction active");
}
```

**`TransactionSynchronization` 接口提供了事务回调点：**

```java
// 实现 TransactionSynchronization 可以在事务生命周期中插入回调
public interface TransactionSynchronization {
    
    // 事务提交前
    void beforeCommit(boolean readOnly);
    
    // 事务完成前（提交或回滚前）
    void beforeCompletion();
    
    // 事务提交后
    void afterCommit();
    
    // 事务完成后（提交或回滚后）
    void afterCompletion(int status);
}
```

## 八、完整链路总结

```
@Transactional 标注的方法调用
    │
    ▼
AOP 代理的方法调用
    │
    ├── TransactionInterceptor.invoke()
    │     └── invokeWithinTransaction()
    │           │
    │           ├── 1. 获取 TransactionAttribute（@Transactional 的属性）
    │           ├── 2. 获取 PlatformTransactionManager
    │           │
    │           ├── 3. tm.getTransaction(txAttr)
    │           │     ├── doGetTransaction() — 获取当前事务状态
    │           │     ├── isExistingTransaction() — 检查是否已有事务
    │           │     ├── handleExistingTransaction() — 处理传播行为
    │           │     │     ├── REQUIRED → 加入/新建
    │           │     │     ├── REQUIRES_NEW → 挂起+新建
    │           │     │     └── NESTED → Savepoint
    │           │     └── doBegin() — con.setAutoCommit(false)
    │           │
    │           ├── 4. 目标方法执行
    │           │
    │           ├── 5a. 正常返回 → commit()
    │           │     └── con.commit()
    │           │
    │           └── 5b. 异常 → completeTransactionAfterThrowing()
    │                 └── rollbackOn() 检查 → con.rollback()
    │
    └── 返回结果
```

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `TransactionInterceptor` | 事务的 MethodInterceptor，拦截 @Transactional 方法 |
| `TransactionAspectSupport` | 模板方法，定义事务执行流程 |
| `AbstractPlatformTransactionManager` | 事务管理器基类，实现传播行为、Savepoint 等 |
| `DataSourceTransactionManager` | JDBC 事务的具体实现 |
| `AnnotationTransactionAttributeSource` | 解析 @Transactional 注解 |
| `RuleBasedTransactionAttribute` | 管理 rollbackFor 回滚规则 |
| `TransactionSynchronizationManager` | 线程级事务资源管理 |
| `TransactionSynchronization` | 事务生命周期回调接口 |

---

**上一篇：** [Spring 事务（一）：@Transactional 使用、传播行为与隔离级别]({{< relref "post/spring-transaction-usage" >}})
