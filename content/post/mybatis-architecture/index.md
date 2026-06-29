---
title: "MyBatis（一）：核心架构与配置"
date: 2018-06-20
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "ORM", "SqlSessionFactory", "SqlSession", "配置文件", "源码分析"]
toc: true
---

## 前言

MyBatis 是 Java 生态中最流行的半自动化 ORM 框架之一。与 JPA/Hibernate 的全自动化不同，MyBatis 将 SQL 的控制权完全交给开发者，同时通过映射机制自动完成结果集的封装。

本文从整体架构出发，覆盖 MyBatis 的核心组件、配置文件解析流程，以及 SqlSessionFactory 的构建过程。

<!--more-->

> **源码版本：** MyBatis 3.5.x

## 一、MyBatis 整体架构

### 1.1 架构分层

```
┌──────────────────────────────────────────────────────┐
│                    应用层（API）                        │
│  SqlSession  ←  Mapper 接口  ←  Mapper Proxy         │
├──────────────────────────────────────────────────────┤
│                    核心处理层                           │
│  ┌──────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │ 配置解析   │  │ SQL 解析与    │  │ 参数映射 结果映射 │  │
│  │ XMLConfig │  │ 动态 SQL     │  │ TypeHandler     │  │
│  │ XMLMapper │  │ StatementHandler│  │ ResultSetHandler│ │
│  └──────────┘  └──────────────┘  └─────────────────┘  │
│  ┌──────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │ Executor  │  │ 缓存（一级/二级）│  │ 插件 Interceptor │  │
│  └──────────┘  └──────────────┘  └─────────────────┘  │
├──────────────────────────────────────────────────────┤
│                    基础支持层                           │
│  数据源   │  连接池   │  事务管理   │  日志   │  反射   │
├──────────────────────────────────────────────────────┤
│                    数据源（DataSource）                 │
└──────────────────────────────────────────────────────┘
```

### 1.2 核心组件

| 组件 | 作用 |
|------|------|
| `SqlSessionFactory` | 工厂类，创建 SqlSession |
| `SqlSession` | 会话，提供数据库操作方法（select/insert/update/delete）|
| `Executor` | 执行器，管理缓存和 StatementHandler 调用 |
| `StatementHandler` | 处理 JDBC Statement，设置参数和执行 SQL |
| `ParameterHandler` | 参数映射，将 Java 对象转为 JDBC 参数 |
| `ResultSetHandler` | 结果集映射，将 JDBC ResultSet 转为 Java 对象 |
| `TypeHandler` | 类型转换器，Java 类型 ↔ JDBC 类型 |
| `MappedStatement` | 映射语句，封装 Mapper XML 中的 select/insert 等标签 |

### 1.3 一次完整查询的调用链

```
Mapper.method()  →  MapperProxy.invoke()
                      │
                    SqlSession.selectList()
                      │
                    Executor.query()
                      │
                    StatementHandler.query()
                      │
                    ParameterHandler.setParameters()
                      │
                    java.sql.Statement.execute()
                      │
                    ResultSetHandler.handleResultSets()
                      │
                    返回 Java 对象
```

---

## 二、配置文件解析

### 2.1 入口：SqlSessionFactoryBuilder

MyBatis 的启动入口是 `SqlSessionFactoryBuilder`：

```java
// 传统方式（XML）
String resource = "mybatis-config.xml";
InputStream inputStream = Resources.getResourceAsStream(resource);
SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(inputStream);

// Spring Boot 中由 MyBatis-Spring-Boot-Starter 自动完成
```

**build() 方法内部：**

```java
public SqlSessionFactory build(InputStream inputStream) {
    return build(inputStream, null, null);
}

public SqlSessionFactory build(InputStream inputStream, String environment, Properties properties) {
    try {
        // 1. ★ 创建 XMLConfigBuilder（SAX 解析 mybatis-config.xml）
        XMLConfigBuilder parser = new XMLConfigBuilder(inputStream, environment, properties);
        // 2. 解析配置 → 得到 Configuration（核心配置类）
        // 3. 根据 Configuration 创建 DefaultSqlSessionFactory
        return build(parser.parse());
    } catch (Exception e) {
        throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    }
}
```

