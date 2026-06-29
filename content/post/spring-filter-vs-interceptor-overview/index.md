---
title: "Filter vs Interceptor（一）：对比与使用"
date: 2018-06-02
draft: false
categories: ["Java"]
tags: ["Spring", "Filter", "Interceptor", "Servlet", "HandlerInterceptor", "对比"]
toc: true
---

## 前言

Filter 和 Interceptor 是 Java Web 开发中两个非常重要的概念。它们都可以在请求处理的前后插入自定义逻辑，但二者处于不同的层次，有各自的生命周期和作用范围。

很多开发者分不清什么时候该用 Filter、什么时候该用 Interceptor。本文从概念、使用方式、执行顺序到源码层面对两者进行全面对比。

<!--more-->

## 一、概念与层次

### 1.1 两者在请求链路中的位置

```
请求进入
    │
    ▼
Tomcat（Servlet 容器）
    │
    ├── Filter 1              ← Servlet 规范层面
    ├── Filter 2
    ├── ...
    │
    └── DispatcherServlet（Spring MVC 前端控制器）
          │
          ├── Interceptor 1   ← Spring 框架层面
          ├── Interceptor 2
          │
          ├── @Controller
          │
          ├── Interceptor 2 (postHandle)
          ├── Interceptor 1 (postHandle)
          │
          └── ViewResolver → 视图渲染
                │
          Filter ... (afterCompletion 之后)
```

**核心区别：**

| 维度 | Filter | Interceptor |
|------|--------|-------------|
| **规范层级** | Servlet 规范 | Spring 框架 |
| **作用范围** | 所有 URL 请求（包括静态资源）| 仅 Spring 管理的请求（经过 DispatcherServlet 的）|
| **访问对象** | HttpServletRequest / Response | 还可以访问 Handler、ModelAndView |
| **拦截粒度** | 粗粒度（URL 模式）| 细粒度（URL 模式 + 方法级别）|
| **执行次数** | 每个请求一次 | 每个请求经过 preHandle → postHandle → afterCompletion 三个阶段 |

## 二、Filter 的使用

### 2.1 接口定义

```java
public interface Filter {
    
    // 初始化（Web 应用启动时调用）
    default void init(FilterConfig filterConfig) {}
    
    // 每次请求调用
    void doFilter(ServletRequest request, ServletResponse response, 
                  FilterChain chain);
    
    // 销毁（Web 应用关闭时调用）
    default void destroy() {}
}
```

### 2.2 实现一个 Filter

```java
@Component  // Spring Boot 中可以直接注册
public class LoggingFilter implements Filter {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    @Override
    public void init(FilterConfig filterConfig) {
        log.info("LoggingFilter 初始化");
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, 
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        
        // 请求前处理
        long start = System.currentTimeMillis();
        log.info("请求开始: {} {}", httpRequest.getMethod(), httpRequest.getRequestURI());
        
        try {
            // 继续执行过滤器链
            chain.doFilter(request, response);
        } finally {
            // 请求后处理
            long elapsed = System.currentTimeMillis() - start;
            log.info("请求结束: {} {}, 耗时: {}ms", 
                     httpRequest.getMethod(), httpRequest.getRequestURI(), elapsed);
        }
    }
    
    @Override
    public void destroy() {
        log.info("LoggingFilter 销毁");
    }
}
```

### 2.3 注册 Filter

**方式一：Spring Boot 中 @Component（自动注册）**

```java
@Component
@Order(1)  // 控制顺序
public class FirstFilter implements Filter { ... }

@Component
@Order(2)
public class SecondFilter implements Filter { ... }
```

**方式二：@Bean + FilterRegistrationBean**

```java
@Configuration
public class FilterConfig {
    
    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter() {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new AuthFilter());
        bean.addUrlPatterns("/api/*");           // 只拦截 /api/*
        bean.setOrder(1);                        // 顺序
        return bean;
    }
    
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
        FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new RateLimitFilter());
        bean.addUrlPatterns("/*");
        bean.setOrder(2);
        return bean;
    }
}
```

**方式三：传统 web.xml（非 Spring Boot）**

```xml
<filter>
    <filter-name>authFilter</filter-name>
    <filter-class>com.example.AuthFilter</filter-class>
</filter>
<filter-mapping>
    <filter-name>authFilter</filter-name>
    <url-pattern>/api/*</url-pattern>
</filter-mapping>
```

### 2.4 常见 Filter 使用场景

```java
// 1. 字符编码
@Bean
public FilterRegistrationBean<CharacterEncodingFilter> encodingFilter() {
    FilterRegistrationBean<CharacterEncodingFilter> bean = new FilterRegistrationBean<>();
    CharacterEncodingFilter filter = new CharacterEncodingFilter();
    filter.setEncoding("UTF-8");
    filter.setForceEncoding(true);
    bean.setFilter(filter);
    bean.addUrlPatterns("/*");
    return bean;
}

// 2. CORS
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletResponse response = (HttpServletResponse) res;
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "*");
        
        if ("OPTIONS".equalsIgnoreCase(((HttpServletRequest) req).getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        chain.doFilter(req, res);
    }
}

// 3. XSS 过滤、SQL 注入过滤、IP 黑白名单、限流等
```

## 三、Interceptor 的使用

### 3.1 接口定义

