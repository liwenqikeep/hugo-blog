---
title: "Spring MVC（四）：异常处理与视图解析"
date: 2018-05-31
draft: false
categories: ["Java"]
tags: ["Spring MVC", "@ControllerAdvice", "@ExceptionHandler", "ViewResolver", "异常处理", "视图"]
toc: true
---

## 前言

前三篇文章分别覆盖了 Spring MVC 的使用、请求处理流程和 HandlerMapping/HandlerAdapter 源码。本系列最后一篇聚焦于两个"收尾"环节：**异常处理**和**视图解析**。

异常处理决定当请求出错时如何响应；视图解析决定当 Controller 返回视图名时如何渲染。

<!--more-->

> **源码版本：** Spring Framework 5.x

## 一、异常处理机制

### 1.1 异常处理演进

```
Spring 3.0 @ExceptionHandler            ← 只对当前 Controller 生效
Spring 3.2 @ControllerAdvice             ← 全局异常处理
Spring 4.3 ResponseEntityExceptionHandler ← 更完整的 REST 异常处理基类
```

### 1.2 @ExceptionHandler 的两种级别

**Controller 级别：**

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @GetMapping("/{id}")
    public User getById(@PathVariable Long id) {
        return userService.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }
    
    // 只处理当前 Controller 中的异常
    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result handleUserNotFound(UserNotFoundException ex) {
        return Result.error(404, ex.getMessage());
    }
}
```

**全局级别（@ControllerAdvice）：**

```java
@ControllerAdvice  // 全局异常处理，对所有 @Controller 和 @RestController 生效
public class GlobalExceptionHandler {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    // 处理业务异常
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleBusinessException(BusinessException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return Result.error(ex.getCode(), ex.getMessage());
    }
    
    // 处理参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return Result.error(400, message);
    }
    
    // 404
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result handleNotFound() {
        return Result.error(404, "接口不存在");
    }
    
    // 兜底：未知异常
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result handleUnknown(Exception ex) {
        log.error("未知异常", ex);
        return Result.error(500, "服务器内部错误");
    }
}
```

**@ControllerAdvice 可以限定范围：**

```java
// 只处理特定包下的 Controller
@ControllerAdvice("com.example.controller")

// 只处理特定注解的类
@ControllerAdvice(annotations = RestController.class)

// 只处理特定类型的类
@ControllerAdvice(assignableTypes = BaseController.class)
```

### 1.3 HandlerExceptionResolver 体系

异常处理的核心接口：

```java
public interface HandlerExceptionResolver {
    
    // 处理 Handler 执行过程中的异常，返回 ModelAndView
    ModelAndView resolveException(HttpServletRequest request,
                                  HttpServletResponse response,
                                  Object handler,
                                  Exception ex);
}
```

**实现类层次：**

```
HandlerExceptionResolver
  └── AbstractHandlerExceptionResolver
        ├── DefaultErrorAttributes           — 收集错误属性
        │
        └── AbstractHandlerMethodExceptionResolver
              └── ExceptionHandlerExceptionResolver  ← ★ 核心
                    （处理 @ExceptionHandler 注解的方法）
```

### 1.4 DispatcherServlet 中的异常处理

先回顾第二篇中 `doDispatch()` 的异常处理路径：

```java
// DispatcherServlet.doDispatch() 末尾
catch (Exception ex) {
    dispatchException = ex;  // 捕获异常，不立即处理
}
// ...
processDispatchResult(processedRequest, response, mappedHandler, mv, dispatchException);

// processDispatchResult 中：
if (exception != null) {
    mv = processHandlerException(request, response, handler, exception);  // ★ 异常处理
}

