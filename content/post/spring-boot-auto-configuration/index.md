---
title: "Spring Boot（一）：自动配置原理"
date: 2019-03-28
draft: false
categories: ["Java"]
tags: ["Spring Boot", "自动配置", "@EnableAutoConfiguration", "AutoConfiguration", "条件注解"]
toc: true
---

## 前言

Spring Boot 最核心的特性就是**自动配置（Auto Configuration）**——它根据 classpath 中的依赖、定义的 Bean 和配置文件中的属性，自动推断并创建应用程序需要的 Bean。

理解自动配置的原理，才能真正掌握 Spring Boot 的精髓。

<!--more-->

> **源码版本：** Spring Boot 2.3.x

## 一、@SpringBootApplication 复合注解

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootConfiguration        // 本质是 @Configuration
@EnableAutoConfiguration        // ★ 自动配置的核心入口
@ComponentScan(excludeFilters = {  // 组件扫描
    @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class) })
public @interface SpringBootApplication {
    // ...
}
```

所以 `@SpringBootApplication` 等价于：

```java
@Configuration
@EnableAutoConfiguration
@ComponentScan
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

---

## 二、@EnableAutoConfiguration

### 2.1 核心导入

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@AutoConfigurationPackage        // 记录扫描的包路径
@Import(AutoConfigurationImportSelector.class)  // ★ 核心：导入自动配置类
public @interface EnableAutoConfiguration {
    
    // 排除指定的自动配置类
    Class<?>[] exclude() default {};
    
    // 排除指定的自动配置类名
    String[] excludeName() default {};
}
```

### 2.2 AutoConfigurationImportSelector

`AutoConfigurationImportSelector` 实现了 `DeferredImportSelector` 接口，在 Spring 容器的 `refresh()` 过程中被调用。

```java
public class AutoConfigurationImportSelector 
        implements DeferredImportSelector, BeanClassLoaderAware,
                   ResourceLoaderAware, BeanFactoryAware, EnvironmentAware,
                   Ordered {
    
    @Override
    public String[] selectImports(AnnotationMetadata annotationMetadata) {
        if (!isEnabled(annotationMetadata)) {
            return NO_IMPORTS;
        }
        
        // ★ 1. 获取所有自动配置类的名称
        AutoConfigurationEntry autoConfigurationEntry = 
                getAutoConfigurationEntry(annotationMetadata);
        
        // 2. 返回自动配置类名数组（Spring 会逐个加载这些类）
        return StringUtils.toStringArray(autoConfigurationEntry.getConfigurations());
    }
    
    protected AutoConfigurationEntry getAutoConfigurationEntry(
            AnnotationMetadata annotationMetadata) {
        
        if (!isEnabled(annotationMetadata)) {
            return EMPTY_ENTRY;
        }
        
        // 1. 获取注解属性（exclude 等）
        AnnotationAttributes attributes = getAttributes(annotationMetadata);
        
        // ★ 2. 从 META-INF/spring.factories 加载配置类
        List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
        
        // 3. 去重
        configurations = removeDuplicates(configurations);
        
        // 4. 处理 exclude 排除
        Set<String> exclusions = getExclusions(annotationMetadata, attributes);
        checkExcludedClasses(configurations, exclusions);
        configurations.removeAll(exclusions);
        
        // ★ 5. 按条件过滤（应用 @Conditional）
        configurations = filter(configurations, autoConfigurationMetadata);
        
        return new AutoConfigurationEntry(configurations, exclusions);
    }
}
```

---

## 三、spring.factories 文件

### 3.1 文件位置

```
自动配置类的配置在 JAR 包中的：
META-INF/spring.factories  （Spring Boot 2.7 之前）
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports  （Spring Boot 2.7+）
```

### 3.2 spring.factories 内容

```properties
# 来自 spring-boot-autoconfigure 包
# META-INF/spring.factories

# 自动配置类
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration,\
org.springframework.boot.autoconfigure.aop.AopAutoConfiguration,\
org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,\
org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration,\
org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration,\
org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,\
org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration,\
...
```

### 3.3 加载流程

```
Spring Boot 启动
    │
    ▼
