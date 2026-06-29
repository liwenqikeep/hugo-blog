---
title: "Spring MVC（二）：DispatcherServlet 请求处理流程"
date: 2018-05-27
draft: false
categories: ["Java"]
tags: ["Spring MVC", "DispatcherServlet", "doDispatch", "HandlerExecutionChain", "源码分析"]
toc: true
---

## 前言

上一篇我们看了 Spring MVC 的核心注解和使用。这篇文章从 `DispatcherServlet` 入手，深入一个 HTTP 请求从进入服务器到返回响应的完整处理流程。

理解 DispatcherServlet 是掌握 Spring MVC 源码的关键——它是整个 MVC 的**前端控制器（Front Controller）**，所有请求都经过它统一分发。

<!--more-->

> **源码版本：** Spring Framework 5.x

## 一、DispatcherServlet 在 Web 容器中的位置

### 1.1 Servlet 层次结构

```
Tomcat / Jetty（Servlet 容器）
  │
  └── FilterChain
        ├── CharacterEncodingFilter
        ├── SpringSecurityFilterChain
        ├── ...
        └── DispatcherServlet（最后一个 Filter 之后执行）
              │
              ├── HandlerMapping  →  路由到 Handler
              ├── HandlerAdapter  →  执行 Handler
              └── ViewResolver    →  解析视图
```

### 1.2 初始化过程

`DispatcherServlet` 继承自 `FrameworkServlet`，`FrameworkServlet` 继承自 `HttpServletBean`，`HttpServletBean` 继承自 `HttpServlet`。

```
HttpServlet
  └── HttpServletBean
        └── FrameworkServlet
              └── DispatcherServlet
```

**初始化关键路径：**

```java
// HttpServletBean.init() → FrameworkServlet.initServletBean() → DispatcherServlet.onRefresh()
// 最终调用 DispatcherServlet.initStrategies()

protected void initStrategies(ApplicationContext context) {
    // 初始化 MVC 九大组件
    initMultipartResolver(context);        // 文件上传解析器
    initLocaleResolver(context);           // 国际化解析器
    initThemeResolver(context);            // 主题解析器
    initHandlerMappings(context);          // ★ 处理器映射器
    initHandlerAdapters(context);          // ★ 处理器适配器
    initHandlerExceptionResolvers(context);// 异常解析器
    initRequestToViewNameTranslator(context); // 请求到视图名翻译
    initViewResolvers(context);            // 视图解析器
    initFlashMapManager(context);          // FlashMap 管理器
}
```

## 二、请求处理核心流程：doDispatch

所有 HTTP 请求最终进入 `DispatcherServlet.doDispatch()`，这是 Spring MVC 最核心的方法。

```java
// DispatcherServlet.java
protected void doDispatch(HttpServletRequest request, HttpServletResponse response) {
    HttpServletRequest processedRequest = request;
    HandlerExecutionChain mappedHandler = null;
    boolean multipartRequestParsed = false;

    try {
        // 1. 检查是否是文件上传请求
        processedRequest = checkMultipart(request);
        multipartRequestParsed = (processedRequest != request);

        // 2. ★ 获取 HandlerExecutionChain（包含 Handler + 拦截器链）
        mappedHandler = getHandler(processedRequest);
        if (mappedHandler == null) {
            noHandlerFound(processedRequest, response);
            return;
        }

        // 3. ★ 获取 HandlerAdapter（适配器模式）
        HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());

        // 4. 处理 Last-Modified 头
        String method = request.getMethod();
        boolean isGetOrHead = "GET".equals(method) || "HEAD".equals(method);
        if (isGetOrHead && ...) {
            // Last-Modified 处理
        }

        // 5. ★ 执行拦截器的 preHandle
        if (!mappedHandler.applyPreHandle(processedRequest, response)) {
            // 如果某个拦截器的 preHandle 返回 false，请求终止
            return;
        }

        // 6. ★ HandlerAdapter 执行目标方法
        mv = ha.handle(processedRequest, response, mappedHandler.getHandler());

        // 7. 如果返回的是 String 视图名，自动选择视图
        applyDefaultViewName(processedRequest, mv);

        // 8. ★ 执行拦截器的 postHandle
        mappedHandler.applyPostHandle(processedRequest, response, mv);

    } catch (Exception ex) {
        // 捕获 Handler 执行过程中的异常
        dispatchException = ex;
    } catch (Throwable err) {
        dispatchException = new NestedServletException("Handler dispatch failed", err);
    }

    // 9. ★ 处理最终结果（视图渲染 + 拦截器 afterCompletion）
    processDispatchResult(processedRequest, response, mappedHandler, mv, dispatchException);
}
```

