---
title: "Spring 框架中的设计模式"
date: 2018-06-18
draft: false
categories: ["Java"]
tags: ["Spring", "设计模式", "IoC", "AOP", "工厂模式", "模板方法", "代理模式", "观察者模式"]
toc: true
---

## 前言

Spring 框架被誉为"设计模式的教科书"——它将 GoF 的 23 种设计模式运用到了极致。分析 Spring 中使用设计模式的方式，不仅能加深对 Spring 的理解，也能学到如何在真实的大型框架中灵活应用设计模式。

本文以前面各篇文章分析的源码为基础，系统性梳理 Spring 中使用的核心设计模式。

<!--more-->

## 一、工厂模式

### 1.1 简单工厂

**体现：** `BeanFactory.getBean()` 根据名称或类型返回对象实例。

```java
// 简单工厂：根据 String 名称创建/查找对象
BeanFactory factory = new ClassPathXmlApplicationContext("beans.xml");
UserService userService = (UserService) factory.getBean("userService");

// 核心实现
public class DefaultSingletonBeanRegistry {
    
    // 缓存已完成初始化的单例 Bean
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);
    
    public Object getSingleton(String beanName) {
        // 从 Map 中获取（工厂的产品）
        return this.singletonObjects.get(beanName);
    }
}
```

### 1.2 工厂方法

**体现：** `@Bean` 注解的方法即工厂方法。

```java
@Configuration
public class AppConfig {
    
    // 工厂方法：返回 UserService 实例
    @Bean
    public UserService userService() {
        return new UserService(userRepository());
    }
    
    // 另一个工厂方法
    @Bean
    public UserRepository userRepository() {
        return new UserRepository();
    }
}
```

Spring 通过 `ConfigurationClassBeanDefinition` 将 `@Bean` 方法注册为 BeanDefinition，实例化时通过反射调用工厂方法。

### 1.3 AbstractFactory（抽象工厂）

**体现：** `ProxyFactory` 根据条件创建 JDK 或 CGLIB 代理。

```java
// DefaultAopProxyFactory 是一个抽象工厂
public class DefaultAopProxyFactory implements AopProxyFactory {
    
    @Override
    public AopProxy createAopProxy(AdvisedSupport config) {
        if (config.isOptimize() || config.isProxyTargetClass() 
                || hasNoUserSuppliedProxyInterfaces(config)) {
            Class<?> targetClass = config.getTargetClass();
            if (targetClass.isInterface()) {
                return new JdkDynamicAopProxy(config);  // JDK 代理
            }
            return new ObjenesisCglibAopProxy(config);  // CGLib 代理
        } else {
            return new JdkDynamicAopProxy(config);
        }
    }
}
```

---

## 二、单例模式

Spring 中 Bean 的默认作用域就是 `singleton`——但这不是通过 GoF 单例模式（私有构造器 + 全局访问点）实现的，而是通过 **注册表（Registry）** 模式。

```java
// DefaultSingletonBeanRegistry 是单例注册表
public class DefaultSingletonBeanRegistry {
    
    // 一级缓存：完全初始化好的单例
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);
    
    // 三级缓存：提前暴露的工厂
    private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);
    
    // 注册单例
    protected void addSingleton(String beanName, Object singletonObject) {
        synchronized (this.singletonObjects) {
            this.singletonObjects.put(beanName, singletonObject);
            this.singletonFactories.remove(beanName);
            this.earlySingletonObjects.remove(beanName);
        }
    }
    
    // 获取单例
    public Object getSingleton(String beanName) {
        return this.singletonObjects.get(beanName);
    }
}
```

**GoF 单例 vs Spring 单例：**

| 维度 | GoF 单例 | Spring 单例 |
|------|---------|------------|
| 控制方式 | 类自身控制（私有构造器）| 容器控制（Map 缓存）|
| 实现 | `getInstance()` 静态方法 | `getBean()` + `singletonObjects` |
| 灵活性 | 低（编译期决定）| 高（可改为 prototype）|
| 范围 | JVM 级别 | 容器级别 |

---

## 三、代理模式

**体现：** Spring AOP、`@Transactional`、`@Async` 的核心实现方式。

