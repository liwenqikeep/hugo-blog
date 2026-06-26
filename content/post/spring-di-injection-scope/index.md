---
title: "Spring DI（三）：依赖注入、循环依赖与作用域"
date: 2018-04-30
draft: false
categories: ["Java"]
tags: ["Spring", "IoC", "DI", "循环依赖", "三级缓存", "@Scope", "AOP", "源码分析"]
toc: true
---

## 前言

前面两篇文章分别覆盖了 IoC 容器初始化和 Bean 生命周期。本篇聚焦于 DI 中最核心的三个问题：**@Autowired 的完整解析链路**、**循环依赖的三级缓存机制**、以及 **作用域与 AOP 代理的协作原理**。

<!--more-->

## 一、@Autowired 深度解析

### 1.1 AutowiredAnnotationBeanPostProcessor 体系

`@Autowired` 的核心处理器是 `AutowiredAnnotationBeanPostProcessor`，它实现了多个后处理器接口：

```
AutowiredAnnotationBeanPostProcessor
  ├── MergedBeanDefinitionPostProcessor       → postProcessMergedBeanDefinition
  │      在 Bean 实例化后立即执行：收集 @Autowired 注入点
  │
  └── InstantiationAwareBeanPostProcessor     → postProcessProperties
         在属性填充阶段执行：执行真正的依赖查找和注入
```

### 1.2 注入点收集

在第一篇提到的 `applyMergedBeanDefinitionPostProcessors()` 阶段，`AutowiredAnnotationBeanPostProcessor` 会扫描目标类的所有字段和方法，找出被 `@Autowired`、`@Value`、`@Inject` 标注的注入点。

```java
// AutowiredAnnotationBeanPostProcessor.java
private InjectionMetadata buildAutowiringMetadata(Class<?> clazz) {
    List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
    Class<?> targetClass = clazz;

    do {
        List<InjectionMetadata.InjectedElement> currElements = new ArrayList<>();
        
        // 1. 扫描所有字段
        for (Field field : targetClass.getDeclaredFields()) {
            // 检查 @Autowired、@Value、@Inject
            AutowiredAnnotation ann = findAutowiredAnnotation(field);
            if (ann != null) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;  // 忽略静态字段
                }
                boolean required = ann.required();
                currElements.add(new AutowiredFieldElement(field, required));
            }
        }
        
        // 2. 扫描所有方法
        for (Method method : targetClass.getDeclaredMethods()) {
            Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
            AutowiredAnnotation ann = findAutowiredAnnotation(bridgedMethod);
            if (ann != null) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                boolean required = ann.required();
                PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
                currElements.add(new AutowiredMethodElement(method, required, pd));
            }
        }
        
        elements.addAll(0, currElements);
        targetClass = targetClass.getSuperclass();
    } while (targetClass != null && targetClass != Object.class);
    
    return new InjectionMetadata(clazz, elements);
}
```

**收集到的元数据结构：**

```java
// 一个典型的 Service 类
public class UserService {
    @Autowired                ← AutowiredFieldElement{field=userRepository, required=true}
    private UserRepository userRepository;
    
    @Autowired                ← AutowiredMethodElement{method=setConfig, required=true}
    public void setConfig(AppConfig config) { ... }
    
    @Value("${app.name}")     ← AutowiredFieldElement{field=appName, required=true}
    private String appName;
}
```

### 1.3 字段注入的执行

字段注入在 `populateBean()` 阶段的 `postProcessProperties()` 中执行：

```java
// AutowiredFieldElement.inject()
@Override
protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) {
    Field field = (Field) this.member;
    Object value;
    
    if (this.cached) {
        // ★ 缓存命中：单例 Bean 第二次及之后不再查找，直接使用缓存
        value = resolvedCachedArgument(beanName, this.cachedFieldValue);
    } else {
        // 第一次：需要查找依赖
        DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
        desc.setContainingClass(bean.getClass());
        Set<String> autowiredBeanNames = new LinkedHashSet<>();
        
        // 调用 BeanFactory 的依赖解析
        value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
        
        // 如果是单例 Bean，缓存解析结果
        synchronized (this) {
            if (!this.cached) {
                if (value != null || this.required) {
                    this.cachedFieldValue = desc;
                    // 缓存注册的依赖 Bean 名称（用于后续销毁时反向引用）
                    registerDependentBeans(beanName, autowiredBeanNames);
                    if (autowiredBeanNames.size() == 1) {
                        // 如果只有一个匹配，直接缓存 Bean 名称
                        String autowiredBeanName = autowiredBeanNames.iterator().next();
                        if (beanFactory.containsBean(autowiredBeanName)) {
                            if (beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
                                this.cachedFieldValue = new ShortcutDependencyDescriptor(
                                        desc, autowiredBeanName, field.getType());
                            }
                        }
                    }
                } else {
                    this.cachedFieldValue = null;
                }
                this.cached = true;
            }
        }
    }
    
    if (value != null) {
        // 反射设置字段
        ReflectionUtils.makeAccessible(field);
        field.set(bean, value);
    }
}
```

