---
title: "Spring MVC（三）：HandlerMapping 与 HandlerAdapter"
date: 2018-05-29
draft: false
categories: ["Java"]
tags: ["Spring MVC", "HandlerMapping", "HandlerAdapter", "RequestMapping", "参数解析", "源码分析"]
toc: true
---

## 前言

上一篇我们追踪了 `DispatcherServlet.doDispatch()` 的完整流程。其中最核心的两个步骤是 `getHandler()` 和 `ha.handle()`——它们分别对应了将请求路由到方法、以及执行方法并处理参数/返回值的完整机制。

本文深入这两个步骤的底层实现。

<!--more-->

> **源码版本：** Spring Framework 5.x

## 一、HandlerMapping 体系

### 1.1 HandlerMapping 接口

```java
public interface HandlerMapping {
    
    // 根据请求获取 HandlerExecutionChain
    HandlerExecutionChain getHandler(HttpServletRequest request);
}
```

**核心实现类层次：**

```
HandlerMapping
  ├── AbstractHandlerMapping（模板方法基类）
  │     ├── AbstractUrlHandlerMapping    — URL 路径匹配
  │     │     └── SimpleUrlHandlerMapping — 显式 URL 映射（静态资源等）
  │     │
  │     └── AbstractHandlerMethodMapping — 方法级别的映射
  │           └── RequestMappingInfoHandlerMapping
  │                 └── RequestMappingHandlerMapping  ← ★ 最常用
  │                       （处理 @RequestMapping 注解）
  │
  └── BeanNameUrlHandlerMapping — Bean name 作为 URL 路径
```

### 1.2 RequestMappingHandlerMapping 的初始化

`RequestMappingHandlerMapping` 在容器初始化时扫描所有 Bean，收集 `@RequestMapping` 注解。

```java
// RequestMappingHandlerMapping 继承 AbstractHandlerMethodMapping
// AbstractHandlerMethodMapping 实现了 InitializingBean

// 容器启动时调用 afterPropertiesSet()
public class RequestMappingHandlerMapping 
        extends RequestMappingInfoHandlerMapping
        implements MatchableHandlerMapping {
    
    // 在父类初始化扫描中，通过 isHandler() 判断哪些 Bean 是 Controller
    @Override
    protected boolean isHandler(Class<?> beanType) {
        // 检查是否标注了 @Controller 或 @RequestMapping
        return AnnotatedElementUtils.hasAnnotation(beanType, Controller.class);
    }
    
    // 从类和方法上提取 @RequestMapping 信息
    @Override
    protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
        // 1. 解析方法上的 @RequestMapping
        RequestMappingInfo info = createRequestMappingInfo(method);
        if (info != null) {
            // 2. 解析类上的 @RequestMapping（拼接类级别路径）
            RequestMappingInfo typeInfo = createRequestMappingInfo(handlerType);
            if (typeInfo != null) {
                info = typeInfo.combine(info);
            }
        }
        return info;
    }
    
    // 创建 RequestMappingInfo
    private RequestMappingInfo createRequestMappingInfo(AnnotatedElement element) {
        RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(
                element, RequestMapping.class);
        if (requestMapping == null) return null;
        
        // 解析 @RequestMapping 的所有属性
        return RequestMappingInfo
                .paths(resolveEmbeddedValuesInPatterns(requestMapping.path()))
                .methods(requestMapping.method())
                .params(requestMapping.params())
                .headers(requestMapping.headers())
                .consumes(requestMapping.consumes())
                .produces(requestMapping.produces())
                .mappingName(requestMapping.name())
                .build();
    }
}
```

### 1.3 映射注册结构

`AbstractHandlerMethodMapping` 内部维护了URL到方法的映射关系：

```java
// AbstractHandlerMethodMapping.java
public abstract class AbstractHandlerMethodMapping<T> {
    
    // ★ 核心映射表：URL → MappingRegistration
    private final MappingRegistry mappingRegistry = new MappingRegistry();
    
    protected class MappingRegistry {
        // 路径 → 映射列表（一个路径可能匹配多个方法）
        private final Map<String, List<T>> urlLookup = new LinkedHashMap<>();
        
        // 映射 → HandlerMethod
        private final Map<T, HandlerMethod> mappingLookup = new LinkedHashMap<>();
        
        // 所有 HandlerMethod 的列表
        private final List<HandlerMethod> handlerMethods = new ArrayList<>();
    }
    
    // 初始化时的注册过程
    protected void registerHandlerMethod(Object handler, Method method, T mapping) {
        HandlerMethod newHandlerMethod = createHandlerMethod(handler, method);
        this.mappingRegistry.register(mapping, newHandlerMethod);
    }
}
```

