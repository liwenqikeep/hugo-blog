---
title: "MyBatis 最佳实践与踩坑"
date: 2018-07-06
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "最佳实践", "踩坑", "项目结构", "设计规范"]
toc: true
---

## 前言

经过前面几篇文章的源码分析，我们对 MyBatis 的底层原理有了深入理解。这篇文章回到实践——在真实的项目中，**如何用好 MyBatis**？**哪些坑需要避开**？**项目结构怎么设计**？

本文总结多年 MyBatis 使用经验，涵盖项目结构、编码规范、常见踩坑和选型建议。

<!--more-->

## 一、项目结构规范

### 1.1 分层结构

```
com.example
├── controller/              # Controller 层（Spring MVC）
├── service/                 # Service 层（业务逻辑）
├── dao/                     # Data Access Object 层（MyBatis Mapper 接口）
│   ├── UserDao.java
│   └── UserDao.xml
├── entity/                  # 数据库实体对象
│   └── User.java
├── dto/                     # 数据传输对象（VO、DTO、Query）
│   ├── UserVO.java
│   └── UserQuery.java
└── config/                  # 配置类
    └── MyBatisConfig.java
```

### 1.2 Mapper 命名规范

```java
// ★ DAO 接口命名
UserDao.java              // → Mapper XML 的 namespace = com.example.dao.UserDao
UserMapper.java           // → Mapper XML 的 namespace = com.example.dao.UserMapper

// ★ 方法命名规范
findById(Long id)         // 按主键查询
findList(Query query)     // 列表查询（带条件）
findPage(Query query)     // 分页查询
count(Query query)        // 计数
insert(T entity)          // 插入
update(T entity)          // 更新
deleteById(Long id)       // 按主键删除
```

### 1.3 实体与 DTO 分离

```java
// ★ Entity — 与数据库表一对一映射
public class User {
    private Long id;
    private String name;
    private String email;
    private String password;     // 数据库有 password 字段
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

// ★ DTO — 按接口需求设计，不暴露敏感字段
public class UserVO {
    private Long id;
    private String name;
    private String email;
    private String createTime;   // 不暴露 password
}

// ★ Query — 查询参数对象
public class UserQuery {
    private String keyword;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer page = 1;
    private Integer pageSize = 20;
}
```

---

## 二、编码规范

### 2.1 使用 @Param 明确参数名

```java
// ✅ 推荐：显式指定参数名
User findByNameAndEmail(@Param("name") String name, 
                         @Param("email") String email);

// ❌ 不推荐：依赖 MyBatis 的参数名解析（需要 -parameters 编译选项）
User findByNameAndEmail(String name, String email);
```

### 2.2 使用 @Options 配置自增主键

```java
// ✅ 推荐
@Options(useGeneratedKeys = true, keyProperty = "id")
@Insert("INSERT INTO users (name, email) VALUES (#{name}, #{email})")
int insert(User user);

// 插入后可直接获取主键
user.getId();  // 自增 ID 已回填
```

### 2.3 使用 <sql> 复用 SQL 片段

```xml
<!-- ★ SQL 片段：列名列表 -->
<sql id="baseColumns">
    id, name, email, status, create_time, update_time
</sql>

<!-- ★ SQL 片段：WHERE 条件 -->
<sql id="whereClause">
    <where>
        <if test="name != null">AND name LIKE CONCAT('%', #{name}, '%')</if>
        <if test="email != null">AND email = #{email}</if>
        <if test="status != null">AND status = #{status}</if>
    </where>
</sql>

<!-- 使用 SQL 片段 -->
<select id="findByQuery" resultType="User">
    SELECT <include refid="baseColumns"/>
    FROM users
    <include refid="whereClause"/>
    ORDER BY id DESC
</select>

<select id="countByQuery" resultType="int">
    SELECT COUNT(1) FROM users
    <include refid="whereClause"/>
</select>
```

### 2.4 逻辑删除统一处理

```xml
<!-- ★ 给所有查询自动加上逻辑删除条件 -->
<sql id="notDeleted">
    AND deleted = 0
</sql>

<select id="findById" resultType="User">
    SELECT * FROM users 
    WHERE id = #{id}
    <include refid="notDeleted"/>
</select>

<update id="deleteById">
    UPDATE users SET deleted = 1, update_time = NOW()
    WHERE id = #{id}
</update>
```

