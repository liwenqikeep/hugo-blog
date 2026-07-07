---
title: "Maven（四）：多模块项目与最佳实践"
date: 2019-03-18
draft: false
categories: ["工具"]
tags: ["Maven", "多模块", "聚合", "继承", "最佳实践"]
toc: true
---

## 前言

中大型项目通常采用多模块结构，通过**聚合**和**继承**来管理多个子模块。本文覆盖多模块项目的结构设计、最佳实践和常见问题。

<!--more-->

## 一、多模块项目结构

### 1.1 典型结构

```
my-project/
├── pom.xml                     ← 父 POM（聚合 + 继承）
├── my-project-common/          ← 公共模块
│   └── pom.xml
├── my-project-service/         ← 业务模块
│   └── pom.xml
├── my-project-web/             ← Web 模块
│   └── pom.xml
└── my-project-api/             ← API 接口模块
    └── pom.xml
```

### 1.2 父 POM

```xml
<!-- parent-pom.xml — 聚合 + 继承 -->
<project>
    <modelVersion>4.0.0</modelVersion>
    
    <!-- 父 POM 的坐标 -->
    <groupId>com.example</groupId>
    <artifactId>my-project</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>          <!-- 父模块打包类型必须是 pom -->
    
    <!-- ★ 聚合子模块 -->
    <modules>
        <module>my-project-common</module>
        <module>my-project-service</module>
        <module>my-project-web</module>
        <module>my-project-api</module>
    </modules>
    
    <!-- ★ 统一版本管理 -->
    <properties>
        <java.version>11</java.version>
        <spring-boot.version>2.3.4.RELEASE</spring-boot.version>
        <mybatis.version>3.5.7</mybatis.version>
        <jackson.version>2.12.3</jackson.version>
    </properties>
    
    <!-- ★ 继承的子模块共享依赖版本 -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${jackson.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
    
    <!-- ★ 所有子模块共享的依赖 -->
    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
    
    <!-- ★ 公共构建配置 -->
    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

### 1.3 子模块 POM

```xml
<!-- my-project-service/pom.xml -->
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>my-project</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    
    <artifactId>my-project-service</artifactId>
    
    <dependencies>
        <!-- 引用兄弟模块 -->
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>my-project-common</artifactId>
            <version>${project.version}</version>  <!-- 使用同一版本 -->
        </dependency>
        
        <!-- 依赖版本已由父 POM 管理，无需写 version -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
    </dependencies>
</project>
```

---

## 二、聚合与继承

### 2.1 聚合 vs 继承

```
聚合（Aggregation）：
  父 POM 通过 <modules> 列出子模块
  作用是：一条命令构建所有子模块（mvn clean install）

继承（Inheritance）：
  子 POM 通过 <parent> 指向父 POM
  作用是：子模块共享父 POM 中的配置
```

| 对比 | 聚合 | 继承 |
|------|------|------|
| 配置方式 | `<modules>` | `<parent>` |
| 作用 | 一起构建 | 共享配置 |
| 依赖 | 子模块自动加入构建 | 子模块继承配置 |
| 关系 | 容器 → 容器内模块 | 父 → 子 |

**通常将聚合和继承放在同一个 POM 中（即一个父 POM 同时扮演两个角色）。**

---

## 三、依赖 scope import

### 3.1 BOM 导入

```xml
<!-- 当父 POM 已经有一个 parent 时（如 Spring Boot），无法再继承 -->
<!-- 使用 dependencyManagement import 解决 -->

<project>
    <!-- 父 POM 已被占用 -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.3.4.RELEASE</version>
    </parent>
    
    <artifactId>my-project</artifactId>
    <packaging>pom</packaging>
    
    <dependencyManagement>
        <dependencies>
            <!-- 导入另一个 BOM -->
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>Hoxton.SR8</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

### 3.2 常用 BOM

```xml
<dependencyManagement>
    <dependencies>
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>Hoxton.SR8</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        
        <!-- Spring Cloud Alibaba -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>2.2.5.RELEASE</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 四、工程化实践

### 4.1 统一版本管理

```xml
<!-- 推荐使用 properties + dependencyManagement 统一管理版本 -->
<!-- 所有依赖的版本都在父 POM 中声明 -->

