---
title: "Spring MVC（一）：核心注解与使用"
date: 2018-05-25
draft: false
categories: ["Java"]
tags: ["Spring MVC", "@Controller", "@RequestMapping", "REST", "@RestController"]
toc: true
---

## 前言

Spring MVC 是 Spring 框架的 Web 层模块，也是最常用的 Web 框架之一。经过多个版本的演进，Spring MVC 从 XML 配置为主发展到今天以注解驱动的简洁编程模型。

本文聚焦于 Spring MVC 核心注解的使用方式、参数绑定机制和 RESTful API 的规范设计，为后续的源码分析做准备。

<!--more-->

## 一、核心注解概览

### 1.1 控制器相关注解

| 注解 | 用途 | 说明 |
|------|------|------|
| `@Controller` | 声明控制器 | 配合视图解析器返回页面 |
| `@RestController` | 声明 REST 控制器 | = `@Controller` + `@ResponseBody` |
| `@RequestMapping` | 请求映射 | 类/方法级别，支持 method、params、headers |
| `@GetMapping` | GET 请求 | `@RequestMapping(method=GET)` 的简写 |
| `@PostMapping` | POST 请求 | 简写 |
| `@PutMapping` | PUT 请求 | 简写 |
| `@DeleteMapping` | DELETE 请求 | 简写 |
| `@PatchMapping` | PATCH 请求 | 简写 |

### 1.2 参数绑定注解

| 注解 | 绑定来源 | 示例 |
|------|---------|------|
| `@RequestParam` | 查询参数 / 表单参数 | `?name=xxx` |
| `@PathVariable` | URL 路径参数 | `/users/{id}` |
| `@RequestBody` | 请求体 | JSON/XML |
| `@RequestHeader` | 请求头 | `Authorization` |
| `@CookieValue` | Cookie | `JSESSIONID` |
| `@ModelAttribute` | 模型属性绑定 | 表单对象 |
| `@SessionAttribute` | Session 属性 | 会话中的属性 |
| `@RequestAttribute` | Request 属性 | request.setAttribute |

## 二、@Controller 与 @RestController

### 2.1 基本控制器

```java
@Controller  // 声明控制器，返回视图名称
@RequestMapping("/users")
public class UserController {
    
    // 返回视图名（配合 ViewResolver 使用）
    @GetMapping("/list")
    public String list(Model model) {
        model.addAttribute("users", userService.findAll());
        return "user/list";  // 视图名 → /WEB-INF/views/user/list.jsp
    }
}
```

### 2.2 REST 控制器

```java
@RestController  // = @Controller + @ResponseBody
@RequestMapping("/api/users")
public class UserRestController {
    
    private final UserService userService;
    
    public UserRestController(UserService userService) {
        this.userService = userService;
    }
    
    @GetMapping
    public List<User> list() {
        return userService.findAll();
    }
    
    @GetMapping("/{id}")
    public User getById(@PathVariable Long id) {
        return userService.findById(id);
    }
    
    @PostMapping
    public User create(@RequestBody @Valid User user) {
        return userService.save(user);
    }
}
```

**@RestController 与 @Controller 的区别：**

```java
// @Controller — 方法返回值是视图名，需要 @ResponseBody 才返回 JSON
@Controller
@RequestMapping("/api")
public class OldController {
    
    @GetMapping("/data")
    @ResponseBody  // 需要加这个才返回 JSON，而不是视图名
    public Map<String, Object> getData() {
        return Map.of("key", "value");
    }
}

// @RestController — 默认所有方法返回值都是响应体
@RestController
@RequestMapping("/api")
public class NewController {
    
    @GetMapping("/data")
    public Map<String, Object> getData() {
        return Map.of("key", "value");  // 自动 JSON 序列化
    }
}
```

## 三、@RequestMapping 详解

### 3.1 请求方法映射

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @RequestMapping(method = RequestMethod.GET)
    public List<User> list() { ... }
    
    // 简写形式：
    @GetMapping("/{id}")       // GET
    @PostMapping              // POST
    @PutMapping("/{id}")      // PUT
    @DeleteMapping("/{id}")   // DELETE
    @PatchMapping("/{id}")    // PATCH
}
```

### 3.2 URL 路径映射

```java
// 精确路径
@GetMapping("/users")

// 路径变量
@GetMapping("/users/{id}")
@GetMapping("/users/{userId}/orders/{orderId}")

// 通配符
@GetMapping("/users/*")       // 匹配 /users/xxx，不匹配 /users/xxx/yyy
@GetMapping("/users/**")      // 匹配 /users/xxx 以及子路径

// 正则匹配
@GetMapping("/users/{id:\\d+}")  // id 只能是数字

// 多路径映射同一个方法
@GetMapping({"/users", "/members"})
```

### 3.3 params 和 headers 条件

```java
// 限制请求参数
@GetMapping(params = "action=search")         // 必须包含 ?action=search
@GetMapping(params = "!action")               // 不能包含 action 参数
@GetMapping(params = {"type=user", "status"}) // 多个条件

