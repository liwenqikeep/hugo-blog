---
title: "MyBatis（三）：动态 SQL 深度解析"
date: 2018-06-24
draft: false
categories: ["Java", "ORM"]
tags: ["MyBatis", "动态SQL", "SqlNode", "OGNL", "XML解析", "源码分析"]
toc: true
---

## 前言

动态 SQL 是 MyBatis 最强大的特性之一。它允许在 XML 中使用 `<if>`、`<where>`、`<foreach>` 等标签，根据参数值动态拼接 SQL 语句，避免在 Java 代码中拼接大量 SQL 字符串。

本文从使用到源码，完整追踪 MyBatis 如何将 XML 中的动态 SQL 标签解析并执行为最终的 SQL 语句。

<!--more-->

> **源码版本：** MyBatis 3.5.x

## 一、动态 SQL 标签概览

```xml
<select id="findUsers" resultType="User">
    SELECT * FROM users
    <where>
        <if test="name != null">
            AND name = #{name}
        </if>
        <if test="email != null">
            AND email = #{email}
        </if>
        <if test="ids != null and ids.size() > 0">
            AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
        </if>
    </where>
    ORDER BY id
    <if test="limit != null">
        LIMIT #{limit}
    </if>
</select>
```

| 标签 | 作用 |
|------|------|
| `<if>` | 条件判断 |
| `<choose>` / `<when>` / `<otherwise>` | 多分支选择 |
| `<where>` | WHERE 条件（自动处理 AND/OR 前缀）|
| `<set>` | SET 子句（自动处理逗号后缀）|
| `<trim>` | 自定义前后缀处理 |
| `<foreach>` | 集合遍历 |
| `<bind>` | 变量绑定 |
| `<sql>` / `<include>` | SQL 片段定义与引用 |

---

## 二、动态 SQL 的解析过程

动态 SQL 的解析分为两个阶段：

```
第一阶段（启动时）：XML → SqlNode 树
    XML 中的 <if>、<where> 等标签 → 对应的 SqlNode 实现类
    非动态文本 → StaticTextSqlNode
    带 #{xxx} 的文本 → TextSqlNode
    
第二阶段（运行时）：SqlNode 树 → SQL 字符串
    根据参数值递归执行 SqlNode.apply()
    → 拼接最终 SQL 字符串 → BoundSql
```

### 2.1 XMLScriptBuilder 解析入口

在解析 Mapper XML 的 `<select>` 等标签时，`XMLStatementBuilder` 委托 `XMLScriptBuilder` 处理动态 SQL：

```java
// XMLStatementBuilder.java
private void parseStatementNode() {
    // ...
    XMLScriptBuilder scriptBuilder = new XMLScriptBuilder(configuration, context, parameterType);
    // ★ 解析脚本节点，返回 SqlSource
    SqlSource sqlSource = scriptBuilder.parseScriptNode();
    // ...
}
```

```java
// XMLScriptBuilder.java
public SqlSource parseScriptNode() {
    // ★ 调用 parseDynamicTags 递归解析节点
    MixedSqlNode rootSqlNode = parseDynamicTags(context);
    SqlSource sqlSource;
    if (isDynamic) {
        // 包含动态标签 → DynamicSqlSource（每次执行时重新解析）
        sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
    } else {
        // 纯静态 SQL → RawSqlSource（编译期就确定 SQL）
        sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
    }
    return sqlSource;
}
```

### 2.2 parseDynamicTags——递归解析

```java
// XMLScriptBuilder.java
protected MixedSqlNode parseDynamicTags(XNode node) {
    List<SqlNode> contents = new ArrayList<>();
    NodeList children = node.getNode().getChildNodes();
    
    for (int i = 0; i < children.getLength(); i++) {
        XNode child = node.newXNode(children.item(i));
        
        if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE
                || child.getNode().getNodeType() == Node.TEXT_NODE) {
            // ★ 文本节点
            String data = child.getStringBody("");
            TextSqlNode textSqlNode = new TextSqlNode(data);
            if (textSqlNode.isDynamic()) {
                // 包含 ${} 或 #{...} 中的 ${}（动态部分）
                contents.add(textSqlNode);
                isDynamic = true;
            } else {
                // 纯静态文本
                contents.add(new StaticTextSqlNode(data));
            }
        } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) {
            // ★ 元素节点（<if>、<where> 等标签）
            String nodeName = child.getNode().getNodeName();
            
            // ★ 根据标签名创建对应的 SqlNode 处理器
            NodeHandler handler = nodeHandlerMap.get(nodeName);
            if (handler == null) {
                throw new BuilderException("Unknown element <" + nodeName + "> ...");
            }
            handler.handleNode(child, contents);
            isDynamic = true;
        }
    }
    return new MixedSqlNode(contents);
}
```