AutoConfigurationImportSelector.selectImports()
    │
    ├── 1. 从 spring.factories 获取 EnableAutoConfiguration 的所有配置类
    │      ↓
    │      130+ 个自动配置类候选
    │
    ├── 2. 根据 @Conditional 条件过滤
    │      ↓
    │      只保留条件满足的配置类
    │
    └── 3. 注册为 Spring Bean
           ↓
           自动配置的 Bean 生效
```

---

## 四、条件注解 @Conditional

自动配置的精髓在于**条件判断**——每个自动配置类上都标注了 `@Conditional` 系列注解，只有在满足条件时才生效。

### 4.1 常用条件注解

| 注解 | 生效条件 |
|------|---------|
| `@ConditionalOnClass` | classpath 中存在指定的类 |
| `@ConditionalOnMissingClass` | classpath 中不存在指定的类 |
| `@ConditionalOnBean` | 容器中已有指定的 Bean |
| `@ConditionalOnMissingBean` | 容器中还没有指定的 Bean |
| `@ConditionalOnProperty` | 配置文件中存在指定的属性 |
| `@ConditionalOnResource` | 存在指定的资源文件 |
| `@ConditionalOnWebApplication` | 当前是 Web 应用 |
| `@ConditionalOnNotWebApplication` | 当前不是 Web 应用 |
| `@ConditionalOnExpression` | SpEL 表达式为 true |
| `@ConditionalOnJava` | Java 版本匹配 |

### 4.2 示例：RedisAutoConfiguration

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RedisOperations.class)  // classpath 中有 Redis 依赖
@EnableConfigurationProperties(RedisProperties.class)
@Import({ LettuceConnectionConfiguration.class, 
          JedisConnectionConfiguration.class })
public class RedisAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")  // 用户没有自定义 RedisTemplate
    public RedisTemplate<Object, Object> redisTemplate(
            RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        return template;
    }
    
    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory redisConnectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(redisConnectionFactory);
        return template;
    }
}
```

**推理过程：**

```
1. pom.xml 中引入 spring-boot-starter-data-redis
   → classpath 中出现 RedisOperations.class
   → @ConditionalOnClass 满足

2. application.yml 中配置 spring.redis.*
   → RedisProperties 被正确填充

3. 用户没有自定义 RedisTemplate Bean
   → @ConditionalOnMissingBean 满足

4. → RedisAutoConfiguration 生效
   → RedisTemplate 和 StringRedisTemplate 自动创建
```

### 4.3 示例：DataSourceAutoConfiguration

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })
@EnableConfigurationProperties(DataSourceProperties.class)
@Import({ DataSourcePoolMetadataProvidersConfiguration.class,
          DataSourceInitializationConfiguration.class })
public class DataSourceAutoConfiguration {
    
    // 内嵌数据源（如 H2）
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(DataSource.class)
    @ConditionalOnProperty(name = "spring.datasource.embedded-database-connection",
                            havingValue = "h2")
    static class EmbeddedDatabaseConfiguration {
        @Bean
        DataSource dataSource(DataSourceProperties properties) {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .build();
        }
    }
    
    // 连接池数据源（HikariCP 等）
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(DataSource.class)
    @ConditionalOnClass(HikariDataSource.class)
    static class Hikari {
        @Bean
        DataSource dataSource(DataSourceProperties properties) {
            return properties.initializeDataSourceBuilder()
                    .type(HikariDataSource.class).build();
        }
    }
}
```

---

## 五、条件注解的源码实现

### 5.1 Condition 接口

```java
// 所有条件注解都基于这两个接口

@FunctionalInterface
public interface Condition {
    boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata);
}

public interface ConfigurationCondition extends Condition {
    ConfigurationPhase getConfigurationPhase();
}
```

### 5.2 OnClassCondition 的实现

```java
// @ConditionalOnClass 的后处理器
class OnClassCondition extends FilteringSpringBootCondition {
    
