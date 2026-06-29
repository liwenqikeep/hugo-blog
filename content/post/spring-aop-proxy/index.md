---
title: "Spring AOP（三）：JDK 代理 vs CGLib 与高级主题"
date: 2018-05-12
draft: false
categories: ["Java"]
tags: ["Spring", "AOP", "JDK动态代理", "CGLib", "ProxyFactory", "AspectJ", "最佳实践"]
toc: true
---

## 前言

前两篇分别覆盖了 AOP 的使用和源码流程。本篇聚焦于 AOP 的底层实现机制：**JDK 动态代理**和 **CGLib** 两种代理方式的工作原理、差异和选型，以及 exposeProxy、AspectJ 对比等高级话题。

<!--more-->

## 一、JDK 动态代理

### 1.1 原理

JDK 动态代理是 Java 原生支持的代理方式，基于 `java.lang.reflect.Proxy` 和 `InvocationHandler` 实现。

**核心限制：** 目标对象**必须实现至少一个接口**，代理对象只能转换为目标对象的接口类型，不能转换为目标类本身。

### 1.2 核心实现

```java
// JdkDynamicAopProxy.java（Spring 源码精简）
final class JdkDynamicAopProxy implements AopProxy, InvocationHandler, Serializable {
    
    private final AdvisedSupport advised;  // 持有 Advisor 配置
    
    @Override
    public Object getProxy(ClassLoader classLoader) {
        // 获取目标类实现的所有接口
        Class<?>[] proxiedInterfaces = 
            AopProxyUtils.completeProxiedInterfaces(this.advised, true);
        
        // ★ JDK 原生 API 创建代理
        return Proxy.newProxyInstance(classLoader, proxiedInterfaces, this);
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 上一篇中详细解释的拦截链调用逻辑
        // ...
    }
}
```

**生成的代理类结构（伪代码）：**

```java
// JDK 为 UserService 生成的代理类
public class $Proxy0 extends Proxy implements UserService {
    
    private InvocationHandler h;  // 即 JdkDynamicAopProxy
    
    public $Proxy0(InvocationHandler h) {
        this.h = h;
    }
    
    @Override
    public void doSomething() {
        // 所有方法调用都转发给 InvocationHandler
        h.invoke(this, getDoSomethingMethod(), null);
    }
}
```

### 1.3 验证

```java
@Service
public class UserService implements UserServiceInterface {
    public void doSomething() { ... }
}

// 调用
UserServiceInterface proxy = context.getBean(UserService.class);
System.out.println(proxy instanceof UserService);  // false！只能转接口
System.out.println(proxy instanceof UserServiceInterface);  // true
```

## 二、CGLib 代理

### 2.1 原理

CGLib 基于 ASM 字节码技术，通过**生成目标类的子类**来实现代理。它不要求目标类实现接口。

**核心限制：** 目标类不能是 `final` 的，目标方法不能是 `final` 或 `static`。

### 2.2 核心实现

```java
// CglibAopProxy.java（Spring 源码精简）
class CglibAopProxy implements AopProxy, Serializable {
    
    private final AdvisedSupport advised;
    
    @Override
    public Object getProxy(ClassLoader classLoader) {
        // 1. 创建 Enhancer（CGLib 的核心类）
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(proxiedSuperclass);  // 目标类作为父类
        enhancer.setInterfaces(AopProxyUtils.completeProxiedInterfaces(this.advised));
        enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);
        
        // 2. 设置回调——拦截器
        Callback[] callbacks = getCallbacks(rootClass);
        enhancer.setCallbacks(callbacks);
        
        // 3. 生成代理对象
        return enhancer.create();
    }
}
```

**主要的 Callback——DynamicAdvisedInterceptor：**

