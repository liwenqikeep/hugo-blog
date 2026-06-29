---
title: "Spring AOP（一）：AOP 概念与五种通知类型"
date: 2018-05-08
draft: false
categories: ["Java"]
tags: ["Spring", "AOP", "@Aspect", "@Pointcut", "通知", "面向切面编程"]
toc: true
---

## 前言

AOP（Aspect-Oriented Programming）是 Spring 框架的另一大核心，与 IoC 互补。IoC 解决的是"对象怎么创建和组装"，AOP 解决的是"横切关注点怎么统一处理"——比如日志、事务、权限、性能监控等。

本文从最基础的使用出发，覆盖 AOP 的核心概念、五种通知类型、切点表达式的定义方式，以及常见的最佳实践。

<!--more-->

## 一、AOP 核心概念

### 1.1 概念总览

```
Aspect（切面）   → 横切关注点的模块化，如日志切面
  │
  ├── Advice（通知）   → 切面要执行的具体操作
  │     ├── @Before     前置通知
  │     ├── @After      后置通知
  │     ├── @AfterReturning 返回通知
  │     ├── @AfterThrowing  异常通知
  │     └── @Around     环绕通知
  │
  ├── Pointcut（切点） → 匹配连接点的表达式
  │
  └── Join Point（连接点） → 程序执行中的某个点（方法调用、异常抛出等）
```

**Spring AOP 的局限：** Spring AOP 基于代理实现，只支持**方法级别的连接点**（即只能拦截方法的调用）。如果要拦截字段访问、构造器调用等，需要使用 AspectJ。

### 1.2 启用 Spring AOP

Spring Boot 项目默认已启用。普通 Spring 项目需要添加注解：

```java
@Configuration
@EnableAspectJAutoProxy  // 启用 AOP
public class AppConfig {
    // ...
}
```

```xml
<!-- XML 方式 -->
<aop:aspectj-autoproxy/>
```

**Maven 依赖：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

## 二、@Aspect 切面定义

### 2.1 第一个切面

```java
@Aspect                // 声明这是一个切面
@Component             // 交给 Spring 管理
public class LoggingAspect {
    
    // 定义切点：匹配 com.example.service 包下所有方法
    @Pointcut("execution(* com.example.service.*.*(..))")
    public void serviceLayer() {}
    
    // 前置通知
    @Before("serviceLayer()")
    public void beforeService(JoinPoint joinPoint) {
        System.out.println("[日志] 调用方法: " + joinPoint.getSignature().getName());
    }
}
```

### 2.2 @Pointcut 切点表达式

**execution 表达式——最常用的切点指示器：**

```java
// execution(修饰符 返回值 包.类.方法(参数) throws 异常)

// 匹配所有 public 方法
@Pointcut("execution(public * *(..))")

// 匹配 com.example.service 包下所有方法
@Pointcut("execution(* com.example.service.*.*(..))")

// 匹配 com.example 包及其子包下的所有方法
@Pointcut("execution(* com.example..*.*(..))")  // 两个点表示子包

// 匹配 UserService 类中的所有方法
@Pointcut("execution(* com.example.service.UserService.*(..))")

// 匹配 find 开头的方法
@Pointcut("execution(* com.example.service.*.find*(..))")

// 匹配只有一个 String 参数的方法
@Pointcut("execution(* com.example.service.*.*(String))")

// 匹配任意参数的方法
@Pointcut("execution(* com.example.service.*.*(..))")
```

**其他切点指示器：**

```java
// within：匹配指定类型内的所有方法
@Pointcut("within(com.example.service.UserService)")
@Pointcut("within(com.example.service..*)")  // service 包及子包

// this / target：匹配代理对象/目标对象是特定类型
@Pointcut("this(com.example.service.UserService)")  // 代理对象
@Pointcut("target(com.example.service.UserService)") // 目标对象

// args：匹配参数类型
@Pointcut("args(String, int)")  // 第一个参数 String，第二个 int

// @annotation：匹配标注了特定注解的方法
@Pointcut("@annotation(org.springframework.transaction.annotation.Transactional)")

// @within：匹配类标注了特定注解的所有方法
@Pointcut("@within(org.springframework.stereotype.Service)")

// @args：匹配某个注解标注的参数
@Pointcut("@args(com.example.annotation.Sensitive)")
```

