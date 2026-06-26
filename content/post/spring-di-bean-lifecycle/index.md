---
title: "Spring DI（二）：Bean 生命周期深度解析"
date: 2018-04-28
draft: false
categories: ["Java"]
tags: ["Spring", "IoC", "Bean生命周期", "BeanPostProcessor", "Aware", "源码分析"]
toc: true
---

## 前言

上一篇文章我们从容器初始化的角度追踪了 IoC 容器的启动过程，最终落到了 `finishBeanFactoryInitialization()` 的 `getBean()` 调用。这篇文章从 `getBean()` 开始，深入追踪一个 Bean **从无到有再到销毁**的完整生命周期。

<!--more-->

## 一、完整生命周期概览

一个 Bean 的完整生命周期可以分为以下阶段：

```
BeanDefinition 注册完成
    │
    ▼
1. 实例化前（InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation）
    │
    ▼
2. 实例化（反射创建对象：new Instance）
    │
    ▼
3. 实例化后（MergedBeanDefinitionPostProcessor.postProcessMergedBeanDefinition）
    │
    ▼
4. 属性填充（populateBean — 依赖注入的核心）
    │
    ▼
5. 设置 Aware 接口
    │
    ▼
6. 初始化前（BeanPostProcessor.postProcessBeforeInitialization）
    │
    ▼
7. 初始化（InitializingBean.afterPropertiesSet / @PostConstruct / init-method）
    │
    ▼
8. 初始化后（BeanPostProcessor.postProcessAfterInitialization）
    │
    ▼
9. Bean 就绪（可使用）
    │
    ▼
10. 销毁前（DestructionAwareBeanPostProcessor.postProcessBeforeDestruction）
    │
    ▼
11. 销毁（DisposableBean.destroy / @PreDestroy / destroy-method）
```

**用 Spring 源码中 `doCreateBean()` 的核心骨架来对应：**

```java
// AbstractAutowireCapableBeanFactory.doCreateBean()

protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
    // 1. 实例化
    BeanWrapper instanceWrapper = createBeanInstance(beanName, mbd, args);
    
    // 2. 允许 MergedBeanDefinitionPostProcessor 后处理
    applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
    
    // 3. 提前暴露（解决循环依赖）
    boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences 
            && isSingletonCurrentlyInCreation(beanName));
    if (earlySingletonExposure) {
        addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
    }
    
    // 4. 属性填充（依赖注入）
    populateBean(beanName, mbd, instanceWrapper);
    
    // 5. 初始化（Aware + BeanPostProcessor + init-method）
    exposedObject = initializeBean(beanName, exposedObject, mbd);
    
    return exposedObject;
}
```

下面逐步展开每个阶段。

## 二、getBean() 入口

### 2.1 doGetBean() 的完整流程

```java
// AbstractBeanFactory.java
protected <T> T doGetBean(String name, @Nullable Class<T> requiredType, 
                          @Nullable Object[] args, boolean typeCheckOnly) {
    // 1. 转换 Bean 名称（处理 & 前缀、别名）
    String beanName = transformedBeanName(name);
    
    // 2. 尝试从缓存获取 — 三级缓存逐级查找
    Object sharedInstance = getSingleton(beanName);
    
    if (sharedInstance != null && args == null) {
        // 缓存命中，处理 FactoryBean 情况
        bean = getObjectForBeanInstance(sharedInstance, name, beanName, null);
    } else {
        // 3. 检查是否存在循环引用
        if (isPrototypeCurrentlyInCreation(beanName)) {
            throw new BeanCurrentlyInCreationException(beanName);
        }
        
        // 4. 检查父容器
        BeanFactory parentBeanFactory = getParentBeanFactory();
        if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
            bean = parentBeanFactory.getBean(nameToLookup, requiredType);
        }
        
        // 5. 标记正在创建（用于循环依赖检测）
        if (!typeCheckOnly) {
            markBeanAsCreated(beanName);
        }
        
        // 6. 合并 BeanDefinition（处理父 BeanDefinition）
        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
        
        // 7. 确保依赖先创建（depends-on 属性）
        String[] dependsOn = mbd.getDependsOn();
        if (dependsOn != null) {
            for (String dep : dependsOn) {
                registerDependentBean(dep, beanName);
                getBean(dep);
            }
        }
        
        // 8. 根据作用域创建 Bean
        if (mbd.isSingleton()) {
            sharedInstance = getSingleton(beanName, () -> {
                try {
                    return createBean(beanName, mbd, args);
                } catch (BeansException ex) {
                    destroySingleton(beanName);
                    throw ex;
                }
            });
            bean = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
        } else if (mbd.isPrototype()) {
            // prototype 每次创建新实例
            Object prototypeInstance = createBean(beanName, mbd, args);
            bean = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
        } else {
            // request / session / application 等作用域
            String scopeName = mbd.getScope();
            Scope scope = this.scopes.get(scopeName);
            Object scopedInstance = scope.get(beanName, () -> createBean(beanName, mbd, args));
            bean = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
        }
    }
    
    // 9. 类型检查/转换
    if (requiredType != null && !requiredType.isInstance(bean)) {
        bean = getTypeConverter().convertIfNecessary(bean, requiredType);
    }
    
    return (T) bean;
}
```

