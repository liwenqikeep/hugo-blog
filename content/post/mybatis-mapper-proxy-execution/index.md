---
title: "MyBatis（二）：Mapper 代理原理与 SQL 执行流程"
date: 2018-06-22
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "MapperProxy", "JDK代理", "Executor", "StatementHandler", "源码分析"]
toc: true
---

## 前言

上一篇我们追踪了 MyBatis 的配置解析和 SqlSessionFactory 的构建。当一切就绪后，最关键的两个问题是：

1. **Mapper 接口是如何被调用的？** —— 我们只写了接口没写实现类，MyBatis 怎么知道要做什么？
2. **SQL 是如何执行的？** —— 从 Java 方法到 JDBC Statement，中间经历了什么？

本文回答这两个问题。

<!--more-->

> **源码版本：** MyBatis 3.5.x

## 一、Mapper 代理原理

### 1.1 注册 Mapper 接口

在配置解析阶段，MyBatis 通过 `<mappers>` 或 `@MapperScan` 注册 Mapper 接口到 `MapperRegistry`：

```java
// Configuration.java
public <T> void addMapper(Class<T> type) {
    // ★ 将 Mapper 接口注册到 mapperRegistry
    mapperRegistry.addMapper(type);
}
```

```java
// MapperRegistry.java
public class MapperRegistry {
    
    private final Configuration config;
    // ★ 存储 Mapper 接口 → MapperProxyFactory 的映射
    private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<>();
    
    public <T> void addMapper(Class<T> type) {
        if (type.isInterface()) {
            if (hasMapper(type)) {
                throw new BindingException("...");
            }
            boolean loadCompleted = false;
            try {
                // ★ 为每个 Mapper 接口创建对应的 MapperProxyFactory
                knownMappers.put(type, new MapperProxyFactory<>(type));
                
                // 解析 Mapper 接口上的注解（如 @Select、@Insert）
                XMLMapperBuilder mapperParser = new XMLMapperBuilder(
                        type.getResource(), config, type.getName(), config.getSqlFragments());
                mapperParser.parse();
                loadCompleted = true;
            } finally {
                if (!loadCompleted) {
                    knownMappers.remove(type);
                }
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
        // ★ 从 knownMappers 获取工厂，创建代理
        final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
        if (mapperProxyFactory == null) {
            throw new BindingException("...");
        }
        return mapperProxyFactory.newInstance(sqlSession);
    }
}
```

### 1.2 MapperProxyFactory——创建代理

```java
// MapperProxyFactory.java
public class MapperProxyFactory<T> {
    
    private final Class<T> mapperInterface;       // Mapper 接口
    private final Map<Method, MapperMethodInvoker> methodCache = new ConcurrentHashMap<>();
    
    public MapperProxyFactory(Class<T> mapperInterface) {
        this.mapperInterface = mapperInterface;
    }
    
    public T newInstance(SqlSession sqlSession) {
        // ★ 创建 MapperProxy（InvocationHandler）
        MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);
        // ★ JDK 动态代理
        return Proxy.newProxyInstance(mapperInterface.getClassLoader(), 
                                       new Class[] { mapperInterface }, mapperProxy);
    }
}
```

### 1.3 MapperProxy——InvocationHandler

```java
// MapperProxy.java
public class MapperProxy<T> implements InvocationHandler, Serializable {
    
    private final SqlSession sqlSession;
    private final Class<T> mapperInterface;
    private final Map<Method, MapperMethodInvoker> methodCache;
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            // 1. 处理 Object 中的方法（toString、hashCode 等）
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, args);
            }
            
            // 2. 处理默认方法（JDK 8+ 接口中的 default 方法）
            if (method.isDefault()) {
                return invokeDefaultMethod(proxy, method, args);
            }
            
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
        
        // ★ 3. 从缓存获取或创建 MapperMethod
        MapperMethodInvoker invoker = cachedInvoker(method);
        // ★ 4. 执行 SQL
        return invoker.invoke(proxy, method, args, sqlSession);
    }
    
    private MapperMethodInvoker cachedInvoker(Method method) {
        return methodCache.computeIfAbsent(method, m -> {
            if (m.isDefault()) {
                return new DefaultMethodInvoker(getMethodHandleJava8(method));
            }
            // ★ 创建 MapperMethod，封装 SQL 执行逻辑
            return new PlainMethodInvoker(new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
        });
    }
}
```