**组合切点：**

```java
// 使用 &&、||、! 组合
@Pointcut("execution(* com.example.service.*.*(..))")
public void serviceLayer() {}

@Pointcut("execution(* com.example.controller.*.*(..))")
public void controllerLayer() {}

// 切 service 层或 controller 层
@Pointcut("serviceLayer() || controllerLayer()")

// 切 service 层但不是 find 方法
@Pointcut("serviceLayer() && !execution(* com.example.service.*.find*(..))")
```

## 三、五种通知类型

### 3.1 通知类型概览

| 通知类型 | 注解 | 执行时机 | 使用场景 |
|---------|------|---------|---------|
| 前置通知 | `@Before` | 目标方法执行之前 | 权限校验、参数校验、日志 |
| 后置通知 | `@After` | 目标方法执行之后（无论是否异常）| 资源清理、释放锁 |
| 返回通知 | `@AfterReturning` | 目标方法正常返回后 | 结果处理、日志、格式化 |
| 异常通知 | `@AfterThrowing` | 目标方法抛出异常后 | 异常记录、告警、兜底 |
| 环绕通知 | `@Around` | 方法执行前后可控 | 性能监控、事务、重试 |

### 3.2 前置通知 @Before

```java
@Aspect
@Component
public class LoggingAspect {
    
    @Before("execution(* com.example.service.*.*(..))")
    public void logBefore(JoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();
        System.out.println("调用方法: " + methodName + ", 参数: " + Arrays.toString(args));
    }
}
```

**JoinPoint 提供的信息：**
- `getSignature()` — 方法签名（类名 + 方法名）
- `getArgs()` — 方法参数
- `getTarget()` — 目标对象
- `getThis()` — 代理对象
- `getKind()` — 连接点类型

### 3.3 后置通知 @After

```java
@After("execution(* com.example.service.*.*(..))")
public void logAfter(JoinPoint joinPoint) {
    System.out.println("方法执行完毕: " + joinPoint.getSignature().getName());
}
// 无论方法正常返回还是抛异常，都会执行
```

### 3.4 返回通知 @AfterReturning

```java
@AfterReturning(
    pointcut = "execution(* com.example.service.*.*(..))",
    returning = "result"           // 方法返回值的参数名
)
public void logReturn(JoinPoint joinPoint, Object result) {
    System.out.println("返回结果: " + result);
}
```

**注意：** `returning` 属性指定的参数名必须与方法参数名一致。如果返回值是 `null`，`result` 参数也会传入 `null`。

### 3.5 异常通知 @AfterThrowing

```java
@AfterThrowing(
    pointcut = "execution(* com.example.service.*.*(..))",
    throwing = "ex"                // 异常对象的参数名
)
public void logException(JoinPoint joinPoint, Exception ex) {
    System.err.println("方法异常: " + joinPoint.getSignature() + ", 异常: " + ex.getMessage());
}
```

**细化异常类型：**

```java
// 只捕获特定的异常
@AfterThrowing(pointcut = "serviceLayer()", throwing = "ex")
public void handleBusinessException(JoinPoint joinPoint, BusinessException ex) {
    // 只有 BusinessException 及其子类才会触发
    // 其他异常类型不会进入此通知
}
```

### 3.6 环绕通知 @Around

环绕通知是最强大的通知类型，可以在方法执行前后自定义逻辑，还可以控制是否执行目标方法。

```java
@Around("serviceLayer()")
public Object measureTime(ProceedingJoinPoint joinPoint) throws Throwable {
    long start = System.currentTimeMillis();
    
    try {
        // 执行目标方法
        Object result = joinPoint.proceed();
        return result;
    } finally {
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("方法耗时: " + elapsed + "ms");
    }
}
```