### 2.2 createBean() 到 doCreateBean()

```java
// AbstractAutowireCapableBeanFactory.java
@Override
protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
    // 1. 解析 Bean 的 class 类型
    Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
    
    // 2. 验证和准备 MethodOverride（lookup-method / replace-method）
    mbd.prepareMethodOverrides();
    
    try {
        // 3. ★ 给 InstantiationAwareBeanPostProcessor 机会：返回代理对象
        Object bean = resolveBeforeInstantiation(beanName, mbd);
        if (bean != null) {
            return bean;  // 后处理器直接返回了代理对象，跳过默认创建
        }
    } catch (Throwable ex) {
        throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage());
    }
    
    // 4. 真正的创建（doCreateBean）
    Object beanInstance = doCreateBean(beanName, mbd, args);
    return beanInstance;
}
```

## 三、实例化阶段

### 3.1 实例化前拦截：resolveBeforeInstantiation

```java
// AbstractAutowireCapableBeanFactory.java
protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
    if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
        if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
            // 调用所有 InstantiationAwareBeanPostProcessor
            for (BeanPostProcessor bp : getBeanPostProcessors()) {
                if (bp instanceof InstantiationAwareBeanPostProcessor) {
                    InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                    // 如果返回非 null，就是用代理替代了原始 Bean
                    Object result = ibp.postProcessBeforeInstantiation(beanClass, beanName);
                    if (result != null) {
                        // 直接返回代理对象
                        // 注意：后续不会走 populateBean 和 initializeBean
                        return result;
                    }
                }
            }
        }
    }
    return null;
}
```

典型的应用场景：**AOP 代理**通过 `AbstractAutoProxyCreator` 在此处返回代理对象、**MyBatis** 的 `MapperScannerConfigurer` 在此处生成 Mapper 代理。

### 3.2 实例化：createBeanInstance

当没有后处理器返回代理对象时，进入 `createBeanInstance()` 进行常规实例化：

```java
// AbstractAutowireCapableBeanFactory.java
protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, 
                                         @Nullable Object[] args) {
    Class<?> beanClass = mbd.getBeanClass();
    
    // 1. 使用 Supplier 创建
    Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
    if (instanceSupplier != null) {
        return instantiateUsingSupplier(instanceSupplier, beanName);
    }
    
    // 2. 使用工厂方法创建（@Bean 方法的底层）
    if (mbd.getFactoryMethodName() != null) {
        return instantiateUsingFactoryMethod(beanName, mbd, args);
    }
    
    // 3. 推断构造器（核心逻辑）
    //    - 如果只有一个构造器且无参 → 直接反射创建
    //    - 如果有多个构造器 → AutowiredAnnotationBeanPostProcessor 推断
    //    - 如果指定了构造器参数 → 按参数匹配
    Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
    if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR
            || mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
        return autowireConstructor(beanName, mbd, ctors, args);
    }
    
    // 4. 无参构造器 → 直接反射实例化
    return instantiateBean(beanName, mbd);
}
```

**构造器推断机制**是通过 `SmartInstantiationAwareBeanPostProcessor.determineCandidateConstructors()` 实现的。`AutowiredAnnotationBeanPostProcessor` 是它的实现，负责找出哪些构造器标注了 `@Autowired`。

```java
// AutowiredAnnotationBeanPostProcessor.determineCandidateConstructors()
public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
    // 1. 查找标注了 @Autowired 的构造器
    // 2. 如果只有一个构造器且是带参构造器，隐式注入
    // 3. 如果找不到，返回 null（无参构造器）
}
```

**实例化的底层实现：**