**关键点：**
- **第一次注入**需要经过 `resolveDependency()` 的完整查找流程
- **单例 Bean 第二次注入**直接从缓存获取，无需再次查找
- `DependencyDescriptor` 封装了字段的类型、泛型、注解信息

### 1.4 resolveDependency 的完整查找链路

```java
// DefaultListableBeanFactory.resolveDependency()
public Object resolveDependency(DependencyDescriptor descriptor, 
                                String requestingBeanName,
                                Set<String> autowiredBeanNames, 
                                TypeConverter typeConverter) {
    // 1. 处理特殊类型
    if (Optional.class == descriptor.getDependencyType()) { ... }
    if (ObjectFactory.class == descriptor.getDependencyType()) { ... }
    if (javax.inject.Provider.class == descriptor.getDependencyType()) { ... }
    
    // 2. 按类型查找
    Map<String, Object> matchingBeans = findAutowireCandidates(
            requestingBeanName, descriptor.getDependencyType(), descriptor);
    
    // 3. 匹配结果处理
    if (matchingBeans.isEmpty()) {
        if (descriptor.isRequired()) {
            throw new NoSuchBeanDefinitionException(...);
        }
        return null;
    }
    
    if (matchingBeans.size() == 1) {
        // 唯一匹配，直接返回
        return matchingBeans.values().iterator().next();
    }
    
    // 4. 多个候选：按 @Primary → @Priority → 字段名 筛选
    String primaryBeanName = determinePrimaryCandidate(matchingBeans, descriptor);
    if (primaryBeanName == null) {
        primaryBeanName = determineHighestPriorityCandidate(matchingBeans, descriptor);
    }
    if (primaryBeanName != null) {
        return matchingBeans.get(primaryBeanName);
    }
    
    // 5. 按字段名/参数名匹配
    return matchingBeans.get(descriptor.getDependencyName());
}
```

**`findAutowireCandidates()` 的类型查找逻辑：**

```java
// DefaultListableBeanFactory.java
protected Map<String, Object> findAutowireCandidates(
        String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {
    
    // 1. 获取所有匹配类型的 Bean 名称
    String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
            this, requiredType, true, descriptor.isEager());
    
    Map<String, Object> result = new LinkedHashMap<>(candidateNames.length);
    
    for (String candidateName : candidateNames) {
        // 2. 排除自身引用（解决自注入问题）
        if (!candidateName.equals(beanName) || autowireSelf(descriptor)) {
            // 3. 检查 @Qualifier
            if (!descriptor.hasQualifier() || matchesQualifier(candidateName, descriptor.getQualifiers())) {
                Object bean = getBean(candidateName);
                result.put(candidateName, bean);
            }
        }
    }
    return result;
}
```

## 二、循环依赖的三级缓存机制

### 2.1 问题定义

```java
@Component
public class A {
    @Autowired
    private B b;
}

@Component
public class B {
    @Autowired
    private A a;
}
```

容器创建 A 时需要注入 B，但 B 又依赖 A——这是一个经典的循环依赖问题。

### 2.2 三级缓存结构

Spring 通过**三级缓存**解决 Setter 注入（非构造器注入）的循环依赖：

```java
// DefaultSingletonBeanRegistry.java

// 第一级：singletonObjects — 完全创建好的单例 Bean（成品）
private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

// 第二级：earlySingletonObjects — 提前暴露的半成品（已实例化，未属性填充）
private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

// 第三级：singletonFactories — 提前暴露的工厂（用于生成代理对象）
private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

// 当前正在创建中的 Bean 集合
private final Set<String> singletonsCurrentlyInCreation = new HashSet<>(16);
```

