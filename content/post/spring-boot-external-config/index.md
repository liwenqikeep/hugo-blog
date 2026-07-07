---
title: "Spring Boot（三）：外部化配置"
date: 2019-04-01
draft: false
categories: ["Java"]
tags: ["Spring Boot", "配置", "@ConfigurationProperties", "@Value", "Profile"]
toc: true
---

## 前言

外部化配置是 Spring Boot 的核心特性之一。它将配置从代码中分离出来，允许在不同环境使用不同的配置，而不需要修改代码。

理解 Spring Boot 的配置体系——**PropertySource 优先级**、**松散绑定**、**类型安全配置**——是高效使用 Spring Boot 的基础。

<!--more-->

## 一、配置优先级

### 1.1 整体优先级

Spring Boot 的配置遵循以下优先级（高到低）：

```
1. 命令行参数（--server.port=8080）
2. JNDI 属性
3. Java 系统属性（System.getProperties()）
4. OS 环境变量
5. application-{profile}.properties/yml
6. application.properties/yml
7. @PropertySource 注解
8. Spring Boot 默认配置（SpringApplication.setDefaultProperties 设置的）
```

**同一优先级中，越靠后的优先级越高。**

```bash
# 命令行参数覆盖配置
java -jar myapp.jar --server.port=9090 --spring.profiles.active=prod

# 环境变量覆盖
export SERVER_PORT=9090
java -jar myapp.jar
```

### 1.2 配置文件的加载顺序

```yml
# 加载顺序（后面的覆盖前面的）
# 1. classpath:/application.yml
# 2. classpath:/application-{profile}.yml
# 3. file:./config/application.yml       （项目根目录 config 下）
# 4. file:./application.yml               （项目根目录下）
# 5. file:./config/*/application.yml
```

```bash
# 可以使用 spring.config.location 自定义配置文件位置
java -jar myapp.jar --spring.config.location=file:/etc/myapp/application.yml
```

---

## 二、配置文件的类型

### 2.1 application.yml vs application.properties

```yml
# application.yml（推荐）
server:
  port: 8080
  servlet:
    context-path: /api

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/db
    username: root
    password: root
```

```properties
# application.properties
server.port=8080
server.servlet.context-path=/api

spring.datasource.url=jdbc:mysql://localhost:3306/db
spring.datasource.username=root
spring.datasource.password=root
```

### 2.2 多环境配置

```yml
# application.yml — 公共配置
server:
  port: 8080
spring:
  profiles:
    active: dev    # 默认激活 dev

---
# application-dev.yml — 开发环境
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/dev_db

---
# application-prod.yml — 生产环境
spring:
  datasource:
    url: jdbc:mysql://prod-db:3306/prod_db
```

```yml
# 或者写在同一个文件（--- 分隔）
spring:
  profiles:
    active: dev

---
spring:
  config:
    activate:
      on-profile: dev
server:
  port: 8080

---
spring:
  config:
    activate:
      on-profile: prod
server:
  port: 80
```

---

## 三、@ConfigurationProperties——类型安全配置

### 3.1 绑定配置到 Java Bean

```yml
# application.yml
app:
  name: MyApp
  version: 1.0.0
  hosts:
    - dev.example.com
    - prod.example.com
  security:
    enabled: true
    token-expire: 3600
```

```java
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    
    private String name;
    private String version;
    private List<String> hosts = new ArrayList<>();
    private Security security = new Security();
    
    // getter / setter
    
    public static class Security {
        private boolean enabled;
        private int tokenExpire;
        // getter / setter
    }
}

// 使用
@Service
public class AppService {
    
    @Autowired
    private AppProperties appProperties;
    
    public void printConfig() {
        System.out.println(appProperties.getName());
        System.out.println(appProperties.getSecurity().getTokenExpire());
    }
}
```

### 3.2 @ConfigurationPropertiesScan

```java
// Spring Boot 2.2+ 可以不用 @Component，通过 @EnableConfigurationProperties 启用

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {
    // AppProperties 会被自动创建
}
```

### 3.3 松散绑定

Spring Boot 支持松散的属性命名规则：

```yml
# 以下写法等效
app.token-expire: 3600       # kebab case（推荐在 yml 中使用）
app.tokenExpire: 3600         # camel case（推荐在 Java 中使用）
app.token_expire: 3600        # snake case
app.TOKEN_EXPIRE: 3600        # upper case（环境变量格式）
```

### 3.4 配置校验

```java
@Component
@ConfigurationProperties(prefix = "app")
@Validated
public class AppProperties {
    
    @NotBlank(message = "应用名称不能为空")
    private String name;
    
    @Min(value = 1, message = "版本号必须大于 0")
    @Max(value = 100)
    private int version;
    
    @Valid
    private Security security = new Security();
    
    public static class Security {
        @NotNull
        private Boolean enabled;
        
        @Min(60)
        private int tokenExpire;
        // getter / setter
    }
    // getter / setter
}
```

