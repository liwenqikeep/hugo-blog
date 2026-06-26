---
title: "Spring DI（一）：IoC 容器初始化与 BeanFactory 体系"
date: 2018-04-26
draft: false
categories: ["Java"]
tags: ["Spring", "IoC", "BeanFactory", "ApplicationContext", "BeanDefinition", "源码分析"]
toc: true
---

## 前言

Spring IoC 容器是 Spring 框架最核心的基础设施。理解容器的初始化过程和 BeanFactory 的层次结构，是深入 Spring 源码的第一步。

本文从一个最简单的 `new AnnotationConfigApplicationContext(Config.class)` 出发，逐行追踪 Spring 容器从无到有的完整创建过程。

<!--more-->

> **源码版本：** Spring Framework 5.x（与 4.x 核心逻辑基本一致）

## 一、BeanFactory 体系结构

在深入初始化过程之前，先理清 Spring IoC 容器最核心的类层次。

### 1.1 BeanFactory 接口层次

```
BeanFactory                          ← 最基础：getBean()、containsBean()、isSingleton()
  │
  └── ListableBeanFactory            ← 可枚举：getBeanDefinitionNames()、getBeansOfType()
        │
        └── ConfigurableBeanFactory  ← 可配置：registerSingleton()、addBeanPostProcessor()
              │
              └── AbstractBeanFactory         ← 模板方法模式骨架
                    │
                    └── AbstractAutowireCapableBeanFactory  ← 自动装配核心：createBean()、populateBean()
                          │
                          └── DefaultListableBeanFactory    ← 默认实现：BeanDefinition 注册与获取
```

**核心职责分工：**

| 接口/类 | 关键能力 |
|---------|---------|
| `BeanFactory` | getBean、containsBean、isSingleton/isPrototype |
| `ListableBeanFactory` | 批量获取 Bean 名称和实例 |
| `HierarchicalBeanFactory` | 支持父子容器 |
| `ConfigurableBeanFactory` | 配置作用域、类加载器、BeanPostProcessor |
| `AutowireCapableBeanFactory` | 自动注入、autowire、createBean |
| `DefaultListableBeanFactory` | BeanDefinition 注册与获取（工作中最常接触的 BeanFactory） |

### 1.2 ApplicationContext 层次

`ApplicationContext` 是一个更高级的容器，它在 BeanFactory 的基础上增加了企业级功能。

```
ApplicationContext
  ├── BeanFactory（通过 getBeanFactory() 获取）
  ├── MessageSource（国际化）
  ├── ResourcePatternResolver（资源加载）
  ├── ApplicationEventPublisher（事件发布）
  └── EnvironmentCapable（环境配置）
```

**常见实现类：**

```
ApplicationContext
  ├── ClassPathXmlApplicationContext     ← 基于 XML 配置
  ├── FileSystemXmlApplicationContext    ← 基于文件系统 XML
  ├── AnnotationConfigApplicationContext ← 基于注解配置（最常用）
  └── GenericWebApplicationContext        ← Web 环境
```

### 1.3 BeanDefinition 体系

`BeanDefinition` 是 IoC 容器的核心数据模型——它描述了 Bean 的所有元信息。

```
BeanDefinition
  ├── beanClass                    // Bean 的类型
  ├── scope                        // 作用域（singleton/prototype）
  ├── lazyInit                     // 是否懒加载
  ├── initMethodName               // 初始化方法名
  ├── destroyMethodName            // 销毁方法名
  ├── constructorArgumentValues    // 构造器参数
  ├── propertyValues               // 属性值
  ├── autowireMode                 // 自动装配模式
  ├── dependsOn                    // 依赖关系
  ├── primary                      // 是否首选
  └── role                         // 角色（APPLICATION/INFRASTRUCTURE/SUPPORT）
```

**BeanDefinition 的三种实现：**

| 实现类 | 使用场景 |
|--------|---------|
| `GenericBeanDefinition` | 通用定义（注解 + XML）|
| `ScannedGenericBeanDefinition` | 组件扫描使用 |
| `AnnotatedGenericBeanDefinition` | 直接注册 `@Configuration` 类时使用 |

## 二、容器初始化流程

### 2.1 入口：AnnotationConfigApplicationContext

