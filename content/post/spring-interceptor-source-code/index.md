---
title: "Filter vs Interceptor（三）：Interceptor 源码深度解析"
date: 2018-06-06
draft: false
categories: ["Java"]
tags: ["Spring", "Interceptor", "HandlerInterceptor", "HandlerExecutionChain", "源码分析", "Spring MVC"]
toc: true
---

## 前言

前两篇我们覆盖了 Filter/Interceptor 的对比使用和 Filter 的源码。这篇文章聚焦于 Spring MVC 中的 **Interceptor（拦截器）** 机制，从 `HandlerExecutionChain` 的构建、拦截器的注册，到 `preHandle`/`postHandle`/`afterCompletion` 三阶段调用的完整源码实现。

<!--more-->

> **源码版本：** Spring Framework 5.x

## 一、Interceptor 在 Spring MVC 中的位置

### 1.1 完整的请求链路

```
Tomcat → Filter → DispatcherServlet
                        │
                        ▼
                   getHandler()
                        │
                        ▼
              HandlerExecutionChain
              ├── Handler (Controller 方法)
              ├── Interceptor 1
              ├── Interceptor 2
              └── Interceptor 3
                        │
                        ▼
              applyPreHandle()     → preHandle(顺序)
              ha.handle()          → Controller 执行
              applyPostHandle()    → postHandle(倒序)
              processDispatchResult → 视图渲染
              triggerAfterCompletion() → afterCompletion(倒序)
```

Interceptor 是 Spring MVC 独有的机制，它被包装在 `HandlerExecutionChain` 中，由 `DispatcherServlet` 统一调度执行。

## 二、HandlerExecutionChain 的构建

### 2.1 从 HandlerMapping.getHandler() 开始

回顾 `AbstractHandlerMapping.getHandler()`——这是 HandlerExecutionChain 的构建入口：

```java
// AbstractHandlerMapping.java
@Override
public HandlerExecutionChain getHandler(HttpServletRequest request) {
    // 1. 子类查找具体的 Handler（如 HandlerMethod）
    Object handler = getHandlerInternal(request);
    if (handler == null) {
        handler = getDefaultHandler();
    }
    if (handler == null) return null;
    
    // 2. 如果 Handler 是 Bean name，从容器获取实例
    if (handler instanceof String) {
        handler = obtainApplicationContext().getBean((String) handler);
    }
    
    // ★ 3. 包装为 HandlerExecutionChain（添加拦截器）
    HandlerExecutionChain executionChain = getHandlerExecutionChain(handler, request);
    
    return executionChain;
}
```

### 2.2 拦截器的添加——getHandlerExecutionChain()

```java
// AbstractHandlerMapping.java
protected HandlerExecutionChain getHandlerExecutionChain(Object handler, 
                                                         HttpServletRequest request) {
    // 1. 如果 handler 已是 HandlerExecutionChain，直接复用
    HandlerExecutionChain chain = (handler instanceof HandlerExecutionChain ?
            (HandlerExecutionChain) handler : new HandlerExecutionChain(handler));
    
    // ★ 2. 遍历所有注册的拦截器
    for (HandlerInterceptor interceptor : this.adaptedInterceptors) {
        // 判断 URL 路径是否匹配
        if (interceptor instanceof MappedInterceptor) {
            MappedInterceptor mappedInterceptor = (MappedInterceptor) interceptor;
            if (mappedInterceptor.matches(request)) {
                chain.addInterceptor(mappedInterceptor.getInterceptor());
            }
        } else {
            // 未指定路径的拦截器 → 添加到所有请求
            chain.addInterceptor(interceptor);
        }
    }
    
    return chain;
}
```

### 2.3 MappedInterceptor 的匹配规则

通过 `InterceptorRegistry.addInterceptor()` 注册时，实际创建的是 `MappedInterceptor`：

