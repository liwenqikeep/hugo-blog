---
title: "MyBatis 结果映射进阶（一）：resultMap 高级用法"
date: 2018-06-30
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "resultMap", "ResultMapping", "TypeHandler", "结果映射", "源码分析"]
toc: true
---

## 前言

MyBatis 最强大的特性之一就是灵活的结果映射。通过 `<resultMap>`，可以将 SQL 查询结果自动映射到复杂的 Java 对象结构——包括嵌套对象、集合、枚举、自定义类型等。

本文聚焦于 `resultMap` 的高级用法和底层映射原理，展示 MyBatis 如何将扁平的关系型数据转化为层次化的 Java 对象。

<!--more-->

> **源码版本：** MyBatis 3.5.x

## 一、resultMap 基础回顾

### 1.1 最简单的 resultMap

```xml
<resultMap id="userMap" type="User">
    <id property="id" column="id"/>
    <result property="name" column="name"/>
    <result property="email" column="email"/>
</resultMap>

<select id="findById" resultMap="userMap">
    SELECT id, name, email FROM users WHERE id = #{id}
</select>
```

### 1.2 自动映射

如果 `resultMap` 不显式配置 `<result>`，MyBatis 会尝试自动映射：

```xml
<!-- 自动映射：将查询列名与 Java 属性名按驼峰规则匹配 -->
<select id="findById" resultType="User">
    SELECT id, user_name AS userName, email FROM users WHERE id = #{id}
</select>
```

```properties
# mybatis-config.xml 中配置自动映射行为
mybatis.configuration.auto-mapping-behavior=PARTIAL  # 默认，只自动映射非嵌套的字段
# NONE = 关闭自动映射
# FULL = 自动映射所有字段（包括嵌套对象）
```

---

## 二、构造器映射

```xml
<resultMap id="userMap" type="User">
    <!-- 构造器参数映射（按参数顺序） -->
    <constructor>
        <idArg column="id" javaType="Long"/>
        <arg column="name" javaType="String"/>
        <arg column="email" javaType="String"/>
    </constructor>
</resultMap>
```

```java
public class User {
    
    private Long id;
    private String name;
    private String email;
    
    // ★ 必须有与 constructor 配置匹配的构造器
    public User(Long id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }
}
```

**构造器映射的解析源码：**

```java
// ResultMap.java — 构造器映射的构建
public static class Builder {
    
    private void buildConstructorMappings() {
        List<ResultMapping> constructorMappings = new ArrayList<>();
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                constructorMappings.add(resultMapping);
            }
        }
        // 按参数顺序排序
        resultMap.constructorResultMappings = constructorMappings;
    }
}
```

---

## 三、关联映射（association）

处理"有一个"的关系（如 User 有一个 Department）。

### 3.1 嵌套查询（Nested Select）

```xml
<resultMap id="userMap" type="User">
    <id property="id" column="id"/>
    <result property="name" column="name"/>
    
    <!-- ★ 嵌套查询：通过另一条 SQL 查询关联对象 -->
    <association property="department" 
                 column="dept_id"           <!-- 传给 select 的参数 -->
                 select="findDeptById"      <!-- 另一个 MappedStatement ID -->
                 fetchType="lazy"/>         <!-- lazy/eager -->
</resultMap>

<select id="findDeptById" resultType="Department">
    SELECT id, name FROM departments WHERE id = #{id}
</select>
```

**问题：N+1 查询。** 如果查 100 个用户，会额外执行 100 次部门查询。

### 3.2 嵌套结果（Nested Results）——推荐

```xml
<!-- ★ 通过 JOIN 一次查询，resultMap 映射嵌套对象 -->
<select id="findUserWithDept" resultMap="userWithDeptMap">
    SELECT 
        u.id, u.name, u.dept_id,
        d.id AS dept_id_, d.name AS dept_name
    FROM users u
    LEFT JOIN departments d ON u.dept_id = d.id
</select>

<resultMap id="userWithDeptMap" type="User">
    <id property="id" column="id"/>
    <result property="name" column="name"/>
    
    <!-- resultMap 嵌套 -->
    <association property="department" 
                 javaType="Department"
                 resultMap="deptResultMap"
                 columnPrefix="dept_"/>  <!-- 列名前缀 -->
</resultMap>

<resultMap id="deptResultMap" type="Department">
    <id property="id" column="id"/>
    <result property="name" column="name"/>
</resultMap>
```