```java
// JDK 动态代理
public class JdkDynamicAopProxy implements AopProxy, InvocationHandler {
    
    private final AdvisedSupport advised;
    
    @Override
    public Object getProxy(ClassLoader classLoader) {
        // 为目标类创建代理对象
        return Proxy.newProxyInstance(classLoader, 
                proxiedInterfaces, this);
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        // 方法调用时执行拦截器链
        List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
        // 执行通知链或目标方法
    }
}
```

**Spring 中代理模式的使用场景：**

| 场景 | 代理方式 | 拦截器 |
|------|---------|--------|
| AOP | JDK/CGLib | MethodInterceptor 链 |
| `@Transactional` | JDK/CGLib | TransactionInterceptor |
| `@Async` | JDK/CGLib | AsyncExecutionInterceptor |
| `@Cacheable` | JDK/CGLib | CacheInterceptor |
| `@Scheduled` | CGLib | 无拦截器，直接注册 Task |
| `@Configuration` | CGLib | 拦截 @Bean 方法调用 |

---

## 四、模板方法模式

**体现：** 定义算法骨架，子类实现具体步骤。

### 4.1 最经典的案例：refresh()

```java
// AbstractApplicationContext.refresh() — 模板方法模式最经典的体现
public abstract class AbstractApplicationContext {
    
    // ★ 模板方法：定义了容器启动的算法骨架
    public void refresh() {
        // 1. 准备
        prepareRefresh();
        // 2. 获取 BeanFactory
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();
        // 3. 预准备 BeanFactory
        prepareBeanFactory(beanFactory);
        
        try {
            // ★ 4. 子类扩展点（Web 容器在这里添加 Web 相关的 Scope）
            postProcessBeanFactory(beanFactory);
            
            // 5-12 固定步骤...
            invokeBeanFactoryPostProcessors(beanFactory);
            registerBeanPostProcessors(beanFactory);
            initMessageSource();
            initApplicationEventMulticaster();
            onRefresh();          // 子类扩展点
            registerListeners();
            finishBeanFactoryInitialization(beanFactory);
            finishRefresh();
        } catch (...) { ... }
    }
    
    // 子类可以覆盖的钩子方法
    protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
    protected void onRefresh() {}
}
```

### 4.2 其他模板方法案例

| 类 | 模板方法 | 子类实现的钩子 |
|---|---------|--------------|
| `AbstractBeanFactory` | `getBean()` → `doGetBean()` | `createBean()` |
| `AbstractAutowireCapableBeanFactory` | `createBean()` → `doCreateBean()` | `createBeanInstance()`, `populateBean()` |
| `AbstractPlatformTransactionManager` | `commit()`, `rollback()` | `doBegin()`, `doCommit()`, `doRollback()` |
| `AbstractHandlerMapping` | `getHandler()` | `getHandlerInternal()` |
| `OncePerRequestFilter` | `doFilter()` | `doFilterInternal()` |

---

## 五、策略模式

**体现：** 可替换的算法族。Spring 中几乎所有的 XXXResolver 都是策略模式。

```java
// HandlerMapping — 策略模式
public interface HandlerMapping {
    HandlerExecutionChain getHandler(HttpServletRequest request);
}

// 多个实现，按策略选择
// 策略1：RequestMappingHandlerMapping → 处理 @RequestMapping
// 策略2：SimpleUrlHandlerMapping → 处理静态资源
// 策略3：BeanNameUrlHandlerMapping → Bean name 映射

// 在 DispatcherServlet 中遍历使用
protected HandlerExecutionChain getHandler(HttpServletRequest request) {
    for (HandlerMapping mapping : this.handlerMappings) {  // 遍历策略
        HandlerExecutionChain handler = mapping.getHandler(request);
        if (handler != null) {
            return handler;  // 使用第一个匹配的策略
        }
    }
    return null;
}
```

**Spring 中策略模式的典型应用：**