### 1.4 MapperMethod——方法调用

`MapperMethod` 是映射方法调用与 SQL 执行的关键桥梁：

```java
// MapperMethod.java
public class MapperMethod {
    
    private final SqlCommand command;   // SQL 命令（statementId + 类型）
    private final MethodSignature method; // 方法签名（返回值类型、参数等）
    
    public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
        // 1. 解析 SQL 命令：从 Configuration 中查找对应的 MappedStatement
        this.command = new SqlCommand(config, mapperInterface, method);
        // 2. 解析方法签名：参数名、返回值类型等
        this.method = new MethodSignature(config, mapperInterface, method);
    }
    
    public Object execute(SqlSession sqlSession, Object[] args) {
        Object result;
        switch (command.getType()) {
            case INSERT: {
                // ★ 参数转换（多个参数 → Map）
                Object param = method.convertArgsToSqlCommandParam(args);
                result = sqlSession.insert(command.getName(), param);
                break;
            }
            case UPDATE: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = sqlSession.update(command.getName(), param);
                break;
            }
            case DELETE: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = sqlSession.delete(command.getName(), param);
                break;
            }
            case SELECT: {
                // ★ 根据返回值类型选择不同的查询方式
                if (method.returnsVoid() && method.hasResultHandler()) {
                    executeWithResultHandler(sqlSession, args);
                    result = null;
                } else if (method.returnsMany()) {
                    result = sqlSession.selectList(command.getName(), getParams(args));
                } else if (method.returnsMap()) {
                    result = sqlSession.selectMap(command.getName(), getParams(args), method.getMapKey());
                } else if (method.returnsCursor()) {
                    result = sqlSession.selectCursor(command.getName(), getParams(args));
                } else {
                    result = sqlSession.selectOne(command.getName(), getParams(args));
                    if (method.returnsOptional()
                            && (result == null || !method.getReturnType().equals(result.getClass()))) {
                        result = Optional.ofNullable(result);
                    }
                }
                break;
            }
            default:
                throw new BindingException("Unknown execution method for: " + command.getName());
        }
        return result;
    }
}
```

---

## 二、参数转换

### 2.1 多参数处理

```java
// Mapper 接口方法
User findByNameAndEmail(@Param("name") String name, @Param("email") String email);
```

MyBatis 将多个参数包装为一个 Map：

```java
// ParamNameResolver.java
public Object getNamedParams(Object[] args) {
    final int paramCount = names.size();
    if (args == null || paramCount == 0) {
        return null;
    }
    if (paramCount == 1) {
        // ★ 只有一个参数 → 直接返回参数值
        Object value = args[names.firstKey()];
        if (!names.hasDefaultParamName() || (value == null)) {
            return value;
        }
        // @Param 或 -parameters 编译选项存在 → 包装为 Map
    }
    
    // ★ 多个参数 → 包装为 Map（key 为 @Param 值或 param1/param2）
    final Map<String, Object> param = new ParamMap<>();
    for (Map.Entry<Integer, String> entry : names.entrySet()) {
        param.put(entry.getValue(), args[entry.getKey()]);
        // 同时保留 param1, param2 兼容性
        param.put("param" + entry.getKey(), args[entry.getKey()]);
    }
    return param;
}
```

---

## 三、SQL 执行流程

### 3.1 总体链路

```
MapperProxy.invoke()
    │
    ▼
MapperMethod.execute()
    │
    ▼
SqlSession.selectList() / insert() / update() / delete()
    │
    ▼
    ┌── 一级缓存命中？──→ 直接返回
    │   No
    ▼
Executor.query()
    │
    ▼
Executor.doQuery()
    │
    ├── StatementHandler.prepare()     → 创建 Statement
    ├── StatementHandler.parameterize() → ParameterHandler.setParameters()
    ├── StatementHandler.query()        → Statement.execute()
    └── ResultSetHandler.handleResultSets() → 封装结果
```