**columnPrefix 的作用：** 当 JOIN 查询中有同名列时，通过前缀区分。`columnPrefix="dept_"` 表示 `dept_resultMap` 中的 `id` 对应 SQL 结果中的 `dept_id` 列。

### 3.3 关联映射的源码

`DefaultResultSetHandler` 中处理 `association` 的核心逻辑：

```java
// DefaultResultSetHandler.java
private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, 
                           String columnPrefix) throws SQLException {
    
    // 1. 创建结果对象（通过反射或构造器）
    Object resultObject = createResultObject(rsw, resultMap, columnPrefix);
    if (resultObject != null && !hasTypeHandlerForResultObject(resultObject, resultMap.getType())) {
        // 2. 获取已加载的列
        final List<String> columns = rsw.getColumnNames();
        
        // 3. 遍历 resultMap 中的 ResultMapping
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            if (resultMapping.isCompositeArgument()) continue;
            
            if (resultMapping.getNestedResultMapId() != null) {
                // ★ association 或 collection 嵌套结果
                // 递归处理嵌套 resultMap
                handleRowValuesForNestedResultMap(rsw, resultMap, ...);
            } else if (resultMapping.getNestedQueryId() != null) {
                // ★ 嵌套查询（N+1 方式）
                handleRowValuesForNestedQuery(rsw, resultMap, ...);
            } else {
                // ★ 简单字段映射
                String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
                Object value = typeHandler.getResult(rsw.getResultSet(), column);
                setValue(resultObject, resultMapping.getProperty(), value);
            }
        }
    }
    return resultObject;
}
```

---

## 四、集合映射（collection）

处理"有多条"的关系（如一个部门有多个用户）。

### 4.1 嵌套查询

```xml
<resultMap id="deptMap" type="Department">
    <id property="id" column="id"/>
    <result property="name" column="name"/>
    
    <collection property="users"
                column="id"
                select="findUsersByDeptId"
                fetchType="lazy"/>
</resultMap>

<select id="findUsersByDeptId" resultType="User">
    SELECT id, name, dept_id FROM users WHERE dept_id = #{deptId}
</select>
```

### 4.2 嵌套结果（推荐，一次 JOIN 解决）

```xml
<select id="findDeptWithUsers" resultMap="deptWithUsersMap">
    SELECT 
        d.id AS dept_id, d.name AS dept_name,
        u.id AS user_id, u.name AS user_name
    FROM departments d
    LEFT JOIN users u ON d.id = u.dept_id
    ORDER BY d.id
</select>

<resultMap id="deptWithUsersMap" type="Department">
    <id property="id" column="dept_id"/>
    <result property="name" column="dept_name"/>
    
    <collection property="users" 
                ofType="User"
                resultMap="userMap"
                columnPrefix="user_"/>
</resultMap>

<resultMap id="userMap" type="User">
    <id property="id" column="id"/>
    <result property="name" column="name"/>
</resultMap>
```

### 4.3 集合映射的去重（id 元素的作用）

```xml
<resultMap id="deptWithUsersMap" type="Department">
    <!-- ★ id 元素关键！它用于判断是否是同一行 -->
    <id property="id" column="dept_id"/>
    
    <collection property="users" ofType="User" columnPrefix="user_">
        <!-- ★ 同样，collection 中的 id 用于去重 user -->
        <id property="id" column="id"/>
        <result property="name" column="name"/>
    </collection>
</resultMap>
```

**为什么 id 这么重要？**

```
SQL 结果：
dept_id | dept_name | user_id | user_name
1       | 技术部     | 1       | Tom
1       | 技术部     | 2       | Jerry
2       | 产品部     | 3       | Alice

没有 <id> 时：MyBatis 会为每一行都创建一个新的 Department 对象
有 <id> 时：MyBatis 根据 dept_id 判断是否是同一个 Department
  → dept_id=1 的两行合并到同一个 Department 对象中
  → users 集合中包含 Tom 和 Jerry 两个 User
```