```java
// CglibAopProxy.DynamicAdvisedInterceptor
private static class DynamicAdvisedInterceptor implements MethodInterceptor {
    
    private final AdvisedSupport advised;
    
    @Override
    public Object intercept(Object proxy, Method method, Object[] args, 
                             MethodProxy methodProxy) throws Throwable {
        Object target = advised.getTargetSource().getTarget();
        Class<?> targetClass = target.getClass();
        
        // 与 JDK 代理一样，获取拦截链并执行
        List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
        
        Object retVal;
        if (chain.isEmpty()) {
            // 没有通知 → 直接调用目标方法
            retVal = methodProxy.invoke(target, args);
        } else {
            // ★ 创建 CglibMethodInvocation（包裹方法调用）
            retVal = new CglibMethodInvocation(proxy, target, method, args, targetClass, chain, methodProxy).proceed();
        }
        return retVal;
    }
}
```

**生成的代理类结构（伪代码）：**

```java
// CGLib 为 UserService 生成的代理类
public class UserService$$EnhancerByCGLIB$$xxx extends UserService {
    
    private MethodInterceptor interceptor;  // 即 DynamicAdvisedInterceptor
    
    @Override
    public void doSomething() {
        // 拦截方法调用，转发给 MethodInterceptor
        interceptor.intercept(this, getDoSomethingMethod(), args, methodProxy);
    }
}
```

### 2.3 验证

```java
@Service
public class UserService {  // 没有接口
    public void doSomething() { ... }
}

// 调用
UserService proxy = context.getBean(UserService.class);
System.out.println(proxy instanceof UserService);  // true！CGLib 通过继承实现
```

## 三、两种代理方式的对比

### 3.1 核心差异

| 维度 | JDK 动态代理 | CGLib |
|------|-------------|-------|
| 原理 | 实现接口（Proxy + InvocationHandler） | 继承父类（Enhancer + MethodInterceptor）|
| 要求 | 必须实现至少一个接口 | 类不能是 final |
| 生成代理对象时机 | 运行时（`Proxy.newProxyInstance()`）| 运行时（`Enhancer.create()`）|
| 代理类 | `$Proxy0` 实现目标接口 | `Xxx$$EnhancerByCGLIB$$xxx` 继承目标类 |
| 方法调用 | 通过 InvocationHandler | 通过 MethodInterceptor + MethodProxy |
| 性能（创建）| 快 | 较慢（ASM 字节码生成）|
| 性能（调用）| 稍慢（反射） | 快（FastClass 机制）|
| 是否可代理 final 方法 | 可（接口里没有 final 方法）| 否（不能继承 override）|
| 是否可代理 static 方法 | 否 | 否 |
| Spring 默认 | 目标有接口时 | 目标无接口时 |

### 3.2 调用性能测试

```java
// 大概性能数据（不同 JDK 版本有差异，仅供参考）
// JDK 8 + Spring 5.x
//
// 创建代理对象：
//   JDK Proxy:   ~2μs
//   CGLib:       ~15μs（首次需 ASM 生成字节码）
//
// 方法调用（无 AOP）：
//   Direct call:               ~30ns
//   JDK Proxy invoke:          ~150ns（反射调用）
//   CGLib methodProxy.invoke:  ~80ns（FastClass 索引调用）
//
// 方法调用（有 AOP 通知链）：
//   差异不大，主要开销在通知链本身
```

**CGLib 的 FastClass 机制：**

```java
// CGLib 为每个类（UserService 和 UserService$$Enhancer...）生成一个 FastClass
// FastClass 是一个索引表，通过 int 索引直接调用方法，避免反射

// FastClass 原理（伪代码）：
class UserService$$FastClass {
    Object invoke(int index, Object obj, Object[] args) {
        switch (index) {
            case 0: return ((UserService) obj).doSomething();
            case 1: return ((UserService) obj).anotherMethod();
            // ...
        }
    }
}

// MethodProxy 内部同时持有目标类 FastClass 和代理类 FastClass 的索引
// 所以 methodProxy.invoke(target, args) 极快——只是一个 int 索引的 switch
```

