---
title: "Maven（三）：仓库与配置"
date: 2019-03-16
draft: false
categories: ["工具"]
tags: ["Maven", "仓库", "Nexus", "settings.xml", "mirror", "profile"]
toc: true
---

## 前言

Maven 仓库用于存储构建产物（jar、war、pom）。理解仓库的分类、settings.xml 的配置，以及如何搭建私服，是团队协作和持续集成的基础。

<!--more-->

## 一、仓库分类

### 1.1 仓库层次

```
Maven 仓库分为三类：

本地仓库（Local Repository）
  ├── ~/.m2/repository/
  ├── 默认存储位置
  └── 存放从远程下载的 jar 和本地构建产物

中央仓库（Central Repository）
  ├── https://repo.maven.apache.org/maven2
  ├── Maven 官方维护的公共仓库
  └── 包含了绝大多数流行的 Java 库

远程仓库（Remote Repository / 私服）
  ├── 公司内部搭建的 Nexus / Artifactory
  ├── 存放公司内部构件
  └── 代理中央仓库（加速下载，节省带宽）
```

### 1.2 查找顺序

```
Maven 查找依赖的顺序：

1. 本地仓库 → 找到则使用
2. 远程仓库（settings.xml 中配置的）
3. 中央仓库（内置）
4. 找不到 → 报错
```

---

## 二、settings.xml 详解

### 2.1 文件位置

```xml
<!-- 全局配置：$M2_HOME/conf/settings.xml -->
<!-- 用户配置：~/.m2/settings.xml（优先级更高） -->

<!-- 推荐：配置用户级别的 settings.xml -->
```

### 2.2 完整配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings>
    <!-- 本地仓库路径 -->
    <localRepository>D:/maven/repository</localRepository>
    
    <!-- 是否使用交互模式 -->
    <interactiveMode>true</interactiveMode>
    
    <!-- 离线模式 -->
    <offline>false</offline>
    
    <!-- 插件组（默认已包含 org.apache.maven.plugins）-->
    <pluginGroups>
        <pluginGroup>org.springframework.boot</pluginGroup>
        <pluginGroup>org.mybatis.generator</pluginGroup>
    </pluginGroups>
    
    <!-- 代理配置（公司内网可能需要）-->
    <proxies>
        <proxy>
            <id>company-proxy</id>
            <active>true</active>
            <protocol>http</protocol>
            <host>proxy.company.com</host>
            <port>8080</port>
            <username>proxyUser</username>
            <password>proxyPass</password>
            <nonProxyHosts>localhost|*.company.com</nonProxyHosts>
        </proxy>
    </proxies>
    
    <!-- 服务器认证（发布 jar 到私服时使用）-->
    <servers>
        <server>
            <id>nexus-releases</id>
            <username>deployer</username>
            <password>deployer123</password>
        </server>
        <server>
            <id>nexus-snapshots</id>
            <username>deployer</username>
            <password>deployer123</password>
        </server>
    </servers>
    
    <!-- 镜像（拦截对某个仓库的请求，转向另一个地址）-->
    <mirrors>
        <mirror>
            <id>nexus-mirror</id>
            <mirrorOf>*</mirrorOf>       <!-- 拦截所有仓库请求 -->
            <url>http://nexus.company.com/repository/maven-public/</url>
        </mirror>
    </mirrors>
    
    <!-- 环境配置（不同环境的仓库地址）-->
    <profiles>
        <profile>
            <id>company-repos</id>
            <repositories>
                <repository>
                    <id>nexus-public</id>
                    <url>http://nexus.company.com/repository/maven-public/</url>
                    <releases><enabled>true</enabled></releases>
                    <snapshots><enabled>true</enabled></snapshots>
                </repository>
            </repositories>
            <pluginRepositories>
                <pluginRepository>
                    <id>nexus-public</id>
                    <url>http://nexus.company.com/repository/maven-public/</url>
                </pluginRepository>
            </pluginRepositories>
        </profile>
    </profiles>
    
    <!-- 激活 profile -->
    <activeProfiles>
        <activeProfile>company-repos</activeProfile>
    </activeProfiles>
</settings>
```

---

## 三、镜像配置

### 3.1 mirrorOf 配置

```xml
<!-- mirrorOf 的值决定了镜像拦截的范围 -->

<mirrorOf>*</mirrorOf>                              <!-- 拦截所有仓库 -->
<mirrorOf>external:*</mirrorOf>                     <!-- 拦截所有外部仓库 -->
<mirrorOf>repo1,repo2</mirrorOf>                    <!-- 拦截指定 ID 的仓库 -->
<mirrorOf>*,!central</mirrorOf>                     <!-- 除中央仓库外的所有 -->
<mirrorOf>central</mirrorOf>                        <!-- 仅拦截中央仓库 -->
```

### 3.2 阿里云镜像（国内开发必备）

```xml
<mirrors>
    <mirror>
        <id>aliyun-maven</id>
        <mirrorOf>central</mirrorOf>
        <name>阿里云 Maven 镜像</name>
        <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
