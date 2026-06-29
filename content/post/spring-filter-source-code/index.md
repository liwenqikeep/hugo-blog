---
title: "Filter vs Interceptor（二）：Filter 源码深度解析"
date: 2018-06-04
draft: false
categories: ["Java"]
tags: ["Spring", "Filter", "FilterChain", "Servlet", "源码分析", "Tomcat", "ApplicationFilterChain"]
toc: true
---

## 前言

上一篇我们从概念和使用上对比了 Filter 和 Interceptor。这篇文章深入到 Servlet 容器和 Spring 框架的底层，完整追踪一个 Filter 从**注册、初始化到每次请求的执行链路**。

Filter 处于 Servlet 规范层面，这意味着它的核心机制在 Tomcat 等 Servlet 容器中实现，Spring 只是在此基础上做了一些包装和增强。

<!--more-->

> **Servlet 规范版本：** Servlet 3.1 / 4.0
> **Tomcat 版本：** 9.x

## 一、Filter 的注册与初始化

### 1.1 Servlet 容器启动时的注册流程

```
Web 应用启动
    │
    ▼
Tomcat 读取 web.xml 或扫描 @WebFilter 注解
    │
    ▼
创建 FilterConfig 和 Filter 实例
    │
    ▼
调用 Filter.init(FilterConfig)
    │
    ▼
将 Filter 包装为 ApplicationFilterConfig，注册到 FilterDef 和 FilterMap
    │
    ▼
等待请求到来
```

**Tomcat 中 `StandardContext` 的 Filter 注册入口：**

```java
// Tomcat — StandardContext.java
public boolean filterStart() {
    // 获取所有 Filter 定义
    for (Map.Entry<String, ApplicationFilterConfig> entry : filterConfigs.entrySet()) {
        ApplicationFilterConfig filterConfig = entry.getValue();
        // 调用 Filter.init()
        filterConfig.getFilter().init(filterConfig);
    }
    return true;
}
```

### 1.2 ApplicationFilterConfig

Tomcat 中每个 Filter 被包装为 `ApplicationFilterConfig`：

```java
// Tomcat — ApplicationFilterConfig.java
public final class ApplicationFilterConfig implements FilterConfig {
    
    private final Context context;          // ServletContext
    private final FilterDef filterDef;      // Filter 定义
    private Filter filter;                  // Filter 实例（可能是单例或原型）
    
    // 获取 Filter 实例（首次调用初始化）
    public Filter getFilter() {
        if (filter == null) {
            filter = filterDef.getFilter();
            if (filter == null) {
                filter = (Filter) instanceManager.newInstance(filterDef.getFilterClass());
                filterDef.setFilter(filter);
                filter.init(this);
            }
        }
        return filter;
    }
}
```

### 1.3 Spring Boot 中的 Filter 注册

Spring Boot 通过 `ServletContextInitializer` 机制将 Filter 注册到 Servlet 容器：

```java
// Spring Boot — RegistrationBean 体系
public abstract class RegistrationBean implements ServletContextInitializer, Ordered {
    // 1. 收集所有 FilterRegistrationBean
    // 2. 在 onStartup() 中注册到 ServletContext
}

// FilterRegistrationBean 的关键方法
public class FilterRegistrationBean<T extends Filter> 
        extends AbstractFilterRegistrationBean<T> {
    
    @Override
    public void onStartup(ServletContext servletContext) {
        // 1. 获取 Filter 名称
        String filterName = getOrDeduceName(this.filter);
        
        // 2. 通过标准的 Servlet API 注册
        Dynamic registration = servletContext.addFilter(filterName, this.filter);
        
        // 3. 设置 URL 模式
        registration.addMappingForUrlPatterns(
                isMatchAfter(),  // 是否追加到已有匹配之后
                false,            // 是否只匹配 Servlet
                getUrlPatterns().toArray(new String[0]));
        
        // 4. 设置初始化参数
        registration.setInitParameters(getInitParameters());
        
        // 5. 设置异步支持
        if (isAsyncSupported()) {
            registration.setAsyncSupported(true);
        }
    }
}
```

`EmbeddedWebApplicationContext` 在启动时会调用所有 `ServletContextInitializer`，完成 Servlet 容器级别的注册：

```java
// Spring Boot — EmbeddedWebApplicationContext.java
private void selfInitialize(ServletContext servletContext) {
    // 获取所有 ServletContextInitializer Bean
    for (ServletContextInitializer bean : getServletContextInitializerBeans()) {
        // 调用 onStartup() 注册 Filter、Servlet、Listener
        bean.onStartup(servletContext);
    }
}
```