<!-- 不要在各子模块中分散写版本号 -->
<!-- 改版本时只改父 POM 一处 -->
```

### 4.2 模块划分原则

```
1. 按业务领域划分
   order-service、user-service、payment-service

2. 按技术层次划分
   common（公共工具）
   dal（数据访问）
   service（业务逻辑）
   web（接口层）

3. 按功能维度划分
   api（接口定义）
   core（核心实现）
   client（客户端 SDK）

推荐：先按技术层次分，再按业务领域拆
```

### 4.3 Spring Boot 多模块

```xml
<!-- Spring Boot 多模块父 POM -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.3.4.RELEASE</version>
</parent>

<groupId>com.example</groupId>
<artifactId>my-project</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>

<modules>
    <module>my-project-common</module>
    <module>my-project-dal</module>
    <module>my-project-service</module>
    <module>my-project-web</module>
</modules>
```

```bash
# 构建整个项目
mvn clean install

# 只构建某个模块及其依赖
mvn install -pl my-project-web -am
# -pl：指定模块
# -am：同时构建依赖的模块

# 构建并跳过指定模块的测试
mvn install -pl my-project-web -am -DskipTests
```

---

## 五、最佳实践

### 5.1 排除非必需依赖

```xml
<!-- 定期检查未使用的依赖 -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>analyze</goal></goals>
        </execution>
    </executions>
</plugin>
```

### 5.2 锁定 jar 版本

```xml
<!-- 通过 properties 集中管理版本，而不是分散在子模块 -->
<!-- ❌ 不推荐 -->
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>30.1-jre</version>  <!-- 版本写在这里，难维护 -->
</dependency>

<!-- ✅ 推荐：统一在父 POM 的 properties 中管理 -->
```

### 5.3 发布 SNAPSHOT 的注意事项

```xml
<!-- 开发阶段使用 SNAPSHOT 版本 -->
<version>1.0.0-SNAPSHOT</version>

<!-- 正式发布时改为 RELEASE 版本 -->
<version>1.0.0</version>

<!-- 注意：SNAPSHOT 可以重复发布，RELEASE 不能 -->
```

### 5.4 生成效率

```bash
# 多线程构建
mvn clean install -T 4

# 跳过不需要的阶段
mvn install -DskipTests

# 离线构建（不需要网络下载）
mvn install -o

# 跳过某些模块
mvn install -pl !my-project-api
```

---

## 六、常见问题

### 6.1 子模块版本不一致

```xml
<!-- 问题：子模块 version 与父 POM 不一致 -->
<!-- 解决：子模块不写 version，继承父 POM 的版本 -->
```

### 6.2 循环依赖

```
问题：模块 A 依赖模块 B，模块 B 也依赖模块 A
解决：拆分为三个模块（common + A + B）
      A 和 B 都依赖 common，但 A 和 B 互不依赖
```

### 6.3 parent 版本与子模块版本

```xml
<!-- 父 POM version 变更时，所有子模块都自动继承 -->
<!-- 避免手动修改每个子模块 -->
<!-- 使用 ${revision} 实现统一版本（Maven 3.5.2+） -->

<properties>
    <revision>1.0.0</revision>
</properties>

<version>${revision}</version>
```

---

## 七、总结

### 多模块项目结构速查

```
父 POM（packaging=pom）
├── common（工具类、通用代码）
├── dal（数据访问层）
├── service（业务逻辑层）
├── api（接口定义、DTO）
└── web（Controller 层）
```

### 配置要点

| 配置 | 位置 | 作用 |
|------|------|------|
| `<parent>` | 子 POM | 继承父 POM |
| `<modules>` | 父 POM | 聚合子模块 |
| `<dependencyManagement>` | 父 POM | 统一依赖版本 |
| `<pluginManagement>` | 父 POM | 统一插件配置 |
| `<properties>` | 父 POM | 统一管理版本号 |

**上一篇：** [Maven（三）：仓库与配置]({{< relref "post/maven-repository-config" >}})

**系列索引：**
- [Maven（一）：POM 模型与依赖管理]({{< relref "post/maven-pom-dependency" >}})
- [Maven（二）：生命周期与插件]({{< relref "post/maven-lifecycle-plugin" >}})
- [Maven（三）：仓库与配置]({{< relref "post/maven-repository-config" >}})
- [Maven（四）：多模块项目与最佳实践]({{< relref "post/maven-multimodule-best-practice" >}})