**去重的核心源码：**

```java
// DefaultResultSetHandler.java
private boolean matchesKey(String property, ResultMap resultMap) {
    // ★ 如果有 <id> 元素，用 id 的值做唯一标识
    // ★ 如果没有 <id>，用所有 <result> 的值做唯一标识
    List<ResultMapping> idMappings = resultMap.getIdResultMappings();
    if (idMappings.isEmpty()) {
        idMappings = resultMap.getPropertyResultMappings();
    }
    
    List<String> uniqueKeyColumns = new ArrayList<>();
    for (ResultMapping idMapping : idMappings) {
        uniqueKeyColumns.add(idMapping.getColumn());
    }
    
    // 将当前行的标识列值拼成唯一键
    String key = createKey(uniqueKeyColumns, rsw);
    
    // ★ 从缓存中查找 —— 同一部门（相同 dept_id）直接复用已有对象
    Object cached = uniqueObjects.get(key);
    if (cached != null) {
        // 直接加到集合中，不创建新 Department 对象
        addToCollection(cached, ...);
        return true;
    } else {
        uniqueObjects.put(key, resultObject);
        return false;
    }
}
```

---

## 五、鉴别器（discriminator）

根据某列的值，动态决定使用哪个 `resultMap`。

```xml
<resultMap id="vehicleMap" type="Vehicle">
    <id property="id" column="id"/>
    <result property="name" column="name"/>
    
    <discriminator javaType="String" column="vehicle_type">
        <case value="CAR" resultMap="carMap"/>
        <case value="TRUCK" resultMap="truckMap"/>
    </discriminator>
</resultMap>

<resultMap id="carMap" type="Car" extends="vehicleMap">
    <result property="doorCount" column="door_count"/>
</resultMap>

<resultMap id="truckMap" type="Truck" extends="vehicleMap">
    <result property="loadCapacity" column="load_capacity"/>
</resultMap>
```

**鉴别器的源码实现：**

```java
// DefaultResultSetHandler.java
private ResultMap resolveDiscriminatedResultMap(ResultSetWrapper rsw, 
                                                  ResultMap resultMap, 
                                                  String columnPrefix) {
    // 1. 获取鉴别器列的值
    String discriminatorValue = rsw.getStringResult(discriminatorColumn, columnPrefix);
    
    // 2. 查找匹配的 case
    ResultMap discriminatedResultMap = resultMap);
    for (Map.Entry<String, String> entry : resultMap.getDiscriminatorMap().entrySet()) {
        if (entry.getKey().equals(discriminatorValue)) {
            // ★ 使用匹配的 resultMap
            discriminatedResultMap = configuration.getResultMap(entry.getValue());
            break;
        }
    }
    
    // 3. 如果匹配到的 resultMap 也有鉴别器，递归
    if (discriminatedResultMap.hasDiscriminator()) {
        discriminatedResultMap = resolveDiscriminatedResultMap(rsw, discriminatedResultMap, ...);
    }
    
    return discriminatedResultMap;
}
```

---

## 六、自定义 TypeHandler

### 6.1 枚举映射

```java
// 方式一：MyBatis 内置的 EnumTypeHandler（存储枚举名） 和 EnumOrdinalTypeHandler（存储序号）

// mybatis-config.xml
<typeHandlers>
    <typeHandler handler="org.apache.ibatis.type.EnumTypeHandler" 
                 javaType="com.example.enums.Status"/>
</typeHandlers>

// 方式二：在映射中指定
<result column="status" property="status" typeHandler="org.apache.ibatis.type.EnumOrdinalTypeHandler"/>
```

### 6.2 自定义 TypeHandler

```java
// 场景：将 JSON 字符串映射为 Java 对象
@MappedTypes(Role.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class RoleTypeHandler extends BaseTypeHandler<Role> {
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, 
                                     Role parameter, JdbcType jdbcType) {
        ps.setString(i, MAPPER.writeValueAsString(parameter));
    }
    
    @Override
    public Role getNullableResult(ResultSet rs, String columnName) {
        String json = rs.getString(columnName);
        return json == null ? null : MAPPER.readValue(json, Role.class);
    }
}

// 注册
@Bean
public RoleTypeHandler roleTypeHandler() {
    return new RoleTypeHandler();
}
```