**核心流程时序图：**

```
请求进入
    │
    ▼
DispatcherServlet.doDispatch()
    │
    ├── 1. checkMultipart()          — 处理文件上传
    │
    ├── 2. getHandler()              — 通过 HandlerMapping 获取执行链
    │      └── HandlerExecutionChain{Handler, Interceptor[]}
    │
    ├── 3. getHandlerAdapter()       — 获取适配的 HandlerAdapter
    │
    ├── 4. applyPreHandle()          — 拦截器前置处理
    │      ├── Interceptor1.preHandle()
    │      ├── Interceptor2.preHandle()
    │      └── ...
    │
    ├── 5. ha.handle()               — 执行目标方法
    │      └── 返回 ModelAndView
    │
    ├── 6. applyPostHandle()         — 拦截器后置处理
    │
    └── 7. processDispatchResult()   — 渲染视图 + afterCompletion
           ├── ViewResolver 解析视图
           ├── View.render() 渲染
           └── Interceptor.afterCompletion()
```

## 三、getHandler()——通过 HandlerMapping 获取执行链

```java
// DispatcherServlet.java
protected HandlerExecutionChain getHandler(HttpServletRequest request) {
    // 遍历所有注册的 HandlerMapping
    for (HandlerMapping mapping : this.handlerMappings) {
        HandlerExecutionChain handler = mapping.getHandler(request);
        if (handler != null) {
            return handler;  // 返回第一个匹配的 Handler
        }
    }
    return null;
}
```

**HandlerMapping 的查找顺序，由 `@Order` 控制：**

```
@RequestMappingHandlerMapping（优先级最高，处理 @RequestMapping 注解）
  └── 通过 URL 匹配找到对应的方法（HandlerMethod）
      └── 包装为 HandlerExecutionChain{HandlerMethod, Interceptor[]}

SimpleUrlHandlerMapping（处理静态资源等）
  └── 匹配 /** 等资源路径

BeanNameUrlHandlerMapping（处理 Bean name 为 URL 路径的 Bean）
```

## 四、getHandlerAdapter()——获取适配器

不同的 Handler 类型需要不同的适配器：

```java
// DispatcherServlet.java
protected HandlerAdapter getHandlerAdapter(Object handler) {
    for (HandlerAdapter adapter : this.handlerAdapters) {
        if (adapter.supports(handler)) {
            return adapter;
        }
    }
    throw new ServletException("No adapter for handler ...");
}
```

**HandlerAdapter 的类型：**

| HandlerAdapter | 支持的 Handler 类型 |
|---------------|-------------------|
| `RequestMappingHandlerAdapter` | `HandlerMethod`（标注了 `@RequestMapping` 的方法）|
| `HttpRequestHandlerAdapter` | `HttpRequestHandler` |
| `SimpleControllerHandlerAdapter` | `Controller` 接口（旧版）|
| `SimpleServletHandlerAdapter` | `Servlet` |

## 五、ha.handle()——执行目标方法

以最常用的 `RequestMappingHandlerAdapter` 为例，它的 `handle()` 方法最终委派给内部方法：

```java
// RequestMappingHandlerAdapter.java 简化
@Override
protected ModelAndView handleInternal(HttpServletRequest request,
                                      HttpServletResponse response,
                                      HandlerMethod handlerMethod) {
    ModelAndView mav;
    
    // 1. 检查是否需要 Session
    checkRequest(request);
    
    // 2. 执行请求
    mav = invokeHandlerMethod(request, response, handlerMethod);
    
    return mav;
}
```

**`invokeHandlerMethod()` 的核心逻辑：**