### 2.3 NodeHandler 注册

```java
// XMLScriptBuilder 初始化时注册各标签的处理器
private void initNodeHandlerMap() {
    nodeHandlerMap.put("trim", new TrimHandler());
    nodeHandlerMap.put("where", new WhereHandler());
    nodeHandlerMap.put("set", new SetHandler());
    nodeHandlerMap.put("foreach", new ForEachHandler());
    nodeHandlerMap.put("if", new IfHandler());
    nodeHandlerMap.put("choose", new ChooseHandler());
    nodeHandlerMap.put("when", new IfHandler());
    nodeHandlerMap.put("otherwise", new OtherwiseHandler());
    nodeHandlerMap.put("bind", new BindHandler());
}
```

---

## 三、SqlNode 体系

每种动态 SQL 标签对应一个 `SqlNode` 实现类：

```java
public interface SqlNode {
    // ★ 运行时根据参数值决定是否生成 SQL
    boolean apply(DynamicContext context);
}
```

### 3.1 SqlNode 实现类层次

```
SqlNode
  ├── StaticTextSqlNode         — 静态文本，直接追加
  ├── TextSqlNode               — 包含 ${} 的动态文本
  ├── MixedSqlNode              — 子 SqlNode 集合
  ├── IfSqlNode                 — <if test="...">
  ├── WhereSqlNode              — <where>（TrimSqlNode 子类）
  ├── SetSqlNode                — <set>（TrimSqlNode 子类）
  ├── TrimSqlNode               — <trim prefix="" prefixOverrides="">
  ├── ForEachSqlNode            — <foreach>
  ├── ChooseSqlNode             — <choose>
  ├── VarDeclSqlNode            — <bind>
  └── SetSqlNode / WhereSqlNode 等
```

### 3.2 IfSqlNode

```java
// IfSqlNode.java
public class IfSqlNode implements SqlNode {
    
    private final ExpressionEvaluator evaluator;  // OGNL 表达式求值器
    private final String test;                    // test 表达式
    private final SqlNode contents;               // 子节点
    
    public IfSqlNode(SqlNode contents, String test) {
        this.test = test;
        this.contents = contents;
        this.evaluator = new ExpressionEvaluator();
    }
    
    @Override
    public boolean apply(DynamicContext context) {
        // ★ 使用 OGNL 计算 test 表达式的值
        if (evaluator.evaluateBoolean(test, context.getBindings())) {
            // 条件为 true → 应用子节点
            contents.apply(context);
            return true;
        }
        return false;
    }
}
```

### 3.3 TrimSqlNode（Where/Set 基类）

```java
// TrimSqlNode.java
public class TrimSqlNode implements SqlNode {
    
    private final SqlNode contents;
    private final String prefix;            // 前缀（WHERE）
    private final String suffix;            // 后缀
    private final List<String> prefixesToOverride;  // 要移除的前缀（AND/OR）
    private final List<String> suffixesToOverride;  // 要移除的后缀（逗号）
    
    @Override
    public boolean apply(DynamicContext context) {
        FilteredDynamicContext filteredContext = new FilteredDynamicContext(context);
        // 1. 先解析子节点（生成临时的 SQL 片段）
        boolean result = contents.apply(filteredContext);
        
        // 2. 处理前缀和后缀
        filteredContext.applyAll();
        return result;
    }
    
    // <where> 的配置：
    //   prefix = "WHERE"
    //   prefixesToOverride = "AND |OR |\nAND |\nOR "
    //
    // <set> 的配置：
    //   prefix = "SET"
    //   suffixesToOverride = ","
}
```

### 3.4 WhereSqlNode 的工作过程

```xml
<where>
    <if test="name != null">AND name = #{name}</if>
    <if test="email != null">AND email = #{email}</if>
</where>
```

```
假设 name=null, email="test@test.com"

处理过程：
1. 子节点依次 apply()
   - name != null → false, 跳过
   - email != null → true, 追加 "AND email = ?"
   
2. 临时结果: "AND email = ?"
   
3. TrimSqlNode 处理:
   - prefix = "WHERE"
   - 移除前缀 "AND " → "email = ?"
   - 最终: "WHERE email = ?"
```

### 3.5 ForEachSqlNode

```xml
<foreach collection="ids" item="id" open="(" separator="," close=")">
    #{id}
</foreach>
```

