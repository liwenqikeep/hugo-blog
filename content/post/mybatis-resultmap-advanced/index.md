---
title: "MyBatis 结果映射进阶（二）：级联查询、鉴别器与延迟加载源码"
date: 2018-07-02
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "延迟加载", "LazyLoad", "级联查询", "鉴别器", "DefaultResultSetHandler", "源码分析"]
toc: true
---

## 前言

上一篇覆盖了 resultMap 的各种配置方式。本文深入到 `DefaultResultSetHandler` 的源码，完整追踪 MyBatis 如何将 JDBC ResultSet 的一行行数据转化为复杂的 Java 对象图——包括级联对象、集合、鉴别器和延迟加载的实现机制。

<!--more-->

> **源码版本：** MyBatis 3.5.x

## 一、DefaultResultSetHandler 核心流程

### 1.1 入口：handleResultSets

```java
// DefaultResultSetHandler.java
@Override
public List<Object> handleResultSets(Statement stmt) throws SQLException {
    List<Object> multipleResults = new ArrayList<>();
    int resultSetCount = 0;
    
    // 1. 获取第一个 ResultSet
    ResultSetWrapper rsw = new ResultSetWrapper(stmt.getResultSet(), configuration);
    
    // 2. 获取 MappedStatement 中配置的 ResultMap
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    
    while (rsw != null && resultMaps.size() > resultSetCount) {
        ResultMap resultMap = resultMaps.get(resultSetCount);
        
        // ★ 核心方法：处理 ResultSet 到 Java 对象的映射
        handleResultSet(rsw, resultMap, multipleResults, null);
        
        // 处理多结果集（下一个 ResultSet）
        rsw = getNextResultSet(stmt);
        resultSetCount++;
    }
    
    // 3. 如果只有一个结果，直接返回
    return collapseSingleResultList(multipleResults);
}
```

### 1.2 handleResultSet——逐行处理

```java
// DefaultResultSetHandler.java
private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap,
                              List<Object> multipleResults, ResultMapping parentMapping) {
    
    if (resultMap.hasNestedResultMaps()) {
        // ★ 有嵌套结果映射（association/collection 的嵌套结果方式）
        // 需要处理多行合并（同一部门的多行用户数据合到一个集合中）
        handleRowValuesForNestedResultMap(rsw, resultMap, multipleResults, parentMapping);
    } else {
        // ★ 简单映射（无关联对象嵌套）
        handleRowValuesForSimpleResultMap(rsw, resultMap, multipleResults, parentMapping);
    }
}
```

### 1.3 简单映射的逐行处理

```java
// DefaultResultSetHandler.java
private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap,
                                                List<Object> results, ResultMapping parentMapping) {
    
    while (rsw.getResultSet().next()) {
        // ★ 对每一行结果创建一个对象
        Object rowValue = getRowValue(rsw, resultMap, null);
        storeObject(results, null, rowValue);
    }
}
```

### 1.4 嵌套结果的多行合并

这是处理 `collection` 和 `association` 嵌套结果的核心逻辑：

```java
// DefaultResultSetHandler.java
private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap,
                                                List<Object> results, ResultMapping parentMapping) {
    
    // ★ 缓存：用于跨行合并（同一个 Department 对象只创建一次）
    final DefaultMap<Object, Object> uniqueKeys = new DefaultHashMap<>();
    
    while (rsw.getResultSet().next()) {
        // 1. 获取当前行对应的对象
        Object rowValue = getRowValue(rsw, resultMap, null);
        
        if (rowValue != null) {
            // 2. ★ 生成唯一键（根据 <id> 元素）
            CacheKey uniqueKey = createUniqueKey(rsw, resultMap, null);
            
            // 3. 检查该唯一键是否已存在
            Object existingObject = uniqueKeys.get(uniqueKey);
            if (existingObject == null) {
                // ★ 新对象 → 保存
                uniqueKeys.put(uniqueKey, rowValue);
                results.add(rowValue);
            } else {
                // ★ 已存在 → 合并当前行的数据到已有对象中
                // （将当前行的 User 数据加入已有 Department 的 users 集合）
                mergeRowValue(rowValue, existingObject, resultMap);
            }
        }
    }
}
```

---

## 二、延迟加载（Lazy Loading）

### 2.1 配置延迟加载

```xml
<settings>
    <!-- 开启延迟加载（默认 false） -->
    <setting name="lazyLoadingEnabled" value="true"/>
    
    <!-- 积极加载（默认 false）：当开启时，访问一个懒加载属性会加载所有懒加载属性 -->
    <setting name="aggressiveLazyLoading" value="false"/>
    
    <!-- 延迟加载触发方法（默认值） -->
    <setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/>
</settings>
```