```java
// SimpleInstantiationStrategy.java — 默认的实例化策略
public Object instantiate(RootBeanDefinition bd, String beanName, BeanFactory owner) {
    if (!bd.hasMethodOverrides()) {
        // 没有方法覆盖，直接反射
        Constructor<?> constructorToUse = bd.getConstructorToUse();
        return constructorToUse.newInstance(args);
    } else {
        // 有 lookup-method / replace-method，使用 CGLib 创建子类
        return instantiateWithMethodInjection(bd, beanName, owner);
    }
}
```

### 3.3 MergedBeanDefinition 后处理

实例化完成后，`doCreateBean()` 立即执行 `applyMergedBeanDefinitionPostProcessors()`：

```java
// AbstractAutowireCapableBeanFactory.java
protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, 
                                                        Class<?> beanType, String beanName) {
    for (BeanPostProcessor bp : getBeanPostProcessors()) {
        if (bp instanceof MergedBeanDefinitionPostProcessor) {
            // 关键：AutowiredAnnotationBeanPostProcessor 在这里解析 @Autowired 元数据
            ((MergedBeanDefinitionPostProcessor) bp).postProcessMergedBeanDefinition(mbd, beanType, beanName);
        }
    }
}
```

这一步做的事情对后续属性填充至关重要：

- **`AutowiredAnnotationBeanPostProcessor`**：扫描所有字段和方法，找出标注了 `@Autowired`/`@Value`/`@Inject` 的注入点，缓存到 `InjectionMetadata`
- **`CommonAnnotationBeanPostProcessor`**：扫描 `@Resource`、`@PostConstruct`、`@PreDestroy`

```java
// AutowiredAnnotationBeanPostProcessor.java
public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, 
                                            Class<?> beanType, String beanName) {
    // 查找所有注入点（字段 + 方法）
    InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
    // 将注入元数据缓存到 BeanDefinition 中
    metadata.checkConfigMembers(beanDefinition);
}
```

## 四、属性填充（依赖注入）

`populateBean()` 是依赖注入的核心实现：

```java
// AbstractAutowireCapableBeanFactory.java
protected void populateBean(String beanName, RootBeanDefinition mbd, 
                            @Nullable BeanWrapper bw) {
    // 1. 实例化后、属性填充前的后处理
    //    如果返回 false，跳过自动属性填充
    for (BeanPostProcessor bp : getBeanPostProcessors()) {
        if (bp instanceof InstantiationAwareBeanPostProcessor) {
            if (!((InstantiationAwareBeanPostProcessor) bp)
                    .postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
                return;  // 跳过自动属性填充
            }
        }
    }

    // 2. 按注入方式收集属性值
    PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);

    // 3. 按 autowireMode 自动装配（很少使用了）
    if (mbd.getResolvedAutowireMode() == AUTOWIRE_BY_NAME) {
        autowireByName(beanName, mbd, bw, newPvs);
    }
    if (mbd.getResolvedAutowireMode() == AUTOWIRE_BY_TYPE) {
        autowireByType(beanName, mbd, bw, newPvs);
    }

    // 4. ★ 核心：调用 InstantiationAwareBeanPostProcessor 
    //    处理 @Autowired、@Resource、@Inject 等注解
    for (BeanPostProcessor bp : getBeanPostProcessors()) {
        if (bp instanceof InstantiationAwareBeanPostProcessor) {
            // AutowiredAnnotationBeanPostProcessor 在此处完成 @Autowired 注入
            pvs = ((InstantiationAwareBeanPostProcessor) bp)
                    .postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
            if (pvs == null) {
                return;
            }
        }
    }

    // 5. 应用 PropertyValues（XML 配置的 <property> 标签这里设置）
    if (pvs != null) {
        applyPropertyValues(beanName, mbd, bw, pvs);
    }
}
```

### 4.1 @Autowired 注入的底层实现

`AutowiredAnnotationBeanPostProcessor` 负责解析和执行 `@Autowired` 注入，核心逻辑在 `postProcessProperties()`：

```java
// AutowiredAnnotationBeanPostProcessor.java
@Override
public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
    // 获取缓存好的注入元数据（在 postProcessMergedBeanDefinition 中已解析）
    InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
    try {
        // 执行注入
        metadata.inject(bean, beanName, pvs);
    } catch (BeanCreationException ex) {
        throw ex;
    } catch (Throwable ex) {
        throw new BeanCreationException(...);
    }
    return pvs;
}
```

**`InjectionMetadata.inject()` 的注入过程：**