### 3.3 如何强制指定代理方式

```java
// 方式一：@EnableAspectJAutoProxy
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)  // 强制 CGLib
public class AopConfig {}

// 方式二：XML
<aop:aspectj-autoproxy proxy-target-class="true"/>

// 方式三：Spring Boot 全局配置
spring.aop.proxy-target-class=true  // Spring Boot 2.x 默认就是 true
```

### 3.4 Spring Boot 中的默认行为

```java
// Spring Boot 2.0 起：
// spring.aop.proxy-target-class 默认为 true
// 即默认使用 CGLib 代理
//
// 原因：
// 1. 大部分 Spring Boot 应用用 @Service/@Component，不显式实现接口
// 2. CGLib 调用性能更好
// 3. 避免了"强制要求接口"的侵入式设计
```

## 四、exposeProxy 与 AopContext

### 4.1 问题回顾

自调用导致 AOP 失效的问题在本系列第一篇中提过。Spring 提供了 `exposeProxy` 机制来补救：

```java
@Service
public class UserService {
    
    public void doSomething() {
        // 自调用：this 是原始对象，AOP 不生效
        internalMethod();  // @Transactional 失效
    }
    
    @Transactional
    public void internalMethod() { ... }
}
```

### 4.2 exposeProxy 解决方案

```java
// 1. 启用 exposeProxy
@Configuration
@EnableAspectJAutoProxy(exposeProxy = true)  // 暴露代理到 AopContext
public class AopConfig {}

// 2. 通过 AopContext 获取当前代理
@Service
public class UserService {
    
    public void doSomething() {
        // 从 AopContext 获取当前代理对象
        ((UserService) AopContext.currentProxy()).internalMethod();
    }
    
    @Transactional
    public void internalMethod() { ... }
}
```

### 4.3 原理

```java
// JdkDynamicAopProxy.invoke() 中：
if (this.advised.exposeProxy) {
    // 将当前代理对象存入 ThreadLocal
    oldProxy = AopContext.setCurrentProxy(proxy);
    setProxyContext = true;
}

// AopContext 的实现：
public final class AopContext {
    private static final ThreadLocal<Object> currentProxy = new ThreadLocal<>();
    
    public static Object currentProxy() {
        Object proxy = currentProxy.get();
        if (proxy == null) {
            throw new IllegalStateException("...");
        }
        return proxy;
    }
}
```

**注意事项：**
- `exposeProxy` 会带来少量性能开销（ThreadLocal 读写）
- 耦合了 `AopContext` API，不是很优雅
- 更推荐的方式：将自调用方法提取到另一个 Service 中

## 五、Spring AOP vs AspectJ

### 5.1 对比

| 维度 | Spring AOP | AspectJ |
|------|-----------|---------|
| 实现方式 | 运行时代理（JDK/CGLib） | 编译期织入（字节码增强）|
| 连接点 | 仅方法调用 | 方法、字段、构造器、初始化等 |
| 代理范围 | Spring 容器管理的 Bean 才可代理 | 任意 Java 对象 |
| 性能 | 运行时代理有调用开销 | 编译期织入，无运行时开销 |
| 使用复杂度 | 简单，与 Spring 集成好 | 需要特定编译器（ajc）|
| 是否改变类结构 | 否（生成代理类） | 是（直接修改原 Class）|
| 是否支持 final 类/方法 | JDK 支持接口，CGLib 不支持 final | 支持（编译时直接织入代码）|

### 5.2 何时需要 AspectJ

Spring AOP 能满足 95% 以上的需求。以下场景需要考虑 AspectJ：

```java
// 场景 1：需要拦截 final 方法或构造器
// 场景 2：需要拦截字段的读写（get/set）
// 场景 3：需要拦截不在 Spring 容器中的对象（如 new 创建的对象）
// 场景 4：对性能要求极高，无法接受运行时代理的开销
```