## 二、FilterChain 的构建

当一个请求到达 Tomcat 时，Tomcat 会为每个请求创建一条 `ApplicationFilterChain`。

### 2.1 FindFilterChain 的创建

```java
// Tomcat — StandardWrapperValve.java（处理请求的 Valve）
public final class StandardWrapperValve extends ValveBase {
    
    @Override
    public void invoke(Request request, Response response) {
        // 1. 创建 ApplicationFilterChain
        ApplicationFilterChain filterChain = 
                ApplicationFilterFactory.createFilterChain(request, wrapper, servlet);
        
        // 2. 执行过滤器链
        filterChain.doFilter(request.getRequest(), response.getResponse());
        
        // 3. 清理
        filterChain.release();
    }
}
```

### 2.2 ApplicationFilterFactory 创建 FilterChain

```java
// Tomcat — ApplicationFilterFactory.java
public static ApplicationFilterChain createFilterChain(ServletRequest request,
                                                       Wrapper wrapper,
                                                       Servlet servlet) {
    ApplicationFilterChain filterChain = null;
    
    // 1. 从请求中获取上下文
    StandardContext context = (StandardContext) wrapper.getParent();
    
    // 2. 获取 FilterMaps（URL 模式和对应的 Filter 引用）
    FilterMap[] filterMaps = context.findFilterMaps();
    
    if (filterMaps != null && filterMaps.length > 0) {
        filterChain = new ApplicationFilterChain();
        filterChain.setServlet(servlet);
        
        // 3. 遍历所有 FilterMap
        for (int i = 0; i < filterMaps.length; i++) {
            FilterMap filterMap = filterMaps[i];
            
            // 4. 检查当前请求是否匹配 Filter 的 URL 模式
            if (matchFiltersURL(filterMap, requestPath)) {
                // 5. 获取对应的 ApplicationFilterConfig
                ApplicationFilterConfig filterConfig = 
                        context.findFilterConfig(filterMap.getFilterName());
                if (filterConfig != null) {
                    // 6. 添加到过滤器链
                    filterChain.addFilter(filterConfig);
                }
            }
        }
    }
    
    return filterChain;
}
```

**URL 模式匹配规则：**

```java
// Tomcat — ApplicationFilterFactory.java
private boolean matchFiltersURL(FilterMap filterMap, String requestPath) {
    // 检查 URL 模式是否匹配
    // 支持：精确匹配 /api/user、路径匹配 /api/*、后缀匹配 *.json
    // 规则同 Servlet URL 映射
    return filterMap.getURLPatternMatcher().matches(requestPath);
}
```

## 三、ApplicationFilterChain 的执行

### 3.1 核心数据结构

```java
// Tomcat — ApplicationFilterChain.java
public final class ApplicationFilterChain implements FilterChain {
    
    // 过滤器链中的 Filter 数组
    private ApplicationFilterConfig[] filters = new ApplicationFilterConfig[0];
    
    // 当前执行位置
    private int pos = 0;
    
    // 最终要调用的 Servlet（业务处理）
    private Servlet servlet = null;
    
    // 添加 Filter
    void addFilter(ApplicationFilterConfig filterConfig) {
        // 简单扩容和追加
        ApplicationFilterConfig[] newFilters = 
                Arrays.copyOf(filters, filters.length + 1);
        newFilters[filters.length] = filterConfig;
        filters = newFilters;
    }
}
```

### 3.2 doFilter 的执行过程

```java
// Tomcat — ApplicationFilterChain.java
@Override
public void doFilter(ServletRequest request, ServletResponse response) {
    // 1. 检查是否还有未执行的 Filter
    if (pos < filters.length) {
        // 2. 取出当前 Filter
        ApplicationFilterConfig filterConfig = filters[pos++];
        
        try {
            // 3. ★ 获取 Filter 实例并调用 doFilter
            Filter filter = filterConfig.getFilter();
            
            // 4. 调用 filter 的 doFilter，传入 this（FilterChain）
            //    这样 filter 内部调用 chain.doFilter() 时会再次进入此方法
            filter.doFilter(request, response, this);
            
        } catch (IOException | ServletException e) {
            throw e;
        }
    } else {
        // 5. ★ 所有 Filter 执行完毕 → 调用 Servlet.service()
        //    进入 Spring MVC 的 DispatcherServlet
        servlet.service(request, response);
    }
}
```