**ProceedingJoinPoint 的特殊方法：**
- `proceed()` — 执行目标方法（无参）
- `proceed(Object[] args)` — 使用修改后的参数执行目标方法
- `proceed()` 必须主动调用，不调用则目标方法不会执行

**典型场景——重试：**

```java
@Around("serviceLayer()")
public Object retryOnFailure(ProceedingJoinPoint joinPoint) throws Throwable {
    int maxRetries = 3;
    Throwable lastException = null;
    
    for (int i = 0; i < maxRetries; i++) {
        try {
            return joinPoint.proceed();
        } catch (DataAccessException e) {
            lastException = e;
            System.out.println("重试 " + (i + 1) + "/" + maxRetries);
            Thread.sleep(100 * (i + 1));  // 递增等待
        }
    }
    throw lastException;
}
```

**典型场景——缓存：**

```java
@Around("@annotation(cacheable)")
public Object cacheResult(ProceedingJoinPoint joinPoint, Cacheable cacheable) throws Throwable {
    String cacheKey = generateCacheKey(joinPoint);
    Object cached = cacheManager.get(cacheKey);
    
    if (cached != null) {
        return cached;  // 缓存命中，不执行目标方法
    }
    
    Object result = joinPoint.proceed();
    cacheManager.put(cacheKey, result);
    return result;
}
```

## 四、通知的执行顺序

### 4.1 正常执行的顺序

```java
@Before  →  目标方法执行  →  @AfterReturning  →  @After

// 详细流程：
// 1. @Before（前置通知）
// 2. 目标方法执行
// 3. @AfterReturning（返回通知）— 正常返回时触发
// 4. @After（后置通知）— 无论如何都会触发
```

### 4.2 异常时的顺序

```java
@Before  →  目标方法抛异常  →  @AfterThrowing  →  @After

// 详细流程：
// 1. @Before（前置通知）
// 2. 目标方法抛出异常
// 3. @AfterThrowing（异常通知）— 异常时触发
// 4. @After（后置通知）— 仍然触发
```

### 4.3 多个切面时的顺序

当多个切面匹配同一个方法时，通过 `@Ordered` 控制顺序：

```java
@Aspect
@Component
@Order(1)  // 数值越小，优先级越高
public class LoggingAspect {
    @Around("serviceLayer()")
    public Object log(ProceedingJoinPoint pjp) throws Throwable {
        System.out.println("第一个切面: Before");
        Object result = pjp.proceed();
        System.out.println("第一个切面: After");
        return result;
    }
}

@Aspect
@Component
@Order(2)
public class TransactionAspect {
    @Around("serviceLayer()")
    public Object transaction(ProceedingJoinPoint pjp) throws Throwable {
        System.out.println("第二个切面: Before");
        Object result = pjp.proceed();
        System.out.println("第二个切面: After");
        return result;
    }
}
```

**执行顺序（@Order 数值越小越外层）：**

```
LoggingAspect @Around(Before)
  └── TransactionAspect @Around(Before)
        └── 目标方法执行
      TransactionAspect @Around(After)
  LoggingAspect @Around(After)
```

## 五、常见使用场景

### 5.1 性能监控

```java
@Aspect
@Component
public class PerformanceAspect {
    
    private final Logger log = LoggerFactory.getLogger(PerformanceAspect.class);
    
    @Around("@annotation(com.example.annotation.Monitor)")
    public Object monitor(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().toShortString();
        long start = System.nanoTime();
        
        try {
            return joinPoint.proceed();
        } finally {
            long elapsed = System.nanoTime() - start;
            long ms = TimeUnit.NANOSECONDS.toMillis(elapsed);
            if (ms > 1000) {  // 超过 1s 告警
                log.warn("慢方法: {} 耗时: {}ms", method, ms);
            }
        }
    }
}
```

### 5.2 参数校验

```java
@Aspect
@Component
public class ValidationAspect {
    
    @Before("execution(* com.example.controller.*.*(..)) && args(validatable)")
    public void validate(JoinPoint joinPoint, Validatable validatable) {
        String error = validatable.validate();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
    }
}
```

