---
title: "MyBatis 批量操作与性能优化"
date: 2018-07-04
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "批量操作", "BatchExecutor", "批处理", "性能优化", "源码分析"]
toc: true
---

## 前言

在实际项目中，经常需要批量插入或更新大量数据。MyBatis 提供了三种不同的执行器：**SimpleExecutor**（默认）、**ReuseExecutor**（复用 Statement）和 **BatchExecutor**（批量执行）。

理解这些执行器的差异，以及 JDBC 批处理的底层原理，可以有效优化数据库写入性能。

<!--more-->

> **源码版本：** MyBatis 3.5.x

## 一、三种 Executor 对比

| 特性 | SimpleExecutor | ReuseExecutor | BatchExecutor |
|------|---------------|---------------|---------------|
| Statement 管理 | 每次创建新 Statement | 缓存 Statement 复用 | 收集 SQL，批量提交 |
| 适用场景 | 默认，适合大多数场景 | SQL 重复执行的场景 | 批量 insert/update |
| batch 支持 | 不支持 | 不支持 | 支持 |
| 配置方式 | `SIMPLE`（默认）| `REUSE` | `BATCH` |

---

## 二、BatchExecutor 源码

### 2.1 核心数据结构

```java
// BatchExecutor.java
public class BatchExecutor extends BaseExecutor {
    
    public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;
    
    // ★ 存储批处理语句的列表
    private final List<BatchResult> batchResults = new ArrayList<>();
    
    // 当前缓存的 SQL
    private String currentSql;
    // 当前缓存的 MappedStatement
    private MappedStatement currentMs;
}
```

**BatchResult：**

```java
// BatchResult.java
public class BatchResult {
    
    private final MappedStatement mappedStatement;   // 映射语句
    private final String sql;                        // SQL 语句
    private final List<Object> parameterObjects;     // 参数对象列表
    private int[] updateCounts;                      // 执行结果
    
    public BatchResult(MappedStatement mappedStatement, String sql) {
        this.mappedStatement = mappedStatement;
        this.sql = sql;
        this.parameterObjects = new ArrayList<>();
    }
}
```

### 2.2 doUpdate——收集批量操作

```java
// BatchExecutor.java
@Override
public int doUpdate(MappedStatement ms, Object parameterObject) {
    final Configuration configuration = ms.getConfiguration();
    final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, 
            RowBounds.DEFAULT, null, null);
    final BoundSql boundSql = handler.getBoundSql();
    final String sql = boundSql.getSql();
    final Statement stmt;
    
    // ★ 判断 SQL 是否和上一次相同
    if (sql.equals(currentSql) && ms.equals(currentMs)) {
        // ★ 相同 SQL + 相同 MappedStatement → 复用上一个 Statement
        int last = batchResults.size() - 1;
        BatchResult batchResult = batchResults.get(last);
        batchResult.addParameterObject(parameterObject);
        
        // 获取缓存的 Statement
        stmt = statementList.get(last);
        // ★ 设置新的参数
        handler.parameterize(stmt);
    } else {
        // ★ 新 SQL 或新 MappedStatement → 创建新的 Statement
        Connection connection = getConnection(ms.getStatementLog());
        stmt = handler.prepare(connection, transaction.getTimeout());
        handler.parameterize(stmt);
        
        currentSql = sql;
        currentMs = ms;
        
        // 添加到 Statement 缓存列表
        statementList.add(stmt);
        batchResults.add(new BatchResult(ms, sql, parameterObject));
    }
    
    // ★ 关键：调用 JDBC 的批处理添加方法
    // 注意这里并没有调用 stmt.executeBatch()
    stmt.addBatch(sql);  // 添加到批处理队列
    
    return BATCH_UPDATE_RETURN_VALUE;
}
```

### 2.3 doFlushStatements——真正执行批处理

```java
// BatchExecutor.java
@Override
public List<BatchResult> doFlushStatements(boolean isRollback) {
    try {
        List<BatchResult> results = new ArrayList<>();
        
        // ★ 遍历所有 Statement，逐个执行 executeBatch()
        for (int i = 0; i < statementList.size(); i++) {
            Statement stmt = statementList.get(i);
            BatchResult batchResult = batchResults.get(i);
            
            try {
                // ★ 真正执行批处理：将积累的 SQL 一次性发送到数据库
                batchResult.setUpdateCounts(stmt.executeBatch());
                
                // 处理自增主键回填
                // ... Jdbc3KeyGenerator 或 SelectKeyGenerator
                
            } catch (BatchUpdateException e) {
                // 批量更新异常的特别处理
                batchResult.setUpdateCounts(e.getUpdateCounts());
                results.add(batchResult);
            }
            
            results.add(batchResult);
        }
        
        return results;
    } finally {
        // 清空缓存
        clearBatch();
    }
}

private void clearBatch() {
    currentSql = null;
    currentMs = null;
    batchResults.clear();
    statementList.clear();
}
```