### 1.4 请求匹配过程——getHandler()

```java
// AbstractHandlerMapping.java（模板方法）
@Override
public HandlerExecutionChain getHandler(HttpServletRequest request) {
    // 1. 由子类实现——查找 Handler
    Object handler = getHandlerInternal(request);
    if (handler == null) {
        handler = getDefaultHandler();  // 默认处理器
    }
    if (handler == null) {
        return null;
    }
    
    // 2. 如果 handler 是 String（Bean name），从容器获取
    if (handler instanceof String) {
        String handlerName = (String) handler;
        handler = obtainApplicationContext().getBean(handlerName);
    }
    
    // 3. ★ 包装为 HandlerExecutionChain（添加拦截器）
    HandlerExecutionChain executionChain = getHandlerExecutionChain(handler, request);
    
    return executionChain;
}
```

**`AbstractHandlerMethodMapping.lookupHandlerMethod()`——方法级别的匹配：**

```java
// AbstractHandlerMethodMapping.java
protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) {
    List<Match> matches = new ArrayList<>();
    
    // 1. 直接路径匹配（先查精确路径）
    List<T> directPathMatches = this.mappingRegistry.getMappingsByUrl(lookupPath);
    if (directPathMatches != null) {
        addMatchingMappings(directPathMatches, matches, request);
    }
    
    // 2. 精确匹配没找到，遍历所有映射
    if (matches.isEmpty()) {
        addMatchingMappings(this.mappingRegistry.getMappings().keySet(), matches, request);
    }
    
    // 3. 处理匹配结果
    if (!matches.isEmpty()) {
        // 多个匹配 → 按条件排序（最精确的排第一）
        matches.sort(MATCH_COMPARATOR);
        
        // 获取最佳匹配
        Match bestMatch = matches.get(0);
        
        // 检查是否有多个相同优先级的匹配 → 冲突
        if (matches.size() > 1) {
            Match secondBestMatch = matches.get(1);
            if (COMPARATOR.compare(bestMatch, secondBestMatch) == 0) {
                throw new IllegalStateException("Ambiguous handler methods mapped ...");
            }
        }
        
        // 处理矩阵变量等
        handleMatch(bestMatch.mapping, lookupPath, request);
        return bestMatch.handlerMethod;
    }
    
    return null;
}
```

**匹配条件排序（按精确度）：**

```
精确路径 > 路径变量 {id} > 通配符 *
  ↓
有 HTTP 方法限定 > 无方法限定
  ↓
有 params 条件 > 无 params 条件
  ↓
有 consumes 条件 > 无 consumes 条件
  ↓
有 produces 条件 > 无 produces 条件
```

## 二、HandlerAdapter 体系

### 2.1 HandlerAdapter 接口

```java
public interface HandlerAdapter {
    
    // 判断是否支持该 Handler
    boolean supports(Object handler);
    
    // 执行 Handler
    ModelAndView handle(HttpServletRequest request, 
                        HttpServletResponse response, 
                        Object handler);
    
    // 获取 Last-Modified
    long getLastModified(HttpServletRequest request, Object handler);
}
```

### 2.2 RequestMappingHandlerAdapter

`RequestMappingHandlerAdapter` 是最核心的 HandlerAdapter，负责执行 `@RequestMapping` 方法。

```java
// RequestMappingHandlerAdapter 是 DispatcherServlet 中最关键的一个适配器
// 它具备两大能力：
// 1. 参数解析（Argument Resolvers）— 将 request 数据转为方法参数
// 2. 返回值处理（Return Value Handlers）— 将方法返回值转为 ModelAndView
```

**初始化时加载的解析器和处理器：**