---

## 四、@Value——属性注入

### 4.1 基本使用

```java
@Component
public class AppService {
    
    // 直接注入配置值
    @Value("${app.name}")
    private String appName;
    
    // 默认值（配置不存在时使用默认值）
    @Value("${app.timeout:3000}")
    private int timeout;
    
    // SpEL 表达式
    @Value("#{systemProperties['user.dir']}")
    private String userDir;
    
    // 随机值
    @Value("${random.int(1,100)}")
    private int randomInt;
    
    @Value("${random.uuid}")
    private String randomUuid;
}
```

### 4.2 @ConfigurationProperties vs @Value

| 对比 | @ConfigurationProperties | @Value |
|------|------------------------|--------|
| 绑定方式 | 批量绑定 | 单个绑定 |
| 松散绑定 | ✅ 支持 | ❌ 不支持 |
| 复杂类型 | ✅ List、Map、嵌套 | ❌ 仅基本类型 |
| 数据校验 | ✅ @Validated | ❌ 不支持 |
| 可复用性 | ✅ 可在多处注入 | ❌ 每次都要写 |

**结论：优先使用 @ConfigurationProperties，@Value 只用于简单场景。**

---

## 五、Profile——多环境支持

### 5.1 激活方式

```bash
# 方式一：application.yml 中配置
spring:
  profiles:
    active: dev

# 方式二：命令行参数
java -jar app.jar --spring.profiles.active=prod

# 方式三：环境变量
export SPRING_PROFILES_ACTIVE=prod

# 方式四：@ActiveProfiles（测试）
@ActiveProfiles("test")
@SpringBootTest
class ApplicationTests {
}
```

### 5.2 自动激活的策略

```bash
# Spring Boot 2.4+ 的 Profile 组
spring:
  profiles:
    group:
      ci: dev,integration-test
      staging: dev,cache-local
      prod: prod,cache-redis

# 激活 ci 组 = 同时激活 dev + integration-test
java -jar app.jar --spring.profiles.active=ci
```

### 5.3 @Profile 条件注解

```java
@Service
@Profile("dev")  // 仅在 dev 环境下加载
public class DevDataInitializer implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // 这里初始化测试数据
    }
}

@Service
@Profile("prod")  // 仅在 prod 环境下加载
public class ProdDataInitializer implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // 生产环境的初始化
    }
}
```

---

## 六、自定义 PropertySource

### 6.1 实现自定义属性源

```java
@Component
public class CustomPropertySourceFactory implements PropertySourceFactory {
    
    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource resource) {
        // 从外部系统加载配置，如 Redis、数据库
        MutablePropertySources sources = new MutablePropertySources();
        
        Map<String, Object> source = new HashMap<>();
        source.put("app.dynamic.config", loadFromExternal());
        
        sources.addFirst(new MapPropertySource("custom", source));
        return sources.get("custom");
    }
    
    private String loadFromExternal() {
        // 从 Redis / 数据库加载
        return "dynamic-value";
    }
}
```

### 6.2 使用自定义 PropertySource

```java
@Configuration
@PropertySource(value = "classpath:custom-config-loader", 
                factory = CustomPropertySourceFactory.class)
public class CustomConfig {
    // 加载的配置可以直接被 @Value 或 @ConfigurationProperties 使用
}
```

---

## 七、加密配置

### 7.1 使用 Jasypt 加密

```xml
<dependency>
    <groupId>com.github.ulisesbocchio</groupId>
    <artifactId>jasypt-spring-boot-starter</artifactId>
    <version>3.0.4</version>
</dependency>
```

```yml
# application.yml
jasypt:
  encryptor:
    password: my-secret-key    # 加密密钥（生产环境从环境变量获取）

spring:
  datasource:
    password: ENC(encryptedPassword)  # 加密后的密码
```

---

## 八、总结

### 配置方式速查

| 方式 | 推荐场景 |
|------|---------|
| application.yml | 一般配置（推荐）|
| application-{profile}.yml | 环境差异配置 |
| 命令行参数 | 临时覆盖 |
| 环境变量 | Docker/K8s 部署 |
| @ConfigurationProperties | 类型安全配置（推荐）|
| @Value | 简单属性注入 |

### 配置优先级速记

```
命令行 > 环境变量 > Profile 配置 > 默认配置 > 代码默认值
```

**上一篇：** [Spring Boot（二）：启动流程]({{< relref "post/spring-boot-startup-process" >}})

**下一篇：** [Spring Boot（四）：Starter 与条件注解]({{< relref "post/spring-boot-starter-condition" >}})