---

## 三、常见踩坑

### 坑 1：`#{}` 和 `${}` 混用导致 SQL 注入

```xml
<!-- ❌ 错误：表名和列名用 #{} 会加引号报错 -->
<select id="findByColumn" resultType="User">
    SELECT * FROM users ORDER BY #{column}  <!-- ORDER BY 'name' → 语法错误 -->
</select>

<!-- ✅ 正确：表名/列名必须用 ${} -->
<select id="findByColumn" resultType="User">
    SELECT * FROM users ORDER BY ${column}
</select>

<!-- ⚠️ 但 ${} 有注入风险，必须校验输入 -->
// Java 代码中做白名单校验
public List<User> findByColumn(String column) {
    // ★ 列名白名单校验
    Set<String> allowedColumns = Set.of("id", "name", "email", "create_time");
    if (!allowedColumns.contains(column)) {
        throw new IllegalArgumentException("非法排序字段: " + column);
    }
    return userDao.findByColumn(column);
}
```

### 坑 2：N+1 查询

```xml
<!-- ❌ 问题场景 -->
<resultMap id="userMap" type="User">
    <association property="department"
                 column="dept_id"
                 select="findDeptById"/>  <!-- 每条 User 执行一次 -->
</resultMap>

<!-- ✅ 解决方案：用 JOIN + 嵌套结果取代嵌套查询 -->
<resultMap id="userMap" type="User">
    <association property="department" 
                 resultMap="deptMap"
                 columnPrefix="dept_"/>
</resultMap>
<select id="findUserList" resultMap="userMap">
    SELECT u.*, d.id AS dept_id_, d.name AS dept_name
    FROM users u LEFT JOIN departments d ON u.dept_id = d.id
</select>
```

### 坑 3：自增主键不返回

```java
// ❌ 问题：插入后拿不到自增 ID
@Insert("INSERT INTO users (name) VALUES (#{name})")
int insert(User user);

// user.getId() → null（没有配置 useGeneratedKeys）

// ✅ 修复
@Options(useGeneratedKeys = true, keyProperty = "id")
@Insert("INSERT INTO users (name) VALUES (#{name})")
int insert(User user);
```

### 坑 4：MySQL 中 `rewriteBatchedStatements`

```java
// ❌ 没有配置时，BatchExecutor 实际上逐条发送
// 日志中看到的是多条 INSERT，不是真正的批量

// ✅ 修复：JDBC URL 加上参数
// jdbc:mysql://localhost:3306/db?rewriteBatchedStatements=true
//
// 配置后，MySQL JDBC 驱动会将多条同结构的 INSERT 合并为一条：
// INSERT INTO users VALUES (?), (?), (?)  ← 真正的批量
```

### 坑 5：大 IN 查询性能问题

```xml
<!-- ❌ 问题：IN 条件中元素过多（几千上万个） -->
<select id="findByIds" resultType="User">
    SELECT * FROM users WHERE id IN
    <foreach collection="list" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
</select>
```

```java
// ✅ 解决方案：分批查询
public List<User> findByIds(List<Long> ids) {
    if (ids.isEmpty()) return Collections.emptyList();
    
    List<User> result = new ArrayList<>();
    int batchSize = 200;  // 每次最多 200 个
    
    for (int i = 0; i < ids.size(); i += batchSize) {
        int end = Math.min(i + batchSize, ids.size());
        List<Long> subIds = ids.subList(i, end);
        result.addAll(userDao.findByIds(subIds));
    }
    return result;
}
```

### 坑 6：MyBatis 缓存脏读

```java
// ❌ 问题：在同一个 SqlSession 中先查后改再查
User user1 = mapper.findById(1L);       // 查数据库，写入一级缓存

user1.setName("NewName");
mapper.update(user1);                   // 清空一级缓存

User user2 = mapper.findById(1L);       // 缓存已清空，重新查数据库
// 这里 user2 获取到的是更新后的数据，没问题

// ❌ 真正的问题在不同 SqlSession 之间：
// Session1 查询数据（缓存到二级缓存）
// Session2 更新数据（只清空了自己的缓存，没有清空 Session1 的二级缓存）
// Session1 再次查询 → 从二级缓存获取脏数据

// ✅ 解决方案：配置 <cache-ref> 共享缓存，或在高并发更新场景下禁用缓存
```