```java
// RequestMappingHandlerAdapter.afterPropertiesSet()
@Override
public void afterPropertiesSet() {
    // 初始化参数解析器（默认已注册 30+ 个）
    this.argumentResolvers = getDefaultArgumentResolvers();
    
    // 初始化参数方法参数解析器（如 @ModelAttribute 方法的参数）
    this.initBinderArgumentResolvers = getDefaultInitBinderArgumentResolvers();
    
    // 初始化返回值处理器
    this.returnValueHandlers = getDefaultReturnValueHandlers();
}

// 默认注册的参数解析器（部分关键）：
private List<HandlerMethodArgumentResolver> getDefaultArgumentResolvers() {
    List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
    
    // 基于注解的参数解析
    resolvers.add(new RequestParamMethodArgumentResolver(beanFactory, false));   // @RequestParam
    resolvers.add(new RequestParamMapMethodArgumentResolver());                   // @RequestParam Map
    resolvers.add(new PathVariableMethodArgumentResolver());                      // @PathVariable
    resolvers.add(new PathVariableMapMethodArgumentResolver());                   // @PathVariable Map
    resolvers.add(new MatrixVariableMethodArgumentResolver());                    // @MatrixVariable
    resolvers.add(new RequestBodyMethodArgumentResolver(beanFactory));            // @RequestBody
    resolvers.add(new RequestHeaderMethodArgumentResolver(beanFactory));          // @RequestHeader
    resolvers.add(new RequestHeaderMapMethodArgumentResolver());                  // @RequestHeader Map
    resolvers.add(new CookieValueMethodArgumentResolver(beanFactory));            // @CookieValue
    resolvers.add(new ExpressionValueMethodArgumentResolver(beanFactory));        // @Value
    
    // 基于类型的参数解析
    resolvers.add(new ServletRequestMethodArgumentResolver());                    // HttpServletRequest
    resolvers.add(new ServletResponseMethodArgumentResolver());                   // HttpServletResponse
    resolvers.add(new HttpEntityMethodArgumentResolver(beanFactory));             // HttpEntity
    resolvers.add(new RedirectAttributesMethodArgumentResolver());                // RedirectAttributes
    resolvers.add(new ModelMethodArgumentResolver());                             // Model
    resolvers.add(new MapMethodArgumentResolver());                               // Map
    resolvers.add(new ErrorsMethodArgumentResolver());                            // Errors/BindingResult
    resolvers.add(new SessionStatusMethodArgumentResolver());                     // SessionStatus
    resolvers.add(new UriComponentsBuilderMethodArgumentResolver());              // UriComponentsBuilder
    
    // 其他...
    return resolvers;
}
```

## 三、参数解析器（HandlerMethodArgumentResolver）

### 3.1 接口定义

```java
public interface HandlerMethodArgumentResolver {
    
    // 是否支持该参数
    boolean supportsParameter(MethodParameter parameter);
    
    // 从 request 中解析参数值
    Object resolveArgument(MethodParameter parameter, 
                           ModelAndViewContainer mavContainer,
                           NativeWebRequest webRequest,
                           WebDataBinderFactory binderFactory);
}
```

### 3.2 解析过程

`RequestMappingHandlerAdapter` 在执行目标方法时，通过 `InvocableHandlerMethod` 逐参数解析：

```java
// InvocableHandlerMethod.java
protected Object[] getMethodArgumentValues(NativeWebRequest request,
                                           ModelAndViewContainer mavContainer,
                                           Object... providedArgs) {
    MethodParameter[] parameters = getMethodParameters();
    Object[] args = new Object[parameters.length];
    
    for (int i = 0; i < parameters.length; i++) {
        MethodParameter parameter = parameters[i];
        parameter.initParameterNameDiscovery(this.parameterNameDiscoverer);
        
        // 遍历所有参数解析器
        for (HandlerMethodArgumentResolver resolver : this.argumentResolvers) {
            if (resolver.supportsParameter(parameter)) {
                // ★ 解析参数
                args[i] = resolver.resolveArgument(parameter, mavContainer, request, this.dataBinderFactory);
                break;
            }
        }
        
        if (args[i] == null) {
            throw new IllegalStateException("Could not resolve parameter ...");
        }
    }
    return args;
}
```

### 3.3 @RequestParam 的解析

