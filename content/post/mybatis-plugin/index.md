---
title: "MyBatis 插件机制：Interceptor 原理与自定义插件"
date: 2018-06-28
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "插件", "Interceptor", "PageHelper", "@Intercepts", "源码分析"]
toc: true
---

## 前言

MyBatis 插件机制允许在 SQL 执行的四个核心组件（Executor、StatementHandler、ParameterHandler、ResultSetHandler）的方法调用前后插入自定义逻辑。这为分页、性能监控、SQL 审计、数据脱敏等功能提供了统一的扩展点。

本文从使用到源码，完整覆盖 MyBatis 插件的实现原理。

<!--more-->

> **源码版本：** MyBatis 3.5.x

## 一、插件机制概述

### 1.1 可拦截的接口

MyBatis 插件可以拦截以下四个核心接口的方法：

| 接口 | 作用 | 可拦截的常用方法 |
|------|------|-----------------|
| **Executor** | SQL 执行器 | query, update, commit, rollback |
| **StatementHandler** | JDBC Statement 处理 | prepare, parameterize, query, update |
| **ParameterHandler** | 参数设置 | setParameters |
| **ResultSetHandler** | 结果集映射 | handleResultSets, handleOutputParameters |

### 1.2 插件拦截原理

```
原始对象 (Executor/StatementHandler 等)
    │
    ▼
InterceptorChain.pluginAll(target)
    │
    ▼
target = interceptor.plugin(target)   ← 每个插件依次代理
    │
    ▼
返回代理对象 (JDK 动态代理)
    │
    ▼
方法调用 → 代理对象 → 匹配方法 → interceptor.intercept()

链式代理（多个插件时）：
原始对象 → 插件3代理 → 插件2代理 → 插件1代理 → 调用
```

---

## 二、插件开发

### 2.1 核心接口

```java
public interface Interceptor {
    
    // ★ 拦截逻辑（在目标方法执行前后插入代码）
    Object intercept(Invocation invocation) throws Throwable;
    
    // ★ 包装目标对象（返回代理对象）
    default Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }
    
    // 设置插件参数
    default void setProperties(Properties properties) {}
}
```

### 2.2 @Intercepts 与 @Signature

```java
@Intercepts({
    @Signature(
        type = Executor.class,          // 拦截的接口
        method = "query",               // 拦截的方法名
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}  // 参数类型
    ),
    @Signature(
        type = Executor.class,
        method = "update",
        args = {MappedStatement.class, Object.class}
    )
})
public class ExamplePlugin implements Interceptor {
    // ...
}
```

### 2.3 完整插件示例

```java
@Intercepts({
    @Signature(
        type = Executor.class,
        method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
    ),
    @Signature(
        type = Executor.class,
        method = "update",
        args = {MappedStatement.class, Object.class}
    )
})
public class SqlLogPlugin implements Interceptor {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 1. 获取目标方法的参数
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs()[1];
        
        // 2. 获取执行的 SQL
        BoundSql boundSql = ms.getBoundSql(parameter);
        String sql = boundSql.getSql();
        
        long start = System.currentTimeMillis();
        try {
            // ★ 3. 执行目标方法
            Object result = invocation.proceed();
            return result;
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            log.info("SQL: [{}] 耗时: {}ms", sql, elapsed);
        }
    }
    
    @Override
    public Object plugin(Object target) {
        // ★ 使用 MyBatis 提供的 Plugin 工具类生成代理
        return Plugin.wrap(target, this);
    }
    
    @Override
    public void setProperties(Properties properties) {
        // 读取插件配置参数
        String maxLogLength = properties.getProperty("maxLogLength", "1000");
        System.out.println("maxLogLength = " + maxLogLength);
    }
}
```

### 2.4 注册插件

```xml
<!-- mybatis-config.xml -->
<plugins>
    <plugin interceptor="com.example.plugin.SqlLogPlugin">
        <property name="maxLogLength" value="500"/>
    </plugin>
    <plugin interceptor="com.github.pagehelper.PageInterceptor">
        <property name="helperDialect" value="mysql"/>
        <property name="reasonable" value="true"/>
    </plugin>
</plugins>
```

```java
// Spring Boot 方式
@Configuration
public class MyBatisConfig {
    
    @Bean
    public SqlLogPlugin sqlLogPlugin() {
        SqlLogPlugin plugin = new SqlLogPlugin();
        Properties props = new Properties();
        props.setProperty("maxLogLength", "500");
        plugin.setProperties(props);
        return plugin;
    }
}
```