### 2.2 XMLConfigBuilder 解析 mybatis-config.xml

```java
// XMLConfigBuilder.java
public Configuration parse() {
    if (parsed) {
        throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // ★ 从 <configuration> 根标签开始解析
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
}

private void parseConfiguration(XNode root) {
    try {
        // 按顺序解析 mybatis-config.xml 中的各元素
        propertiesElement(root.evalNode("properties"));       // <properties>
        Properties settings = settingsAsProperties(root.evalNode("settings")); // <settings>
        loadCustomVfs(settings);
        loadCustomLogImpl(settings);
        typeAliasesElement(root.evalNode("typeAliases"));     // <typeAliases>
        pluginElement(root.evalNode("plugins"));              // <plugins>
        objectFactoryElement(root.evalNode("objectFactory"));
        objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
        reflectorFactoryElement(root.evalNode("reflectorFactory"));
        settingsElement(settings);
        environmentsElement(root.evalNode("environments"));   // <environments> ★ 数据源和事务
        databaseIdProviderElement(root.evalNode("databaseIdProvider"));
        typeHandlerElement(root.evalNode("typeHandlers"));    // <typeHandlers>
        mapperElement(root.evalNode("mappers"));              // <mappers> ★ 注册 Mapper
    } catch (Exception e) {
        throw new BuilderException("Error parsing SQL Mapper Configuration.", e);
    }
}
```

### 2.3 解析 Mapper 映射文件

```java
// XMLConfigBuilder.mapperElement()
private void mapperElement(XNode parent) {
    if (parent == null) return;
    
    for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
            // ★ <package name="com.example.mapper"/>
            String mapperPackage = child.getStringAttribute("name");
            configuration.addMappers(mapperPackage);
        } else {
            // <mapper resource="..." /> 或 <mapper class="..." /> 或 <mapper url="..." />
            String resource = child.getStringAttribute("resource");
            String url = child.getStringAttribute("url");
            String mapperClass = child.getStringAttribute("class");
            
            if (resource != null) {
                // ★ 通过 XMLMapperBuilder 解析 Mapper XML 文件
                XMLMapperBuilder mapperParser = new XMLMapperBuilder(
                        Resources.getResourceAsStream(resource), 
                        configuration, resource, configuration.getSqlFragments());
                mapperParser.parse();
            } else if (url != null) {
                XMLMapperBuilder mapperParser = new XMLMapperBuilder(
                        Resources.getUrlAsStream(url), 
                        configuration, url, configuration.getSqlFragments());
                mapperParser.parse();
            } else if (mapperClass != null) {
                Class<?> mapperInterface = Resources.classForName(mapperClass);
                configuration.addMapper(mapperInterface);
            }
        }
    }
}
```

### 2.4 XMLMapperBuilder 解析 Mapper XML

```java
// XMLMapperBuilder.java
public void parse() {
    if (!configuration.isResourceLoaded(resource)) {
        // 1. 处理 <mapper> 根标签
        configurationElement(parser.evalNode("/mapper"));
        configuration.addLoadedResource(resource);
        // 2. 将 Mapper 接口绑定到 namespace
        bindMapperForNamespace();
    }
}

private void configurationElement(XNode context) {
    try {
        String namespace = context.getStringAttribute("namespace");
        
        // 1. 解析 <cache> — 二级缓存配置
        cacheElement(context.evalNode("cache"));
        
        // 2. 解析 <cache-ref> — 引用其他命名空间的缓存
        cacheRefElement(context.evalNode("cache-ref"));
        
        // 3. ★ 解析 <parameterMap>（已废弃）
        parameterMapElement(context.evalNodes("/mapper/parameterMap"));
        
        // 4. ★ 解析 <resultMap> — 结果映射
        resultMapElements(context.evalNodes("/mapper/resultMap"));
        
        // 5. ★ 解析 <sql> — SQL 片段
        sqlElement(context.evalNodes("/mapper/sql"));
        
        // 6. ★★ 解析 select|insert|update|delete 语句
        buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
        throw new BuilderException(...);
    }
}
```