```java
// RequestParamMethodArgumentResolver.java
@Override
public boolean supportsParameter(MethodParameter parameter) {
    // 检查是否标注了 @RequestParam
    return parameter.hasParameterAnnotation(RequestParam.class);
}

@Override
public Object resolveArgument(MethodParameter parameter, ...) {
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
    
    // 1. 获取参数名（从注解或 -parameters 编译选项）
    String name = (requestParam != null ? requestParam.name() : "");
    if (name.isEmpty()) {
        name = parameter.getParameterName();  // 需要 -parameters 编译选项
    }
    
    // 2. 获取请求中的值
    HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
    String[] paramValues = servletRequest.getParameterValues(name);
    
    // 3. 类型转换
    Class<?> paramType = parameter.getParameterType();
    Object result = conversionService.convert(paramValues, paramType);
    
    return result;
}
```

### 3.4 @RequestBody 的解析

```java
// RequestBodyMethodArgumentResolver.java
@Override
public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(RequestBody.class);
}

@Override
public Object resolveArgument(MethodParameter parameter, ...) {
    // 1. 读取请求体（HttpMessageConverter）
    HttpInputMessage inputMessage = new ServletServerHttpRequest(
            webRequest.getNativeRequest(HttpServletRequest.class));
    
    Class<?> paramType = parameter.getParameterType();
    MethodParameter methodParam = parameter;
    
    // 2. 遍历所有 HttpMessageConverter
    //    MappingJackson2HttpMessageConverter（JSON）
    //    MappingJackson2XmlHttpMessageConverter（XML）
    //    StringHttpMessageConverter（String）
    for (HttpMessageConverter<?> converter : this.messageConverters) {
        if (converter.canRead(paramType, methodParam)) {
            // ★ 反序列化请求体为 Java 对象
            return converter.read(paramType, methodParam, inputMessage);
        }
    }
}
```

## 四、返回值处理器（HandlerMethodReturnValueHandler）

### 4.1 接口定义

```java
public interface HandlerMethodReturnValueHandler {
    
    // 是否支持该返回类型
    boolean supportsReturnType(MethodParameter returnType);
    
    // 将返回值写入 response 或添加到 ModelAndView
    void handleReturnValue(Object returnValue,
                           MethodParameter returnType,
                           ModelAndViewContainer mavContainer,
                           NativeWebRequest webRequest);
}
```

### 4.2 返回值处理过程

`RequestMappingHandlerAdapter` 在方法执行完成后，遍历返回值处理器处理结果：

```java
// ServletInvocableHandlerMethod.java
public void invokeAndHandle(NativeWebRequest request, 
                            ModelAndViewContainer mavContainer,
                            Object... providedArgs) {
    // 1. 执行目标方法
    Object returnValue = invokeForRequest(request, mavContainer, providedArgs);
    
    // 2. 遍历返回值处理器
    for (HandlerMethodReturnValueHandler handler : this.returnValueHandlers) {
        if (handler.supportsReturnType(returnType)) {
            // ★ 处理返回值
            handler.handleReturnValue(returnValue, returnType, mavContainer, request);
            return;
        }
    }
}
```

### 4.3 常见返回值处理器

| 处理器 | 返回类型 | 行为 |
|--------|---------|------|
| `ModelAndViewMethodReturnValueHandler` | `ModelAndView` | 直接设置视图和模型 |
| `ModelMethodProcessor` | `Model` | 将 Model 内容合并到 mavContainer |
| `ViewMethodReturnValueHandler` | `View` | 设置视图对象 |
| `ResponseBodyHandlerMethodReturnValueHandler` | `@ResponseBody` 注解 | 使用 MessageConverter 写入响应体 |
| `ViewNameMethodReturnValueHandler` | `String` | 将字符串视为视图名 |
| `MapMethodProcessor` | `Map` | 将 Map 添加到 Model 中 |
| `HttpEntityMethodProcessor` | `HttpEntity`/`ResponseEntity` | 写入响应体 + 状态码 |
| `CallableMethodReturnValueHandler` | `Callable` | 异步处理 |

### 4.4 @ResponseBody 的处理