| 级别 | 名称 | 内容 | 用途 |
|------|------|------|------|
| 1 | `singletonObjects` | 成品对象 | 正常运行时获取 Bean |
| 2 | `earlySingletonObjects` | 半成品对象 | 循环依赖中提前暴露 |
| 3 | `singletonFactories` | 工厂（函数） | 生成代理对象 |

### 2.3 三级缓存工作流程

以 A → B → A 的循环为例，执行步骤如下：

```
【1】getBean(A) → 开始创建 A
    singletonsCurrentlyInCreation = {A}
    A 实例化完成（new A()，但属性未填充）
    ↓
【2】A 将自己加入三级缓存
    singletonFactories.put(A, earlySingletonFactory)
    earlySingletonFactories 保存了一个函数：
        () → getEarlyBeanReference(A, mbd, rawBean)
    ↓
【3】populateBean(A) → 发现需要注入 B → getBean(B)
    ↓
【4】getBean(B) → 开始创建 B
    singletonsCurrentlyInCreation = {A, B}
    B 实例化完成
    ↓
【5】B 将自己加入三级缓存
    singletonFactories.put(B, factory)
    ↓
【6】populateBean(B) → 发现需要注入 A → getBean(A)
    ↓
【7】getSingleton(A) — 查询缓存：
    a. 一级缓存 singletonObjects  → 无（A 还没创建完）
    b. 二级缓存 earlySingletonObjects → 无（还没生成）
    c. ★ 三级缓存 singletonFactories → 有！执行工厂函数
       ↓
       factory = singletonFactories.remove(A)  // 从三级缓存移除
       getEarlyBeanReference(A) → 检查是否需要 AOP 代理
       ↓
    d. 返回结果放入二级缓存
       earlySingletonObjects.put(A, bean)
    e. 返回 A 的引用（可能是原始对象或 AOP 代理对象）
    ↓
【8】B 的 A 属性注入完成
    B 继续完成属性填充和初始化
    ↓
【9】B 创建完成 → 存入一级缓存 singletonObjects
    ↓
【10】返回 B 的引用给 A
    A 的 B 属性注入完成
    A 继续完成剩余阶段
    ↓
【11】A 创建完成 → 从二级缓存移除，存入一级缓存
    singletonObjects.put(A, a)
```

### 2.4 缓存查询源码

```java
// DefaultSingletonBeanRegistry.java
@Nullable
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
    // 1. 查一级缓存（成品）
    Object singletonObject = this.singletonObjects.get(beanName);
    
    // 2. 如果一级没有，且该 Bean 正在创建中
    if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
        synchronized (this.singletonObjects) {
            // 3. 查二级缓存（半成品）
            singletonObject = this.earlySingletonObjects.get(beanName);
            
            // 4. 二级也没有，且允许提前引用
            if (singletonObject == null && allowEarlyReference) {
                // 5. 查三级缓存（工厂）
                ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
                if (singletonFactory != null) {
                    // 6. 执行工厂方法，获取 Bean 引用
                    singletonObject = singletonFactory.getObject();
                    // 7. 升到二级缓存（从三级移除）
                    this.earlySingletonObjects.put(beanName, singletonObject);
                    this.singletonFactories.remove(beanName);
                }
            }
        }
    }
    return singletonObject;
}
```

### 2.5 三级缓存为什么需要第三级？

**为什么不能只用两级缓存？**——为了支持 AOP 代理。

如果一个 Bean 需要 AOP 代理，代理对象必须在**循环依赖暴露时**就生成，而不是等到 Bean 完全初始化之后。

```java
// AbstractAutowireCapableBeanFactory.java — 添加到三级缓存
boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences 
        && isSingletonCurrentlyInCreation(beanName));
if (earlySingletonExposure) {
    addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
}
```

**`getEarlyBeanReference()` 调用 AOP 的后处理器：**

```java
// AbstractAutowireCapableBeanFactory.java
protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
    Object exposedObject = bean;
    if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
        for (BeanPostProcessor bp : getBeanPostProcessors()) {
            if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
                // AbstractAutoProxyCreator 在这里检查是否需要提前创建代理
                exposedObject = ((SmartInstantiationAwareBeanPostProcessor) bp)
                        .getEarlyBeanReference(exposedObject, beanName);
            }
        }
    }
    return exposedObject;
}
```