```java
// InjectionMetadata.java
public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) {
    Collection<InjectedElement> checkedElements = this.checkedElements;
    Collection<InjectedElement> elements = (checkedElements != null ? checkedElements : this.injectedElements);
    
    if (!elements.isEmpty()) {
        for (InjectedElement element : elements) {
            // 逐个处理每个注入点
            element.inject(target, beanName, pvs);
        }
    }
}
```

**字段注入的具体执行：**

```java
// AutowiredAnnotationBeanPostProcessor.java 的内部类 AutowiredFieldElement
@Override
protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) {
    Field field = (Field) this.member;
    Object value;
    
    if (this.cached) {
        // 缓存命中（单例 Bean 第二次及以后直接使用缓存值）
        value = resolvedCachedArgument(beanName, this.cachedFieldValue);
    } else {
        // 解析依赖：通过类型/名称查找 Bean
        DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
        desc.setContainingClass(bean.getClass());
        Set<String> autowiredBeanNames = new LinkedHashSet<>();
        
        // ★ 核心：调用 BeanFactory.resolveDependency()
        value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
    }
    
    if (value != null) {
        // 反射设置字段值
        ReflectionUtils.makeAccessible(field);
        field.set(bean, value);
    }
}
```

**`resolveDependency()` 的查找逻辑：**

```java
// DefaultListableBeanFactory.java
public Object resolveDependency(DependencyDescriptor descriptor, 
                                @Nullable String requestingBeanName,
                                @Nullable Set<String> autowiredBeanNames,
                                @Nullable TypeConverter typeConverter) {
    // 1. 处理 required=false
    descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());
    
    // 2. 如果是 Optional、@Lazy 等，特殊处理
    if (Optional.class == descriptor.getDependencyType()) { ... }
    if (ObjectFactory.class == descriptor.getDependencyType()) { ... }
    
    // 3. 按类型查找候选 Bean
    Map<String, Object> matchingBeans = findAutowireCandidates(beanName, descriptor.getDependencyType(), descriptor);
    
    // 4. 如果匹配多个，按 @Primary / @Priority / 名称 筛选
    if (matchingBeans.size() > 1) {
        // 根据 @Primary → 根据 @Priority → 根据字段名 决定最终注入哪个
        String primaryBeanName = determinePrimaryCandidate(matchingBeans, descriptor);
        if (primaryBeanName == null) {
            primaryBeanName = determineHighestPriorityCandidate(matchingBeans, descriptor);
        }
        if (primaryBeanName == null) {
            throw new NoUniqueBeanDefinitionException(...);
        }
    }
    
    // 5. 返回最终确定的对象引用（注意：这里返回的是引用不是新对象）
    return matchingBeans.get(beanName);
}
```

## 五、初始化阶段

属性填充完成后，进入初始化阶段：

```java
// AbstractAutowireCapableBeanFactory.java
protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
    // 1. 调用 Aware 接口
    invokeAwareMethods(beanName, bean);

    // 2. 初始化前处理（postProcessBeforeInitialization）
    Object wrappedBean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);

    // 3. 执行初始化方法（@PostConstruct → InitializingBean → init-method）
    invokeInitMethods(beanName, wrappedBean, mbd);

    // 4. 初始化后处理（postProcessAfterInitialization）
    wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);

    return wrappedBean;
}
```

### 5.1 Aware 接口回调

```java
// AbstractAutowireCapableBeanFactory.java
private void invokeAwareMethods(String beanName, Object bean) {
    if (bean instanceof Aware) {
        if (bean instanceof BeanNameAware) {
            ((BeanNameAware) bean).setBeanName(beanName);
        }
        if (bean instanceof BeanClassLoaderAware) {
            ((BeanClassLoaderAware) bean).setBeanClassLoader(getBeanClassLoader());
        }
        if (bean instanceof BeanFactoryAware) {
            ((BeanFactoryAware) bean).setBeanFactory(this);
        }
    }
}
```

> **注意：** `ApplicationContextAware` 在这里**不会**被调用，因为这里只有 `BeanFactory` 级别的 Aware。`ApplicationContextAware` 由 `ApplicationContextAwareProcessor` 在 `applyBeanPostProcessorsBeforeInitialization()` 中处理。

**所有 Aware 接口的执行顺序：**