---

## 三、Plugin.wrap——代理生成源码

MyBatis 提供了 `Plugin` 工具类，通过 JDK 动态代理自动处理签名匹配。

```java
// Plugin.java
public class Plugin implements InvocationHandler {
    
    private final Object target;          // 被代理的目标对象
    private final Interceptor interceptor; // 拦截器
    private final Map<Class<?>, Set<Method>> signatureMap;  // 缓存拦截的方法
    
    private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
        this.target = target;
        this.interceptor = interceptor;
        this.signatureMap = signatureMap;
    }
    
    // ★ 包装目标对象（静态方法）
    public static Object wrap(Object target, Interceptor interceptor) {
        // 1. 解析 @Intercepts 注解，构建签名映射
        Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
        
        // 2. 获取目标对象的 Class
        Class<?> type = target.getClass();
        
        // 3. 查找目标类实现了哪些可拦截的接口（Executor/StatementHandler 等）
        Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
        
        if (interfaces.length > 0) {
            // ★ 4. 创建 JDK 动态代理
            return Proxy.newProxyInstance(
                    type.getClassLoader(),
                    interfaces,
                    new Plugin(target, interceptor, signatureMap));
        }
        
        return target;  // 没有需要拦截的接口 → 直接返回原对象
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            // ★ 检查当前方法是否需要被拦截
            Set<Method> methods = signatureMap.get(method.getDeclaringClass());
            if (methods != null && methods.contains(method)) {
                // ★ 调用拦截器的 intercept 方法
                return interceptor.intercept(new Invocation(target, method, args));
            }
            // 不需要拦截 → 直接调用原方法
            return method.invoke(target, args);
        } catch (Exception e) {
            throw ExceptionUtil.unwrapThrowable(e);
        }
    }
    
    // 解析 @Intercepts 注解
    private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
        Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
        if (interceptsAnnotation == null) {
            throw new PluginException("...");
        }
        
        Signature[] sigs = interceptsAnnotation.value();
        Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
        
        for (Signature sig : sigs) {
            // 根据 @Signature 中的 type、method、args 找到对应的方法
            Set<Method> methods = signatureMap.computeIfAbsent(sig.type(), k -> new HashSet<>());
            try {
                Method method = sig.type().getMethod(sig.method(), sig.args());
                methods.add(method);
            } catch (NoSuchMethodException e) {
                throw new PluginException("...");
            }
        }
        return signatureMap;
    }
    
    // 查找目标对象实现了哪些可拦截的接口
    private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
        Set<Class<?>> interfaces = new HashSet<>();
        while (type != null) {
            for (Class<?> c : type.getInterfaces()) {
                if (signatureMap.containsKey(c)) {
                    interfaces.add(c);
                }
            }
            type = type.getSuperclass();
        }
        return interfaces.toArray(new Class<?>[0]);
    }
}
```

### Invocation 对象

```java
// Invocation.java
public class Invocation {
    
    private final Object target;      // 目标对象
    private final Method method;      // 被拦截的方法
    private final Object[] args;      // 方法参数
    
    public Invocation(Object target, Method method, Object[] args) {
        this.target = target;
        this.method = method;
        this.args = args;
    }
    
    public Object getTarget() { return target; }
    public Method getMethod() { return method; }
    public Object[] getArgs() { return args; }
    
    // ★ 执行目标方法（在拦截器中调用）
    public Object proceed() throws InvocationTargetException, IllegalAccessException {
        return method.invoke(target, args);
    }
}
```

---

## 四、拦截器链的执行

### 4.1 InterceptorChain

多个插件形成一条链，依次代理目标对象：

```java
// InterceptorChain.java
public class InterceptorChain {
    
    private final List<Interceptor> interceptors = new ArrayList<>();
    
    // ★ 将目标对象通过所有插件依次包装
    public Object pluginAll(Object target) {
        for (Interceptor interceptor : interceptors) {
            target = interceptor.plugin(target);  // 每个插件包装一次
        }
        return target;
    }
    
    public void addInterceptor(Interceptor interceptor) {
        interceptors.add(interceptor);
    }
    
    public List<Interceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }
}
```

### 4.2 在四大组件中注册

在 `Configuration.newExecutor()`、`Configuration.newStatementHandler()` 等方法中调用 `pluginAll()`：