### 3.2 Executor 接口

```java
public interface Executor {
    
    // 查询
    <E> List<E> query(MappedStatement ms, Object parameter, 
                      RowBounds rowBounds, ResultHandler resultHandler);
    
    // 更新（也处理 insert/delete）
    int update(MappedStatement ms, Object parameter);
    
    // 事务
    void commit(boolean required);
    void rollback(boolean required);
    
    // 清理一级缓存
    void clearLocalCache();
}
```

**三种 Executor 类型：**

| 类型 | 说明 | 配置方式 |
|------|------|---------|
| `SimpleExecutor` | 每次执行都创建新的 Statement（默认）| `defaultExecutorType=SIMPLE` |
| `ReuseExecutor` | 缓存 Statement，重复使用 | `defaultExecutorType=REUSE` |
| `BatchExecutor` | 批量执行，一次性提交 | `defaultExecutorType=BATCH` |

### 3.3 创建 Executor

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
        executor = new SimpleExecutor(this, transaction);  // ★ 默认
    }
    
    // ★ 如果开启了二级缓存（cacheEnabled=true），用 CachingExecutor 装饰
    if (cacheEnabled) {
        executor = new CachingExecutor(executor);
    }
    
    // ★ 应用插件（Interceptor）
    executor = (Executor) interceptorChain.pluginAll(executor);
    
    return executor;
}
```

### 3.4 SimpleExecutor.doQuery()

```java
// SimpleExecutor.java
public class SimpleExecutor extends BaseExecutor {
    
    @Override
    public <E> List<E> doQuery(MappedStatement ms, Object parameter,
                                RowBounds rowBounds, ResultHandler resultHandler,
                                BoundSql boundSql) {
        Statement stmt = null;
        try {
            // 1. 获取配置
            Configuration configuration = ms.getConfiguration();
            
            // 2. ★ 创建 StatementHandler（RoutingStatementHandler 包装具体实现）
            StatementHandler handler = configuration.newStatementHandler(
                    this, ms, parameter, rowBounds, resultHandler, boundSql);
            
            // 3. ★ prepare：创建 JDBC Statement 并设置参数
            stmt = prepareStatement(handler, ms.getStatementLog());
            
            // 4. ★ query：执行 SQL 并处理结果
            return handler.query(stmt, resultHandler);
        } finally {
            closeStatement(stmt);
        }
    }
    
    private Statement prepareStatement(StatementHandler handler, Log statementLog) {
        Connection connection = getConnection(statementLog);
        // ★ 创建 Statement（PreparedStatement 或普通 Statement）
        Statement stmt = handler.prepare(connection, transaction.getTimeout());
        // ★ 设置参数（将 Java 参数设置到 PreparedStatement 中）
        handler.parameterize(stmt);
        return stmt;
    }
}
```

### 3.5 StatementHandler 体系

```java
public interface StatementHandler {
    
    // ★ 创建 Statement（JDBC Connection.createStatement / prepareStatement）
    Statement prepare(Connection connection, Integer transactionTimeout);
    
    // ★ 设置参数（委托给 ParameterHandler）
    void parameterize(Statement statement);
    
    // ★ 执行查询（ResultSet 委托给 ResultSetHandler 处理）
    <E> List<E> query(Statement statement, ResultHandler resultHandler);
    
    // 执行更新
    int update(Statement statement);
    
    // 获取 BoundSql
    BoundSql getBoundSql();
    
    // 获取 ParameterHandler
    ParameterHandler getParameterHandler();
}
```

**RoutingStatementHandler——路由到具体实现：**

```java
public class RoutingStatementHandler implements StatementHandler {
    
    private final StatementHandler delegate;
    