// processHandlerException 实现：
protected ModelAndView processHandlerException(HttpServletRequest request,
                                               HttpServletResponse response,
                                               Object handler, Exception ex) {
    // 遍历所有 HandlerExceptionResolver
    for (HandlerExceptionResolver resolver : this.handlerExceptionResolvers) {
        ModelAndView mv = resolver.resolveException(request, response, handler, ex);
        if (mv != null) {
            return mv;
        }
    }
    throw ex;  // 没有处理器处理，重新抛出
}
```

### 1.5 ExceptionHandlerExceptionResolver 的工作过程

当标注了 `@ExceptionHandler` 的方法后，`ExceptionHandlerExceptionResolver` 负责匹配和执行它：

```java
// ExceptionHandlerExceptionResolver 查找 @ExceptionHandler 方法的过程：
// 1. 从 @ControllerAdvice 类中扫描所有 @ExceptionHandler 方法
// 2. 从当前 Controller 类中扫描所有 @ExceptionHandler 方法
// 3. 构建 异常类型 → 方法 的映射
// 4. 发生异常时，按异常类型的继承关系匹配

// 匹配规则：
//   抛出 NullPointerException
//   → 查找 @ExceptionHandler(NullPointerException.class)
//   → 未找到 → 查找 @ExceptionHandler(RuntimeException.class) 
//   → 未找到 → 查找 @ExceptionHandler(Exception.class)
//   → 找到就执行
```

**异常匹配的优先级：**

```java
@ControllerAdvice
public class ExceptionHandlerDemo {
    
    // 如果抛出的异常同时匹配多个处理器，
    // 选择异常类型继承树上最近的
    @ExceptionHandler(FileNotFoundException.class)  // 精确匹配
    @ExceptionHandler(IOException.class)            // 父类匹配
    @ExceptionHandler(Exception.class)              // 兜底
}
```

### 1.6 统一异常处理的最佳实践

```java
// 1. 自定义业务异常
public class BusinessException extends RuntimeException {
    private final int code;
    private final String message;
    
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }
}

// 2. 全局异常处理
@RestControllerAdvice  // = @ControllerAdvice + @ResponseBody
public class GlobalExceptionHandler {
    
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException ex) {
        return Result.error(ex.getCode(), ex.getMessage());
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return Result.error(400, msg);
    }
    
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException ex) {
        return Result.error(400, ex.getMessage());
    }
    
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        return Result.error(400, "缺少参数: " + ex.getParameterName());
    }
    
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException ex) {
        return Result.error(400, "请求体格式错误");
    }
    
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return Result.error(405, "不支持的请求方法");
    }
    
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception ex) {
        log.error("服务器内部错误", ex);
        return Result.error(500, "服务器繁忙，请稍后重试");
    }
}
```

## 二、视图解析机制

### 2.1 视图解析的总体流程

```
Controller 方法返回 String（视图名）或 ModelAndView
    │
    ▼
DispatcherServlet.render()
    │
    ├── resolveViewName()  — 遍历 ViewResolver 将视图名转为 View 对象
    │
    └── view.render()      — View 对象渲染（如 JSP 就是 forward 到 .jsp 文件）
```

### 2.2 ViewResolver 体系

```java
public interface ViewResolver {
    
    // 根据视图名和 locale 解析为 View 对象
    View resolveViewName(String viewName, Locale locale);
}
```

**常见的 ViewResolver：**

```
ViewResolver
  ├── InternalResourceViewResolver                — JSP 视图
  ├── ThymeleafViewResolver                       — Thymeleaf 模板
  ├── FreeMarkerViewResolver                      — FreeMarker 模板
  ├── BeanNameViewResolver                        — Bean name 作为 View
  ├── ContentNegotiatingViewResolver              — 根据内容协商选择
  └── JsonViewResolver / MappingJackson2JsonView   — JSON 视图
```

### 2.3 视图解析链

`DispatcherServlet` 中维护了一个 `viewResolvers` 列表，解析时依次尝试：

```java
// DispatcherServlet.java
protected View resolveViewName(String viewName, Map<String, Object> model,
                               Locale locale, HttpServletRequest request) {
    // 遍历所有已注册的 ViewResolver
    for (ViewResolver viewResolver : this.viewResolvers) {
        View view = viewResolver.resolveViewName(viewName, locale);
        if (view != null) {
            return view;  // 返回第一个解析成功的 View
        }
    }
    return null;
}
```

**常见配置：**

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Override
    public void configureViewResolvers(ViewResolverRegistry registry) {
        // JSP（优先级高）
        registry.jsp("/WEB-INF/views/", ".jsp");
        
        // 或 Thymeleaf（通过 ThymeleafAutoConfiguration 自动配置）
    }
}
```