```java
// ForEachSqlNode.java
public class ForEachSqlNode implements SqlNode {
    
    private final String collectionExpression;  // 集合表达式（"ids"）
    private final String item;                  // 当前元素变量名（"id"）
    private final String index;                 // 索引变量名
    private final String open;                  // 开始符（"("）
    private final String close;                 // 结束符（")"）
    private final String separator;             // 分隔符（","）
    private final SqlNode contents;             // #{id}
    
    @Override
    public boolean apply(DynamicContext context) {
        // 1. 通过 OGNL 获取集合对象
        Map<String, Object> bindings = context.getBindings();
        Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
        
        // 2. 追加 open
        if (!iterable.iterator().hasNext()) {
            return false;
        }
        context.appendSql(open);
        
        int i = 0;
        for (Object itemObj : iterable) {
            // 根据是否第一个元素决定是否加分隔符
            if (i > 0) {
                context.appendSql(separator);
            }
            
            // ★ 将当前元素和索引绑定到 context
            // 使得子节点中的 #{item} 能获取到值
            context.addItem(item, itemObj);
            if (index != null) {
                context.addItem(index, i);
            }
            
            // 调用子节点（#{item}）
            contents.apply(context);
            i++;
        }
        
        // 追加 close
        context.appendSql(close);
        return true;
    }
}
```

---

## 四、${} 与 #{} 的区别

### 4.1 TextSqlNode（处理 ${}）

```java
// TextSqlNode.java
public class TextSqlNode implements SqlNode {
    
    private final String text;
    private final Pattern filter = Pattern.compile("(\\$\\{[^}]+\\})");
    
    @Override
    public boolean apply(DynamicContext context) {
        // ★ 使用正则匹配 ${xxx} 并替换为 OGNL 求值
        String value = filter.matcher(text).replaceAll(match -> {
            String key = match.group().substring(2, match.group().length() - 1);
            Object value = OgnlCache.getValue(key, context.getBindings());
            return String.valueOf(value);  // 直接替换为字符串
        });
        context.appendSql(value);
        return true;
    }
    
    public boolean isDynamic() {
        // 包含 ${} 即为动态 SQL
        return filter.matcher(text).find();
    }
}
```

### 4.2 #{} 的处理

`#{}` 不在 TextSqlNode 阶段处理，而是在 `ParameterHandler` 设置参数时处理：

```java
// #{name} 在 SQL 解析阶段被替换为 ?
// 参数值通过 ParameterHandler.setParameters() 设置

// 最终 SQL：SELECT * FROM users WHERE name = ?
// 参数值：通过 PreparedStatement.setString(1, "Tom") 设置
```

### 4.3 对比

| 方式 | 处理时机 | 安全 | 用法 |
|------|---------|------|------|
| `#{}` | 参数设置阶段（PreparedStatement）| ✅ 防 SQL 注入 | 参数占位符 |
| `${}` | SQL 生成阶段（字符串替换）| ❌ 有注入风险 | 表名、列名、排序字段 |

---

## 五、DynamicSqlSource 与 BoundSql

### 5.1 DynamicSqlSource

```java
// DynamicSqlSource.java
public class DynamicSqlSource implements SqlSource {
    
    private final Configuration configuration;
    private final SqlNode rootSqlNode;   // SQL 节点树
    
    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        // ★ 每次执行时重新生成 SQL
        DynamicContext context = new DynamicContext(configuration, parameterObject);
        
        // 1. 执行 SqlNode 树，拼接 SQL 字符串
        rootSqlNode.apply(context);
        
        // 2. 解析 #{} 占位符，生成 ParameterMapping 列表
        SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
        Class<?> parameterType = Object.class;
        
        // ★ 将 #{xxx} 替换为 ?，并记录 ParameterMapping
        SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
        
        // 3. 返回 BoundSql（包含 SQL 和参数映射信息）
        BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
        
        // 4. 复制额外的参数绑定（<bind> 标签等）
        for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
            boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
        }
        
        return boundSql;
    }
}
```

### 5.2 DynamicContext

```java
// DynamicContext.java
public class DynamicContext {
    
    // ★ 使用 StringBuilder 拼接 SQL
    private final StringBuilder sqlBuilder = new StringBuilder();
    
    // ★ 参数绑定上下文
    private final ContextMap bindings;
    
    public DynamicContext(Configuration configuration, Object parameterObject) {
        // 将参数对象放入 bindings
        if (parameterObject != null && !(parameterObject instanceof Map)) {
            // 如果参数不是 Map，通过 MetaObject 包装
            MetaObject metaObject = configuration.newMetaObject(parameterObject);
            bindings = new ContextMap(metaObject);
        } else {
            bindings = new ContextMap(null);
            bindings.put("_parameter", parameterObject);
        }
        
        // 内置绑定：_parameter、_databaseId
        bindings.put("_databaseId", configuration.getDatabaseId());
    }
    
    public void appendSql(String sql) {
        sqlBuilder.append(sql);
        sqlBuilder.append(" ");
    }
    
    public String getSql() {
        return sqlBuilder.toString().trim();
    }
    
    public Map<String, Object> getBindings() {
        return bindings;
    }
}
```