</mirrors>
```

---

## 四、私服搭建（Nexus）

### 4.1 Nexus 仓库类型

```
Nexus 中有三种仓库类型：

proxy（代理仓库）：
  代理中央仓库或其他远程仓库
  缓存下载的构件，加快下载速度

hosted（宿主仓库）：
  存放公司自研的构件
  如 SNAPSHOT 版本、Release 版本

group（仓库组）：
  将多个 proxy + hosted 组合在一起
  对外提供统一的访问地址
```

### 4.2 部署构件到私服

```xml
<!-- pom.xml — 发布配置 -->
<distributionManagement>
    <repository>
        <id>nexus-releases</id>
        <name>Nexus Release Repository</name>
        <url>http://nexus.company.com/repository/maven-releases/</url>
    </repository>
    <snapshotRepository>
        <id>nexus-snapshots</id>
        <name>Nexus Snapshot Repository</name>
        <url>http://nexus.company.com/repository/maven-snapshots/</url>
    </snapshotRepository>
</distributionManagement>
```

```bash
# 部署到私服
mvn deploy

# SNAPSHOT 版本自动部署到 snapshot 仓库
# RELEASE 版本自动部署到 releases 仓库（不能重复发布相同版本）
```

---

## 五、Profile 配置

### 5.1 多环境构建

```xml
<profiles>
    <!-- 开发环境（默认）-->
    <profile>
        <id>dev</id>
        <activation>
            <activeByDefault>true</activeByDefault>
        </activation>
        <properties>
            <env>dev</env>
            <db.url>jdbc:mysql://localhost:3306/dev_db</db.url>
            <db.username>dev_user</db.username>
            <db.password>dev_pass</db.password>
        </properties>
    </profile>
    
    <!-- 测试环境 -->
    <profile>
        <id>test</id>
        <properties>
            <env>test</env>
            <db.url>jdbc:mysql://test-db:3306/test_db</db.url>
            <db.username>test_user</db.username>
            <db.password>test_pass</db.password>
        </properties>
    </profile>
    
    <!-- 生产环境 -->
    <profile>
        <id>production</id>
        <properties>
            <env>prod</env>
            <db.url>jdbc:mysql://prod-db:3306/prod_db</db.url>
            <db.username>prod_user</db.username>
            <db.password>prod_pass</db.password>
        </properties>
    </profile>
</profiles>
```

```bash
# 激活 profile
mvn package -P production
mvn package -P test
```

### 5.2 profile 激活方式

```xml
<!-- 方式一：命令行 -P -->
mvn package -P production

<!-- 方式二：settings.xml 中默认激活 -->
<activeProfiles>
    <activeProfile>company-repos</activeProfile>
</activeProfiles>

<!-- 方式三：条件激活 -->
<activation>
    <activeByDefault>true</activeByDefault>    <!-- 默认激活 -->
    <jdk>1.8</jdk>                              <!-- JDK 版本 -->
    <os>
        <name>Windows 10</name>
        <family>windows</family>
    </os>
    <property>
        <name>environment</name>
        <value>dev</value>
    </property>
    <file>
        <exists>${basedir}/dev.properties</exists>  <!-- 文件存在时激活 -->
    </file>
</activation>
```

---

## 六、常见问题

### 6.1 jar 下载失败

```bash
# 问题：下载过程中断导致 jar 损坏
# 现象：Maven 报错 "missing artifact" 或 checksum 错误

# 解决：删除本地仓库对应目录下的 jar，重新下载
rm -rf ~/.m2/repository/com/example/broken-jar/
mvn clean install
```

### 6.2 settings.xml 配置失效

```bash
# 检查当前生效的配置
mvn help:effective-settings

# 检查当前生效的 POM
mvn help:effective-pom

# 查看依赖来源
mvn dependency:tree
```

### 6.3 代理问题

```xml
<!-- 公司网络需要 HTTP 代理 -->
<proxy>
    <id>company-proxy</id>
    <active>true</active>
    <protocol>http</protocol>
    <host>proxy.company.com</host>
    <port>8080</port>
    <nonProxyHosts>localhost|*.company.com|10.*</nonProxyHosts>
</proxy>
```

---

## 七、总结

### 仓库层次速记

```
本地仓库 ← 私服（Nexus）← 中央仓库
查找顺序：本地 → 私服 → 中央
```

### settings.xml 关键元素

| 元素 | 说明 |
|------|------|
| localRepository | 本地仓库路径 |
| servers | 认证信息（仓库密码）|
| mirrors | 镜像（拦截仓库请求）|
| profiles | 环境配置（仓库地址、属性）|
| activeProfiles | 默认激活的 profile |

**上一篇：** [Maven（二）：生命周期与插件]({{< relref "post/maven-lifecycle-plugin" >}})

**下一篇：** [Maven（四）：多模块项目与最佳实践]({{< relref "post/maven-multimodule-best-practice" >}})