    public RoutingStatementHandler(Executor executor, MappedStatement ms,
                                    Object parameter, RowBounds rowBounds,
                                    ResultHandler resultHandler, BoundSql boundSql) {
        switch (ms.getStatementType()) {
            case STATEMENT:
                delegate = new SimpleStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);
                break;
            case PREPARED:
                // ★ 默认使用 PreparedStatement
                delegate = new PreparedStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);
                break;
            case CALLABLE:
                delegate = new CallableStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);
                break;
        }
    }
    
    // 委托给具体的 delegate
    @Override public Statement prepare(...) { return delegate.prepare(connection, timeout); }
    @Override public void parameterize(...) { delegate.parameterize(stmt); }
    @Override public <E> List<E> query(...) { return delegate.query(stmt, rh); }
}
```

### 3.6 PreparedStatementHandler

```java
// PreparedStatementHandler.java
public class PreparedStatementHandler extends BaseStatementHandler {
    
    @Override
    public Statement prepare(Connection connection, Integer transactionTimeout) {
        // ★ 创建 PreparedStatement（含 SQL）
        return connection.prepareStatement(boundSql.getSql());
    }
    
    @Override
    public void parameterize(Statement statement) {
        // ★ 委托给 ParameterHandler 设置参数
        parameterHandler.setParameters((PreparedStatement) statement);
    }
    
    @Override
    public <E> List<E> query(Statement statement, ResultHandler resultHandler) {
        // ★ 执行 SQL
        PreparedStatement ps = (PreparedStatement) statement;
        ps.execute();
        // ★ 委托给 ResultSetHandler 处理结果
        return resultSetHandler.handleResultSets(ps);
    }
    
    @Override
    public int update(Statement statement) {
        PreparedStatement ps = (PreparedStatement) statement;
        ps.execute();
        return ps.getUpdateCount();
    }
}
```

---

## 四、ParameterHandler

```java
// DefaultParameterHandler.java
public class DefaultParameterHandler implements ParameterHandler {
    
    private final TypeHandlerRegistry typeHandlerRegistry;
    private final MappedStatement mappedStatement;
    private final Object parameterObject;
    private final BoundSql boundSql;
    
    @Override
    public void setParameters(PreparedStatement ps) {
        // ★ 从 BoundSql 中获取参数映射信息
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        
        if (parameterMappings != null) {
            for (int i = 0; i < parameterMappings.size(); i++) {
                ParameterMapping parameterMapping = parameterMappings.get(i);
                
                // 跳过输出参数（存储过程 OUT 参数）
                if (parameterMapping.getMode() != ParameterMode.OUT) {
                    Object value;
                    // 获取参数值
                    String propertyName = parameterMapping.getProperty();
                    if (boundSql.hasAdditionalParameter(propertyName)) {
                        value = boundSql.getAdditionalParameter(propertyName);
                    } else if (parameterObject == null) {
                        value = null;
                    } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                        value = parameterObject;
                    } else {
                        // ★ 复杂对象 → 通过 MetaObject 获取属性值
                        MetaObject metaObject = configuration.newMetaObject(parameterObject);
                        value = metaObject.getValue(propertyName);
                    }
                    
                    // ★ 通过 TypeHandler 设置参数
                    TypeHandler typeHandler = parameterMapping.getTypeHandler();
                    JdbcType jdbcType = parameterMapping.getJdbcType();
                    typeHandler.setParameter(ps, i + 1, value, jdbcType);
                }
            }
        }
    }
}
```

---

## 五、ResultSetHandler

```java
// DefaultResultSetHandler.java
public class DefaultResultSetHandler implements ResultSetHandler {
    
    @Override
    public List<Object> handleResultSets(Statement stmt) {
        List<Object> multipleResults = new ArrayList<>();
        int resultSetCount = 0;
        
        // 1. 获取第一个 ResultSet
        ResultSetWrapper rsw = new ResultSetWrapper(stmt.getResultSet(), configuration);
        
        // 2. 获取 ResultMap（可能有多个 —— 多结果集）
        List<ResultMap> resultMaps = mappedStatement.getResultMaps();
        
        while (rsw != null && resultMaps.size() > resultSetCount) {
            ResultMap resultMap = resultMaps.get(resultSetCount);
            
            // ★ 处理 ResultSet → Java 对象
            handleResultSet(rsw, resultMap, multipleResults, null);
            
            // 处理下一个 ResultSet
            rsw = getNextResultSet(stmt);
            resultSetCount++;
        }
        
        // 3. 处理多结果集合并
        return collapseSingleResultList(multipleResults);
    }
    
