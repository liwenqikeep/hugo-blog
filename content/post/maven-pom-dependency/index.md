---
title: "Maven（一）：POM 模型与依赖管理"
date: 2019-03-12
draft: false
categories: ["工具"]
tags: ["Maven", "POM", "依赖管理", "坐标", "scope", "传递依赖"]
toc: true
---

## 前言

Maven 是 Java 生态中最流行的构建工具，核心在于其**约定优于配置**的理念和强大的**依赖管理**能力。理解 POM 模型和依赖机制，是使用 Maven 的基础。

本文从 POM 结构入手，逐步深入到依赖的 scope、传递性、冲突解决机制。

<!--more-->

## 一、POM 模型

### 1.1 POM 结构

```xml
<project>
    <!-- 项目坐标（唯一标识） -->
    <groupId>com.example</groupId>
    <artifactId>my-app</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>     <!-- jar/war/pom -->
    
    <!-- 继承 -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.3.4.RELEASE</version>
        <relativePath/>  <!-- 从仓库查找 -->
    </parent>
    
    <!-- 属性 -->
    <properties>
        <java.version>11</java.version>
        <maven.compiler.source>${java.version}</maven.compiler.source>
        <maven.compiler.target>${java.version}</maven.compiler.target>
    </properties>
    
    <!-- 依赖 -->
    <dependencies>
        <dependency>...</dependency>
    </dependencies>
    
    <!-- 依赖管理（统一版本） -->
    <dependencyManagement>
        <dependencies>...</dependencies>
    </dependencyManagement>
    
    <!-- 构建配置 -->
    <build>
        <plugins>...</plugins>
    </build>
</project>
```

### 1.2 坐标（Coordinates）

```xml
<!-- Maven 坐标 = GAV：唯一标识一个构件 -->

<groupId>com.example</groupId>          <!-- 组织/项目名 -->
<artifactId>user-service</artifactId>    <!-- 模块名 -->
<version>1.0.0-RELEASE</version>         <!-- 版本号 -->
<packaging>jar</packaging>               <!-- 打包方式 -->

<!-- 版本规范 -->
1.0.0-RELEASE     → 正式发布版
1.0.0-SNAPSHOT    → 开发快照版
1.0.0-RC1         → 候选发布版
1.0.0-M1          → 里程碑版
```

---

## 二、依赖配置

### 2.1 基本依赖

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <version>2.3.4.RELEASE</version>
        <!-- scope、optional、exclusions 等 -->
    </dependency>
</dependencies>
```

### 2.2 scope——依赖范围

| scope | 编译期 | 运行期 | 打包 | 说明 |
|-------|:------:|:------:|:----:|------|
| **compile**（默认）| ✅ | ✅ | ✅ | 所有阶段都可用 |
| **provided** | ✅ | ✅ | ❌ | 容器提供（如 servlet-api）|
| **runtime** | ❌ | ✅ | ✅ | 编译不需要，运行需要（如 JDBC 驱动）|
| **test** | ❌ | ❌ | ❌ | 仅测试阶段（如 JUnit）|
| **system** | ✅ | ✅ | ❌ | 本地系统路径（不推荐）|

```xml
<!-- provided：编译时需要，运行时由容器提供 -->
<dependency>
    <groupId>javax.servlet</groupId>
    <artifactId>javax.servlet-api</artifactId>
    <version>4.0.1</version>
    <scope>provided</scope>
</dependency>

<!-- runtime：运行时需要，编译时不需要 -->
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <version>8.0.25</version>
    <scope>runtime</scope>
</dependency>

<!-- test：仅测试阶段 -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter-api</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 三、传递依赖

### 3.1 传递性

```
A 依赖 B，B 依赖 C
→ A 自动获得 C 的依赖

传递依赖的 scope 继承规则：

依赖① → 依赖②    结果
compile    compile     compile
compile    provided    provided（有争议）
compile    runtime     runtime
compile    test        无
provided   compile     provided
provided   provided    provided
runtime    compile     runtime
runtime    runtime     runtime
```

### 3.2 依赖冲突

```
当同一个 jar 出现多个版本时，Maven 按以下规则选择：

1. 最短路径优先
   A → B → C → logback 1.0.0（路径长度 3）
   A → D → logback 2.0.0（路径长度 2）
   → 选择 logback 2.0.0（路径更短）

2. 第一声明优先（路径长度相同时）
   A → B → logback 1.0.0
   A → C → logback 2.0.0
   B 在 C 之前声明 → 选择 logback 1.0.0
```

