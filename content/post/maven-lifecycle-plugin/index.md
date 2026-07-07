---
title: "Maven（二）：生命周期与插件"
date: 2019-03-14
draft: false
categories: ["工具"]
tags: ["Maven", "生命周期", "插件", "plugin", "goal", "构建"]
toc: true
---

## 前言

Maven 的构建过程基于**生命周期（Lifecycle）**抽象，而具体的构建任务由**插件（Plugin）**执行。理解生命周期和插件的关系，是灵活使用 Maven 的关键。

Maven 的核心设计：**生命周期定义阶段，插件执行任务。**

<!--more-->

## 一、三套生命周期

Maven 有三套独立的生命周期：**clean**（清理）、**default**（构建）、**site**（站点）。

```bash
# clean 生命周期
mvn clean           # 清理 target 目录

# default 生命周期（最常用）
mvn compile         # 编译
mvn test            # 测试
mvn package         # 打包
mvn install         # 安装到本地仓库
mvn deploy          # 部署到远程仓库

# site 生命周期
mvn site            # 生成站点文档
mvn site-deploy     # 部署站点
```

### 1.1 default 生命周期（22 个阶段）

```
validate          ← 验证项目是否正确
initialize        ← 初始化构建状态
generate-sources  ← 生成源代码
process-sources   ← 处理源代码
generate-resources ← 生成资源文件
process-resources ← 复制资源到输出目录
compile           ← ★ 编译源代码
process-classes   ← 处理编译后的文件
generate-test-sources  ← 生成测试代码
process-test-sources   ← 处理测试代码
generate-test-resources ← 生成测试资源
process-test-resources ← 复制测试资源
test-compile      ← 编译测试代码
process-test-classes   ← 处理编译后的测试代码
test              ← ★ 运行测试
prepare-package   ← 打包预处理
package           ← ★ 打包（jar/war）
pre-integration-test   ← 集成测试预处理
integration-test  ← 集成测试
post-integration-test ← 集成测试后处理
verify            ← 验证
install           ← ★ 安装到本地仓库
deploy            ← ★ 部署到远程仓库
```

**执行后面的阶段时，前面的阶段也会自动执行：**

```bash
mvn package
# 自动执行：validate → ... → compile → test → package

mvn install
# 自动执行：validate → ... → compile → test → package → install
```

---

## 二、插件

### 2.1 插件与目标

```
每个插件包含多个目标（Goal）。
目标绑定到生命周期的某个阶段执行。

plugin:goal   →  生命周期 phase
                           ↓
compiler:compile    →  compile
surefire:test       →  test
jar:jar             →  package
install:install     →  install
```

### 2.2 内置绑定

| 生命周期阶段 | 插件:目标 | 说明 |
|-------------|-----------|------|
| process-resources | resources:resources | 复制主资源 |
| compile | compiler:compile | 编译主代码 |
| process-test-resources | resources:testResources | 复制测试资源 |
| test-compile | compiler:testCompile | 编译测试代码 |
| test | surefire:test | 运行测试 |
| package | jar:jar / war:war | 打包 |
| install | install:install | 安装到本地仓库 |
| deploy | deploy:deploy | 部署到远程仓库 |

### 2.3 插件配置

```xml
<build>
    <plugins>
        <!-- 编译器插件 -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.8.1</version>
            <configuration>
                <source>11</source>
                <target>11</target>
                <encoding>UTF-8</encoding>
            </configuration>
        </plugin>
        
        <!-- JAR 插件 -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <version>3.2.0</version>
            <configuration>
                <archive>
                    <manifest>
                        <!-- 指定启动类 -->
                        <mainClass>com.example.Application</mainClass>
                    </manifest>
                </archive>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## 三、常用插件

### 3.1 maven-compiler-plugin

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.8.1</version>
    <configuration>
        <source>11</source>
        <target>11</target>
        <encoding>UTF-8</encoding>
        <!-- 启用注解处理器 -->
        <annotationProcessorPaths>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>1.18.20</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

### 3.2 maven-surefire-plugin（测试）

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>2.22.2</version>
    <configuration>
        <!-- 跳过测试 -->
        <skipTests>true</skipTests>
        
        <!-- 包含特定测试 -->
        <includes>
            <include>**/*Test.java</include>
            <include>**/*Tests.java</include>
        </includes>
        
        <!-- 排除特定测试 -->
        <excludes>
            <exclude>**/*IntegrationTest.java</exclude>
        </excludes>
        
        <!-- 并行运行 -->
        <parallel>methods</parallel>
        <useUnlimitedThreads>true</useUnlimitedThreads>
    </configuration>
</plugin>
```