```java
// Configuration.java
public Executor newExecutor(Transaction transaction, ExecutorType executorType) {
    executorType = executorType == null ? defaultExecutorType : executorType;
    executorType = executorType == null ? ExecutorType.SIMPLE : executorType;
    
    Executor executor;
    if (ExecutorType.BATCH == executorType) {
        executor = new BatchExecutor(this, transaction);
    } else if (ExecutorType.REUSE == executorType) {
        executor = new ReuseExecutor(this, transaction);
    } else {
        executor = new SimpleExecutor(this, transaction);
    }
    
    if (cacheEnabled) {
        executor = new CachingExecutor(executor);
    }
    
    // ★ 应用插件
    executor = (Executor) interceptorChain.pluginAll(executor);
    
    return executor;
}

public StatementHandler newStatementHandler(Executor executor, MappedStatement ms,
                                             Object parameter, RowBounds rowBounds,
                                             ResultHandler resultHandler, BoundSql boundSql) {
    StatementHandler statementHandler = new RoutingStatementHandler(
            executor, ms, parameter, rowBounds, resultHandler, boundSql);
    // ★ 应用插件
    statementHandler = (StatementHandler) interceptorChain.pluginAll(statementHandler);
    return statementHandler;
}

public ParameterHandler newParameterHandler(MappedStatement ms, Object parameterObject,
                                             BoundSql boundSql) {
    ParameterHandler parameterHandler = new DefaultParameterHandler(ms, parameterObject, boundSql);
    // ★ 应用插件
    parameterHandler = (ParameterHandler) interceptorChain.pluginAll(parameterHandler);
    return parameterHandler;
}

public ResultSetHandler newResultSetHandler(Executor executor, MappedStatement ms,
                                             RowBounds rowBounds, ParameterHandler parameterHandler,
                                             ResultHandler resultHandler, BoundSql boundSql) {
    ResultSetHandler resultSetHandler = new DefaultResultSetHandler(executor, ms, parameterHandler,
            resultHandler, boundSql, rowBounds);
    // ★ 应用插件
    resultSetHandler = (ResultSetHandler) interceptorChain.pluginAll(resultSetHandler);
    return resultSetHandler;
}
```

### 4.3 多个插件的执行顺序

```xml
<!-- 注册顺序：插件A → 插件B → 插件C -->
<plugins>
    <plugin interceptor="com.example.PluginA"/>
    <plugin interceptor="com.example.PluginB"/>
    <plugin interceptor="com.example.PluginC"/>
</plugins>
```

**代理链构建顺序：**

```
原始 Executor
  → PluginC.wrap() → 生成 C 代理
    → PluginB.wrap() → 生成 B 代理
      → PluginA.wrap() → 生成 A 代理
```

**方法调用顺序（A → B → C → 原始 → C → B → A）：**

```
PluginA.intercept()
  └── PluginA.proceed()
        └── PluginB.intercept()
              └── PluginB.proceed()
                    └── PluginC.intercept()
                          └── PluginC.proceed()
                                └── Executor.query()（原始）
```

---

## 五、PageHelper 原理简析

PageHelper 是最常用的 MyBatis 分页插件。其核心原理：

```java
// PageHelper 拦截 Executor.query()，在 SQL 执行前自动添加 limit 语句

@Intercepts({
    @Signature(
        type = Executor.class,
        method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
    )
})
public class PageInterceptor implements Interceptor {
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 1. 从线程缓存中获取当前线程的分页参数
        Page page = PageContext.getPage();
        
        if (page == null) {
            // 没有分页参数 → 直接执行
            return invocation.proceed();
        }
        
        // 2. 获取 MappedStatement 和 BoundSql
        Object[] args = invocation.getArgs();
        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args[1];
        BoundSql boundSql = ms.getBoundSql(parameter);
        
        // 3. 自动生成 count 查询，获取总记录数
        if (page.isCount()) {
            String countSql = "SELECT COUNT(1) FROM (" + boundSql.getSql() + ") tmp";
            // ... 执行 count 查询
            page.setTotal(countResult);
        }
        
        // 4. 修改原始 SQL，追加 LIMIT 语句
        String pageSql = boundSql.getSql() + " LIMIT " + page.getStart() + ", " + page.getPageSize();
        
        // ★ 通过反射修改 BoundSql 中的 SQL
        ReflectionUtil.setFieldValue(boundSql, "sql", pageSql);
        
        // 5. 执行修改后的 SQL
        return invocation.proceed();
    }
}
```

---

## 六、常用插件场景

### 6.1 性能监控

