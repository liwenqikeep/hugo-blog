---
title: "Spring Boot（四）：Starter 与条件注解"
date: 2019-04-03
draft: false
categories: ["Java"]
tags: ["Spring Boot", "Starter", "条件注解", "AutoConfiguration", "@Conditional"]
toc: true
---

## 前言

Starter 是 Spring Boot 的"一键式"依赖管理机制。一个 Starter 将相关的依赖和自动配置打包在一起，开发者只需引入一个依赖即可使用完整功能。

条件注解是 Starter 实现的基础——每个 Starte 的自动配置类通过条件注解判断是否应该生效。

<!--more-->

## 一、Starter 机制

### 1.1 什么是 Starter

```
Starter = 一组相关依赖 + 自动配置类

spring-boot-starter-web：
  ├── spring-webmvc
  ├── spring-boot-starter-tomcat
  ├── spring-boot-starter-json
  └── 自动配置类：WebMvcAutoConfiguration

spring-boot-starter-data-redis：
  ├── spring-data-redis
  ├── lettuce-core
  └── 自动配置类：RedisAutoConfiguration
```

### 1.2 命名规范

```xml
<!-- Spring Boot 官方 Starter -->
spring-boot-starter-*           # 官方：spring-boot-starter-web
spring-boot-starter-data-*      # 官方数据：spring-boot-starter-data-redis

<!-- 自定义 Starter -->
*-spring-boot-starter           # 自定义：mybatis-spring-boot-starter
```

---

## 二、自定义 Starter

### 2.1 项目结构

```
my-service-spring-boot-starter/
├── pom.xml
├── src/
│   └── main/
│       ├── java/
│       │   └── com/example/autoconfigure/
│       │       ├── MyServiceAutoConfiguration.java    ← 自动配置类
│       │       ├── MyServiceProperties.java           ← 配置属性类
│       │       └── MyService.java                     ← 业务类
│       └── resources/
│           └── META-INF/
│               └── spring/
│                   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  ← 自动配置注册
```

### 2.2 自动配置类

```java
// MyServiceProperties.java
@ConfigurationProperties(prefix = "my.service")
public class MyServiceProperties {
    
    private String host = "localhost";
    private int port = 8080;
    private boolean enabled = true;
    
    // getter / setter
}
```

```java
// MyService.java
public class MyService {
    
    private final String host;
    private final int port;
    
    public MyService(String host, int port) {
        this.host = host;
        this.port = port;
    }
    
    public String getUrl() {
        return "http://" + host + ":" + port;
    }
}
```

```java
// MyServiceAutoConfiguration.java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MyService.class)
@EnableConfigurationProperties(MyServiceProperties.class)
public class MyServiceAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "my.service", name = "enabled", 
                            havingValue = "true", matchIfMissing = true)
    public MyService myService(MyServiceProperties properties) {
        return new MyService(properties.getHost(), properties.getPort());
    }
}
```

### 2.3 注册自动配置

```properties
# Spring Boot 2.7+ 推荐方式
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.autoconfigure.MyServiceAutoConfiguration
```

### 2.4 使用自定义 Starter

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>my-service-spring-boot-starter</artifactId>
</dependency>
```

```yml
# 使用配置
my:
  service:
    host: api.example.com
    port: 443
    enabled: true
```

---

## 三、条件注解详解

### 3.1 类条件

```java
// @ConditionalOnClass：当 classpath 中有某个类时生效
@Configuration
@ConditionalOnClass(RedisOperations.class)
public class RedisAutoConfiguration {
    // classpath 中有 Redis 依赖时，此配置生效
}

// @ConditionalOnMissingClass：当 classpath 中没有某个类时生效
@Configuration
@ConditionalOnMissingClass("org.apache.tomcat.util.threads.TaskThread")
public class UndertowAutoConfiguration {
    // classpath 中没有 Tomcat 时，此配置生效
}
```

### 3.2 Bean 条件

```java
// @ConditionalOnBean：容器中已有指定 Bean 时生效
@Configuration
public class DataSourceConfig {
    
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)  // 只有 JdbcTemplate 存在时才创建
    public JdbcTemplateService jdbcTemplateService() {
        return new JdbcTemplateService();
    }
}

// @ConditionalOnMissingBean：容器中没有指定 Bean 时生效
// 这是自动配置中最重要的条件——确保用户自定义的 Bean 不会被覆盖
@Configuration
public class RedisAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory factory) {
        // 如果用户没有自定义 RedisTemplate，则创建默认的
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        return template;
    }
}
```

### 3.3 属性条件

```java
// @ConditionalOnProperty：根据配置属性判断

// 最简单形式：属性存在即可
@ConditionalOnProperty("my.feature.enabled")

// 指定值
@ConditionalOnProperty(prefix = "my.feature", name = "enabled", havingValue = "true")

// 不存在时是否匹配
@ConditionalOnProperty(prefix = "my.feature", name = "enabled", 
                        havingValue = "true", matchIfMissing = true)
// matchIfMissing = true：属性不存在时也算匹配（默认生效）
```

### 3.4 资源条件

```java
// @ConditionalOnResource：存在指定资源时生效
@Configuration
@ConditionalOnResource(resources = "classpath:my-config.xml")
public class XmlConfigConfiguration {
    // 存在 my-config.xml 时生效
}
```

### 3.5 SpEL 条件

```java
// @ConditionalOnExpression：SpEL 表达式为 true 时生效
@Configuration
@ConditionalOnExpression("${my.feature.enabled:true} && '${spring.profiles.active}' != 'test'")
public class FeatureConfiguration {
    // my.feature.enabled=true 且不是 test 环境时生效
}
```

### 3.6 Web 应用条件

```java
// @ConditionalOnWebApplication：Web 应用时生效
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebMvcAutoConfiguration {
    // Servlet 类型的 Web 应用时生效
}