// 限制请求头
@GetMapping(headers = "X-API-Version=1")      // 请求头必须有 X-API-Version:1
@GetMapping(consumes = "application/json")    // 只接受 JSON 请求
@GetMapping(produces = "application/json")    // 只返回 JSON 响应
```

### 3.4 produces 与 consumes

```java
@RestController
@RequestMapping("/api")
public class ContentNegotiationController {
    
    // 只处理 JSON 格式的请求，只返回 JSON
    @GetMapping(value = "/user", produces = "application/json")
    public User getUser() { ... }
    
    // 只处理 XML 格式的请求
    @PostMapping(value = "/user", consumes = "application/xml")
    public User createUser(@RequestBody User user) { ... }
    
    // 根据客户端 Accept 头自动选择响应格式
    @GetMapping(value = "/data", produces = { "application/json", "application/xml" })
    public Data getData() { ... }
}
```

## 四、参数绑定详解

### 4.1 @RequestParam

```java
@RestController
@RequestMapping("/api")
public class ParamController {
    
    // GET /api/users?page=1&size=20
    @GetMapping("/users")
    public Page<User> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        // page=1, size=20, keyword=null（如果没传）
        return userService.findPage(page, size, keyword);
    }
    
    // 绑定到 Map
    @GetMapping("/search")
    public Result search(@RequestParam Map<String, String> params) {
        // params = {q="spring", category="framework"}
        return service.search(params);
    }
}
```

### 4.2 @PathVariable

```java
@RestController
@RequestMapping("/api/users")
public class PathVariableController {
    
    // GET /api/users/123
    @GetMapping("/{id}")
    public User getById(@PathVariable Long id) {
        return userService.findById(id);
    }
    
    // GET /api/users/123/orders/456
    @GetMapping("/{userId}/orders/{orderId}")
    public Order getOrder(
            @PathVariable Long userId,
            @PathVariable Long orderId) {
        return orderService.findById(userId, orderId);
    }
    
    // 指定名称（当参数名与路径变量名不一致时）
    @GetMapping("/{userId}")
    public User getByUserId(@PathVariable("userId") Long id) {
        return userService.findById(id);
    }
}
```

### 4.3 @RequestBody

```java
@RestController
@RequestMapping("/api/users")
public class BodyController {
    
    // POST /api/users  body: {"name":"Tom","age":20}
    @PostMapping
    public User create(@RequestBody @Valid User user) {
        // Spring 自动将 JSON 反序列化为 User 对象
        return userService.save(user);
    }
    
    // 接收原始 JSON 字符串
    @PostMapping("/raw")
    public String createRaw(@RequestBody String jsonBody) {
        // jsonBody 是原始 JSON 字符串
        return jsonBody;
    }
}
```

### 4.4 @RequestHeader 与 @CookieValue

```java
@RestController
@RequestMapping("/api")
public class HeaderController {
    
    @GetMapping("/info")
    public Result getInfo(
            @RequestHeader("Authorization") String token,
            @RequestHeader("User-Agent") String userAgent,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return Result.ok()
                .put("token", token)
                .put("userAgent", userAgent);
    }
    
    @GetMapping("/session")
    public String getSession(
            @CookieValue(value = "JSESSIONID", defaultValue = "") String sessionId) {
        return "Session: " + sessionId;
    }
}
```

### 4.5 @ModelAttribute 绑定表单对象

```java
public class UserQuery {
    private String keyword;
    private int page = 1;
    private int size = 20;
    // getter / setter
}

@RestController
@RequestMapping("/api/users")
public class ModelAttributeController {
    
    // GET /api/users/search?keyword=spring&page=1&size=10
    @GetMapping("/search")
    public Page<User> search(@ModelAttribute UserQuery query) {
        // Spring 自动将请求参数绑定到 UserQuery 对象
        return userService.search(query);
    }
}
```

## 五、参数校验 @Valid

### 5.1 基本使用

```java
// DTO 加上校验注解
public class CreateUserRequest {
    
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 20, message = "用户名长度 2-20")
    private String username;
    
    @NotNull
    @Email(message = "邮箱格式不正确")
    private String email;
    
    @Min(1) @Max(150)
    private int age;
    
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误")
    private String phone;
    
    // getter / setter
}
```

```java
@RestController
@RequestMapping("/api/users")
public class ValidationController {
    
    @PostMapping
    public Result create(@RequestBody @Valid CreateUserRequest request) {
        // 校验通过后执行
        userService.create(request);
        return Result.ok();
    }
}
```

### 5.2 分组校验

```java
public interface CreateGroup {}
public interface UpdateGroup {}

public class UserRequest {
    
    @Null(groups = CreateGroup.class)
    @NotNull(groups = UpdateGroup.class)
    private Long id;
    