### 2.5 构建 MappedStatement

```java
// XMLStatementBuilder.java — 解析 <select> 等标签
private void parseStatementNode() {
    String id = context.getStringAttribute("id");
    String parameterMap = context.getStringAttribute("parameterMap");
    String parameterType = context.getStringAttribute("parameterType");
    String resultMap = context.getStringAttribute("resultMap");
    String resultType = context.getStringAttribute("resultType");
    String resultSetType = context.getStringAttribute("resultSetType");
    String statementType = StatementType.STATEMENT.name();
    
    // 1. SQL 类型（SELECT/INSERT/UPDATE/DELETE）
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(context.getName().toUpperCase());
    
    // 2. 是否使用 JDBC 预编译（默认 PREPARED）
    String nodeName = context.getStringAttribute("statementType", StatementType.PREPARED.name());
    statementType = nodeName;
    
    // 3. 超时设置
    Integer timeout = context.getIntAttribute("timeout");
    
    // 4. 解析动态 SQL（<if>、<where> 等标签）
    // ★ 内部创建 SqlNode 树
    XMLScriptBuilder scriptBuilder = new XMLScriptBuilder(configuration, context, parameterType);
    SqlSource sqlSource = scriptBuilder.parseScriptNode();
    
    // 5. 构建 MappedStatement
    MappedStatement.Builder statementBuilder = new MappedStatement.Builder(
            configuration, id, sqlSource, sqlCommandType);
    
    // 设置其他属性...
    configuration.addMappedStatement(statementBuilder.build());
}
```

---

## 三、Configuration 核心对象

MyBatis 的配置解析最终汇聚为一个 `Configuration` 对象——它是 MyBatis 运行时所有配置的中央存储。

```java
public class Configuration {
    
    // 环境（数据源 + 事务管理器）
    protected Environment environment;
    
    // ★ 所有 MappedStatement（每个 select/insert 等对应一个）
    protected final Map<String, MappedStatement> mappedStatements = 
            new StrictMap<>("Mapped Statements collection");
    
    // ★ 所有 Mapper 接口注册
    protected final Map<Class<?>, MapperRegistry> mapperRegistry = 
            new MapperRegistry(this);
    
    // 所有 ResultMap
    protected final Map<String, ResultMap> resultMaps = 
            new StrictMap<>("Result Maps collection");
    
    // TypeAlias 注册
    protected final TypeAliasRegistry typeAliasRegistry = new TypeAliasRegistry();
    
    // TypeHandler 注册
    protected final TypeHandlerRegistry typeHandlerRegistry = new TypeHandlerRegistry();
    
    // 所有插件（Interceptor）
    protected final InterceptorChain interceptorChain = new InterceptorChain();
    
    // 缓存
    protected boolean cacheEnabled = true;
    protected LocalCacheScope localCacheScope = LocalCacheScope.SESSION;
    
    // 懒加载
    protected boolean lazyLoadingEnabled = false;
    protected boolean aggressiveLazyLoading = false;
}
```

---

## 四、SqlSessionFactory 与 SqlSession

### 4.1 DefaultSqlSessionFactory

解析配置完成后，`SqlSessionFactoryBuilder` 创建 `DefaultSqlSessionFactory`：