### 5.3 Spring 中使用有限 AspectJ（load-time weaving）

```java
@Configuration
@EnableLoadTimeWeaving  // 启用 AspectJ 加载时织入
public class AspectJConfig {
    // 需要配置 agent: -javaagent:spring-instrument-5.x.jar
}
```

## 六、AOP 的最佳实践

### 6.1 切面设计原则

```java
// 1. 切面单一职责
// ✅ 好的设计：日志切面只做日志，事务切面只做事务
// ❌ 坏的设计：一个切面既做日志又做事务又做缓存

// 2. 切面粒度适中
// ✅ 使用自定义注解做精确的切点
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitored {}

@Aspect
@Component
public class MonitorAspect {
    @Around("@annotation(Monitored)")  // 只拦截标注了 @Monitored 的方法
    public Object monitor(ProceedingJoinPoint pjp) throws Throwable {
        // ...
    }
}
```

### 6.2 通知类型的选择

```java
// ✅ 优先选择非环绕通知（如果需要功能足够）
@Before → 权限校验、参数校验
@AfterReturning → 结果处理
@AfterThrowing → 异常处理
@After → 资源清理

// ⚠️ 环绕通知只在必要时使用
@Around → 性能监控、重试、缓存、事务
```

### 6.3 用自定义注解驱动 AOP

```java
// 自定义注解
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Cacheable {
    String key();
    int ttl() default 60;
}

// 切面
@Aspect
@Component
public class CacheAspect {
    
    private final CacheManager cacheManager;
    
    public CacheAspect(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }
    
    @Around("@annotation(cacheable)")  // 直接绑定注解参数
    public Object cache(ProceedingJoinPoint pjp, Cacheable cacheable) throws Throwable {
        String cacheKey = generateKey(cacheable.key(), pjp.getArgs());
        Object cached = cacheManager.get(cacheKey);
        
        if (cached != null) {
            return cached;
        }
        
        Object result = pjp.proceed();
        cacheManager.put(cacheKey, result, cacheable.ttl());
        return result;
    }
}
```

### 6.4 性能考量

- 非环绕通知每次都要判断方法是否匹配（Pointcut 匹配），尽量减少切点的复杂度
- 如果切点表达式很复杂，考虑用 `@annotation` 代替复杂的 `execution` 表达式
- 成熟的 Spring 应用可能有几十个切面，每次方法调用都要遍历所有 Advisor 检查匹配

## 七、总结

### 代理方式决策

```
目标类 → 是否有接口？
  ├── 是 → Spring 默认 JDK 代理
  │        可以通过 proxyTargetClass=true 强制 CGLib
  │
  └── 否 → CGLib 代理（无接口时自动 fallback）
  
CGLib 不可用的情况：
  ├── 目标类是 final class
  └── 目标方法是 final/static
```

### 最佳实践清单

| 实践 | 说明 |
|------|------|
| 优先用 `@annotation` 切点 | 比 `execution` 更精确、性能更好 |
| 优先用非环绕通知 | 除非需要控制方法执行流程 |
| 提取自调用方法到独立 Service | 比 `AopContext.currentProxy()` 更清洁 |
| 自定义注解驱动 AOP | 语义清晰，不依赖包路径 |
| 避免切面数量过多 | 每次方法调用都要遍历匹配 |
| 关注 `@Order` 顺序 | 多个切面时明确执行顺序 |

---

**上一篇：** [Spring AOP（二）：AOP 源码深度解析]({{< relref "post/spring-aop-source-code" >}})

**系列索引：**
- [Spring AOP（一）：AOP 概念与五种通知类型]({{< relref "post/spring-aop-usage" >}})
- [Spring AOP（二）：AOP 源码深度解析]({{< relref "post/spring-aop-source-code" >}})
- [Spring AOP（三）：JDK 代理 vs CGLib 与高级主题]({{< relref "post/spring-aop-proxy" >}})