| 接口 | 多个实现 | 用途 |
|------|---------|------|
| `HandlerMapping` | RequestMappingHandlerMapping / SimpleUrlHandlerMapping 等 | 请求路由 |
| `HandlerAdapter` | RequestMappingHandlerAdapter / HttpRequestHandlerAdapter 等 | 适配 Handler |
| `ViewResolver` | InternalResourceViewResolver / ThymeleafViewResolver 等 | 视图解析 |
| `PlatformTransactionManager` | DataSourceTransactionManager / JpaTransactionManager 等 | 事务管理 |
| `HandlerMethodArgumentResolver` | 30+ 个实现（@RequestParam / @PathVariable / @RequestBody 等）| 参数解析 |
| `HandlerMethodReturnValueHandler` | 十几个实现（@ResponseBody / ModelAndView / String 等）| 返回值处理 |

---

## 六、观察者模式

**体现：** Spring 事件机制。

```java
// 三个角色

// 1. 主题（Subject）
public interface ApplicationEventPublisher {
    void publishEvent(Object event);
}

// 2. 观察者（Observer）
public interface ApplicationListener<E extends ApplicationEvent> {
    void onApplicationEvent(E event);
}

// 3. 事件分发器（Concrete Subject）
public class SimpleApplicationEventMulticaster {
    
    public void multicastEvent(ApplicationEvent event, ResolvableType eventType) {
        // 获取所有匹配的观察者并通知
        for (ApplicationListener<?> listener : getApplicationListeners(event, type)) {
            invokeListener(listener, event);
        }
    }
}
```

**Spring 中对观察者模式的扩展：**

- **同步/异步**：通过 `SimpleApplicationEventMulticaster.setTaskExecutor()` 支持
- **事件过滤**：通过 `@EventListener(condition = ...)` 支持 SpEL 条件
- **事务绑定**：通过 `@TransactionalEventListener` 将事件绑定到事务阶段

---

## 七、责任链模式

### 7.1 FilterChain

Tomcat 中的 `ApplicationFilterChain` 是最经典的责任链实现——递归实现：

```java
// ApplicationFilterChain 使用递归推进链
public final class ApplicationFilterChain implements FilterChain {
    
    private ApplicationFilterConfig[] filters = new ApplicationFilterConfig[0];
    private int pos = 0;
    private Servlet servlet;
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response) {
        if (pos < filters.length) {
            // 执行当前 Filter，将自身（this）传入
            filters[pos++].getFilter().doFilter(request, response, this);
            // Filter 内部调用 chain.doFilter() 时递归回到这里
        } else {
            // 所有 Filter 执行完毕 → 执行目标 Servlet
            servlet.service(request, response);
        }
    }
}
```

### 7.2 Interceptor 链

Spring MVC 的 `HandlerExecutionChain` 同样是责任链：

```java
public class HandlerExecutionChain {
    
    private final List<HandlerInterceptor> interceptorList = new ArrayList<>();
    private int interceptorIndex = -1;
    
    // preHandle：顺序执行
    boolean applyPreHandle(HttpServletRequest request, HttpServletResponse response) {
        for (int i = 0; i < this.interceptorList.size(); i++) {
            if (!this.interceptorList.get(i).preHandle(request, response, this.handler)) {
                triggerAfterCompletion(request, response, null);
                return false;
            }
            this.interceptorIndex = i;
        }
        return true;
    }
}
```

### 7.3 AOP 通知链

`ReflectiveMethodInvocation.proceed()` 也是责任链：

```java
public class ReflectiveMethodInvocation {
    
    private int currentInterceptorIndex = -1;
    private List<Object> interceptors;
    
    @Override
    public Object proceed() throws Throwable {
        // 全部执行完毕 → 调用目标方法
        if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
            return invokeJoinpoint();
        }
        // 取下个拦截器执行
        Object interceptor = this.interceptors.get(++this.currentInterceptorIndex);
        return ((MethodInterceptor) interceptor).invoke(this);
    }
}
```

---

## 八、适配器模式

**体现：** `HandlerAdapter` 适配不同的处理器类型。

```java
public interface HandlerAdapter {
    boolean supports(Object handler);
    ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler);
}

// 多个适配器，支持不同类型的 Handler
@RequestMappingHandlerAdapter  → 适配 HandlerMethod（@RequestMapping 方法）
HttpRequestHandlerAdapter      → 适配 HttpRequestHandler
SimpleControllerHandlerAdapter → 适配 Controller 接口
SimpleServletHandlerAdapter    → 适配 Servlet
```