// @ConditionalOnNotWebApplication：非 Web 应用时生效
@Configuration
@ConditionalOnNotWebApplication
public class NonWebConfiguration {
    // 非 Web 应用时生效
}
```

---

## 四、条件注解的组合使用

### 4.1 多个条件同时满足

```java
@Configuration
@ConditionalOnClass(DataSource.class)           // 有数据源
@ConditionalOnProperty(prefix = "spring.datasource", 
                        name = "url")          // 配置了数据库 URL
@ConditionalOnMissingBean(DataSource.class)     // 没有自定义数据源
public class DataSourceAutoConfiguration {
    // 三个条件都满足时，自动配置数据源
}
```

### 4.2 条件在方法级别

```java
@Configuration
public class CacheConfiguration {
    
    @Bean
    @ConditionalOnProperty("spring.redis.host")  // 配置了 Redis
    public CacheManager redisCacheManager() {
        return new RedisCacheManager();
    }
    
    @Bean
    @ConditionalOnMissingBean(CacheManager.class)  // 没有其他缓存管理器
    public CacheManager simpleCacheManager() {
        return new ConcurrentMapCacheManager();
    }
}
```

---

## 五、常用 Starter 速览

### 5.1 常见官方 Starter

| Starter | 用途 |
|---------|------|
| spring-boot-starter-web | Web 应用（嵌入 Tomcat）|
| spring-boot-starter-webflux | 响应式 Web |
| spring-boot-starter-data-jpa | JPA 数据访问 |
| spring-boot-starter-data-redis | Redis 数据访问 |
| spring-boot-starter-data-mongodb | MongoDB |
| spring-boot-starter-security | 安全认证 |
| spring-boot-starter-actuator | 监控端点 |
| spring-boot-starter-test | 测试框架 |
| spring-boot-starter-amqp | RabbitMQ |
| spring-boot-starter-validation | 参数校验 |

### 5.2 常见第三方 Starter

| Starter | 用途 |
|---------|------|
| mybatis-spring-boot-starter | MyBatis |
| spring-cloud-starter-* | Spring Cloud 组件 |
| com.alibaba.cloud:spring-cloud-starter-alibaba-nacos | Nacos |
| com.github.xiaoymin:knife4j-spring-boot-starter | API 文档 |
| com.github.binarywang:weixin-java-mp-spring-boot-starter | 微信公众号 |

---

## 六、条件注解的调试

### 6.1 查看自动配置报告

```bash
# 开启 debug 日志，查看哪些自动配置生效/不生效
application.yml:
debug: true

# 生效的正选：Positive matches
# 不生效的负选：Negative matches
# 排除的排除：Exclusions
```

```bash
# 输出示例——Positive matches
# DataSourceAutoConfiguration matched:
#    - @ConditionalOnClass found required class 'javax.sql.DataSource' (OnClassCondition)
#    - @ConditionalOnProperty (spring.datasource.url) matched (OnPropertyCondition)

# 输出示例——Negative matches
# RedisAutoConfiguration:
#    Did not match:
#       - @ConditionalOnClass did not find required class 'org.springframework.data.redis.core.RedisOperations' (OnClassCondition)
```

### 6.2 排除特定的自动配置

```java
// 方式一：@SpringBootApplication 中排除
@SpringBootApplication(exclude = { 
    DataSourceAutoConfiguration.class,
    RedisAutoConfiguration.class 
})

// 方式二：配置文件排除
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration

// 方式三：@EnableAutoConfiguration 中排除
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class})
```

---

## 七、总结

### 条件注解速查

| 注解 | 判断依据 | 常用场景 |
|------|---------|---------|
| @ConditionalOnClass | classpath 中是否有某个类 | 根据依赖判断 |
| @ConditionalOnBean | 容器中是否已有某个 Bean | 防止覆盖用户定义 |
| @ConditionalOnProperty | 配置属性是否存在/等于某值 | 功能开关 |
| @ConditionalOnExpression | SpEL 表达式 | 复杂条件 |
| @ConditionalOnWebApplication | 是否是 Web 应用 | 区分 Web/非 Web |

### Starter 设计原则

```
1. 命名规范：xxx-spring-boot-starter
2. 自动配置类：附带 @Configuration + @Conditional 系列注解
3. 属性类：@ConfigurationProperties 绑定配置
4. 注册：META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
5. 条件：使用 @ConditionalOnMissingBean 确保用户自定义优先
```

**上一篇：** [Spring Boot（三）：外部化配置]({{< relref "post/spring-boot-external-config" >}})

**系列索引：**
- [Spring Boot（一）：自动配置原理]({{< relref "post/spring-boot-auto-configuration" >}})
- [Spring Boot（二）：启动流程]({{< relref "post/spring-boot-startup-process" >}})
- [Spring Boot（三）：外部化配置]({{< relref "post/spring-boot-external-config" >}})
- [Spring Boot（四）：Starter 与条件注解]({{< relref "post/spring-boot-starter-condition" >}})