### 2.4 InternalResourceViewResolver（JSP）

```java
// InternalResourceViewResolver.java
public class InternalResourceViewResolver extends UrlBasedViewResolver {
    
    public InternalResourceViewResolver() {
        // 默认视图类
        setViewClass(InternalResourceView.class);
    }
    
    // 配置示例：
    // prefix = "/WEB-INF/views/"
    // suffix = ".jsp"
    // 视图名 "user/list" → /WEB-INF/views/user/list.jsp
}
```

**View 的渲染：**

```java
// InternalResourceView.java
public class InternalResourceView extends AbstractUrlBasedView {
    
    @Override
    protected void renderMergedOutputModel(Map<String, Object> model,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        // 1. 将 Model 数据设置到 request 属性中
        exposeModelAsRequestAttributes(model, request);
        
        // 2. forward 到 JSP
        RequestDispatcher rd = request.getRequestDispatcher(getUrl());
        rd.forward(request, response);
    }
}
```

### 2.5 ContentNegotiatingViewResolver

`ContentNegotiatingViewResolver` 是一个特殊的 ViewResolver，它根据请求的 `Accept` 头决定返回什么格式的视图：

```java
// ContentNegotiatingViewResolver 不自己创建 View，
// 而是委派给其他 ViewResolver，选择最合适的视图

// 1. 获取客户端期望的媒体类型（Accept 头 / URL 后缀 / 请求参数）
// 2. 委派给所有注册的 ViewResolver 获取候选 View
// 3. 根据媒体类型选择最匹配的 View

// 例如：请求 Accept: application/json
// → 选择 MappingJackson2JsonView 返回 JSON
//
// 例如：请求 Accept: text/html
// → 选择 InternalResourceView 返回 JSP
```

## 三、REST 与视图渲染的方式对比

### 3.1 @ResponseBody / @RestController

当 Controller 标注了 `@ResponseBody`（或使用 `@RestController`），**不经过视图解析**：

```
Controller 方法返回 Result<User>
    │
    ▼
ResponseBodyHandlerMethodReturnValueHandler
    │
    ▼
MappingJackson2HttpMessageConverter.write()
    │
    ▼
序列化为 JSON 字符串 → 写入 response body
    │
    ▼
mavContainer.setRequestHandled(true)  // 标记不需要视图渲染
    │
    ▼
DispatcherServlet.render() 不执行（因为 ModelAndView 为 null）
```

### 3.2 返回视图名

当 Controller 返回 `String`（视图名），经过完整的视图解析：

```
Controller 方法返回 "user/list"
    │
    ▼
ViewNameMethodReturnValueHandler
    │
    ▼
mavContainer.setViewName("user/list")
    │
    ▼
DispatcherServlet.processDispatchResult()
    → render()
      → resolveViewName("user/list", locale)
        → InternalResourceViewResolver
          → prefix + "user/list" + suffix
          → "/WEB-INF/views/user/list.jsp"
      → view.render(model, request, response)
```

### 3.3 直接返回 View 对象

```java
@GetMapping("/pdf")
public View downloadPdf() {
    // 直接返回 View 对象，不经过 ViewResolver
    return new PdfReportView();
}
```

## 四、统一响应处理方案

### 4.1 方案一：ResponseBodyAdvice

```java
@ControllerAdvice
public class UnifiedResponseAdvice implements ResponseBodyAdvice<Object> {
    
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 排除已经包装的类型和文档类（如 Swagger）
        return !returnType.getParameterType().isAssignableFrom(Result.class);
    }
    
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result) {
            return body;
        }
        
        // String 类型特殊处理（StringHttpMessageConverter 不支持其他类型）
        if (body instanceof String) {
            return objectMapper.writeValueAsString(Result.ok(body));
        }
        
        return Result.ok(body);
    }
}
```