```java
// MappedInterceptor.java
public final class MappedInterceptor implements HandlerInterceptor {
    
    private final HandlerInterceptor interceptor;  // 真正的拦截器
    private final PathMatcher pathMatcher;
    private final List<String> includePatterns;    // 包含路径
    private final List<String> excludePatterns;    // 排除路径
    
    @Override
    public boolean matches(HttpServletRequest request) {
        String path = getRequestPath(request);
        
        // 1. 检查排除路径
        for (String pattern : excludePatterns) {
            if (pathMatcher.match(pattern, path)) {
                return false;  // 匹配排除路径 → 不添加
            }
        }
        
        // 2. 如果没有包含路径，默认为所有 URL
        if (includePatterns.isEmpty()) {
            return true;
        }
        
        // 3. 检查包含路径
        for (String pattern : includePatterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        
        return false;
    }
}
```

### 2.4 拦截器的注册与筛选

```java
// WebMvcConfigurer.addInterceptors() → InterceptorRegistry
public class InterceptorRegistry {
    
    private final List<HandlerInterceptor> interceptors = new ArrayList<>();
    
    // 注册拦截器
    public InterceptorRegistration addInterceptor(HandlerInterceptor interceptor) {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        this.interceptors.add(registration.getInterceptor());
        return registration;
    }
    // 返回的是注册后的拦截器列表（可能是 MappedInterceptor）
    public List<HandlerInterceptor> getInterceptors() {
        return this.interceptors;
    }
}
```

**InterceptorRegistration 构建 MappedInterceptor：**

```java
public class InterceptorRegistration {
    
    private final HandlerInterceptor interceptor;
    private List<String> includePatterns = new ArrayList<>();
    private List<String> excludePatterns = new ArrayList<>();
    private int order = 0;
    
    public InterceptorRegistration addPathPatterns(String... patterns) {
        this.includePatterns.addAll(Arrays.asList(patterns));
        return this;
    }
    
    public InterceptorRegistration excludePathPatterns(String... patterns) {
        this.excludePatterns.addAll(Arrays.asList(patterns));
        return this;
    }
    
    public InterceptorRegistration order(int order) {
        this.order = order;
        return this;
    }
    
    // ★ 最终构建 MappedInterceptor
    protected HandlerInterceptor getInterceptor() {
        if (this.includePatterns.isEmpty()) {
            // 没有指定路径 → 直接返回原始拦截器
            return this.interceptor;
        }
        // 指定了路径 → 包装为 MappedInterceptor
        return new MappedInterceptor(this.includePatterns, 
                                      this.excludePatterns, 
                                      this.interceptor);
    }
}
```

## 三、拦截器的执行

### 3.1 DispatcherServlet 中的调用

拦截器的三个方法分别在 `DispatcherServlet.doDispatch()` 的不同阶段调用：

```java
// DispatcherServlet.doDispatch() 核心片段

// ★ 第一阶段：preHandle（Handler 执行前）
if (!mappedHandler.applyPreHandle(processedRequest, response)) {
    return;  // 任一拦截器返回 false，请求终止
}

// ★ 第二阶段：Handler 执行
mv = ha.handle(processedRequest, response, mappedHandler.getHandler());

// ★ 第三阶段：postHandle（Handler 执行后，视图渲染前）
mappedHandler.applyPostHandle(processedRequest, response, mv);

// ★ 第四阶段：视图渲染 + afterCompletion
processDispatchResult(processedRequest, response, mappedHandler, mv, dispatchException);
```

### 3.2 applyPreHandle——前置处理

`preHandle` 按照拦截器注册顺序**顺序执行**。任何拦截器返回 `false` 都会中断请求。

```java
// HandlerExecutionChain.java
boolean applyPreHandle(HttpServletRequest request, HttpServletResponse response) {
    // 顺序执行所有拦截器的 preHandle
    for (int i = 0; i < this.interceptorList.size(); i++) {
        HandlerInterceptor interceptor = this.interceptorList.get(i);
        
        if (!interceptor.preHandle(request, response, this.handler)) {
            // ★ preHandle 返回 false → 触发已执行拦截器的 afterCompletion
            triggerAfterCompletion(request, response, null);
            return false;  // 终止请求
        }
        
        this.interceptorIndex = i;  // 记录已执行到第几个
    }
    return true;
}
```

### 3.3 applyPostHandle——后置处理

`postHandle` 按照拦截器顺序的**倒序执行**。