### 2.4 何时刷新

`BaseExecutor` 中的 `flushStatements()` 在以下时机被调用：

```java
// BaseExecutor.java
// 1. 提交事务时
@Override
public void commit(boolean required) {
    // ★ 提交前刷新批处理
    flushStatements();
    // ...
}

// 2. 回滚事务时
@Override
public void rollback(boolean required) {
    // ★ 回滚前刷新（不会真正执行，只是清除）
    flushStatements(true);
}

// 3. 查询操作前（先 flush 未完成的批处理）
@Override
public <E> List<E> query(...) {
    if (queryStack == 0 && ms.isFlushCacheRequired()) {
        // ★ 查询前刷新批处理
        flushStatements();
    }
    // ...
}
```

---

## 三、批量操作的正确使用方式

### 3.1 通过 SqlSession 控制

```java
// ★ 方式一：创建 BATCH 类型的 SqlSession
try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
    UserMapper mapper = session.getMapper(UserMapper.class);
    
    for (User user : userList) {
        mapper.insert(user);  // 不会立即执行，加入批处理队列
    }
    
    // ★ 提交时一次性发送所有 SQL
    session.commit();  // 触发 doFlushStatements → stmt.executeBatch()
}
```

### 3.2 Spring 中的批量操作

```java
// ★ Spring 中需要通过 SqlSessionTemplate 的 BATCH 模式
@Autowired
private SqlSessionTemplate sqlSessionTemplate;

public void batchInsert(List<User> users) {
    // 使用 BATCH 执行器
    sqlSessionTemplate.execute(ExecutorType.BATCH, session -> {
        UserMapper mapper = session.getMapper(UserMapper.class);
        for (User user : users) {
            mapper.insert(user);
        }
        // 自动在事务提交时执行 executeBatch()
        return null;
    });
}
```

### 3.3 批量操作测试

```java
@Service
public class UserBatchService {
    
    @Autowired
    private SqlSessionFactory sqlSessionFactory;
    
    // ★ 普通逐条插入
    public void singleInsert(List<User> users) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (User user : users) {
                mapper.insert(user);
            }
            session.commit();
        }
    }
    
    // ★ 批量插入（BatchExecutor）
    public void batchInsert(List<User> users) {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (User user : users) {
                mapper.insert(user);
            }
            session.commit();  // 真正执行
        }
    }
    
    // ★ 分批批量插入（防止一次太多）
    public void batchInsertInBatches(List<User> users, int batchSize) {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            int count = 0;
            for (User user : users) {
                mapper.insert(user);
                count++;
                if (count % batchSize == 0) {
                    // ★ 每 batchSize 条提交一次
                    session.flushStatements();
                    session.clearCache();
                }
            }
            session.commit();
        }
    }
}
```

---

## 四、性能对比

### 4.1 三种方式性能对比（插入 10000 条数据）

| 方式 | 耗时（约）| SQL 发送次数 | 特点 |
|------|---------|------------|------|
| 逐条 INSERT（Simple）| ~15s | 10000 次 | 每次一条 SQL，最慢 |
| MyBatis Batch | ~3s | 1 次（批量） | 批量提交，JDBC 批处理 |
| 拼接 SQL（VALUES 多条）| ~1s | 1 次 | 一条 SQL 插多条，最快 |

### 4.2 拼接 SQL 的批量插入（最快）

```xml
<!-- Mapper XML -->
<insert id="batchInsertByList">
    INSERT INTO users (name, email, create_time) VALUES
    <foreach collection="list" item="user" separator=",">
        (#{user.name}, #{user.email}, NOW())
    </foreach>
</insert>
```

```java
// 调用 —— 一条 SQL 解决，不需要 BATCH 模式
public void batchInsertByList(List<User> users) {
    userMapper.batchInsertByList(users);
}
```

### 4.3 性能对比总结