### 2.2 延迟加载的源码实现

延迟加载的核心思路是：**不直接返回实际对象，而是返回一个代理对象**。当代理对象的某个方法被调用时，才真正去执行 SQL 获取数据。

```java
// DefaultResultSetHandler.java
private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap,
                                   String columnPrefix) throws SQLException {
    
    // ... 常规创建对象 ...
    
    // 如果开启了延迟加载，且存在嵌套查询
    if (resultMap.hasNestedQueries() && lazyLoadingEnabled) {
        // ★ 使用 Javassist 或 CGLib 创建代理
        if (configuration.isUseActualParamName()) {
            // 创建延迟加载代理
        }
    }
}
```

**ProxyFactory——创建延迟加载代理：**

```java
// ProxyFactory.java — MyBatis 的延迟加载代理工厂
public interface ProxyFactory {
    
    // 创建延迟加载代理
    Object createProxy(Object target, ResultLoaderMap lazyLoader, 
                       Configuration configuration, ObjectFactory objectFactory,
                       List<Class<?>> constructorArgTypes, List<Object> constructorArgs);
    
    // 判断是否是代理对象
    boolean isProxy(Object object);
}
```

**JavassistProxyFactory 的实现：**

```java
// JavassistProxyFactory.java
public class JavassistProxyFactory implements ProxyFactory {
    
    @Override
    public Object createProxy(Object target, ResultLoaderMap lazyLoader,
                               Configuration configuration, ObjectFactory objectFactory,
                               List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        
        // ★ 创建 Enhancer（动态生成子类）
        return EnhancedResultObjectProxyImpl.createProxy(target, lazyLoader, configuration,
                objectFactory, constructorArgTypes, constructorArgs);
    }
    
    // ★ 内部类：CGLib 的 MethodInterceptor
    private static class EnhancedResultObjectProxyImpl implements MethodHandler {
        
        private final ResultLoaderMap lazyLoader;
        
        @Override
        public Object invoke(Object enhanced, Method method, Method methodProxy, 
                              Object[] args) throws Throwable {
            final String methodName = method.getName();
            
            // ★ 判断是否是延迟加载属性的 getter 方法
            if (lazyLoader.size() > 0 && !lazyLoader.isLoaded(methodName)) {
                if (isLazyLoadTrigger(methodName)) {
                    // ★ 触发延迟加载：执行嵌套查询，填充所有懒加载属性
                    lazyLoader.loadAll();
                } else if (isPropertyGetter(methodName)) {
                    // ★ 只加载当前 getter 对应的属性
                    lazyLoader.load(methodName);
                }
            }
            
            // 调用实际方法
            return methodProxy.invoke(enhanced, args);
        }
    }
}
```

### 2.3 实际执行延迟加载

```java
// ResultLoaderMap.java
public class ResultLoaderMap {
    
    private final Map<String, LoadPair> loaderMap = new HashMap<>();
    
    public boolean load(String property) throws SQLException {
        LoadPair pair = loaderMap.remove(property.toUpperCase(Locale.ENGLISH));
        if (pair != null) {
            // ★ 执行嵌套查询，加载数据
            pair.load();
            return true;
        }
        return false;
    }
    
    public void loadAll() throws SQLException {
        final Set<String> methodNameSet = loaderMap.keySet();
        // 遍历所有延迟加载属性，依次加载
        for (final String methodName : methodNameSet) {
            load(methodName);
        }
    }
    
    // ★ LoadPair 内部封装了实际的查询调用
    private static class LoadPair {
        
        private final ResultLoader loader;
        
        void load() throws SQLException {
            // ★ 执行 SqlSession.selectList() — 发出一条新 SQL
            Object value = loader.loadResult();
            // 设置目标对象的属性值
            metaObject.setValue(property, value);
        }
    }
}
```

---

## 三、鉴别器的运行时行为

鉴别器在运行时通过 `resolveDiscriminatedResultMap` 方法动态确定使用哪个 `resultMap`：

```java
// DefaultResultSetHandler.getRowValue() 中
private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap,
                            String columnPrefix) throws SQLException {
    
    // 创建结果对象
    Object resultObject = createResultObject(rsw, resultMap, columnPrefix);
    
    if (resultObject != null) {
        // ★ 如果有鉴别器，重新获取匹配的 resultMap
        if (resultMap.hasDiscriminator()) {
            resultMap = resolveDiscriminatedResultMap(rsw, resultMap, columnPrefix);
        }
        
        // 执行实际的映射...
    }
    
    return resultObject;
}
```

**鉴别器的多级处理：**