```java
// HandlerExecutionChain.java
void applyPostHandle(HttpServletRequest request, HttpServletResponse response,
                     ModelAndView mv) {
    // 倒序执行所有拦截器的 postHandle
    for (int i = this.interceptorList.size() - 1; i >= 0; i--) {
        HandlerInterceptor interceptor = this.interceptorList.get(i);
        interceptor.postHandle(request, response, this.handler, mv);
    }
}
```

### 3.4 triggerAfterCompletion——完成处理

`afterCompletion` 也是**倒序执行**，且**无论是否异常都会调用**。

```java
// HandlerExecutionChain.java
void triggerAfterCompletion(HttpServletRequest request, HttpServletResponse response,
                            Exception ex) {
    // 只触发已执行过 preHandle 的拦截器
    // interceptorIndex 记录了最后一个成功执行 preHandle 的索引
    for (int i = this.interceptorIndex; i >= 0; i--) {
        HandlerInterceptor interceptor = this.interceptorList.get(i);
        try {
            interceptor.afterCompletion(request, response, this.handler, ex);
        } catch (Throwable ex2) {
            logger.error("afterCompletion failed", ex2);
        }
    }
}
```

### 3.5 完整的调用时序

```
注册顺序：InterceptorA → InterceptorB → InterceptorC
（按 Order 正序排列）

正常请求：
  [顺序]     InterceptorA.preHandle()     → true
  [顺序]     InterceptorB.preHandle()     → true
  [顺序]     InterceptorC.preHandle()     → true
              ↓
            Controller 方法执行
              ↓
  [倒序]     InterceptorC.postHandle()
  [倒序]     InterceptorB.postHandle()
  [倒序]     InterceptorA.postHandle()
              ↓
            视图渲染
              ↓
  [倒序]     InterceptorC.afterCompletion()
  [倒序]     InterceptorB.afterCompletion()
  [倒序]     InterceptorA.afterCompletion()


InterceptorB.preHandle() 返回 false：
  [顺序]     InterceptorA.preHandle()     → true (interceptorIndex=0)
  [顺序]     InterceptorB.preHandle()     → false
              ↓
            ！！！Controller 不会执行！！！
            ！！！后续拦截器的 preHandle 不会执行！！！
              ↓
  [倒序]     triggerAfterCompletion:
             InterceptorA.afterCompletion()   ← 只执行已执行过的


Controller 抛出异常：
  [顺序]     InterceptorA.preHandle()     → true
  [顺序]     InterceptorB.preHandle()     → true
              ↓
            Controller 执行 → 抛出异常
              ↓
            postHandle 不会执行（因异常）
              ↓
  [倒序]     InterceptorB.afterCompletion(ex)
  [倒序]     InterceptorA.afterCompletion(ex)
              ↓
            DispatcherServlet 的异常处理器处理异常
```

## 四、拦截器的注册顺序与执行顺序

### 4.1 通过 @Order 控制顺序

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册顺序决定了 preHandle 的执行顺序
        registry.addInterceptor(interceptorA).order(1);  // 第一个执行 preHandle
        registry.addInterceptor(interceptorB).order(2);  // 第二个执行 preHandle
        registry.addInterceptor(interceptorC).order(3);  // 第三个执行 preHandle
    }
}
```

### 4.2 HandlerExecutionChain 中拦截器的排序

`AbstractHandlerMapping` 初始化时会对拦截器排序：

```java
// AbstractHandlerMapping.java
public void setInterceptors(Object... interceptors) {
    List<HandlerInterceptor> interceptorList = new ArrayList<>();
    for (Object interceptor : interceptors) {
        // ... 转换逻辑
        interceptorList.add(adaptInterceptor(interceptor));
    }
    // ★ 按 @Order 排序
    AnnotationAwareOrderComparator.sort(interceptorList);
    this.adaptedInterceptors = interceptorList;
}
```

## 五、拦截器 vs AOP

拦截器在 Spring MVC 层面的功能与 AOP 有部分重叠，但定位不同：

| 维度 | 拦截器（HandlerInterceptor） | AOP |
|------|---------------------------|-----|
| 作用范围 | 仅 Controller 方法 | 任意 Spring Bean 的方法 |
| 可访问 Servlet 对象 | ✅ request/response | ❌ 不能 |
| 可访问 Handler 方法信息 | ✅ | ✅ |
| 可控制是否执行目标方法 | ✅（preHandle 返回 false） | ✅（@Around.proceed()）|
| 粒度 | 类级别 + 路径匹配 | 方法级别（Pointcut 表达式）|
| 与 Spring 容器关系 | 独立 | 紧密集成 |

**什么时候用拦截器而不是 AOP？**

```java
// ✅ 需要访问 request/response 时 → 用拦截器
@Component
public class RequestContextInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                             HttpServletResponse response,
                             Object handler) {
        // 拦截器可以直接访问 request 设置属性
        request.setAttribute("startTime", System.currentTimeMillis());
        return true;
    }
}