    private void handleRowValue(ResultSetWrapper rsw, ResultMap resultMap, 
                                 Object resultObject, String columnPrefix, ResultMapping parentMapping) {
        // ★ 获取已加载的列
        final List<String> columns = rsw.getColumnNames();
        
        // ★ 遍历 ResultMapping
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            // 获取 TypeHandler
            TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
            
            // ★ 通过 TypeHandler 从 ResultSet 读取数据
            Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
            
            // ★ 通过反射设置到目标对象的属性
            metaObject.setValue(resultMapping.getProperty(), value);
        }
    }
}
```

---

## 六、类型转换（TypeHandler）

```java
// TypeHandler 接口
public interface TypeHandler<T> {
    
    // Java → JDBC（设置参数）
    void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType);
    
    // JDBC → Java（读取结果）
    T getResult(ResultSet rs, String columnName);
    
    T getResult(ResultSet rs, int columnIndex);
    
    T getResult(CallableStatement cs, int columnIndex);
}

// 常用内置 TypeHandler
// IntegerTypeHandler、StringTypeHandler、DateTypeHandler 等
// 自定义 TypeHandler：继承 BaseTypeHandler

public class JsonTypeHandler extends BaseTypeHandler<Map<String, Object>> {
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, 
                                     Map<String, Object> parameter, JdbcType jdbcType) {
        ps.setString(i, MAPPER.writeValueAsString(parameter));  // Map → JSON String
    }
    
    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, String columnName) {
        String json = rs.getString(columnName);
        return MAPPER.readValue(json, Map.class);  // JSON String → Map
    }
}
```

---

## 七、总结

### Mapper 代理完整链路

```
MyBatis 启动：MapperRegistry.addMapper(UserMapper.class)
    → 创建 MapperProxyFactory<UserMapper>
    
运行时：@Autowired UserMapper userMapper
    → SqlSession.getMapper(UserMapper.class)
      → MapperProxyFactory.newInstance(sqlSession)
        → Proxy.newProxyInstance(MapperProxy)
        
调用：userMapper.findByName("Tom")
    → MapperProxy.invoke()
      → MapperMethod.execute()
        → sqlSession.selectList("com.example.UserMapper.findByName", params)
          → Executor.query()
```

### SQL 执行四大组件

```
Executor        → 执行器（调度 StatementHandler、管理缓存）
StatementHandler → SQL 处理器（创建 Statement、管理执行流程）
ParameterHandler → 参数处理器（Java → JDBC 参数）
ResultSetHandler → 结果处理器（JDBC ResultSet → Java 对象）
```

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `MapperRegistry` | 注册 Mapper 接口，管理 MapperProxyFactory |
| `MapperProxyFactory` | 为 Mapper 接口创建 JDK 动态代理 |
| `MapperProxy` | InvocationHandler，拦截方法调用 |
| `MapperMethod` | 封装方法到 SQL 执行的映射 |
| `ParamNameResolver` | 处理多参数到 Map 的转换 |
| `SimpleExecutor` | 默认执行器，每次新创建 Statement |
| `CachingExecutor` | 二级缓存装饰器 |
| `RoutingStatementHandler` | 路由到具体 StatementHandler |
| `PreparedStatementHandler` | 处理 PreparedStatement |
| `DefaultParameterHandler` | 参数设置到 PreparedStatement |
| `DefaultResultSetHandler` | ResultSet 到 Java 对象映射 |
| `TypeHandler` | Java 类型 ↔ JDBC 类型转换 |

---

**上一篇：** [MyBatis（一）：核心架构与配置]({{< relref "post/mybatis-architecture" >}})

**下一篇：** [MyBatis（三）：动态 SQL 深度解析]({{< relref "post/mybatis-dynamic-sql" >}})