```java
// DispatcherServlet 中遍历适配器
HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());
// → 内部遍历所有 adapter，找到 supports() 返回 true 的那个
```

**另一个适配器模式的经典案例**：`ApplicationListenerMethodAdapter` 将 `@EventListener` 注解方法适配为 `ApplicationListener` 接口。

---

## 九、装饰器模式

**体现：** `BeanPostProcessor` 对 Bean 实例进行功能增强（装饰）。

最典型的是 AOP 的 `AbstractAutoProxyCreator`：

```java
// AbstractAutoProxyCreator 是一个 BeanPostProcessor
// 它在 Bean 初始化完成后，用代理对象"装饰"原始 Bean

@Override
public Object postProcessAfterInitialization(Object bean, String beanName) {
    // ★ 判断是否需要创建代理
    Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
    if (specificInterceptors != null) {
        // 用代理对象替换原始对象（装饰）
        return createProxy(bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
    }
    return bean;
}
```

其他装饰器案例：
- `HttpServletRequestWrapper` — 装饰原始 request（添加功能）
- `HttpServletResponseWrapper` — 装饰原始 response
- `OncePerRequestFilter` — 在 Filter 层面装饰请求处理

---

## 十、其他设计模式

| 设计模式 | Spring 体现 |
|---------|-----------|
| **创建型** |  |
| 原型模式 | `@Scope("prototype")` 每次 getBean 返回新实例 |
| 建造者模式 | `BeanDefinitionBuilder`、`UriComponentsBuilder`、`MockMvcBuilders` |
| **结构型** |  |
| 桥接模式 | `View` + `ViewResolver`（抽象与实现分离）|
| 组合模式 | `CompositePropertySource`、`CompositeFilter` |
| **行为型** |  |
| 命令模式 | `PlatformTransactionManager` 的事务操作（commit/rollback）|
| 迭代器模式 | `CompositeIterator`（组合迭代器）|
| 状态模式 | 事务的 TransactionStatus 状态管理 |
| 解释器模式 | Spring SpEL（表达式解析）|
| 空对象模式 | `NullBean`（Spring 内部使用的空对象）|

---

## 十一、总结

### 设计模式速查表

| 设计模式 | Spring 中最经典的体现 |
|---------|---------------------|
| **工厂模式** | `BeanFactory`、`@Bean` 工厂方法、`ProxyFactory` |
| **单例模式** | `DefaultSingletonBeanRegistry`（注册表式单例）|
| **代理模式** | AOP（`JdkDynamicAopProxy`、`CglibAopProxy`）|
| **模板方法** | `AbstractApplicationContext.refresh()` |
| **策略模式** | `HandlerMapping`、`ViewResolver`、`PlatformTransactionManager` |
| **观察者模式** | `ApplicationEventPublisher` + `ApplicationListener` |
| **责任链模式** | `ApplicationFilterChain`、`HandlerExecutionChain`、`ReflectiveMethodInvocation` |
| **适配器模式** | `HandlerAdapter`、`ApplicationListenerMethodAdapter` |
| **装饰器模式** | `BeanPostProcessor`（AOP 代理）|
| **建造者模式** | `BeanDefinitionBuilder`、`UriComponentsBuilder` |

### 一句总结

> **Spring 框架是对设计模式的大规模工业化运用——理解这些模式，不仅能更好地使用 Spring，也能在阅读其他框架源码时举一反三。**

---

**相关阅读：**

- [Spring DI（一）：IoC 容器初始化与 BeanFactory 体系]({{< relref "post/spring-di-ioc-container" >}})
- [Spring AOP（三）：JDK 代理 vs CGLib 与高级主题]({{< relref "post/spring-aop-proxy" >}})
- [Spring MVC（二）：DispatcherServlet 请求处理流程]({{< relref "post/spring-mvc-dispatcher-servlet" >}})
- [Spring 事件（二）：事件发布与监听源码深度解析]({{< relref "post/spring-event-source-code" >}})
- [Filter vs Interceptor（二）：Filter 源码深度解析]({{< relref "post/spring-filter-source-code" >}})