```java
// ResponseBodyHandlerMethodReturnValueHandler.java
// 实际上是 AbstractMessageConverterMethodProcessor 的子类

@Override
public boolean supportsReturnType(MethodParameter returnType) {
    // 检查方法或类上是否标了 @ResponseBody
    return returnType.hasMethodAnnotation(ResponseBody.class)
            || returnType.getContainingClass().isAnnotationPresent(ResponseBody.class);
}

@Override
public void handleReturnValue(Object returnValue, ...) {
    // 1. 写入响应
    writeWithMessageConverters(returnValue, returnType, webRequest);
    
    // 2. 标记请求已处理完成（不需要视图渲染）
    mavContainer.setRequestHandled(true);
}

// 序列化逻辑（简化的 JSON 序列化）
protected void writeWithMessageConverters(Object value, ...) {
    // 确定客户端可接受的响应类型（Accept 头）
    MediaType selectedMediaType = ...;
    
    // 遍历 MessageConverter
    for (HttpMessageConverter<?> converter : this.messageConverters) {
        GenericHttpMessageConverter genericConverter = ...;
        
        if (genericConverter.canWrite(targetType, valueType, selectedMediaType)) {
            // ★ 序列化并写入响应
            genericConverter.write(value, selectedMediaType, outputMessage);
            return;
        }
    }
}
```

## 五、WebDataBinder 与数据绑定

### 5.1 绑定过程

当方法参数是自定义对象时，`WebDataBinder` 负责将请求参数绑定到对象字段：

```java
// 目标方法
@PostMapping("/users")
public Result create(@RequestBody @Valid User user) { ... }

// WebDataBinder 的绑定过程（简化）
public class WebDataBinder extends DataBinder {
    
    public void bind(HttpServletRequest request) {
        // 1. 获取所有请求参数
        Map<String, String[]> params = request.getParameterMap();
        
        // 2. 遍历对象的属性
        PropertyValues pvs = new MutablePropertyValues();
        for (PropertyDescriptor pd : getTargetObject().getPropertyDescriptors()) {
            String paramName = pd.getName();
            if (params.containsKey(paramName)) {
                // 3. 类型转换并赋值
                pvs.add(paramName, params.get(paramName));
            }
        }
        
        // 4. 应用绑定
        super.bind(pvs);
    }
}
```

### 5.2 类型转换

Spring MVC 内置了丰富的类型转换器：

```java
// 自动支持的类型转换：
// 字符串 → int / long / double / boolean
// 字符串 → LocalDate / LocalDateTime（@DateTimeFormat）
// 字符串 → 枚举（名称匹配）
// 数组 ↔ 集合
// 等等

@GetMapping("/search")
public Result search(
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
    // Spring 自动将字符串解析为 LocalDate
}
```

## 六、总结

### HandlerMapping 匹配过程

```
请求 URL: GET /api/users/123
    │
    ▼
RequestMappingHandlerMapping.getHandlerInternal()
    │
    ├── 从 mappingRegistry 查找路径匹配
    │     ├── 直接路径匹配：/api/users/{id} ✓
    │     └── 通配符匹配：/api/** ✓（优先级低）
    │
    ├── 按条件排序（精确度高的排前面）
    │
    └── 返回 HandlerMethod{UserController.getById(Long)}
          └── 包装为 HandlerExecutionChain
                ├── HandlerMethod
                ├── Interceptor1
                └── Interceptor2
```

### HandlerAdapter 参数与返回值处理

```
方法签名：Result<User> getById(@PathVariable Long id)

参数解析过程：
  @PathVariable Long id
    → PathVariableMethodArgumentResolver
    → 从 URL 路径提取 id 值
    → 类型转换为 Long
    → 返回 123L

返回值处理过程：
  Result<User>
    → ResponseBodyHandlerMethodReturnValueHandler
    → MappingJackson2HttpMessageConverter
    → 序列化为 JSON → 写入响应体
```

### 核心类速查

| 类 | 作用 |
|---|------|
| `RequestMappingHandlerMapping` | 处理 @RequestMapping 注解，建立 URL→Handler 映射 |
| `RequestMappingHandlerAdapter` | 执行 Handler，参数解析 + 返回值处理 |
| `HandlerMethodArgumentResolver` | 从 Request 中解析方法参数（30+ 实现）|
| `HandlerMethodReturnValueHandler` | 处理方法返回值 |
| `HttpMessageConverter` | 请求体/响应体的序列化反序列化 |

---

**上一篇：** [Spring MVC（二）：DispatcherServlet 请求处理流程]({{< relref "post/spring-mvc-dispatcher-servlet" >}})

**下一篇：** [Spring MVC（四）：异常处理与视图解析]({{< relref "post/spring-mvc-exception-view" >}})