```java
// 一行代码启动容器
AnnotationConfigApplicationContext context = 
    new AnnotationConfigApplicationContext(AppConfig.class);
```

**构造器源码：**

```java
// AnnotationConfigApplicationContext.java
public class AnnotationConfigApplicationContext extends GenericApplicationContext
        implements AnnotationConfigRegistry {

    // 核心组件
    private final AnnotatedBeanDefinitionReader reader;    // 读取 @Configuration 类
    private final ClassPathBeanDefinitionScanner scanner;  // 扫描包路径

    public AnnotationConfigApplicationContext(Class<?>... componentClasses) {
        // 1. 调用父类构造器：创建 DefaultListableBeanFactory
        super();  
        // 2. 初始化 reader 和 scanner
        this.reader = new AnnotatedBeanDefinitionReader(this);
        this.scanner = new ClassPathBeanDefinitionScanner(this);
        // 3. 注册配置类
        register(componentClasses);
        // 4. 刷新容器（核心！）
        refresh();
    }
}
```

### 2.2 refresh()——容器的完整启动

`refresh()` 是 `AbstractApplicationContext` 中定义的方法，是整个 IoC 容器的启动入口。

```java
// AbstractApplicationContext.java
@Override
public void refresh() throws BeansException, IllegalStateException {
    synchronized (this.startupShutdownMonitor) {
        // 1. 刷新前置准备
        prepareRefresh();

        // 2. 获取 BeanFactory（子类实现）
        //    在 GenericApplicationContext 中就是获取已有的 DefaultListableBeanFactory
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

        // 3. BeanFactory 预准备
        prepareBeanFactory(beanFactory);

        try {
            // 4. BeanFactory 后置处理（子类扩展）
            postProcessBeanFactory(beanFactory);

            // 5. 调用 BeanFactoryPostProcessor
            //    这里包含了 @Configuration 类解析、@ComponentScan 扫描、
            //    @Import 处理等所有注解配置的解析逻辑
            invokeBeanFactoryPostProcessors(beanFactory);

            // 6. 注册 BeanPostProcessor
            registerBeanPostProcessors(beanFactory);

            // 7. 初始化 MessageSource
            initMessageSource();

            // 8. 初始化事件广播器
            initApplicationEventMulticaster();

            // 9. 子类模板方法（如 Web 容器中的 onRefresh）
            onRefresh();

            // 10. 注册监听器
            registerListeners();

            // 11. 实例化所有非懒加载的单例 Bean
            finishBeanFactoryInitialization(beanFactory);

            // 12. 完成刷新：发布事件
            finishRefresh();
        } catch (BeansException ex) {
            // 异常处理：销毁已创建的 Bean
            destroyBeans();
            cancelRefresh(ex);
            throw ex;
        } finally {
            resetCommonCaches();
        }
    }
}
```

`refresh()` 的 12 个步骤可以用下图概括：

```
prepareRefresh()                            [准备环境]
    │
obtainFreshBeanFactory()                     [获取 BeanFactory]
    │
prepareBeanFactory(beanFactory)              [配置 BeanFactory]
    │
postProcessBeanFactory(beanFactory)          [子类扩展点]
    │
invokeBeanFactoryPostProcessors()            [★ 核心：解析配置类、扫描组件]
    │
registerBeanPostProcessors()                 [注册后处理器]
    │
initMessageSource() / initApplicationEventMulticaster()  [初始化基础设施]
    │
onRefresh()                                  [Web 容器等子类扩展]
    │
registerListeners()                          [注册 ApplicationListener]
    │
finishBeanFactoryInitialization()            [★ 实例化所有非懒加载单例 Bean]
    │
finishRefresh()                              [发布 ContextRefreshedEvent]
```

下面展开最关键的两个步骤：**第 5 步（BeanFactoryPostProcessor 调用）**和**第 11 步（单例 Bean 实例化）**。

## 三、invokeBeanFactoryPostProcessors——配置类解析

这一步是整个注解驱动的核心。Spring 通过 `ConfigurationClassPostProcessor`（一个 `BeanDefinitionRegistryPostProcessor`）来处理 `@Configuration` 类。

### 3.1 调用链路