```java
@Intercepts({
    @Signature(type = Executor.class, method = "query", args = {...}),
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class PerformancePlugin implements Interceptor {
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        String methodName = ms.getId();
        
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (elapsed > 1000) {  // 超过 1 秒告警
                log.warn("慢查询: {} 耗时: {}ms", methodName, elapsed);
            }
        }
    }
}
```

### 6.2 数据脱敏

```java
@Intercepts({
    @Signature(type = ResultSetHandler.class, method = "handleResultSets", 
               args = {Statement.class})
})
public class DataMaskPlugin implements Interceptor {
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        
        // 对返回结果中的敏感字段做脱敏处理
        if (result instanceof List) {
            for (Object item : (List<?>) result) {
                if (item instanceof UserVO) {
                    UserVO user = (UserVO) item;
                    user.setPhone(maskPhone(user.getPhone()));
                    user.setEmail(maskEmail(user.getEmail()));
                }
            }
        }
        return result;
    }
    
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }
}
```

### 6.3 SQL 重写

```java
@Intercepts({
    @Signature(type = StatementHandler.class, method = "prepare", 
               args = {Connection.class, Integer.class})
})
public class SqlRewritePlugin implements Interceptor {
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        BoundSql boundSql = handler.getBoundSql();
        
        // 自动添加逻辑删除条件
        String sql = boundSql.getSql();
        if (!sql.contains("deleted") && handler instanceof PreparedStatementHandler) {
            if (sql.toUpperCase().contains("SELECT")) {
                ReflectionUtil.setFieldValue(boundSql, "sql", sql + " AND deleted = 0");
            }
        }
        
        return invocation.proceed();
    }
}
```

---

## 七、插件开发注意事项

### 7.1 不要随意拦截 StatementHandler.prepare()

```java
// ❌ 不推荐拦截 prepare()——它会改变 Statement 的创建流程
// 可能导致意外的副作用（Statement 类型变化、连接问题等）

// ✅ 推荐拦截的范围
// Executor.query/update — 适合分页、审计、慢查询
// StatementHandler.parameterize — 适合参数修改
// ResultSetHandler.handleResultSets — 适合结果加工
```

### 7.2 多个插件的顺序依赖

```java
// 如果插件B依赖插件A处理后的数据，需要控制注册顺序
@Bean
public SqlLogPlugin sqlLogPlugin() { return new SqlLogPlugin(); }

@Bean
public PerformancePlugin performancePlugin() { return new PerformancePlugin(); }

// 可以给插件类加 @Order 控制顺序
```

### 7.3 修改 BoundSql 需谨慎

```java
// 如果通过反射修改 BoundSql.sql 字段
// 需要确保也修改 ParameterMapping，否则参数设置可能会错位
Field sqlField = BoundSql.class.getDeclaredField("sql");
sqlField.setAccessible(true);
sqlField.set(boundSql, newSql);
```

---

## 八、总结

### 插件机制核心流程

```
1. 启动时注册 Interceptor → InterceptorChain
2. 创建四大组件时：pluginAll(target)
   → 每个 Interceptor.plugin(target)
     → Plugin.wrap(target, interceptor)
       → Proxy.newProxyInstance 创建代理
3. 方法调用 → 代理 invoke
   → Plugin.invoke()
     → 检查 signatureMap 是否匹配
       → 匹配 → interceptor.intercept(new Invocation(target, method, args))
       → 不匹配 → method.invoke(target, args)
```

### 可拦截的四大组件

| 组件 | 常用拦截方法 | 典型插件用途 |
|------|------------|------------|
| Executor | query, update, commit | 分页、审计、慢查询监控 |
| StatementHandler | prepare, parameterize | SQL 重写、参数加密 |
| ParameterHandler | setParameters | 参数脱敏 |
| ResultSetHandler | handleResultSets | 结果加密、数据脱敏 |

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `Interceptor` | 插件接口，定义 intercept/plugin/setProperties |
| `Invocation` | 封装目标方法调用的上下文 |
| `Plugin` | 动态代理核心，处理 @Intercepts 匹配 |
| `InterceptorChain` | 插件链，依次包装目标对象 |
| `Configuration` | 创建四大组件时调用 pluginAll |

---

**关联阅读：**
- [MyBatis（二）：Mapper 代理原理与 SQL 执行流程]({{< relref "post/mybatis-mapper-proxy-execution" >}})
- [MyBatis（四）：缓存机制与源码解析]({{< relref "post/mybatis-cache" >}})