```bash
# 跳过测试
mvn package -DskipTests           # 编译测试但不运行
mvn package -Dmaven.test.skip=true  # 完全跳过测试编译和运行

# 运行指定测试
mvn test -Dtest=UserServiceTest
mvn test -Dtest=UserServiceTest#testGetUser
mvn test -Dtest=UserServiceTest,OrderServiceTest
```

### 3.3 maven-shade-plugin（打胖包）

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.2.4</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <!-- 指定启动类 -->
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>com.example.Application</mainClass>
                    </transformer>
                </transformers>
                <!-- 排除不需要的文件 -->
                <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                </excludes>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 3.4 maven-assembly-plugin（自定义打包）

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-assembly-plugin</artifactId>
    <version>3.3.0</version>
    <configuration>
        <descriptorRefs>
            <descriptorRef>jar-with-dependencies</descriptorRef>
        </descriptorRefs>
        <archive>
            <manifest>
                <mainClass>com.example.Application</mainClass>
            </manifest>
        </archive>
    </configuration>
    <executions>
        <execution>
            <id>make-assembly</id>
            <phase>package</phase>
            <goals><goal>single</goal></goals>
        </execution>
    </executions>
</plugin>
```

### 3.5 maven-source-plugin（源码包）

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-source-plugin</artifactId>
    <version>3.2.1</version>
    <executions>
        <execution>
            <id>attach-sources</id>
            <phase>verify</phase>
            <goals><goal>jar-no-fork</goal></goals>
        </execution>
    </executions>
</plugin>
```

---

## 四、自定义插件绑定

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-antrun-plugin</artifactId>
            <version>3.0.0</version>
            <executions>
                <!-- 绑定到 compile 阶段 -->
                <execution>
                    <id>custom-task</id>
                    <phase>compile</phase>
                    <goals><goal>run</goal></goals>
                    <configuration>
                        <target>
                            <echo message="自定义任务在 compile 阶段执行"/>
                            <mkdir dir="${project.build.directory}/generated"/>
                        </target>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

---

## 五、命令行常用操作

```bash
# 查看插件列表
mvn help:describe -Dplugin=compiler

# 查看插件目标
mvn help:describe -Dplugin=compiler -Dgoal=compile

# 跳过测试
mvn install -DskipTests

# 使用多线程构建
mvn clean install -T 4                # 4 个线程
mvn clean install -T 1C               # 每个 CPU 核一个线程

# 指定 profile
mvn package -P production

# 离线模式（不从远程仓库下载）
mvn package -o

# debug 模式（显示详细日志）
mvn package -X
```

---

## 六、总结

### 生命周期速记

```
clean  → 清理
default → compile → test → package → install → deploy
site   → 生成站点
```

### 常用插件速查

| 插件 | 用途 | 绑定阶段 |
|------|------|---------|
| maven-compiler-plugin | 编译代码 | compile |
| maven-surefire-plugin | 运行测试 | test |
| maven-jar-plugin | 打包 jar | package |
| maven-shade-plugin | 打胖包（含依赖）| package |
| maven-assembly-plugin | 自定义打包 | package |
| maven-source-plugin | 源码包 | verify |
| maven-deploy-plugin | 部署到仓库 | deploy |
| maven-install-plugin | 安装到本地 | install |

**上一篇：** [Maven（一）：POM 模型与依赖管理]({{< relref "post/maven-pom-dependency" >}})

**下一篇：** [Maven（三）：仓库与配置]({{< relref "post/maven-repository-config" >}})