    @Override
    protected ConditionOutcome[] getOutcomes(String[] autoConfigurationClasses,
                                              AutoConfigurationMetadata autoConfigurationMetadata) {
        // 检查 classpath 中是否有指定的类
        // 使用 ClassUtils.isPresent() 或 ASM 读取字节码元信息
    }
}

// 核心方法：ClassUtils.isPresent
public static boolean isPresent(String className, ClassLoader classLoader) {
    try {
        Class.forName(className, false, classLoader);
        return true;
    } catch (ClassNotFoundException ex) {
        return false;
    }
}
```

### 5.3 OnBeanCondition 的实现

```java
class OnBeanCondition extends FilteringSpringBootCondition {
    
    @Override
    protected ConditionOutcome getMatchOutcome(ConditionContext context,
                                                AnnotatedTypeMetadata metadata) {
        // 检查容器中是否已存在指定的 Bean
        // 通过 BeanFactory.getBeanNamesForType() 判断
        // 确保用户自定义的 Bean 优先级高于自动配置
    }
}
```

---

## 六、配置类过滤机制

### 6.1 AutoConfigurationImportFilter

```java
// 在配置类被注册前进行过滤
// 避免加载不需要的配置类，提高启动速度

public interface AutoConfigurationImportFilter {
    boolean[] match(String[] autoConfigurationClasses,
                    AutoConfigurationMetadata autoConfigurationMetadata);
}

// 实现类：OnClassCondition
// 在加载配置类之前，先检查其 @ConditionalOnClass 是否满足
// 如果不满足，直接跳过，不再加载该类
```

### 6.2 配置类的排序

```java
// 自动配置类之间有依赖关系，需要按顺序加载

// @AutoConfigureBefore：在指定配置类之前加载
// @AutoConfigureAfter：在指定配置类之后加载
// @AutoConfigureOrder：指定加载顺序

@Configuration
@AutoConfigureAfter(DataSourceAutoConfiguration.class)  // 在数据源之后加载
@AutoConfigureBefore(MybatisAutoConfiguration.class)     // 在 MyBatis 之前加载
public class CustomDatabaseConfiguration {
    // ...
}
```

---

## 七、自定义自动配置

### 7.1 创建自动配置类

```java
// 1. 创建自动配置类
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(SomeService.class)
@EnableConfigurationProperties(SomeProperties.class)
public class SomeAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    public SomeService someService(SomeProperties properties) {
        return new SomeService(properties.getName());
    }
}

// 2. 创建属性绑定类
@ConfigurationProperties(prefix = "some")
public class SomeProperties {
    private String name = "default";
    // getter / setter
}
```

### 7.2 注册自动配置

```properties
# 方式一：Spring Boot 2.7 之前
# META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.example.autoconfigure.SomeAutoConfiguration

# 方式二：Spring Boot 2.7+ 推荐
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.autoconfigure.SomeAutoConfiguration
```

```xml
<!-- 创建自动配置的 Starter -->
<!-- 只需要一个 pom 文件 + 自动配置代码即可 -->

<project>
    <artifactId>some-spring-boot-starter</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>some-library</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
    </dependencies>
</project>
```

---

## 八、总结

### 自动配置的核心流程

```
1. @SpringBootApplication → @EnableAutoConfiguration
2. AutoConfigurationImportSelector 加载 spring.factories 中的配置类
3. AutoConfigurationImportFilter 按 @ConditionalOnClass 预过滤
4. 注册剩余的自动配置类
5. 条件注解 @ConditionalOnBean/MissingBean 最终生效
6. 满足条件的 Bean 被创建
```

### 条件注解速记

| 注解 | 判断依据 |
|------|---------|
| @ConditionalOnClass | classpath 中是否有某个类 |
| @ConditionalOnMissingClass | classpath 中是否没有某个类 |
| @ConditionalOnBean | 容器中是否已有某个 Bean |
| @ConditionalOnMissingBean | 容器中是否还没有某个 Bean |
| @ConditionalOnProperty | 配置属性是否存在/等于某值 |
| @ConditionalOnExpression | SpEL 表达式求值 |

**下一篇：** [Spring Boot（二）：启动流程]({{< relref "post/spring-boot-startup-process" >}})