```java
// AbstractApplicationContext.java
invokeBeanFactoryPostProcessors(beanFactory);
    ↓
PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());
    ↓
// 找出所有 BeanDefinitionRegistryPostProcessor（BeanFactoryPostProcessor 的子接口）
// 优先调用实现了 PriorityOrdered 的
for (BeanDefinitionRegistryPostProcessor processor : registryProcessors) {
    processor.postProcessBeanDefinitionRegistry(registry);
}
    ↓
ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry()
    ↓
processConfigBeanDefinitions(registry);  // 核心逻辑
```

### 3.2 ConfigurationClassPostProcessor 的处理逻辑

```java
// ConfigurationClassPostProcessor.java
public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {
    // 1. 找出所有已注册的候选配置类
    List<BeanDefinitionHolder> configCandidates = new ArrayList<>();
    String[] candidateNames = registry.getBeanDefinitionNames();
    
    for (String beanName : candidateNames) {
        BeanDefinition bd = registry.getBeanDefinition(beanName);
        if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, this.metadataReaderFactory)) {
            configCandidates.add(new BeanDefinitionHolder(bd, beanName));
        }
    }

    // 2. 通过 ConfigurationClassParser 解析配置类
    ConfigurationClassParser parser = new ConfigurationClassParser(
            this.metadataReaderFactory, this.problemReporter, this.environment,
            this.resourceLoader, this.componentScanBeanNameGenerator, registry);
    
    Set<BeanDefinitionHolder> candidates = new LinkedHashSet<>(configCandidates);
    // 递归解析（处理 @Import 等嵌套）
    parser.parse(candidates);
    parser.validate();

    // 3. 获取解析出的所有配置类，转换成 BeanDefinition
    Set<ConfigurationClass> configClasses = parser.getConfigurationClasses();
    
    // 4. 使用 ConfigurationClassBeanDefinitionReader 将 @Bean 方法注册为 BeanDefinition
    if (this.metadataReaderFactory instanceof CachingMetadataReaderFactory) {
        // ...
    }
    reader.loadBeanDefinitions(configClasses);
}
```

**解析器会处理哪些注解？**

| 注解 | 处理逻辑 |
|------|---------|
| `@ComponentScan` | 扫描指定包下的所有 `@Component` 类，注册为 BeanDefinition |
| `@Import` | 递归处理导入的配置类或普通类 |
| `@ImportResource` | 加载 XML 配置文件中的 Bean 定义 |
| `@Bean` | 将方法返回值作为 Bean 注册 |
| `@PropertySource` | 加载属性文件到 Environment |

### 3.3 @ComponentScan 扫描过程

```java
// ClassPathBeanDefinitionScanner.doScan()
protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
    for (String basePackage : basePackages) {
        // 1. 扫描包下的所有 .class 文件
        Set<BeanDefinition> candidates = findCandidateComponents(basePackage);
        
        for (BeanDefinition candidate : candidates) {
            // 2. 解析 @Scope
            ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(candidate);
            candidate.setScope(scopeMetadata.getScopeName());
            
            // 3. 生成 BeanName
            String beanName = this.beanNameGenerator.generateBeanName(candidate, this.registry);
            
            // 4. 处理 @Lazy、@Primary、@DependsOn 等注解
            AnnotationConfigUtils.processCommonDefinitionAnnotations(candidate);
            
            // 5. 检查 Bean 定义是否冲突
            if (checkCandidate(beanName, candidate)) {
                BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(candidate, beanName);
                // 6. 注册到 BeanDefinitionRegistry
                registerBeanDefinition(definitionHolder, this.registry);
            }
        }
    }
    return beanDefinitions;
}
```

## 四、BeanDefinition 注册

经过 `ConfigurationClassPostProcessor` 处理后，所有 Bean 定义都被注册到了 `DefaultListableBeanFactory` 中。