### 坑 7：MyBatis 的 equals 和 hashCode

```java
// ❌ 问题：MyBatis 通过反射创建对象，如果实体类重写了 equals/hashCode
// 且依赖了未加载的延迟加载属性，会触发延迟加载
// 甚至导致循环加载

// ✅ 正确做法：实体类的 equals/hashCode 只使用主键
public class User {
    private Long id;
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);  // ★ 只用主键
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
```

---

## 四、MyBatis vs JPA

| 维度 | MyBatis | JPA / Hibernate |
|------|---------|----------------|
| SQL 控制 | 完全控制，手写 SQL | 自动生成，也可手写 JPQL |
| 学习成本 | 低（懂 SQL 就行）| 高（需要理解缓存、懒加载等）|
| 复杂查询 | 灵活 | 较复杂（Criteria API 冗长）|
| 批量操作 | 表格，性能好 | 需注意 N+1 和批量策略 |
| 动态 SQL | 强大（标签完善）| 需要 Specification 或 @Query |
| 数据库迁移 | 需手动维护 SQL | 自动 DDL |
| 适用场景 | 复杂查询、性能敏感 | 简单 CRUD、标准查询 |

**选型建议：**

| 场景 | 推荐 |
|------|------|
| 报表系统、复杂查询 | MyBatis |
| 简单 CRUD 为主的项目 | JPA |
| 对 SQL 性能要求高 | MyBatis |
| 快速原型开发 | JPA |
| 大型项目、团队 SQL 能力强 | MyBatis |
| 需要自动 DDL 和多数据库兼容 | JPA |

---

## 五、MyBatis + Spring Boot 推荐配置

```properties
# application.yml
mybatis:
  mapper-locations: classpath:mapper/**/*.xml   # Mapper XML 路径
  type-aliases-package: com.example.entity       # 别名包
  configuration:
    map-underscore-to-camel-case: true           # 下划线→驼峰
    cache-enabled: false                         # 是否开启二级缓存（生产建议关闭）
    lazy-loading-enabled: false                  # 延迟加载（建议关闭，避免 N+1）
    aggressive-lazy-loading: false
    default-fetch-size: 100
    default-statement-timeout: 30
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl  # SQL 日志
    # log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # 开发时输出到控制台
```

---

## 六、总结

### 最佳实践清单

| 类别 | 实践 |
|------|------|
| **项目结构** | DAO 接口 + XML 放在一起、实体与 DTO 分离 |
| **编码规范** | 用 `@Param`、`@Options`、`<sql>` 片段复用 |
| **SQL 安全** | `${}` 白名单校验、`#{}` 防注入 |
| **性能** | 批量用 BatchExecutor 或拼接 VALUES、避免 N+1 |
| **缓存** | 生产环境谨慎开启二级缓存、注意脏读 |
| **分页** | 用 PageHelper 或手动 LIMIT，避免大偏移量 |
| **配置** | 开启 `map-underscore-to-camel-case`、配置 `rewriteBatchedStatements` |

### 核心原则

> **MyBatis 的核心优势是把 SQL 控制权交还给开发者——用好这个优势，保持 SQL 简洁高效，避免滥用复杂的级联查询和延迟加载。**

---

**相关阅读：**
- [MyBatis（一）：核心架构与配置]({{< relref "post/mybatis-architecture" >}})
- [MyBatis（二）：Mapper 代理原理与 SQL 执行流程]({{< relref "post/mybatis-mapper-proxy-execution" >}})
- [MyBatis（三）：动态 SQL 深度解析]({{< relref "post/mybatis-dynamic-sql" >}})
- [MyBatis 插件机制：Interceptor 原理与自定义插件]({{< relref "post/mybatis-plugin" >}})
- [MyBatis 批量操作与性能优化]({{< relref "post/mybatis-batch-performance" >}})