| 场景 | 推荐方式 | 原因 |
|------|---------|------|
| 插入少量数据（<100） | 逐条 INSERT | 简单、代码清晰 |
| 插入大量数据（100~10000）| 拼接 SQL（VALUES 多条）| 单条 SQL，网络开销最小 |
| 批量更新（UPDATE）| BatchExecutor | 每条 UPDATE 不同，无法拼接 |
| 批量删除 | `<foreach>` 拼接 IN | 简单高效 |

---

## 五、性能优化最佳实践

### 5.1 合理设置 fetchSize

```java
// ★ 避免一次性加载大量数据到内存
@Options(fetchSize = 1000)  // 每次从数据库取 1000 条
@Select("SELECT * FROM large_table")
List<LargeData> selectAll();
```

### 5.2 使用游标（Cursor）处理大结果集

```java
// ★ 游标方式逐条读取，不一次性加载到内存
@Select("SELECT * FROM large_table")
Cursor<LargeData> scanAll();

// 使用
try (Cursor<LargeData> cursor = mapper.scanAll()) {
    cursor.forEach(data -> {
        // 逐条处理
        process(data);
    });
}
```

### 5.3 合理配置数据源参数

```properties
# HikariCP 配置示例
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000

# MySQL JDBC 参数优化（批量写入时尤其重要）
spring.datasource.url=jdbc:mysql://localhost:3306/db?\
  rewriteBatchedStatements=true&\  # ★ 开启批处理重写（关键）
  useCompression=true&\             # 启用压缩
  useServerPrepStmts=false          # 关闭服务端预编译（批处理中不推荐）
```

### 5.4 分页查询优化

```java
// ★ 使用 MySQL 标准分页（覆盖索引优化）
@Select("SELECT id, name, email FROM users ORDER BY id LIMIT #{offset}, #{limit}")
List<User> findByPage(@Param("offset") int offset, @Param("limit") int limit);

// ❌ 不要使用 SELECT * + 大偏移量分页
// SELECT * FROM users LIMIT 100000, 20  ← 前面 10 万条扫描浪费
```

---

## 六、ReuseExecutor——Statement 复用

```java
// ReuseExecutor.java
public class ReuseExecutor extends BaseExecutor {
    
    // ★ 缓存 SQL → Statement 的映射
    private final Map<String, Statement> statementMap = new HashMap<>();
    
    @Override
    public int doUpdate(MappedStatement ms, Object parameterObject) {
        // ...
        Statement stmt = null;
        try {
            // ★ 检查是否已存在该 SQL 对应的 Statement
            stmt = getStatement(handler.getBoundSql().getSql());
            handler.parameterize(stmt);
            return handler.update(stmt);
        } finally {
            closeStatement(stmt);
        }
    }
    
    private Statement getStatement(String sql) {
        // ★ 从 Map 中查找已缓存的 Statement
        Statement stmt = statementMap.get(sql);
        if (stmt == null) {
            // 没有缓存 → 创建新的
            stmt = prepareStatement(handler);
            statementMap.put(sql, stmt);
        }
        return stmt;
    }
}
```

---

## 七、总结

### Executor 选型

| 执行器 | Statement 策略 | 适用场景 |
|--------|---------------|---------|
| SimpleExecutor | 每次关闭、每次新建 | 默认，通用场景 |
| ReuseExecutor | 缓存复用（Map<SQL, Statement>）| 大量重复 SQL 的场景 |
| BatchExecutor | 收集后批量提交 | 批量 insert/update |

### 批量操作推荐

| 操作类型 | 推荐方式 | 说明 |
|---------|---------|------|
| 批量 INSERT | `<foreach>` VALUES 多条 | 性能最好，单条 SQL |
| 批量 UPDATE | BatchExecutor | 每条 SQL 不同 |
| 批量 DELETE | `<foreach>` IN 子句 | 性能最好 |

### 性能优化原则

1. **批量操作用 BatchExecutor 或拼接 SQL**
2. **开启 `rewriteBatchedStatements=true`（MySQL）**
3. **大结果集用游标（Cursor）或分页**
4. **合理设置 fetchSize**
5. **避免 SELECT \*** 和超大偏移量分页

---

**相关阅读：**
- [MyBatis（二）：Mapper 代理原理与 SQL 执行流程]({{< relref "post/mybatis-mapper-proxy-execution" >}})
- [MyBatis 结果映射进阶（一）：resultMap 高级用法]({{< relref "post/mybatis-resultmap-basics" >}})