    @NotBlank(groups = CreateGroup.class)
    private String username;
    
    // 默认归入 Default 组
    @Email
    private String email;
}

@RestController
public class GroupValidationController {
    
    @PostMapping
    public Result create(@RequestBody @Validated(CreateGroup.class) UserRequest req) {
        // 只校验 CreateGroup 组的规则
    }
    
    @PutMapping("/{id}")
    public Result update(@RequestBody @Validated(UpdateGroup.class) UserRequest req) {
        // 只校验 UpdateGroup 组的规则
    }
}
```

## 六、统一返回处理

### 6.1 统一响应体

```java
// 通用响应包装
public class Result<T> {
    private int code;
    private String message;
    private T data;
    
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }
    
    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }
    
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
```

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @GetMapping
    public Result<List<User>> list() {
        return Result.ok(userService.findAll());
    }
    
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        User user = userService.findById(id);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.ok(user);
    }
}
```

### 6.2 ResponseBodyAdvice 统一包装

```java
@ControllerAdvice
public class ResponseWrapper implements ResponseBodyAdvice<Object> {
    
    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        // 对所有 Controller 方法生效
        return true;
    }
    
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        // 如果已经是 Result 类型，不重复包装
        if (body instanceof Result) {
            return body;
        }
        // 统一包装
        return Result.ok(body);
    }
}
```

## 七、常见设计与最佳实践

### 7.1 Controller 规范

```java
// ✅ 推荐的 Controller 结构
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    
    private final UserService userService;
    
    public UserController(UserService userService) {
        this.userService = userService;
    }
    
    @GetMapping
    public Result<List<User>> list(UserQuery query) {
        return Result.ok(userService.findAll(query));
    }
    
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        return Result.ok(userService.findById(id));
    }
    
    @PostMapping
    public Result<User> create(@RequestBody @Valid CreateUserRequest request) {
        return Result.ok(userService.create(request));
    }
    
    @PutMapping("/{id}")
    public Result<User> update(@PathVariable Long id,
                               @RequestBody @Valid UpdateUserRequest request) {
        return Result.ok(userService.update(id, request));
    }
    
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.ok();
    }
}
```

### 7.2 命名规范

| 方法 | URL | HTTP 方法 | 说明 |
|------|-----|-----------|------|
| list | GET `/api/users` | GET | 列表查询 |
| getById | GET `/api/users/{id}` | GET | 单个查询 |
| create | POST `/api/users` | POST | 创建 |
| update | PUT `/api/users/{id}` | PUT | 全量更新 |
| patch | PATCH `/api/users/{id}` | PATCH | 部分更新 |
| delete | DELETE `/api/users/{id}` | DELETE | 删除 |

### 7.3 Controller 层只做编排

```java
// ✅ 好的实践：Controller 只做请求接收和响应返回
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    
    private final OrderService orderService;
    private final PaymentService paymentService;
    
    @PostMapping
    public Result<OrderVO> create(@RequestBody @Valid CreateOrderRequest request) {
        // 请求验证由 @Valid 处理
        // 调用 Service 层处理业务
        OrderVO result = orderService.create(request);
        // 统一返回
        return Result.ok(result);
    }
}

// ❌ 坏实践：Controller 中写业务逻辑
@RestController
public class BadController {
    
    @PostMapping("/bad")
    public Result bad(@RequestBody Map<String, Object> req) {
        // 校验
        // 业务逻辑
        // 数据库操作
        // 什么都不该在 Controller 做
    }
}
```

## 八、总结

### 核心注解速查

| 注解 | 位置 | 作用 |
|------|------|------|
| `@Controller` | 类 | 声明控制器 |
| `@RestController` | 类 | 声明 REST 控制器 |
| `@RequestMapping` | 类/方法 | 请求映射 |
| `@GetMapping` | 方法 | GET 映射简写 |
| `@PostMapping` | 方法 | POST 映射简写 |
| `@RequestParam` | 参数 | 绑定查询参数 |
| `@PathVariable` | 参数 | 绑定路径变量 |
| `@RequestBody` | 参数 | 绑定请求体 |
| `@Valid` / `@Validated` | 参数 | 参数校验 |
| `@ResponseStatus` | 方法 | 指定响应状态码 |

### 最佳实践清单

1. **用 `@RestController`** 替代 `@Controller` + `@ResponseBody`
2. **用简写 Mapping**（`@GetMapping` 等）替代 `@RequestMapping(method=...)`
3. **Controller 不做业务逻辑**，只做请求接收和响应返回
4. **统一响应体**，用 `Result<T>` 包装
5. **参数校验**放在 DTO 上，用 `@Valid` 触发
6. **RESTful URL 规范**：资源名词复数、层级用 `/` 分隔

---

**下一篇：** [Spring MVC（二）：DispatcherServlet 请求处理流程]({{< relref "post/spring-mvc-dispatcher-servlet" >}})