```java
// RequestMappingHandlerAdapter.java
protected ModelAndView invokeHandlerMethod(HttpServletRequest request,
                                           HttpServletResponse response,
                                           HandlerMethod handlerMethod) {
    // 1. 创建 ServletWebRequest
    ServletWebRequest webRequest = new ServletWebRequest(request, response);
    
    // 2. 创建 WebDataBinderFactory
    WebDataBinderFactory binderFactory = getDataBinderFactory(handlerMethod);
    
    // 3. 创建 ModelFactory
    ModelFactory modelFactory = getModelFactory(handlerMethod, binderFactory);
    
    // 4. ★ 创建 ServletInvocableHandlerMethod，包装目标方法
    ServletInvocableHandlerMethod invocableMethod = 
            createInvocableHandlerMethod(handlerMethod);
    invocableMethod.setHandlerMethodArgumentResolvers(this.argumentResolvers);  // 参数解析器
    invocableMethod.setHandlerMethodReturnValueHandlers(this.returnValueHandlers);  // 返回值处理器
    invocableMethod.setDataBinderFactory(binderFactory);
    invocableMethod.setParameterNameDiscoverer(this.parameterNameDiscoverer);
    
    // 5. 创建 ModelAndViewContainer
    ModelAndViewContainer mavContainer = new ModelAndViewContainer();
    mavContainer.addAllAttributes(RequestContextUtils.getInputFlashMap(request));
    modelFactory.initModel(webRequest, mavContainer, invocableMethod);
    
    // 6. ★ 执行目标方法
    invocableMethod.invokeAndHandle(webRequest, mavContainer);
    
    // 7. 返回 ModelAndView
    return getModelAndView(mavContainer, modelFactory, webRequest);
}
```

## 六、拦截器机制

### 6.1 HandlerInterceptor 接口

```java
public interface HandlerInterceptor {
    
    // 在 Handler 执行之前调用
    // 返回 true：继续执行；返回 false：中断请求
    default boolean preHandle(HttpServletRequest request, 
                              HttpServletResponse response, 
                              Object handler) {
        return true;
    }
    
    // 在 Handler 执行之后、视图渲染之前调用
    default void postHandle(HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler,
                            @Nullable ModelAndView modelAndView) {}
    
    // 请求完成之后调用（无论是否异常）
    default void afterCompletion(HttpServletRequest request,
                                  HttpServletResponse response,
                                  Object handler,
                                  @Nullable Exception ex) {}
}
```

### 6.2 执行顺序

```java
// HandlerExecutionChain.java — 源码中的执行逻辑

// preHandle：顺序执行（1→2→3）
boolean applyPreHandle(HttpServletRequest request, HttpServletResponse response) {
    for (int i = 0; i < this.interceptorList.size(); i++) {
        if (!this.interceptorList.get(i).preHandle(request, response, this.handler)) {
            // 如果 preHandle 返回 false，触发已执行拦截器的 afterCompletion
            triggerAfterCompletion(request, response, null);
            return false;
        }
    }
    return true;
}

// postHandle：倒序执行（3→2→1）
void applyPostHandle(HttpServletRequest request, HttpServletResponse response, ModelAndView mv) {
    for (int i = this.interceptorList.size() - 1; i >= 0; i--) {
        this.interceptorList.get(i).postHandle(request, response, this.handler, mv);
    }
}

// afterCompletion：倒序执行（3→2→1）
void triggerAfterCompletion(HttpServletRequest request, HttpServletResponse response, Exception ex) {
    for (int i = this.interceptorList.size() - 1; i >= 0; i--) {
        this.interceptorList.get(i).afterCompletion(request, response, this.handler, ex);
    }
}
```

### 6.3 定义与注册拦截器

```java
// 1. 定义拦截器
@Component
public class LoggingInterceptor implements HandlerInterceptor {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                             HttpServletResponse response, 
                             Object handler) {
        log.info("请求开始: {} {}", request.getMethod(), request.getRequestURI());
        return true;
    }
    
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        log.info("请求处理完成: {}", request.getRequestURI());
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (ex != null) {
            log.error("请求异常: {}", request.getRequestURI(), ex);
        }
        log.info("请求结束: {}", request.getRequestURI());
    }
}

// 2. 注册拦截器
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loggingInterceptor())
                .addPathPatterns("/api/**")             // 拦截路径
                .excludePathPatterns("/api/login");     // 排除路径
    }
}
```

## 七、processDispatchResult——视图渲染

无论 Handler 是否正常返回，最终都会进入 `processDispatchResult()`：