**如果只有两级缓存（不用三级）：**
- 在实例化完成后就要立即创建 AOP 代理，但此时还不知道该 Bean 是否会被循环依赖引用
- 如果创建了代理但没有循环依赖，浪费了代理创建成本

**三级缓存的延迟创建优势：**
- 只有真的发生了循环依赖（调用了 `getSingleton()` 且在一二级没找到），才会执行三级工厂，生成代理对象
- 如果没有循环依赖，代理对象的创建延迟到 `postProcessAfterInitialization` 阶段，正常进行

```java
// AbstractAutoProxyCreator 中两个方法的协作
public Object getEarlyBeanReference(Object bean, String beanName) {
    // 如果循环依赖触发了，提前创建代理并缓存
    this.earlyProxyReferences.add(cacheKey);
    return wrapIfNecessary(bean, beanName, cacheKey);
}

public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (!this.earlyProxyReferences.contains(cacheKey)) {
        // 只有没有被提前代理的 Bean，才在这里创建代理
        return wrapIfNecessary(bean, beanName, cacheKey);
    }
    return bean;  // 已提前代理，直接返回
}
```

### 2.6 哪些循环依赖解决不了？

```java
// ❌ 构造器注入的循环依赖（无法解决）
@Component
public class A {
    public A(B b) {}  // 构造器依赖 B，但 B 还没创建
}

@Component
public class B {
    public B(A a) {}  // 构造器依赖 A，但 A 还没创建
}
// → BeanCurrentlyInCreationException: Requested bean is currently in creation
```

**原因：** 构造器注入在 `createBeanInstance()` 阶段就需要参数，此时 Bean 还没实例化，无法提前暴露到三级缓存。缓存机制要求先实例化再注入。

**解决方案：**
1. 改为 Setter 注入（`@Autowired` 字段/方法）
2. 使用 `@Lazy` 延迟加载其中一个依赖

```java
@Component
public class A {
    @Lazy  // 创建 A 时不真正创建 B，而是创建 B 的代理
    @Autowired
    private B b;
}
```

## 三、作用域机制

### 3.1 作用域类型

```java
// ConfigurableBeanFactory.java 中定义的作用域常量
public interface ConfigurableBeanFactory extends HierarchicalBeanFactory, SingletonBeanRegistry {
    String SCOPE_SINGLETON = "singleton";  // 单例（默认）
    String SCOPE_PROTOTYPE = "prototype";  // 原型（每次 getBean 创建新实例）
}

// WebApplicationContext 中定义的 Web 作用域
public interface WebApplicationContext extends ApplicationContext {
    String SCOPE_REQUEST = "request";    // 每次请求一个实例
    String SCOPE_SESSION = "session";    // 每个会话一个实例
    String SCOPE_APPLICATION = "application"; // 每个 ServletContext 一个实例
}
```

### 3.2 Singleton 与 Prototype

**Singleton（默认）：**

```java
// AbstractBeanFactory.getSingleton() — 获取单例 Bean
@Override
public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
    synchronized (this.singletonObjects) {
        // 1. 检查缓存
        Object singletonObject = this.singletonObjects.get(beanName);
        if (singletonObject == null) {
            // 2. 标记正在创建
            beforeSingletonCreation(beanName);
            try {
                // 3. 执行创建逻辑
                singletonObject = singletonFactory.getObject();
                // 4. 存入一级缓存
                addSingleton(beanName, singletonObject);
            } finally {
                afterSingletonCreation(beanName);
            }
        }
        return singletonObject;
    }
}
```

**Prototype：**

```java
// AbstractBeanFactory.java — Prototype 创建路径
if (mbd.isPrototype()) {
    Object prototypeInstance = null;
    try {
        // 标记创建中（用于检测 prototype 的循环依赖）
        beforePrototypeCreation(beanName);
        prototypeInstance = createBean(beanName, mbd, args);
    } finally {
        afterPrototypeCreation(beanName);
    }
    bean = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
}
```

**Prototype 没有缓存、不注册销毁回调：**
- 每次 `getBean()` 都是全新实例
- Spring 不管理 prototype Bean 的销毁（`DisposableBean` 不生效，`@PreDestroy` 不执行）