```java
// DefaultSqlSessionFactory.java
public class DefaultSqlSessionFactory implements SqlSessionFactory {
    
    private final Configuration configuration;
    
    @Override
    public SqlSession openSession() {
        return openSessionFromDataSource(configuration.getDefaultExecutorType(), 
                                         null, false);
    }
    
    private SqlSession openSessionFromDataSource(ExecutorType execType, 
                                                  TransactionIsolationLevel level, 
                                                  boolean autoCommit) {
        Transaction tx = null;
        try {
            // 1. 获取环境配置（数据源 + 事务管理器）
            Environment environment = configuration.getEnvironment();
            
            // 2. 创建事务工厂
            TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(environment);
            tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);
            
            // 3. ★ 创建 Executor（执行器，有 SIMPLE/REUSE/BATCH 三种）
            Executor executor = configuration.newExecutor(tx, execType);
            
            // 4. ★ 创建 DefaultSqlSession
            return new DefaultSqlSession(configuration, executor, autoCommit);
        } catch (Exception e) {
            closeTransaction(tx);
            throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
        }
    }
}
```

### 4.2 SqlSession 执行 SQL

```java
// DefaultSqlSession.java
public class DefaultSqlSession implements SqlSession {
    
    private final Configuration configuration;
    private final Executor executor;
    
    @Override
    public <T> T selectOne(String statement, Object parameter) {
        // selectList 取第一个
        List<T> list = this.selectList(statement, parameter);
        if (list.size() == 1) {
            return list.get(0);
        }
        // ...
    }
    
    @Override
    public <E> List<E> selectList(String statement, Object parameter) {
        // ★ 从 Configuration 中获取 MappedStatement
        MappedStatement ms = configuration.getMappedStatement(statement);
        // ★ 委托给 Executor 执行
        return executor.query(ms, wrapCollection(parameter), 
                              RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
    }
    
    @Override
    public int insert(String statement, Object parameter) {
        return update(statement, parameter);
    }
    
    @Override
    public int update(String statement, Object parameter) {
        MappedStatement ms = configuration.getMappedStatement(statement);
        return executor.update(ms, wrapCollection(parameter));
    }
    
    @Override
    public int delete(String statement, Object parameter) {
        return update(statement, parameter);
    }
}
```

---

## 五、总结

### MyBatis 启动到执行的全流程

```
mybatis-config.xml / Spring Boot 配置
    │
    ▼
SqlSessionFactoryBuilder.build()
    │
    ├── XMLConfigBuilder.parse()
    │     ├── properties → settings → typeAliases
    │     ├── environments → 数据源 + 事务
    │     └── mappers → 注册 Mapper XML
    │
    ├── XMLMapperBuilder.parse()
    │     ├── <cache> → 二级缓存配置
    │     ├── <resultMap> → 结果映射
    │     ├── <sql> → SQL 片段
    │     └── <select|insert|update|delete> → MappedStatement
    │
    └── Configuration（中央配置对象）
          │
          ▼
    DefaultSqlSessionFactory
          │
          ▼
    SqlSession = DefaultSqlSession(Configuration, Executor)
          │
          ▼
    Mapper 代理 → SqlSession → Executor → ...
```

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `SqlSessionFactoryBuilder` | 入口，解析配置构建 SqlSessionFactory |
| `XMLConfigBuilder` | 解析 mybatis-config.xml |
| `XMLMapperBuilder` | 解析 Mapper XML 文件 |
| `XMLStatementBuilder` | 解析 select/insert/update/delete 标签 |
| `XMLScriptBuilder` | 解析动态 SQL 标签 |
| `Configuration` | 全局配置的中央存储 |
| `DefaultSqlSessionFactory` | 创建 SqlSession |
| `DefaultSqlSession` | SQL 会话实现，委托给 Executor |
| `MappedStatement` | 封装一条 SQL 语句的完整信息 |

---

**下一篇：** [MyBatis（二）：Mapper 代理原理与 SQL 执行流程]({{< relref "post/mybatis-mapper-proxy-execution" >}})