---

## 七、ResultMapping 的解析

每个 `<result>`、`<id>`、`<association>`、`<collection>` 标签最终都解析为 `ResultMapping` 对象。

```java
// ResultMapping.java
public class ResultMapping {
    
    private Configuration configuration;
    private String property;           // Java 属性名
    private String column;             // 数据库列名
    private Class<?> javaType;         // Java 类型
    private JdbcType jdbcType;         // JDBC 类型
    private TypeHandler<?> typeHandler; // 类型处理器
    private String nestedResultMapId;  // 嵌套 resultMap ID（association/collection）
    private String nestedQueryId;      // 嵌套查询 ID
    private Set<String> notNullColumns; // NOT NULL 列集合
    private String columnPrefix;       // 列名前缀
    private List<ResultFlag> flags;    // 标志（ID/CONSTRUCTOR）
    private List<ResultMapping> composites; // 复合主键
}
```

**ResultMapping 的构建（在 ResultMap.Builder 中）：**

```java
private ResultMapping buildResultMappingFromContext(XNode context) {
    try {
        String property = context.getStringAttribute("property");
        String column = context.getStringAttribute("column");
        String javaTypeAttr = context.getStringAttribute("javaType");
        String jdbcTypeAttr = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        String nestedResultMap = context.getStringAttribute("resultMap");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String fetchType = context.getStringAttribute("fetchType");
        String resultSet = context.getStringAttribute("resultSet");
        
        // ★ 解析 javaType
        Class<?> javaType = resolveJavaType(javaTypeAttr, nestedResultMap, property);
        
        // ★ 解析 typeHandler
        TypeHandler<?> typeHandler = resolveTypeHandler(context, javaType);
        
        // ★ 构建 ResultMapping
        ResultMapping.Builder builder = new ResultMapping.Builder(configuration, property, column, javaType);
        builder.jdbcType(resolveJdbcType(jdbcTypeAttr));
        builder.nestedQueryId(nestedSelect);
        builder.nestedResultMapId(nestedResultMap);
        builder.columnPrefix(columnPrefix);
        builder.fetchType(resolveFetchType(fetchType));
        builder.typeHandler(typeHandler);
        
        return builder.build();
    } catch (Exception e) {
        throw new BuilderException("Error building ResultMapping.", e);
    }
}
```

---

## 八、总结

### resultMap 配置速查

| 元素 | 用途 | 关键属性 |
|------|------|---------|
| `<id>` | 主键映射（性能优化 + 去重）| property, column |
| `<result>` | 普通字段映射 | property, column |
| `<constructor>` | 构造器参数映射 | `<idArg>`, `<arg>` |
| `<association>` | 嵌套对象（has-one）| select / resultMap, columnPrefix |
| `<collection>` | 集合对象（has-many）| select / resultMap, columnPrefix |
| `<discriminator>` | 根据列值选择 resultMap | column, javaType, `<case>` |

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `ResultMap` | 封装整个 resultMap 的配置信息 |
| `ResultMapping` | 单个字段/关联的映射配置 |
| `DefaultResultSetHandler` | 核心：将 ResultSet 转为 Java 对象 |
| `ResultSetWrapper` | ResultSet 的包装器（列名、TypeHandler 查找）|
| `TypeHandlerRegistry` | TypeHandler 注册与管理 |

---

**下一篇：** [MyBatis 结果映射进阶（二）：级联查询、鉴别器与延迟加载源码]({{< relref "post/mybatis-resultmap-advanced" >}})

**系列索引：**
- [MyBatis 结果映射进阶（一）：resultMap 高级用法]({{< relref "post/mybatis-resultmap-basics" >}})
- [MyBatis 结果映射进阶（二）：级联查询、鉴别器与延迟加载源码]({{< relref "post/mybatis-resultmap-advanced" >}})