### 3.3 @Scope 的底层原理

```java
// ScopeMetadataResolver 解析 @Scope 注解
public ScopeMetadata resolveScopeMetadata(BeanDefinition definition) {
    ScopeMetadata metadata = new ScopeMetadata();
    // 如果是注解定义，读取 @Scope 注解
    AnnotatedBeanDefinition annDef = (AnnotatedBeanDefinition) definition;
    Scope scope = annDef.getMetadata().getAnnotation(Scope.class.getName());
    if (scope != null) {
        metadata.setScopeName(scope.scopeName());
        // 读取 proxyMode：是否生成代理
        metadata.setScopedProxyMode(scope.proxyMode());
    }
    return metadata;
}
```

**`ScopedProxyMode` 的三种模式：**

| 模式 | 效果 | 使用场景 |
|------|------|---------|
| `NO` | 不生成代理（默认） | singleton / prototype |
| `INTERFACES` | JDK 动态代理 | request/session，Bean 实现了接口 |
| `TARGET_CLASS` | CGLib 代理 | request/session，Bean 没有接口 |

### 3.4 作用域代理的原理

当一个 `@Scope("request")` 的 Bean 被注入到一个 `singleton` Bean 时，直接用原始 Bean 是不行的——因为 singleton Bean 在容器启动时创建，而 request Bean 每个请求都不同。

Spring 通过 **作用域代理（Scoped Proxy）** 解决这个问题：

```java
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class UserContext {
    private String userId;
    // getter / setter
}

@Service
public class UserService {
    @Autowired
    private UserContext userContext;  // 注入的是代理对象
}
```

**代理对象的创建——`ScopedProxyFactoryBean`：**

```java
// AopProxyUtils 生成的作用域代理工作方式：
// 代理对象持有一个目标 Bean 名称和作用域
// 每次调用方法时，从 scope 中获取当前线程/请求的实例

// 伪代码表示代理的逻辑：
public class UserContextProxy extends UserContext {
    private final String targetBeanName = "userContext";
    private final Scope scope = RequestScopeHolder.getScope();
    
    @Override
    public String getUserId() {
        // 每次从当前 request scope 获取真正的实例
        UserContext real = (UserContext) scope.get(targetBeanName);
        return real.getUserId();
    }
}
```

**`@Scope` 和代理的底层实现——`ScopedProxyUtils.createScopedProxy()`：**

```java
// ScopedProxyUtils.java
public static BeanDefinitionHolder createScopedProxy(BeanDefinitionHolder definition,
                                                     BeanDefinitionRegistry registry, boolean proxyTargetClass) {
    String originalBeanName = definition.getBeanName();
    String targetBeanName = ScopedProxyUtils.getTargetBeanName(originalBeanName);
    
    // 1. 修改原始 Bean 的名称（加 scopedTarget. 前缀）
    //    原始 UserContext → scopedTarget.userContext
    
    // 2. 创建代理 BeanDefinition（使用 ScopedProxyFactoryBean）
    RootBeanDefinition proxyDefinition = new RootBeanDefinition(ScopedProxyFactoryBean.class);
    proxyDefinition.getPropertyValues().add("targetBeanName", targetBeanName);
    proxyDefinition.getPropertyValues().add("proxyTargetClass", proxyTargetClass);
    
    // 3. 用代理 BeanDefinition 替换原始定义
    //    外部通过"userContext"拿到的是代理
    //    内部用"scopedTarget.userContext"获取真实实例
}
```

### 3.5 @Lazy 的原理

```java
@Lazy
@Component
public class LazyBean { ... }
```

**`@Lazy` 的效果：**
- **作用于 `@Configuration` 类**：所有 `@Bean` 方法延迟初始化
- **作用于单个 `@Bean`**：该 Bean 延迟初始化（直到第一次使用才创建）
- **作用于 `@Autowired` 字段**：注入一个 `LazyResolutionProxy` 代理

```java
// @Lazy 在注入点的处理（DependencyDescriptor）
public class DependencyDescriptor {
    private final boolean required;
    private final boolean eager;  // !@Lazy
    
    // 如果字段标注了 @Lazy，eager=false
    // resolveDependency 遇到 eager=false 时不创建真正的 Bean
    // 而是创建 Lazy 代理
}
```