**关键设计——递归式责任链：**

```
ApplicationFilterChain.doFilter()
    │
    ├── [pos=0] Filter1.doFilter(req, res, chain)
    │         └── chain.doFilter()    ← 再调用 doFilter，pos 变为 1
    │               │
    │               ├── [pos=1] Filter2.doFilter(req, res, chain)
    │               │         └── chain.doFilter()  ← pos 变为 2
    │               │               │
    │               │               ├── [pos=2] ...
    │               │               │         ...
    │               │               │
    │               │               └── [pos=n] 所有 Filter 执行完
    │               │                     └── servlet.service() → Controller
    │               │
    │               └── Filter2.doFilter() 之后的代码 ← 响应回来后执行
    │
    └── Filter1.doFilter() 之后的代码 ← 响应回来后执行
```

### 3.3 完整的调用时序

```java
// 假设有 3 个 Filter（按注册顺序：LoggingFilter → AuthFilter → CorsFilter）
// 执行时序如下：

// 阶段一：请求处理（方向：外 → 内）
LoggingFilter.doFilter() {
    // 请求前置代码
    System.out.println("1. LoggingFilter before");
    
    chain.doFilter(request, response);  // ← 进入下一个 Filter
    │
    ├── AuthFilter.doFilter() {
    │       System.out.println("2. AuthFilter before");
    │       
    │       chain.doFilter(request, response);
    │       │
    │       ├── CorsFilter.doFilter() {
    │       │       System.out.println("3. CorsFilter before");
    │       │       
    │       │       chain.doFilter(request, response);
    │       │       │
    │       │       ├── 所有 Filter 执行完毕
    │       │       │     └── DispatcherServlet.service()
    │       │       │           → Controller 执行
    │       │       │
    │       │       System.out.println("3. CorsFilter after");
    │       │   }
    │       
    │       System.out.println("2. AuthFilter after");
    │   }
    
    System.out.println("1. LoggingFilter after");
}

// 输出：
// 1. LoggingFilter before
// 2. AuthFilter before
// 3. CorsFilter before
// → Controller 处理请求 ←
// 3. CorsFilter after
// 2. AuthFilter after
// 1. LoggingFilter after
```

## 四、OncePerRequestFilter

Spring 提供了 `OncePerRequestFilter` 来解决一些特殊情况：当请求被 forward 到其他 Servlet 时，Filter 可能会被调用多次。

### 4.1 问题场景

```java
@GetMapping("/forward")
public String forward() {
    return "forward:/other-path";  // forward 会导致 Filter 再次执行
}
```

### 4.2 OncePerRequestFilter 的实现

```java
// Spring — OncePerRequestFilter.java
public abstract class OncePerRequestFilter extends GenericFilterBean {
    
    // 请求属性名称，用于标记该请求是否已经被此 Filter 处理过
    public static final String ALREADY_FILTERED_SUFFIX = ".FILTERED";
    
    @Override
    public final void doFilter(ServletRequest request, ServletResponse response,
                               FilterChain chain) throws IOException, ServletException {
        
        if (!(request instanceof HttpServletRequest) 
                || !(response instanceof HttpServletResponse)) {
            throw new ServletException("OncePerRequestFilter just supports HTTP requests");
        }
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // 1. 生成当前 Filter 的标记属性名（如 "com.example.MyFilter.FILTERED"）
        String alreadyFilteredAttributeName = getAlreadyFilteredAttributeName();
        
        // 2. 检查 request 中是否已有该标记
        if (request.getAttribute(alreadyFilteredAttributeName) != null) {
            // ★ 已经处理过 → 直接放行，不再执行
            chain.doFilter(request, response);
        } else {
            // ★ 第一次处理 → 设置标记并执行
            request.setAttribute(alreadyFilteredAttributeName, Boolean.TRUE);
            try {
                // 子类实现具体的过滤逻辑
                doFilterInternal(httpRequest, httpResponse, chain);
            } finally {
                // 请求完成后移除标记（清理）
                request.removeAttribute(alreadyFilteredAttributeName);
            }
        }
    }
    
    // 子类实现具体过滤逻辑
    protected abstract void doFilterInternal(
            HttpServletRequest request, 
            HttpServletResponse response, 
            FilterChain chain) throws IOException, ServletException;
}
```

### 4.3 自定义 Filter 继承 OncePerRequestFilter