| 顺序 | Aware 接口 | 处理方 |
|------|-----------|--------|
| 1 | `BeanNameAware` | `invokeAwareMethods()` |
| 2 | `BeanClassLoaderAware` | `invokeAwareMethods()` |
| 3 | `BeanFactoryAware` | `invokeAwareMethods()` |
| 4 | `EnvironmentAware` | `ApplicationContextAwareProcessor` |
| 5 | `EmbeddedValueResolverAware` | `ApplicationContextAwareProcessor` |
| 6 | `ResourceLoaderAware` | `ApplicationContextAwareProcessor` |
| 7 | `ApplicationEventPublisherAware` | `ApplicationContextAwareProcessor` |
| 8 | `MessageSourceAware` | `ApplicationContextAwareProcessor` |
| 9 | `ApplicationContextAware` | `ApplicationContextAwareProcessor` |

### 5.2 初始化前：BeanPostProcessor

```java
// AbstractAutowireCapableBeanFactory.java
public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName) {
    Object result = existingBean;
    for (BeanPostProcessor processor : getBeanPostProcessors()) {
        Object current = processor.postProcessBeforeInitialization(result, beanName);
        if (current == null) {
            return result;
        }
        result = current;
    }
    return result;
}
```

**常见的 BeforeInitialization 处理：**

| BeanPostProcessor | 功能 |
|------------------|------|
| `ApplicationContextAwareProcessor` | 注入 ApplicationContext、Environment 等 |
| `InitDestroyAnnotationBeanPostProcessor` | 扫描 @PostConstruct 方法（标记，尚未执行）|
| `CommonAnnotationBeanPostProcessor` | 处理 @Resource 注入 |

### 5.3 初始化方法

```java
// AbstractAutowireCapableBeanFactory.java
protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
    // 1. 检查是否实现了 InitializingBean
    boolean isInitializingBean = (bean instanceof InitializingBean);
    if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
        // 调用 afterPropertiesSet()
        ((InitializingBean) bean).afterPropertiesSet();
    }

    // 2. 调用自定义 init-method
    if (mbd != null && bean.getClass() != NullBean.class) {
        String initMethodName = mbd.getInitMethodName();
        if (initMethodName != null && !(isInitializingBean && "afterPropertiesSet".equals(initMethodName))
                && !mbd.isExternallyManagedInitMethod(initMethodName)) {
            // 通过反射调用 init-method
            invokeCustomInitMethod(beanName, bean, mbd);
        }
    }
}
```

**三种初始化方式的关系：**

```
执行顺序：@PostConstruct → InitializingBean.afterPropertiesSet() → @Bean(initMethod="xxx")

@PostConstruct 由 InitDestroyAnnotationBeanPostProcessor 在 
postProcessBeforeInitialization 中扫描并缓存，在 postProcessAfterInitialization 
中执行？不——实际上 @PostConstruct 不是通过 BeanPostProcessor 执行的。
```

实际上，`@PostConstruct` 的处理是由 `CommonAnnotationBeanPostProcessor`（它继承 `InitDestroyAnnotationBeanPostProcessor`）在初始化阶段处理的。让我纠正：

```java
// InitDestroyAnnotationBeanPostProcessor.java
// 这个 postProcessBeforeInitialization 会触发 @PostConstruct 的执行
public Object postProcessBeforeInitialization(Object bean, String beanName) {
    LifecycleMetadata metadata = findLifecycleMetadata(bean.getClass());
    metadata.invokeInitMethods(bean, beanName);
    return bean;
}
```

所以完整的顺序是：

```
初始化前（postProcessBeforeInitialization）
  └─ InitDestroyAnnotationBeanPostProcessor → 执行 @PostConstruct 方法

invokeInitMethods()
  ├─ InitializingBean.afterPropertiesSet()
  └─ 自定义 init-method
```

### 5.4 初始化后：BeanPostProcessor

```java
// AbstractAutowireCapableBeanFactory.java
public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName) {
    Object result = existingBean;
    for (BeanPostProcessor processor : getBeanPostProcessors()) {
        Object current = processor.postProcessAfterInitialization(result, beanName);
        if (current == null) {
            return result;
        }
        result = current;
    }
    return result;
}
```

**这个阶段最重要的事情：**

- **`AbstractAutoProxyCreator`（AOP）**在此处检查当前 Bean 是否需要 AOP 代理，如果需要，返回代理对象替换原对象

```java
// AbstractAutoProxyCreator.java (AOP 核心)
@Override
public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof AopInfrastructureBean) {
        return bean;
    }
    // 检查是否需要创建代理
    if (!this.earlyProxyReferences.contains(cacheKey)) {
        // 创建 AOP 代理（用 JDK 动态代理或 CGLib 包装 Bean）
        return wrapIfNecessary(bean, beanName, cacheKey);
    }
    return bean;
}
```