// ❌ AOP 中获取 HttpServletRequest 比较困难（需要用 RequestContextHolder）

// ✅ 需要根据 URL 路径做控制时 → 用拦截器
registry.addInterceptor(adminInterceptor)
        .addPathPatterns("/api/admin/**")
        .excludePathPatterns("/api/admin/login");
```

## 六、常见问题

### 6.1 拦截器不生效的原因

```java
// 1. 拦截器没有注册
// ❌ 只写了 @Component，但没在 addInterceptors 中注册
@Component
public class MyInterceptor implements HandlerInterceptor { ... }

// 需要在 WebMvcConfigurer 中注册
// 2. Spring Boot 中不能忘记 @Configuration
// 3. URL 模式写错（如 /api/* 不匹配 /api/user/detail）
```

### 6.2 拦截器中获取请求参数

```java
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                         Object handler) {
    // 方式一：直接从 request 获取
    String token = request.getHeader("Authorization");
    String userId = request.getParameter("userId");
    
    // 方式二：从 HandlerMethod 获取注解信息
    if (handler instanceof HandlerMethod) {
        HandlerMethod hm = (HandlerMethod) handler;
        RequiresPermission annotation = hm.getMethodAnnotation(RequiresPermission.class);
        if (annotation != null) {
            // 根据注解信息做权限校验
        }
    }
    
    return true;
}
```

### 6.3 拦截器中注入 Bean

```java
@Component  // 确保是 Spring 管理的 Bean
public class AuthInterceptor implements HandlerInterceptor {
    
    @Autowired  // 正常注入
    private UserService userService;
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                             HttpServletResponse response,
                             Object handler) {
        userService.validateToken(...);  // 直接使用
        return true;
    }
}
```

## 七、总结

### Interceptor 三个方法速查

| 方法 | 执行时机 | 参数特点 | 用途 |
|------|---------|---------|------|
| `preHandle` | Handler 执行前 | 有 response | 权限校验、参数预处理 |
| `postHandle` | Handler 执行后、视图渲染前 | 有 ModelAndView | 修改视图模型、记录响应 |
| `afterCompletion` | 视图渲染后 | 有 Exception | 清理资源、记录异常日志 |

### Interceptor 执行顺序

```
preHandle:    A → B → C（注册顺序，@Order 正序）
postHandle:   C → B → A（倒序）
afterCompletion: C → B → A（倒序，只执行 preHandle 成功的）
```

### 核心源码文件索引

| 类 | 位置 | 作用 |
|---|------|------|
| `HandlerExecutionChain` | Spring MVC | 包装 Handler + 拦截器列表 |
| `AbstractHandlerMapping` | Spring MVC | getHandlerExecutionChain() 添加拦截器 |
| `MappedInterceptor` | Spring MVC | 带 URL 匹配规则的拦截器 |
| `InterceptorRegistry` | Spring MVC | 注册拦截器的入口 |
| `InterceptorRegistration` | Spring MVC | 链式配置拦截器路径和顺序 |
| `DispatcherServlet` | Spring MVC | doDispatch 中调用 interceptor 方法 |

---

**上一篇：** [Filter vs Interceptor（二）：Filter 源码深度解析]({{< relref "post/spring-filter-source-code" >}})

**系列索引：**
- [Filter vs Interceptor（一）：对比与使用]({{< relref "post/spring-filter-vs-interceptor-overview" >}})
- [Filter vs Interceptor（二）：Filter 源码深度解析]({{< relref "post/spring-filter-source-code" >}})
- [Filter vs Interceptor（三）：Interceptor 源码深度解析]({{< relref "post/spring-interceptor-source-code" >}})