```java
// AutowiredAnnotationBeanPostProcessor 对 @Lazy 的处理
// 如果字段/参数标了 @Lazy，createLazyResolutionProxyIfNecessary()
// 返回一个代理对象，真正访问时才触发 getBean()
if (field.isAnnotationPresent(Lazy.class)) {
    // 注入代理，不立即创建
    return createLazyResolutionProxyIfNecessary(descriptor, beanName);
}
```

## 四、三种注入方式的对比

| 维度 | 字段注入 | Setter 注入 | 构造器注入 |
|------|---------|------------|-----------|
| 写法简洁性 | ★★★★★ | ★★★☆☆ | ★★★☆☆ |
| 循环依赖 | 支持 | 支持 | 不支持 |
| 不变性 | 可变 | 可变 | 不可变（final） |
| 测试友好性 | 一般（需反射） | 好（直接 set）| 最好（直接 new）|
| Spring 官方推荐 | 不推荐 | 一般 | **推荐**（Spring 团队推荐）|

```java
// ✅ Spring 官方推荐：构造器注入
@Service
public class OrderService {
    private final UserService userService;
    private final ProductService productService;
    
    public OrderService(UserService userService, ProductService productService) {
        this.userService = userService;
        this.productService = productService;
    }
}

// ⚠️ 但构造器注入不支持循环依赖
// 如果存在循环依赖，只能降级为 Setter 或字段注入
```

## 五、总结

### 三级缓存核心要点

```
一级缓存：singletonObjects      → 成品 Bean（完全创建完成）
二级缓存：earlySingletonObjects → 半成品 Bean（已实例化，未注入/初始化）
三级缓存：singletonFactories    → 工厂函数（延迟生成，通常用于创建 AOP 代理）

查找顺序：一级 → 二级 → 三级
创建顺序：先加入三级 → 被引用时升到二级 → 完成后升到一级
```

### 各种循环依赖的处理结果

| 注入方式 | 能否解决 | 原因 |
|---------|---------|------|
| Setter 注入 | ✅ 能 | 先实例化，后注入；实例化后可提前暴露 |
| 字段注入（@Autowired） | ✅ 能 | 等同于 Setter 注入，先实例化后填充 |
| 构造器注入 | ❌ 不能 | 实例化时就需要依赖，无法提前暴露 |
| @Lazy + 构造器 | ✅ 能 | 延迟创建代理，不触发真正创建 |

### 作用域速查

| 作用域 | 实例数 | 创建时机 | 销毁管理 |
|-------|--------|---------|---------|
| singleton | 1 | BeanFactory 启动时 | 容器关闭时销毁 |
| prototype | 每次 getBean | 每次 getBean | Spring 不管理 |
| request | 每次 HTTP 请求 | 请求处理时 | 请求结束 |
| session | 每个 HTTP 会话 | 会话创建时 | 会话销毁 |
| application | 每个 ServletContext | 应用启动时 | 应用停止 |

### 核心源码文件索引

| 类 | 关键方法 | 功能 |
|---|---------|------|
| `AutowiredAnnotationBeanPostProcessor` | `buildAutowiringMetadata()` | 收集 @Autowired 注入点 |
| `AutowiredAnnotationBeanPostProcessor` | `postProcessProperties()` | 执行依赖注入 |
| `DefaultListableBeanFactory` | `resolveDependency()` | 依赖查找（类型 + @Qualifier + @Primary）|
| `DefaultSingletonBeanRegistry` | `getSingleton()` | 三级缓存查找 |
| `DefaultSingletonBeanRegistry` | `addSingletonFactory()` | 添加到三级缓存 |
| `AbstractAutoProxyCreator` | `getEarlyBeanReference()` | 提前创建 AOP 代理 |
| `AbstractAutoProxyCreator` | `postProcessAfterInitialization()` | 常规 AOP 代理创建 |
| `ScopedProxyUtils` | `createScopedProxy()` | 创建作用域代理 |

---

**上一篇：** [Spring DI（二）：Bean 生命周期深度解析]({{< relref "post/spring-di-bean-lifecycle" >}})

**系列第一篇：** [Spring DI（一）：IoC 容器初始化与 BeanFactory 体系]({{< relref "post/spring-di-ioc-container" >}})