### 5.3 异常统一处理

```java
@Aspect
@Component
public class ExceptionAspect {
    
    @AfterThrowing(pointcut = "execution(* com.example.service.*.*(..))", throwing = "ex")
    public void handleServiceException(JoinPoint joinPoint, Exception ex) {
        // 记录错误日志
        log.error("Service 异常: {} 方法: {}", ex.getMessage(), joinPoint.getSignature());
        
        // 通知监控系统（如 Sentry、Prometheus）
        MetricsCollector.recordError(joinPoint.getSignature().getName());
    }
}
```

## 六、常见坑与最佳实践

### 6.1 自调用问题

Spring AOP 基于代理——**目标对象内部的方法调用不走代理**：

```java
@Service
public class UserService {
    
    public void doSomething() {
        // 这里的 this 是原始对象，不是代理对象
        // 所以 internalMethod() 上的 @Transactional 不生效
        internalMethod();
    }
    
    @Transactional
    public void internalMethod() {
        // 事务不会生效！
    }
}
```

**解决方案：**

```java
// 方案一：注入自身（循环依赖，不推荐）
@Service
public class UserService {
    @Autowired
    private UserService self;  // 注入代理对象
    
    public void doSomething() {
        self.internalMethod();  // 通过代理调用，AOP 生效
    }
}

// 方案二：提取到另一个 Service（推荐）
@Service
public class UserService {
    @Autowired
    private InternalService internalService;
    
    public void doSomething() {
        internalService.internalMethod();
    }
}
```

### 6.2 同一个类中 @Async / @Transactional 等问题

自调用导致所有基于代理的注解（`@Async`、`@Transactional`、`@Cacheable`、AOP 自定义注解）都会失效。原理相同：**代理包装的是外部调用，内部 `this.method()` 不经过代理**。

### 6.3 切点过于宽泛

```java
// ❌ 太宽泛，会匹配到 spring 内部的方法
@Pointcut("execution(* *(..))")

// ✅ 限定到业务包
@Pointcut("execution(* com.example..*.*(..))")
```

### 6.4 环绕通知中忘记调用 proceed()

```java
// ❌ 忘记 proceed 会导致目标方法不执行
@Around("serviceLayer()")
public Object around(ProceedingJoinPoint pjp) {
    System.out.println("before");
    // 忘记调用 pjp.proceed()
    return null;  // 目标方法被跳过
}
```

### 6.5 多线程环境下 ThreadLocal 的正确使用

```java
@Aspect
@Component
public class RequestContextAspect {
    
    private static final ThreadLocal<Map<String, Object>> CONTEXT = new ThreadLocal<>();
    
    @Before("execution(* com.example.controller.*.*(..))")
    public void initContext() {
        CONTEXT.set(new HashMap<>());
    }
    
    @After("execution(* com.example.controller.*.*(..))")
    public void clearContext() {
        CONTEXT.remove();  // 必须清理！
    }
}
```

## 七、总结

### AOP 使用核心速查

```
@EnableAspectJAutoProxy → 开启 AOP
@Aspect + @Component → 定义切面
@Pointcut → 定义切点
@Before / @After / @AfterReturning / @AfterThrowing / @Around → 五种通知

切点表达式语法：
  execution(修饰符 返回值 包.类.方法(参数))
  within(包.类)
  @annotation(注解全限定名)
  this(类型) / target(类型)
  args(参数类型)
```

### 通知执行顺序

```
正常：@Before → 目标方法 → @AfterReturning → @After
异常：@Before → 目标方法抛异常 → @AfterThrowing → @After
多个切面：按 @Order 数值从小到大（优先级高到低）
```

### 常见坑

1. **自调用**不经过代理 → AOP 失效
2. **环绕通知忘记 proceed()** → 目标方法不执行
3. **切点表达式太宽泛** → 性能下降、误拦截
4. **ThreadLocal 未清理** → 内存泄漏

---

**下一篇：** [Spring AOP（二）：AOP 源码深度解析]({{< relref "post/spring-aop-source-code" >}})