```bash
# 查看依赖树（排查冲突）
mvn dependency:tree

# 查看详细依赖
mvn dependency:tree -Dverbose

# 输出示例
# [INFO] com.example:my-app:jar:1.0.0
# [INFO] +- org.springframework.boot:spring-boot-starter-web:jar:2.3.4.RELEASE:compile
# [INFO] |  +- org.springframework.boot:spring-boot-starter:jar:2.3.4.RELEASE:compile
# [INFO] |  |  +- org.springframework.boot:spring-boot:jar:2.3.4.RELEASE:compile
# [INFO] |  |  +- org.springframework.boot:spring-boot-autoconfigure:jar:2.3.4.RELEASE:compile
# [INFO] |  |  \- org.yaml:snakeyaml:jar:1.26:compile
# (selected for conflict resolution 1.26)  → 冲突解决结果
```

### 3.3 排除依赖

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>some-library</artifactId>
    <version>1.0</version>
    <exclusions>
        <!-- 排除传递进来的冲突依赖 -->
        <exclusion>
            <groupId>commons-logging</groupId>
            <artifactId>commons-logging</artifactId>
        </exclusion>
        <!-- 排除多个 -->
        <exclusion>
            <groupId>log4j</groupId>
            <artifactId>log4j</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### 3.4 可选依赖

```xml
<!-- optional：引入此依赖的项目不会自动获得此依赖 -->
<!-- 用于"二选一"的场景，如 slf4j 的多种日志实现 -->

<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-log4j12</artifactId>
    <version>1.7.30</version>
    <optional>true</optional>
</dependency>
```

---

## 四、属性管理

### 4.1 常用属性

```xml
<properties>
    <!-- Java 版本 -->
    <java.version>11</java.version>
    <maven.compiler.source>${java.version}</maven.compiler.source>
    <maven.compiler.target>${java.version}</maven.compiler.target>
    
    <!-- 编码 -->
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
    
    <!-- 统一版本管理 -->
    <spring-boot.version>2.3.4.RELEASE</spring-boot.version>
    <spring-cloud.version>Hoxton.SR8</spring-cloud.version>
    <mybatis.version>3.5.7</mybatis.version>
</properties>
```

### 4.2 通过属性统一版本

```xml
<properties>
    <jackson.version>2.12.3</jackson.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-core</artifactId>
        <version>${jackson.version}</version>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
    </dependency>
</dependencies>
```

---

## 五、依赖管理——dependencyManagement

### 5.1 作用

```
dependencyManagement 不直接引入依赖
只声明版本号，供子模块引用

子模块使用该依赖时，不需要写 version
统一版本管理，避免不一致
```

### 5.2 父 POM 中声明

```xml
<!-- parent-pom.xml -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.12.3</version>
        </dependency>
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
            <version>8.0.25</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

```xml
<!-- 子模块中无需指定版本 -->
<dependencies>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <!-- 省略 version，继承父 POM 的声明 -->
    </dependency>
</dependencies>
```

---

## 六、依赖分析常用命令

```bash
# 查看依赖树
mvn dependency:tree

# 查看已解析的依赖列表
mvn dependency:resolve

# 分析未使用的依赖
mvn dependency:analyze
# 会报告：Used undeclared dependencies（用到但未声明）
#         Unused declared dependencies（声明但未用到）

# 查看依赖来源
mvn dependency:tree -Dincludes=com.fasterxml.jackson
# 只显示 Jackson 相关的依赖树
```

---

## 七、总结

### POM 核心元素速查

| 元素 | 说明 |
|------|------|
| groupId:artifactId:version | 坐标（GAV，唯一标识）|
| packaging | 打包方式（jar/war/pom）|
| parent | 继承父 POM |
| properties | 属性定义 |
| dependencies | 依赖声明 |
| dependencyManagement | 版本统一管理 |
| build | 构建配置（插件等）|

### 依赖配置速查

| 元素 | 说明 |
|------|------|
| scope | compile/provided/runtime/test |
| optional | 可选依赖 |
| exclusions | 排除传递依赖 |
| type | 依赖类型（默认 jar）|
| classifier | 分类器（如 sources）|

**下一篇：** [Maven（二）：生命周期与插件]({{< relref "post/maven-lifecycle-plugin" >}})