```java
// DispatcherServlet.java
private void processDispatchResult(HttpServletRequest request,
                                    HttpServletResponse response,
                                    HandlerExecutionChain mappedHandler,
                                    ModelAndView mv,
                                    Exception exception) {
    
    boolean errorView = false;
    
    // 1. 处理异常 → 通过 HandlerExceptionResolver 获取错误视图
    if (exception != null) {
        if (exception instanceof ModelAndViewDefiningException) {
            mv = ((ModelAndViewDefiningException) exception).getModelAndView();
        } else {
            Object handler = (mappedHandler != null ? mappedHandler.getHandler() : null);
            mv = processHandlerException(request, response, handler, exception);
            errorView = (mv != null);
        }
    }

    // 2. 渲染视图
    if (mv != null && !mv.wasCleared()) {
        render(mv, request, response);
    }
    
    // 3. 触发拦截器的 afterCompletion
    if (mappedHandler != null) {
        mappedHandler.triggerAfterCompletion(request, response, null);
    }
}
```

### render() 视图渲染

```java
// DispatcherServlet.java
protected void render(ModelAndView mv, HttpServletRequest request,
                      HttpServletResponse response) {
    // 1. 解析视图
    View view;
    String viewName = mv.getViewName();
    if (viewName != null) {
        // 通过 ViewResolver 解析视图名 → View 对象
        view = resolveViewName(viewName, mv.getModelInternal(), locale, request);
    } else {
        view = mv.getView();  // 直接使用 View 对象
    }
    
    // 2. 渲染
    view.render(mv.getModelInternal(), request, response);
}
```

## 八、完整请求生命周期

```
1. 浏览器发送 HTTP 请求
    │
2. Tomcat 接收请求，创建 HttpServletRequest 和 HttpServletResponse
    │
3. FilterChain 执行（编码、安全认证等）
    │
4. DispatcherServlet.doDispatch()
    │
    ├── 4.1 checkMultipart()                — 文件上传处理
    ├── 4.2 getHandler()                    — HandlerMapping 路由
    ├── 4.3 getHandlerAdapter()             — 获取适配器
    ├── 4.4 applyPreHandle()                — 拦截器前置（顺序）
    ├── 4.5 ha.handle()                     — HandlerAdapter 执行
    │         ├── 参数解析                   — 从 request 中提取参数
    │         ├── 方法调用                   — 反射执行 @RequestMapping 方法
    │         └── 返回值处理                 — @ResponseBody → JSON
    ├── 4.6 applyPostHandle()               — 拦截器后置（倒序）
    ├── 4.7 processDispatchResult()
    │         ├── render()                   — 视图渲染
    │         └── triggerAfterCompletion()   — 拦截器最终处理（倒序）
    │
5. 响应返回给客户端
```

## 九、总结

### DispatcherServlet 核心方法速查

| 方法 | 作用 | 关键对象 |
|------|------|---------|
| `doDispatch()` | 请求分发入口 | — |
| `checkMultipart()` | 检查文件上传 | MultipartResolver |
| `getHandler()` | 遍历 HandlerMapping 匹配 | HandlerExecutionChain |
| `getHandlerAdapter()` | 获取 HandlerAdapter | HandlerAdapter |
| `ha.handle()` | 执行 Handler | ModelAndView |
| `applyPreHandle()` | 拦截器前置 | HandlerInterceptor |
| `applyPostHandle()` | 拦截器后置 | HandlerInterceptor |
| `processDispatchResult()` | 渲染结果 + afterCompletion | ViewResolver, View |
| `processHandlerException()` | 异常处理 | HandlerExceptionResolver |

### 核心设计模式

| 模式 | 体现 |
|------|------|
| **前端控制器** | DispatcherServlet |
| **策略模式** | HandlerMapping / HandlerAdapter / ViewResolver |
| **适配器模式** | HandlerAdapter 适配不同的 Handler |
| **责任链模式** | 拦截器链 + FilterChain |
| **模板方法** | doDispatch 定义流程骨架 |

---

**上一篇：** [Spring MVC（一）：核心注解与使用]({{< relref "post/spring-mvc-usage" >}})

**下一篇：** [Spring MVC（三）：HandlerMapping 与 HandlerAdapter]({{< relref "post/spring-mvc-handler-mapping-adapter" >}})