```java
// DefaultListableBeanFactory.java
private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);

@Override
public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
        throws BeanDefinitionStoreException {
    
    // 1. 验证 BeanDefinition（解析 class 名称等）
    if (beanDefinition instanceof AbstractBeanDefinition) {
        ((AbstractBeanDefinition) beanDefinition).validate();
    }

    // 2. 检查是否已存在同名的 BeanDefinition
    BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
    if (existingDefinition != null) {
        // 如果是同名的，根据 allowBeanDefinitionOverriding 决定是否覆盖
        if (!isAllowBeanDefinitionOverriding()) {
            throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), ...);
        }
        // 覆盖已有定义
        this.beanDefinitionMap.put(beanName, beanDefinition);
    } else {
        // 3. 检查是否有 Bean 正在创建（循环依赖检测的一部分）
        if (hasBeanCreationStarted()) {
            synchronized (this.beanDefinitionMap) {
                this.beanDefinitionMap.put(beanName, beanDefinition);
                List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames);
                updatedDefinitions.add(beanName);
                this.beanDefinitionNames = updatedDefinitions;
                this.manualSingletonNames.remove(beanName);
            }
        } else {
            this.beanDefinitionMap.put(beanName, beanDefinition);
            this.beanDefinitionNames.add(beanName);
            this.manualSingletonNames.remove(beanName);
        }
        
        // 4. 清除该 Bean 的旧解析缓存
        this.frozenBeanDefinitionNames = null;
    }
}
```

这一步后，**Bean 的定义信息全部准备就绪**，但 Bean 实例尚未创建。

**BeanDefinition 注册完成后的内存状态：**

```
DefaultListableBeanFactory
│
├── beanDefinitionMap（ConcurrentHashMap）
│   ├── "userService"     → GenericBeanDefinition
│   ├── "userRepository"  → ScannedGenericBeanDefinition
│   ├── "dataSource"      → GenericBeanDefinition
│   └── "appConfig"       → AnnotatedGenericBeanDefinition
│
├── beanDefinitionNames（有序 List）
│   ["appConfig", "userService", "userRepository", ...]
│
└── singletonObjects       ← 此时为空（尚未实例化）
```

## 五、finishBeanFactoryInitialization——单例 Bean 实例化

`refresh()` 的第 11 步，是所有「非懒加载单例 Bean」的实例化入口。

```java
// AbstractApplicationContext.java
protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
    // 1. 初始化 ConversionService（类型转换）
    if (beanFactory.containsBean(CONVERSION_SERVICE_BEAN_NAME)
            && beanFactory.isTypeMatch(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class)) {
        beanFactory.setConversionService(
                beanFactory.getBean(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class));
    }

    // 2. 冻结 BeanDefinition（标记，不允许再修改）
    beanFactory.freezeConfiguration();

    // 3. 实例化所有非懒加载单例 Bean
    beanFactory.preInstantiateSingletons();
}
```

**`preInstantiateSingletons()` 在 `DefaultListableBeanFactory` 中的实现：**

```java
// DefaultListableBeanFactory.java
@Override
public void preInstantiateSingletons() throws BeansException {
    // 1. 遍历所有 BeanDefinition 名称
    List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);
    
    // 2. 遍历触发 getBean 创建每个 Bean
    for (String beanName : beanNames) {
        RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
        
        // 只处理非抽象、单例、非懒加载的 Bean
        if (!bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
            // 如果是 FactoryBean，特殊处理
            if (isFactoryBean(beanName)) {
                Object bean = getBean(FACTORY_BEAN_PREFIX + beanName);
                // 如果是 SmartFactoryBean 且标记为 eager，触发 getObject()
                if (bean instanceof SmartFactoryBean && ((SmartFactoryBean<?>) bean).isEagerInit()) {
                    getBean(beanName);
                }
            } else {
                // ★ 核心调用：触发 Bean 创建
                getBean(beanName);
            }
        }
    }

    // 3. 后处理：对于实现了 SmartInitializingSingleton 的 Bean
    //    在所有单例实例化完成后回调
    for (String beanName : beanNames) {
        Object singletonInstance = getSingleton(beanName);
        if (singletonInstance instanceof SmartInitializingSingleton) {
            SmartInitializingSingleton smart = (SmartInitializingSingleton) singletonInstance;
            smart.afterSingletonsInstantiated();
        }
    }
}
```

这里的 `getBean(beanName)` 最终进入 `AbstractBeanFactory.doGetBean()`，下一篇文章中会详细分析从 `getBean` 到 `createBean` 的完整路径。

## 六、BeanFactory 预准备

回到 `refresh()` 的第 3 步 `prepareBeanFactory()`，它为 BeanFactory 配置了一些重要基础设施：