```
SQL 结果：vehicle_type = "CAR"
    │
    ▼
resolveDiscriminatedResultMap(vehicleMap)
    │
    ├── 获取 vehicle_type 列值 → "CAR"
    ├── 查找 CAR 对应的 resultMap → "carMap"
    ├── carMap 也有鉴别器吗？
    │     ├── 无 → 返回 carMap
    │     └── 有 → 递归
    │
    ▼
返回最终的 ResultMap → carMap（可能包含额外属性 doorCount）
```

---

## 四、主键生成与自增主键

### 4.1 useGeneratedKeys

```xml
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO users (name, email) VALUES (#{name}, #{email})
</insert>
```

### 4.2 主键回填的源码

```java
// DefaultResultSetHandler.java
public void handleOutputParameters(CallableStatement cs) {
    // ... 处理存储过程的输出参数
}

// 在 BaseExecutor 中
@Override
public int update(MappedStatement ms, Object parameter) {
    // 执行更新
    int result = doUpdate(ms, parameter);
    
    // ★ 如果配置了 useGeneratedKeys，处理自增主键回填
    if (ms.getKeyGenerator() != null) {
        ms.getKeyGenerator().processAfter(ms, parameter, result, null);
    }
    
    clearLocalCache();
    return result;
}
```

**Jdbc3KeyGenerator——JDBC 自增主键回填：**

```java
// Jdbc3KeyGenerator.java
public class Jdbc3KeyGenerator implements KeyGenerator {
    
    @Override
    public void processAfter(Executor executor, MappedStatement ms,
                              Statement stmt, Object parameter) {
        // ★ 从 JDBC Statement 中获取自增主键值
        try (ResultSet rs = stmt.getGeneratedKeys()) {
            if (rs.next()) {
                final Configuration configuration = ms.getConfiguration();
                final TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
                
                // ★ 将主键值设置回参数对象的 keyProperty 属性
                String keyProperty = ms.getKeyProperties()[0];
                MetaObject metaObject = configuration.newMetaObject(parameter);
                
                if (metaObject.hasSetter(keyProperty)) {
                    Class<?> keyPropertyType = metaObject.getSetterType(keyProperty);
                    TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(keyPropertyType);
                    metaObject.setValue(keyProperty, typeHandler.getResult(rs, null));
                }
            }
        }
    }
}
```

---

## 五、完整的 ResultSet 处理时序

```
handleResultSets(Statement)
    │
    ├── ResultSetWrapper 包装 ResultSet
    │
    ├── 循环处理每个 ResultMap（多结果集）
    │     │
    │     ├── 有嵌套结果映射？
    │     │     ├── 是 → handleRowValuesForNestedResultMap
    │     │     │         ├── 逐行读取结果集
    │     │     │         ├── 生成唯一键（<id> 列）
    │     │     │         ├── 缓存判断：新对象/已有对象
    │     │     │         └── 递归处理嵌套的 association/collection
    │     │     │
    │     │     └── 否 → handleRowValuesForSimpleResultMap
    │     │                 └── 逐行读取 → getRowValue()
    │     │
    │     └── getRowValue()
    │           ├── createResultObject() — 创建 Java 对象
    │           ├── 检查鉴别器 → resolveDiscriminatedResultMap()
    │           ├── 遍历 ResultMapping 映射每个字段
    │           │     ├── 简单字段 → TypeHandler.getResult() → setValue()
    │           │     ├── association → 递归/嵌套查询
    │           │     └── collection → 递归/嵌套查询
    │           └── 延迟加载 → 创建代理对象
    │
    └── collapseSingleResultList() → 返回
```

---

## 六、总结

### 结果映射的核心组件

| 组件 | 职责 |
|------|------|
| `ResultSetWrapper` | 包装 ResultSet，缓存列名和 TypeHandler |
| `ResultMap` | 描述 Java 对象与数据库列的映射关系 |
| `ResultMapping` | 一个字段/关联的映射描述 |
| `DefaultResultSetHandler` | 核心，执行实际映射和对象创建 |
| `TypeHandler` | 类型转换 |
| `ProxyFactory` | 创建延迟加载代理对象 |
| `ResultLoaderMap` | 管理延迟加载的属性和触发 |

### 关键设计点

- **级联结果去重**：通过 `<id>` 元素和 `uniqueKeys` 缓存实现
- **延迟加载**：通过代理对象 + `ResultLoaderMap` 实现
- **鉴别器**：运行时递归确定使用哪个 resultMap

---

**上一篇：** [MyBatis 结果映射进阶（一）：resultMap 高级用法]({{< relref "post/mybatis-resultmap-basics" >}})