```java
@Component
public class AuthFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        // 这里只执行一次，即使有 forward 也不重复
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            response.setStatus(401);
            return;
        }
        chain.doFilter(request, response);
    }
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 可以加排除逻辑
        String path = request.getRequestURI();
        return path.equals("/api/login") || path.equals("/api/register");
    }
}
```

## 五、Spring Security 中的 FilterChain

Spring Security 依赖 Filter 实现，它通过 `DelegatingFilterProxy` 将控制权委托给 Spring 容器中的 `FilterChainProxy`：

### 5.1 DelegatingFilterProxy

```java
// Spring Web — DelegatingFilterProxy.java
public class DelegatingFilterProxy extends GenericFilterBean {
    
    private String targetBeanName;  // Filter 的 Bean 名称
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        // 1. 从 Spring 容器中获取真实的 Filter
        Filter delegate = getFilterBean();
        
        // 2. 委托给 Spring 管理的 Filter
        delegate.doFilter(request, response, chain);
    }
    
    // 延迟获取 Filter Bean（避免启动顺序问题）
    protected Filter getFilterBean() {
        return (Filter) getWebApplicationContext().getBean(targetBeanName);
    }
}
```

### 5.2 FilterChainProxy

```java
// Spring Security — FilterChainProxy.java
public class FilterChainProxy extends GenericFilterBean {
    
    private List<SecurityFilterChain> filterChains;
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        // 匹配当前请求对应的 SecurityFilterChain
        for (SecurityFilterChain filterChain : filterChains) {
            if (filterChain.matches(request)) {
                // ★ 执行 Spring Security 内部的 Filter 链
                VirtualFilterChain vfc = new VirtualFilterChain(
                        (HttpServletRequest) request, 
                        (HttpServletResponse) response, chain);
                vfc.doFilter(request, response);
                return;
            }
        }
        // 没有匹配的，放行
        chain.doFilter(request, response);
    }
}
```

## 六、Filter 的执行异常处理

Filter 链中如果某个 Filter 抛出异常，异常会逐层向上传递：

```java
// ApplicationFilterChain 中的异常处理
@Override
public void doFilter(ServletRequest request, ServletResponse response) {
    if (pos < filters.length) {
        ApplicationFilterConfig filterConfig = filters[pos++];
        try {
            Filter filter = filterConfig.getFilter();
            filter.doFilter(request, response, this);
        } catch (Throwable e) {
            // ★ 异常抛出后，后续 Filter 和 Servlet 都不会执行
            // 将异常抛出到 StandardWrapperValve
            throw e;
        }
    } else {
        servlet.service(request, response);
    }
}

// StandardWrapperValve 中处理异常
try {
    filterChain.doFilter(request.getRequest(), response.getResponse());
} catch (ServletException | IOException e) {
    // 异常处理：返回 500 或错误页面
    exception(request, response, e);
}
```

## 七、总结

### 核心源码文件索引

| 文件 | 位置 | 作用 |
|------|------|------|
| `ApplicationFilterChain.java` | Tomcat | Filter 链的核心——递归执行 Filter |
| `ApplicationFilterFactory.java` | Tomcat | 为每个请求创建 Filter 链 |
| `ApplicationFilterConfig.java` | Tomcat | 包装 Filter 实例和配置 |
| `StandardWrapperValve.java` | Tomcat | Servlet 调用的 Valve，触发 FilterChain |
| `FilterRegistrationBean.java` | Spring Boot | 将 Filter 注册到 ServletContext |
| `OncePerRequestFilter.java` | Spring Web | 确保 Filter 每个请求只执行一次 |
| `DelegatingFilterProxy.java` | Spring Web | 将 Filter 委托给 Spring 容器管理 |
| `FilterChainProxy.java` | Spring Security | Spring Security 的 Filter 链 |

### Filter 生命周期

```
init()           → Web 应用启动时（一次）
doFilter()       → 每个请求（N 次）
destroy()        → Web 应用关闭时（一次）
```

### Filter Chain 责任链模式

```
ApplicationFilterChain 使用递归实现责任链：
1. doFilter(pos=0) → Filter1.doFilter() → chain.doFilter() → 递归
2. doFilter(pos=1) → Filter2.doFilter() → chain.doFilter() → 递归
3. ...
n. doFilter(pos=n) → servlet.service()  ← 最终目标
```

---

**上一篇：** [Filter vs Interceptor（一）：对比与使用]({{< relref "post/spring-filter-vs-interceptor-overview" >}})

**下一篇：** [Filter vs Interceptor（三）：Interceptor 源码深度解析]({{< relref "post/spring-interceptor-source-code" >}})