### 4.2 方案二：统一异常 + AOP

```java
@Aspect
@Component
public class ResultAspect {
    
    @Around("execution(* com.example.controller.*.*(..))")
    public Object wrapResult(ProceedingJoinPoint pjp) {
        try {
            Object result = pjp.proceed();
            return Result.ok(result);
        } catch (BusinessException ex) {
            return Result.error(ex.getCode(), ex.getMessage());
        } catch (Exception ex) {
            log.error("调用异常", ex);
            return Result.error(500, "服务异常");
        }
    }
}
```

## 五、Spring Boot 中的异常处理

### 5.1 默认错误处理

Spring Boot 提供了默认的 `/error` 端点：

```properties
# 自定义错误配置
server.error.path=/error              # 错误路径（默认 /error）
server.error.include-stacktrace=never  # 是否包含堆栈信息（生产环境设为 never）
server.error.include-message=always    # 是否包含错误消息
```

### 5.2 自定义错误页面

```java
// 简单方式：在 resources/templates/error/ 目录下放模板文件
// resources/templates/error/404.html — 404 错误页面
// resources/templates/error/5xx.html — 5xx 错误页面
// resources/templates/error/error.html — 通用错误页面
```

### 5.3 完全自定义错误

```java
// 实现 ErrorController
@Component
public class CustomErrorController implements ErrorController {
    
    @RequestMapping("/error")
    public Result<Void> handleError(HttpServletRequest request) {
        Integer status = (Integer) request.getAttribute(
                RequestDispatcher.ERROR_STATUS_CODE);
        String message = (String) request.getAttribute(
                RequestDispatcher.ERROR_MESSAGE);
        return Result.error(status != null ? status : 500, message);
    }
}
```

## 六、总结

### 异常处理速查

```
异常发生时
    │
    ▼
doDispatch() catch → processDispatchResult()
    │
    ▼
processHandlerException()
    │
    ▼
遍历 HandlerExceptionResolver
    │
    └── ExceptionHandlerExceptionResolver
          │
          ├── 从 @ControllerAdvice 找匹配的 @ExceptionHandler
          ├── 从当前 Controller 找匹配的 @ExceptionHandler
          └── 按异常继承关系匹配最接近的
                │
                ▼
        返回 ModelAndView（错误视图）或 null（未处理则抛出 500）
```

### 视图解析速查

```
Controller 返回视图名
    │
    ▼
DispatcherServlet.render()
    │
    ├── 遍历 ViewResolver
    │     └── InternalResourceViewResolver: "user/list" → /WEB-INF/views/user/list.jsp
    │
    └── View.render()
          └── InternalResourceView: Model 属性 → request → forward JSP
```

### 系列四篇总结

| 篇目 | 核心内容 |
|------|---------|
| （一）核心注解与使用 | @Controller、@RequestMapping、参数绑定、RESTful |
| （二）DispatcherServlet 流程 | doDispatch、HandlerExecutionChain、拦截器 |
| （三）HandlerMapping/Adapter | RequestMappingHandlerMapping 路由、参数解析器、返回值处理器 |
| （四）异常处理与视图解析 | @ExceptionHandler、ViewResolver、统一响应 |

---

**上一篇：** [Spring MVC（三）：HandlerMapping 与 HandlerAdapter]({{< relref "post/spring-mvc-handler-mapping-adapter" >}})

**系列索引：**
- [Spring MVC（一）：核心注解与使用]({{< relref "post/spring-mvc-usage" >}})
- [Spring MVC（二）：DispatcherServlet 请求处理流程]({{< relref "post/spring-mvc-dispatcher-servlet" >}})
- [Spring MVC（三）：HandlerMapping 与 HandlerAdapter]({{< relref "post/spring-mvc-handler-mapping-adapter" >}})
- [Spring MVC（四）：异常处理与视图解析]({{< relref "post/spring-mvc-exception-view" >}})