这就是为什么你通过 `ApplicationContext.getBean()` 拿到的对象可能是 **一个代理对象**，而不是原始对象。

## 六、销毁阶段

### 6.1 注册销毁回调

Spring 在创建完 Bean 后，会检查是否需要注册销毁回调：

```java
// AbstractAutowireCapableBeanFactory.java
protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
    // 只有 singleton 作用域需要注册（prototype 不注册）
    if (mbd.isSingleton() && scope == null) {
        if (bean instanceof DisposableBean || ...) {
            registerDisposableBean(beanName, new DisposableBeanAdapter(bean, beanName, mbd));
        }
    }
}
```

### 6.2 销毁执行顺序

```java
// DisposableBeanAdapter.destroy()
public void destroy() {
    // 1. @PreDestroy 方法
    //    （由 InitDestroyAnnotationBeanPostProcessor 扫描并收集）
    invokeLifecycleMethods(this.lifecycleMetadata, this.bean);
    
    // 2. DisposableBean.destroy()
    if (this.invokeDisposableBean) {
        ((DisposableBean) this.bean).destroy();
    }
    
    // 3. 自定义 destroy-method
    if (this.destroyMethod != null) {
        invokeCustomDestroyMethod(this.destroyMethod);
    }
}
```

```
执行顺序：@PreDestroy → DisposableBean.destroy() → @Bean(destroyMethod="xxx")
```

## 七、完整生命周期总结

```
start
  │
  ▼
instantiate ────────────────────────────────────────── createBeanInstance()
  │  实例化: new Instance() / CGLib / FactoryMethod
  │
  ▼
postProcessMergedBeanDefinition ────────────────────── applyMergedBeanDefinitionPostProcessors()
  │  解析 @Autowired 注入点、@PostConstruct 等元数据并缓存
  │
  ▼
addSingletonFactory ────────────────────────────────── addSingletonFactory()
  │  提前暴露（循环依赖三级缓存）
  │
  ▼
populateBean ───────────────────────────────────────── populateBean()
  │  属性填充：@Autowired / @Resource / @Value / XML property
  │
  ▼
invokeAwareMethods ─────────────────────────────────── invokeAwareMethods()
  │  BeanNameAware / BeanClassLoaderAware / BeanFactoryAware
  │
  ▼
postProcessBeforeInitialization ────────────────────── applyBeanPostProcessorsBeforeInitialization()
  │  ApplicationContextAwareProcessor → ApplicationContextAware 等
  │  InitDestroyAnnotationBeanPostProcessor → @PostConstruct
  │
  ▼
invokeInitMethods ──────────────────────────────────── invokeInitMethods()
  │  InitializingBean.afterPropertiesSet()
  │  init-method
  │
  ▼
postProcessAfterInitialization ─────────────────────── applyBeanPostProcessorsAfterInitialization()
  │  ★ AbstractAutoProxyCreator → AOP 代理（此处生成代理对象）
  │
  ▼
Bean 就绪 ──────────────────────────────────────────── 放入 singletonObjects 缓存
  │
  ▼
  容器关闭
  │
  ▼
postProcessBeforeDestruction ───────────────────────── DestructionAwareBeanPostProcessor
  │  @PreDestroy
  │
  ▼
  destroy()
     DisposableBean.destroy()
     destroy-method
```

### 关键扩展点速查

| 阶段 | 扩展接口 | 典型实现 |
|------|---------|---------|
| 实例化前 | `InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation` | AOP 代理、MyBatis Mapper 代理 |
| 实例化 | `SmartInstantiationAwareBeanPostProcessor.determineCandidateConstructors` | @Autowired 构造器推断 |
| 元数据 | `MergedBeanDefinitionPostProcessor.postProcessMergedBeanDefinition` | @Autowired 注入点收集 |
| 属性填充 | `InstantiationAwareBeanPostProcessor.postProcessProperties` | @Autowired 注入执行 |
| 初始化前 | `BeanPostProcessor.postProcessBeforeInitialization` | @PostConstruct、Aware 注入 |
| 初始化后 | `BeanPostProcessor.postProcessAfterInitialization` | AOP 代理生成 |
| 销毁前 | `DestructionAwareBeanPostProcessor.postProcessBeforeDestruction` | @PreDestroy |

---

**上一篇：** [Spring DI（一）：IoC 容器初始化与 BeanFactory 体系]({{< relref "post/spring-di-ioc-container" >}})

**下一篇：** [Spring DI（三）：依赖注入、循环依赖与作用域]({{< relref "post/spring-di-injection-scope" >}})