```java
// AbstractApplicationContext.java
protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // 1. 设置类加载器
    beanFactory.setBeanClassLoader(getClassLoader());
    
    // 2. 设置表达式解析器（#{...} SpEL 支持）
    beanFactory.setBeanExpressionResolver(new StandardBeanExpressionResolver());
    
    // 3. 设置属性编辑器注册器
    beanFactory.addPropertyEditorRegistrar(new ResourceEditorRegistrar(this, getEnvironment()));

    // 4. 添加 ApplicationContextAwareProcessor
    //    使 Bean 能实现 Aware 接口回调
    beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this));
    
    // 5. 忽略特定 Aware 接口的自动装配
    beanFactory.ignoreDependencyInterface(EnvironmentAware.class);
    beanFactory.ignoreDependencyInterface(EmbeddedValueResolverAware.class);
    beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class);
    beanFactory.ignoreDependencyInterface(ApplicationEventPublisherAware.class);
    beanFactory.ignoreDependencyInterface(MessageSourceAware.class);
    beanFactory.ignoreDependencyInterface(ApplicationContextAware.class);

    // 6. 注册特殊依赖的自动装配
    beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);
    beanFactory.registerResolvableDependency(ResourceLoader.class, this);
    beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this);
    beanFactory.registerResolvableDependency(ApplicationContext.class, this);

    // 7. 注册一些基础设施 Bean 的后处理器
    beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(this));

    // 8. 提前注册一些基础设施 Bean
    if (!beanFactory.containsLocalBean(ENVIRONMENT_BEAN_NAME)) {
        beanFactory.registerSingleton(ENVIRONMENT_BEAN_NAME, getEnvironment());
    }
}
```

**这一步很重要——它告诉容器：**
- 当 Bean 中 `@Autowired EnvironmentAware` 时，应该注入什么
- 当 Bean 实现了 `ApplicationContextAware`，由谁来处理回调
- 哪些基础设施 Bean 必须在容器启动时就可用

## 七、总结

### IoC 容器初始化流程速记

```
1. 创建 DefaultListableBeanFactory
2. 注册配置类（AnnotatedBeanDefinitionReader）
3. refresh() 启动
   ├─ prepareRefresh()           — 环境准备
   ├─ obtainFreshBeanFactory()   — 获取 BeanFactory
   ├─ prepareBeanFactory()       — 配置 Aware、类加载器等
   ├─ invokeBeanFactoryPostProcessors() — ★ 解析配置类、组件扫描
   │    └─ ConfigurationClassPostProcessor
   │         ├─ @ComponentScan 扫描包
   │         ├─ @Import 递归导入
   │         ├─ @Bean 注册方法 Bean
   │         └─ @PropertySource 加载配置
   ├─ registerBeanPostProcessors() — 注册后处理器
   ├─ 初始化基础设施（MessageSource / 事件广播器等）
   ├─ finishBeanFactoryInitialization() — ★ 实例化非懒加载单例
   │    └─ preInstantiateSingletons()
   │         └─ getBean() → doGetBean() → createBean()
   └─ finishRefresh() — 发布事件
```

### BeanFactory 与 ApplicationContext 对比

| 维度 | BeanFactory | ApplicationContext |
|------|------------|-------------------|
| Bean 生命周期管理 | 基础（getBean） | 完整（含后处理器） |
| BeanPostProcessor | 手动注册 | 自动注册调用 |
| 事件机制 | 无 | 支持 |
| 国际化 | 无 | MessageSource |
| 资源加载 | 无 | ResourceLoader |
| 启动速度 | 快 | 较慢（需提前实例化单例）|

### 核心源码文件索引

| 类名 | 位置 | 关键方法 |
|------|------|---------|
| `AbstractApplicationContext` | `context/support/` | `refresh()` |
| `AnnotationConfigApplicationContext` | `context/annotation/` | 构造函数 |
| `DefaultListableBeanFactory` | `beans/factory/support/` | `registerBeanDefinition()`, `preInstantiateSingletons()` |
| `ConfigurationClassPostProcessor` | `context/annotation/` | `processConfigBeanDefinitions()` |
| `ConfigurationClassParser` | `context/annotation/` | `parse()` |
| `ClassPathBeanDefinitionScanner` | `context/annotation/` | `doScan()` |
| `AnnotatedBeanDefinitionReader` | `context/annotation/` | `registerBean()` |

---

**下一篇：** [Spring DI（二）：Bean 生命周期深度解析]({{< relref "post/spring-di-bean-lifecycle" >}})