```java
public interface HandlerInterceptor {
    
    // Handler 执行前调用
    // 返回 true：继续请求；返回 false：终止
    default boolean preHandle(HttpServletRequest request, 
                              HttpServletResponse response, 
                              Object handler) {
        return true;
    }
    
    // Handler 执行后、视图渲染前调用
    default void postHandle(HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler,
                            @Nullable ModelAndView modelAndView) {}
    
    // 请求完成后调用（无论是否异常）
    default void afterCompletion(HttpServletRequest request,
                                  HttpServletResponse response,
                                  Object handler,
                                  @Nullable Exception ex) {}
}
```

### 3.2 实现一个 Interceptor

```java
@Component
public class AuthInterceptor implements HandlerInterceptor {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                             HttpServletResponse response,
                             Object handler) {
        // 检查是否 HandlerMethod（避免静态资源等非方法请求）
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":401,\"message\":\"未授权\"}");
            return false;  // 拦截请求
        }
        
        // 校验 token ...
        return true;  // 放行
    }
    
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        // Handler 执行后调用，可以修改 ModelAndView
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求完成后的清理工作（总是执行）
        if (ex != null) {
            log.error("请求异常", ex);
        }
    }
}
```

### 3.3 注册 Interceptor

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Autowired
    private AuthInterceptor authInterceptor;
    
    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")               // 需要拦截的路径
                .excludePathPatterns("/api/login")        // 排除的路径
                .order(1);                                // 顺序
        
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .order(2);
    }
}
```

### 3.4 常见 Interceptor 使用场景

```java
// 1. 权限校验
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                         Object handler) {
    // 检查用户是否有权限访问当前 API
    return checkPermission(request, handler);
}

// 2. 日志记录（可获取方法名、参数等详细信息）
@Override
public void afterCompletion(HttpServletRequest request, ...) {
    if (handler instanceof HandlerMethod) {
        HandlerMethod hm = (HandlerMethod) handler;
        String methodName = hm.getMethod().getName();
        // 记录方法级别的日志
    }
}

// 3. 请求预处理（设置 ThreadLocal 上下文、读取公共参数等）

// 4. 响应后处理（统一包装响应、记录慢方法等）

// 5. 性能监控（精确到具体方法）
```

## 四、执行顺序对比

### 4.1 正常请求

```
Filter.doFilter → preHandle → Controller → postHandle → afterCompletion → Filter 返回

详细顺序：
1. Filter 1.doFilter()                 // doFilter 之前的代码
2. Filter 2.doFilter()                 // doFilter 之前的代码
3. chain.doFilter()                    // 进入 DispatcherServlet
4. Interceptor 1.preHandle()
5. Interceptor 2.preHandle()
6. Controller 方法执行
7. Interceptor 2.postHandle()
8. Interceptor 1.postHandle()          // postHandle 倒序
9. 视图渲染
10. Interceptor 2.afterCompletion()    // afterCompletion 倒序
11. Interceptor 1.afterCompletion()
12. Filter 2.doFilter()                // doFilter 之后的代码
13. Filter 1.doFilter()                // doFilter 之后的代码
```

### 4.2 preHandle 返回 false

```
Interceptor 1.preHandle() → return false
  ↓
不会进入 Controller，不会执行后续 Interceptor 的 preHandle
触发已执行拦截器的 afterCompletion
  ↓
Interceptor 1.afterCompletion()
```

### 4.3 Controller 抛出异常

```
Filter → preHandle → Controller 抛异常
  ↓
不会执行 postHandle
执行 Interceptor 的 afterCompletion（倒序）
  ↓
DispatcherServlet 的异常处理器处理异常
  ↓
Filter 的 doFilter 异常处理
```

## 五、对比总结

### 5.1 核心差异对照

| 能力 | Filter | Interceptor |
|------|--------|-------------|
| **依赖规范** | Servlet API | Spring Framework |
| **请求/响应包装** | ✅ 可以替换 request/response | ❌ 不能 |
| **访问 Handler 元数据** | ❌ | ✅ 可获取方法名、注解等 |
| **访问 ModelAndView** | ❌ | ✅（postHandle）|
| **拦截静态资源** | ✅ | ❌（默认不拦截）|
| **可以终止请求** | ✅（不调 chain.doFilter）| ✅（preHandle 返回 false）|
| **作用粒度** | URL 路径 | URL 路径 + 方法 |
| **Spring Boot 自动配置** | Servlet 容器自动发现 | 需实现 WebMvcConfigurer |

### 5.2 选型指南

```
场景 → 该用 Filter 还是 Interceptor？

需要操作请求/响应对象本身（如包装 request） → Filter
需要获取 Controller 方法和注解信息         → Interceptor
需要与 ModelAndView 交互                  → Interceptor
需要在视图渲染后做操作                      → Interceptor（afterCompletion）
需要对静态资源也生效                        → Filter
功能与 Spring 框架无关（如编码过滤）        → Filter
功能与业务逻辑紧密相关（如权限）            → Interceptor
```

### 5.3 一句话原则

> **Filter 解决"请求通路上"的问题（编码、压缩、安全），Interceptor 解决"业务调用中"的问题（权限、日志、上下文）。**

---

**下一篇：** [Filter vs Interceptor（二）：Filter 源码深度解析]({{< relref "post/spring-filter-source-code" >}})