### 5.3 SqlSourceBuilder

`SqlSourceBuilder` 解析 `#{xxx}` 占位符，将其替换为 `?` 并记录参数映射：

```java
// SqlSourceBuilder.java
public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {
    // ★ 使用正则匹配 #{xxx} 占位符
    ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(
            configuration, parameterType, additionalParameters);
    GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
    
    // 替换 #{xxx} → ?，同时记录 ParameterMapping
    String sql = parser.parse(originalSql);
    
    return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
}
```

---

## 六、完整的动态 SQL 执行时序

```xml
<select id="findByName" resultType="User">
    SELECT * FROM users
    <where>
        <if test="name != null">AND name = #{name}</if>
    </where>
</select>
```

**执行过程：**

```
1. 启动时：XMLScriptBuilder.parseDynamicTags()
    → 生成 SqlNode 树：
      MixedSqlNode
        ├── StaticTextSqlNode("SELECT * FROM users")
        ├── WhereSqlNode
        │     └── IfSqlNode(test="name != null")
        │           └── TextSqlNode("AND name = #{name}")
        └── （无其他）

2. 运行时：DynamicSqlSource.getBoundSql(parameterObject)
    a. DynamicContext 初始化（参数绑定）
    b. MixedSqlNode.apply() 遍历子节点
       → StaticTextSqlNode: "SELECT * FROM users"
       → WhereSqlNode:
           → IfSqlNode.apply():
             → OGNL 计算 name != null → true
             → TextSqlNode.apply(): "AND name = #{name}"
           → TrimSqlNode 处理: 移除 "AND " → "WHERE name = #{name}"
    c. SqlSourceBuilder.parse():
       → "#{name}" → "?"
       → ParameterMapping("name")
    d. 生成 BoundSql: SQL = "SELECT * FROM users WHERE name = ?"
```

---

## 七、总结

### 动态 SQL 两个阶段

| 阶段 | 发生时间 | 工作 | 产出 |
|------|---------|------|------|
| 解析 | 容器启动时 | XML → SqlNode 树 | MixedSqlNode（节点树）|
| 执行 | 每次调用时 | SqlNode.apply() → SQL | BoundSql（最终 SQL + 参数映射）|

### SqlNode 解析对照

| XML 标签 | SqlNode 实现 | 核心逻辑 |
|---------|-------------|---------|
| `<if test="">` | IfSqlNode | OGNL 求值，true 则输出子节点 |
| `<where>` | WhereSqlNode | 自动处理 AND/OR 前缀 |
| `<set>` | SetSqlNode | 自动处理逗号后缀 |
| `<trim>` | TrimSqlNode | 自定义前后缀和覆盖 |
| `<foreach>` | ForEachSqlNode | 遍历集合，拼接分隔符 |
| `<choose>` | ChooseSqlNode | 遍历 WhenSqlNode，匹配第一个 |
| `${xxx}` | TextSqlNode | OGNL 求值后直接替换为字符串 |
| `#{xxx}` | SqlSourceBuilder | 替换为 ?，记录 ParameterMapping |

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `XMLScriptBuilder` | 解析动态 SQL 标签，构建 SqlNode 树 |
| `MixedSqlNode` | 子 SqlNode 容器，按序执行 |
| `IfSqlNode` | `<if>` 标签实现 |
| `TrimSqlNode` | `<trim>/<where>/<set>` 实现基类 |
| `ForEachSqlNode` | `<foreach>` 标签实现 |
| `TextSqlNode` | 包含 `${}` 的动态文本 |
| `DynamicContext` | SQL 拼接上下文 + 参数绑定 |
| `DynamicSqlSource` | 每次执行时重新生成 BoundSql |
| `SqlSourceBuilder` | 解析 `#{}` 占位符为 `?` |
| `ExpressionEvaluator` | OGNL 表达式求值 |

---

**上一篇：** [MyBatis（二）：Mapper 代理原理与 SQL 执行流程]({{< relref "post/mybatis-mapper-proxy-execution" >}})

**下一篇：** [MyBatis（四）：缓存机制与源码解析]({{< relref "post/mybatis-cache" >}})
