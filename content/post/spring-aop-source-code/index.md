---
title: "Spring AOP（二）：AOP 源码深度解析"
date: 2018-05-10
draft: false
categories: ["Java"]
tags: ["Spring", "AOP", "源码分析", "AbstractAutoProxyCreator", "Advisor", "ProxyFactory", "通知链", "JdkDynamicAopProxy"]
toc: true
---

## 前言

上一篇我们看了 AOP 的使用。这篇文章深入源码，从 `@EnableAspectJAutoProxy` 开始，完整追踪**一个 `@Aspect` 切面是如何被解析**、**如何代理目标 Bean**、**以及方法调用时通知链如何执行的**全过程。

<!--more-->

> **源码版本：** Spring Framework 5.x

## 一、启用 AOP 入口

### 1.1 @EnableAspectJAutoProxy

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(AspectJAutoProxyRegistrar.class)  // 核心：导入 Registrar
public @interface EnableAspectJAutoProxy {
    boolean proxyTargetClass() default false;
    boolean exposeProxy() default false;
}
```

`AspectJAutoProxyRegistrar` 是一个 `ImportBeanDefinitionRegistrar`，它在容器启动时注册一个关键的 Bean 后处理器：

```java
// AspectJAutoProxyRegistrar.java
class AspectJAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {
    
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, 
                                         BeanDefinitionRegistry registry) {
        // ★ 注册 AnnotationAwareAspectJAutoProxyCreator
        AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry);
        
        // 处理 proxyTargetClass 和 exposeProxy 属性
        AnnotationAttributes enableAspectJAutoProxy = 
            AnnotationConfigUtils.attributesFor(importingClassMetadata, EnableAspectJAutoProxy.class);
        if (enableAspectJAutoProxy != null) {
            if (enableAspectJAutoProxy.getBoolean("proxyTargetClass")) {
                AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
            }
            if (enableAspectJAutoProxy.getBoolean("exposeProxy")) {
                AopConfigUtils.forceAutoProxyCreatorToExposeProxy(registry);
            }
        }
    }
}
```

**注册的核心后处理器：**

```
AnnotationAwareAspectJAutoProxyCreator
  └── AspectJAwareAdvisorAutoProxyCreator
        └── AbstractAdvisorAutoProxyCreator
              └── AbstractAutoProxyCreator        ← 核心代理创建
                    └── SmartInstantiationAwareBeanPostProcessor
                    └── BeanFactoryAware
```

`AnnotationAwareAspectJAutoProxyCreator` 是整个 AOP 的**入口**——它是一个 `BeanPostProcessor`，在 Bean 初始化完成后介入，判断是否需要创建代理。

### 1.2 完整的类层次

```
AbstractAutoProxyCreator（模板方法，定义代理创建流程）
  │
  └── AbstractAdvisorAutoProxyCreator
        │  + 获取所有 Advisor（通知器）
        │  + 筛适用于当前 Bean 的 Advisor
        │
        └── AspectJAwareAdvisorAutoProxyCreator
              │  + 对 aspectJ 切面排序
              │
              └── AnnotationAwareAspectJAutoProxyCreator
                    │  + 额外扫描 @Aspect 注解的 Bean
                    │  + 将其解析为 Advisor
```

## 二、代理创建流程

### 2.1 AbstractAutoProxyCreator 的核心逻辑

`AbstractAutoProxyCreator` 作为 `BeanPostProcessor`，在 Bean 初始化完成后（`postProcessAfterInitialization`）介入：

```java
// AbstractAutoProxyCreator.java
@Override
public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof AopInfrastructureBean) {
        return bean;
    }
    
    // 如果已经被提前代理（循环依赖场景），跳过
    if (!this.earlyProxyReferences.contains(cacheKey)) {
        // ★ 核心：包装为代理对象
        return wrapIfNecessary(bean, beanName, cacheKey);
    }
    return bean;
}
```

**`wrapIfNecessary()` 的完整逻辑：**

```java
// AbstractAutoProxyCreator.java
protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
    // 1. 检查是否已经处理过（避免重复代理）
    if (StringUtils.hasLength(beanName) && this.targetSourcedBeans.contains(beanName)) {
        return bean;
    }
    
    // 2. 检查是否特殊的 Infrastructure Bean（不代理）
    if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
        return bean;
    }
    
    // 3. 判断当前 Bean 是否需要被代理
    //    - 排除 AOP 框架自身的类
    //    - 对 @Aspect 类不创建代理（它们是切面定义，不是目标）
    if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName)) {
        this.advisedBeans.put(cacheKey, Boolean.FALSE);
        return bean;
    }

    // 4. ★ 获取适用于当前 Bean 的 Advisor（通知器）
    Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
    
    if (specificInterceptors != DO_NOT_PROXY) {
        // 5. 标记该 Bean 需要代理
        this.advisedBeans.put(cacheKey, Boolean.TRUE);
        // 6. ★ 创建代理
        Object proxy = createProxy(bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
        this.proxyTypes.put(cacheKey, proxy.getClass());
        return proxy;
    }
    
    this.advisedBeans.put(cacheKey, Boolean.FALSE);
    return bean;
}
```

### 2.2 获取适用的 Advisor

```java
// AbstractAdvisorAutoProxyCreator.java
@Override
protected Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName, 
                                                 TargetSource targetSource) {
    // 1. 查找所有可用的 Advisor（包括 @Aspect 解析出来的）
    List<Advisor> candidateAdvisors = findCandidateAdvisors();
    
    // 2. 筛选出适用于当前 Bean 的 Advisor
    List<Advisor> eligibleAdvisors = findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName);
    
    // 3. 特殊处理：引入（Introduction）通知
    extendAdvisors(eligibleAdvisors);
    
    if (!eligibleAdvisors.isEmpty()) {
        // 4. 排序（@Order 和 @Priority）
        eligibleAdvisors = sortAdvisors(eligibleAdvisors);
        return eligibleAdvisors.toArray();
    }
    
    return DO_NOT_PROXY;  // 没有适用的 Advisor → 不代理
}
```

**`findAdvisorsThatCanApply()` 的过滤逻辑：**

```java
// AopUtils.java
public static boolean canApply(Pointcut pc, Class<?> targetClass, boolean hasIntroductions) {
    // 1. 先判断 ClassFilter 是否匹配（类级别）
    if (!pc.getClassFilter().matches(targetClass)) {
        return false;
    }
    
    // 2. 再判断 MethodMatcher 是否匹配（方法级别）
    MethodMatcher methodMatcher = pc.getMethodMatcher();
    
    // 如果是 IntroductionAwareMethodMatcher，特殊处理
    // ...
    
    // 3. 获取目标类及接口的所有方法
    Set<Class<?>> classes = new LinkedHashSet<>();
    classes.addAll(ClassUtils.getAllInterfacesForClassAsSet(targetClass));
    classes.add(targetClass);
    
    // 4. 逐个方法检查
    for (Class<?> clazz : classes) {
        Method[] methods = ReflectionUtils.getAllDeclaredMethods(clazz);
        for (Method method : methods) {
            // execution 表达式在这里匹配！
            if (methodMatcher.matches(method, targetClass)) {
                return true;  // 任何一个方法匹配 → 需要代理
            }
        }
    }
    
    return false;
}
```

### 2.3 创建代理

```java
// AbstractAutoProxyCreator.java
protected Object createProxy(Class<?> beanClass, String beanName,
                             Object[] specificInterceptors, TargetSource targetSource) {
    
    // 1. 创建 ProxyFactory（AOP 代理的核心工厂）
    ProxyFactory proxyFactory = new ProxyFactory();
    proxyFactory.copyFrom(this);
    
    // 2. 根据 proxyTargetClass 决定代理方式
    if (!proxyFactory.isProxyTargetClass()) {
        if (shouldProxyTargetClass(beanClass, beanName)) {
            proxyFactory.setProxyTargetClass(true);
        } else {
            // 判断是否实现了接口
            evaluateProxyInterfaces(beanClass, proxyFactory);
        }
    }

    // 3. 将 Advisor 添加到 ProxyFactory
    Advisor[] advisors = buildAdvisors(beanName, specificInterceptors);
    proxyFactory.addAdvisors(advisors);
    
    // 4. 设置 TargetSource
    proxyFactory.setTargetSource(targetSource);
    
    // 5. ★ 创建代理对象（JDK 或 CGLib）
    return proxyFactory.getProxy(getProxyClassLoader());
}
```

**`evaluateProxyInterfaces()` 决定代理方式：**

```java
// ProxyFactory 内部逻辑
// 如果目标类实现了至少一个接口 → JDK 动态代理
// 如果目标类没有实现任何接口 → CGLib 代理
// 如果 proxyTargetClass=true → 强制 CGLib 代理
```

**`proxyFactory.getProxy()` 的最终调用：**

```java
// ProxyCreatorSupport.java
public Object getProxy(ClassLoader classLoader) {
    // 创建 AopProxy 并获取代理对象
    return createAopProxy().getProxy(classLoader);
}

// DefaultAopProxyFactory.java — 决定使用 JDK 还是 CGLib
public AopProxy createAopProxy(AdvisedSupport config) {
    if (config.isOptimize() || config.isProxyTargetClass() 
            || hasNoUserSuppliedProxyInterfaces(config)) {
        Class<?> targetClass = config.getTargetClass();
        if (targetClass.isInterface()) {
            return new JdkDynamicAopProxy(config);
        }
        // 使用 CGLib
        return new ObjenesisCglibAopProxy(config);
    } else {
        return new JdkDynamicAopProxy(config);  // JDK 动态代理
    }
}
```

## 三、@Aspect 切面的解析

### 3.1 AnnotationAwareAspectJAutoProxyCreator 的特殊能力

上面 `findCandidateAdvisors()` 在 `AbstractAdvisorAutoProxyCreator` 中会收集所有 `Advisor`，而 `AnnotationAwareAspectJAutoProxyCreator` 重写了该方法，额外从 `@Aspect` 类中解析：

```java
// AnnotationAwareAspectJAutoProxyCreator.java
@Override
protected List<Advisor> findCandidateAdvisors() {
    // 1. 调用父类：从注册的 Advisor Bean 中获取
    List<Advisor> advisors = super.findCandidateAdvisors();
    
    // 2. ★ 额外从 @Aspect 类中解析 Advisor
    if (this.aspectJAdvisorsBuilder != null) {
        advisors.addAll(this.aspectJAdvisorsBuilder.buildAspectJAdvisors());
    }
    
    return advisors;
}
```

### 3.2 @Aspect 的解析——buildAspectJAdvisors()

```java
// BeanFactoryAspectJAdvisorsBuilder.java
public List<Advisor> buildAspectJAdvisors() {
    List<String> aspectNames = this.aspectBeanNames;
    
    if (aspectNames == null) {
        synchronized (this) {
            aspectNames = new ArrayList<>();
            
            // 1. 从 BeanFactory 中获取所有 Bean 名称
            String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                    this.beanFactory, Object.class, true, false);
            
            for (String beanName : beanNames) {
                // 2. 检查是否标注了 @Aspect 注解
                if (this.advisorFactory.isAspect(beanClass)) {
                    aspectNames.add(beanName);
                    
                    // 3. ★ 获取 @Aspect 类的元数据
                    AspectMetadata am = new AspectMetadata(beanClass, beanName);
                    
                    // 4. 解析 @Aspect 类中的所有通知方法
                    List<Advisor> classAdvisors = this.advisorFactory.getAdvisors(am);
                    
                    // 5. 缓存到 metadataCache
                    aspectBeans.put(beanName, beanClass);
                    advisorsCache.put(beanName, classAdvisors);
                }
            }
            
            this.aspectBeanNames = aspectNames;
        }
    }
    
    // 返回所有缓存的 Advisor
    // ...
}
```

### 3.3 将通知方法转换为 Advisor

`ReflectiveAspectJAdvisorFactory.getAdvisors()` 完成从 `@Aspect` 方法到 Spring `Advisor` 的转换：

```java
// ReflectiveAspectJAdvisorFactory.java
@Override
public List<Advisor> getAdvisors(AspectMetadata am) {
    List<Advisor> advisors = new ArrayList<>();
    Class<?> aspectClass = am.getAspectClass();
    
    // 遍历 @Aspect 类的所有方法
    for (Method method : getAdvisorMethods(aspectClass)) {
        // ★ 每个 @Before/@Around/@After 方法 → 一个 Advisor
        Advisor advisor = getAdvisor(method, am.getAspectInstanceFactory(), 
                                     advisors.size(), am.getDeclarationOrder());
        if (advisor != null) {
            advisors.add(advisor);
        }
    }
    
    // 处理 Introduction（@DeclareParents）——略
    return advisors;
}

@Override
public Advisor getAdvisor(Method candidateAdviceMethod, 
                           AspectInstanceFactory aif,
                           int declarationOrderInAspect,
                           String aspectName) {
    // 1. 验证方法上的通知注解（@Before、@Around 等）
    AspectJAnnotation<?> aspectJAnnotation = 
            AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
    if (aspectJAnnotation == null) {
        return null;
    }
    
    // 2. 解析 @Pointcut 表达式，创建切点
    Pointcut pointcut = getPointcut(aspectJAnnotation, candidateAdviceMethod, aif);
    
    // 3. 创建 InstantiationModelAwarePointcutAdvisorImpl
    return new InstantiationModelAwarePointcutAdvisorImpl(
            this, aif, candidateAdviceMethod, pointcut, aspectName, 
            declarationOrderInAspect, aspectJAnnotation.getAnnotationType());
}
```

## 四、代理方法的调用

### 4.1 JDK 动态代理的调用

当代理对象的方法被调用时，进入 `JdkDynamicAopProxy.invoke()`：

```java
// JdkDynamicAopProxy.java
@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Object oldProxy = null;
    boolean setProxyContext = false;
    
    TargetSource targetSource = this.advised.targetSource;
    Object target = null;
    
    try {
        // 1. 处理 equals/hashCode 特殊方法
        if (!this.equalsDefined && AopUtils.isEqualsMethod(method)) { ... }
        if (!this.hashCodeDefined && AopUtils.isHashCodeMethod(method)) { ... }
        
        // 2. 处理 DecoratingProxy / Advised 接口方法
        if (method.getDeclaringClass() == DecoratingProxy.class) { ... }
        
        // 3. 处理 exposeProxy 设置
        if (this.advised.exposeProxy) {
            oldProxy = AopContext.setCurrentProxy(proxy);
            setProxyContext = true;
        }

        // 4. 获取目标对象
        target = targetSource.getTarget();
        Class<?> targetClass = (target != null ? target.getClass() : null);
        
        // 5. ★ 获取拦截器链（通知链）
        List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
        
        // 6. 执行调用
        if (chain.isEmpty()) {
            // 没有通知 → 直接反射调用目标方法
            Object[] argsToUse = AopProxyUtils.adaptArgumentsIfNecessary(method, args);
            ret = method.invoke(target, argsToUse);
        } else {
            // ★ 有通知 → 创建 MethodInvocation，执行拦截链
            MethodInvocation invocation = new ReflectiveMethodInvocation(
                    proxy, target, method, args, targetClass, chain);
            ret = invocation.proceed();  // 执行通知链
        }
        
        return ret;
    } finally {
        if (target != null && !targetSource.isStatic()) {
            targetSource.releaseTarget(target);
        }
        if (setProxyContext) {
            AopContext.setCurrentProxy(oldProxy);
        }
    }
}
```

### 4.2 通知链的获取

```java
// AdvisedSupport.java
public List<Object> getInterceptorsAndDynamicInterceptionAdvice(Method method, Class<?> targetClass) {
    // 1. 构造缓存的 key（方法 + 目标类）
    MethodCacheKey cacheKey = new MethodCacheKey(method);
    
    // 2. 尝试从缓存获取
    List<Object> cached = this.methodCache.get(cacheKey);
    if (cached == null) {
        // 3. 从所有注册的 Advisor 中筛选
        MethodInterceptor[] interceptors = 
            this.advisorChainFactory.getInterceptorsAndDynamicInterceptionAdvice(
                    this, method, targetClass);
        this.methodCache.put(cacheKey, cached);
    }
    return cached;
}
```

**`advisorChainFactory.getInterceptorsAndDynamicInterceptionAdvice()` 内部：**

```java
// DefaultAdvisorChainFactory.java
public List<Object> getInterceptorsAndDynamicInterceptionAdvice(
        Advised config, Method method, Class<?> targetClass) {
    
    List<Object> interceptorList = new ArrayList<>(config.getAdvisors().length);
    Class<?> actualClass = (targetClass != null ? targetClass : method.getDeclaringClass());
    
    // 遍历所有 Advisor，检查是否适用于当前方法
    for (Advisor advisor : config.getAdvisors()) {
        if (advisor instanceof PointcutAdvisor) {
            PointcutAdvisor pointcutAdvisor = (PointcutAdvisor) advisor;
            // ★ Pointcut 匹配检查
            if (pointcutAdvisor.getPointcut().getClassFilter().matches(actualClass)
                    && pointcutAdvisor.getPointcut().getMethodMatcher().matches(method, actualClass)) {
                // 将 Advisor 转换为 MethodInterceptor
                MethodInterceptor interceptor = (MethodInterceptor) advisor.getAdvice();
                interceptorList.add(interceptor);
            }
        } else if (advisor instanceof IntroductionAdvisor) {
            // 引入通知处理
        } else {
            // 默认添加到链中
            interceptorList.add(advisor.getAdvice());
        }
    }
    
    return interceptorList;
}
```

### 4.3 通知链的执行——ReflectiveMethodInvocation

```java
// ReflectiveMethodInvocation.java
public class ReflectiveMethodInvocation implements ProxyMethodInvocation {
    
    protected final Object proxy;        // 代理对象
    protected final Object target;       // 目标对象
    protected final Method method;       // 目标方法
    protected Object[] arguments;        // 方法参数
    private final Class<?> targetClass;  // 目标类
    private List<Object> interceptors;   // 拦截器链
    private int currentInterceptorIndex = -1;  // 当前执行到的位置
    
    @Override
    public Object proceed() throws Throwable {
        // 所有拦截器执行完毕 → 调用目标方法
        if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
            return invokeJoinpoint();  // 反射调用目标方法
        }
        
        // 取下个拦截器
        Object interceptor = this.interceptors.get(++this.currentInterceptorIndex);
        
        if (interceptor instanceof MethodInterceptor) {
            // ★ 调用拦截器的 invoke 方法
            return ((MethodInterceptor) interceptor).invoke(this);
        } else {
            // 动态拦截（DynamicMethodMatcherInterceptor）
            return ((InterceptorAndDynamicMethodMatcher) interceptor)
                    .getInterceptor().invoke(this);
        }
    }
    
    // 调用目标方法
    protected Object invokeJoinpoint() throws Throwable {
        return AopUtils.invokeJoinpointUsingReflection(this.target, this.method, this.arguments);
    }
}
```

**关键设计——责任链模式：**

```
proceed() 执行流程示意图：

MethodInterceptor[0].invoke(invocation)  ← @Around（最外层）
  └── 自定义前置逻辑
        └── invocation.proceed()
              └── MethodInterceptor[1].invoke(invocation)  ← @Before
                    └── invocation.proceed()
                          └── MethodInterceptor[2].invoke(invocation)  ← @After
                                └── invocation.proceed()
                                      └── invokeJoinpoint() → 目标方法执行
                                ← @After 后置逻辑
                    ← @Before 后置逻辑（无）
              ← @Around 后置逻辑
```

### 4.4 通知方法适配为 MethodInterceptor

Spring 的每种通知类型都对应一个 `MethodInterceptor` 实现：

```java
// @Around → AspectJAroundAdvice（直接实现 MethodInterceptor）
public class AspectJAroundAdvice extends AbstractAspectJAdvice implements MethodInterceptor {
    @Override
    public Object invoke(MethodInvocation mi) throws Throwable {
        // 直接调用 @Around 方法，传入的 ProceedingJoinPoint 包装了 mi
        return invokeAdviceMethod(getJoinPointMatch(), null, null);
    }
}

// @Before → AspectJMethodBeforeAdvice（适配为 MethodBeforeAdviceInterceptor）
public class MethodBeforeAdviceInterceptor implements MethodInterceptor {
    @Override
    public Object invoke(MethodInvocation mi) throws Throwable {
        this.advice.before(mi.getMethod(), mi.getArguments(), mi.getThis());  // 先执行 @Before
        return mi.proceed();  // 继续执行
    }
}

// @After → AspectJAfterAdvice
public class AspectJAfterAdvice extends AbstractAspectJAdvice implements MethodInterceptor {
    @Override
    public Object invoke(MethodInvocation mi) throws Throwable {
        try {
            return mi.proceed();  // 先执行目标
        } finally {
            invokeAdviceMethod(getJoinPointMatch(), null, null);  // finally 中执行 @After
        }
    }
}

// @AfterReturning → AspectJAfterReturningAdvice
public class AfterReturningAdviceInterceptor implements MethodInterceptor {
    @Override
    public Object invoke(MethodInvocation mi) throws Throwable {
        Object retVal = mi.proceed();  // 执行目标
        this.advice.afterReturning(retVal, mi.getMethod(), mi.getArguments(), mi.getThis());  // 返回后执行
        return retVal;
    }
}

// @AfterThrowing → AspectJAfterThrowingAdvice
public class AspectJAfterThrowingAdvice extends AbstractAspectJAdvice implements MethodInterceptor {
    @Override
    public Object invoke(MethodInvocation mi) throws Throwable {
        try {
            return mi.proceed();  // 执行目标
        } catch (Throwable ex) {
            // 匹配异常类型后执行
            invokeAdviceMethod(getJoinPointMatch(), null, ex);
            throw ex;
        }
    }
}
```

## 五、完整调用链路回顾

```
【1. 容器启动时】
@EnableAspectJAutoProxy
  → AspectJAutoProxyRegistrar.registerBeanDefinitions()
  → 注册 AnnotationAwareAspectJAutoProxyCreator
    │
    ▼
【2. @Aspect 类的解析（postProcessBeforeInstantiation 时）】
BeanFactoryAspectJAdvisorsBuilder.buildAspectJAdvisors()
  → 扫描所有 @Aspect 标注的 Bean
  → ReflectiveAspectJAdvisorFactory.getAdvisors()
    → 对每个 @Before/@Around/@After 方法：
      ① 解析通知注解
      ② 解析 @Pointcut 表达式
      ③ 创建 InstantiationModelAwarePointcutAdvisorImpl
    → 缓存所有 Advisor
    │
    ▼
【3. 目标 Bean 初始化完成后】
AbstractAutoProxyCreator.postProcessAfterInitialization()
  → wrapIfNecessary()
    → findEligibleAdvisors()
      → findCandidateAdvisors() [获取所有 Advisor]
      → findAdvisorsThatCanApply() [Pointcut 匹配]
    → createProxy()
      → ProxyFactory.getProxy()
        → JdkDynamicAopProxy / CglibAopProxy
    │
    ▼
【4. 代理方法被调用】
JdkDynamicAopProxy.invoke()
  → getInterceptorsAndDynamicInterceptionAdvice()
    → 根据方法匹配所有适用的 Advisor
    → 转换为 MethodInterceptor 列表
  → new ReflectiveMethodInvocation(proxy, target, method, args, chain)
  → invocation.proceed()
    → 依次按顺序执行 MethodInterceptor
    → 最后 invokeJoinpoint() 调用目标方法
```

## 六、总结

### 核心类源码速查

| 类 | 关键方法 | 作用 |
|---|---------|------|
| `AnnotationAwareAspectJAutoProxyCreator` | `findCandidateAdvisors()` | 从 @Aspect 解析 Advisor |
| `AbstractAutoProxyCreator` | `postProcessAfterInitialization()` | 在初始化后创建代理 |
| `AbstractAutoProxyCreator` | `wrapIfNecessary()` | 判断是否需要代理并创建 |
| `AbstractAdvisorAutoProxyCreator` | `getAdvicesAndAdvisorsForBean()` | 获取适用于 Bean 的 Advisor |
| `DefaultAopProxyFactory` | `createAopProxy()` | 决定 JDK 还是 CGLib |
| `JdkDynamicAopProxy` | `invoke()` | JDK 代理的调用入口 |
| `ReflectiveMethodInvocation` | `proceed()` | 执行通知链 |
| `MethodBeforeAdviceInterceptor` | `invoke()` | @Before 的实现 |
| `AspectJAroundAdvice` | `invoke()` | @Around 的实现 |

### AOP 执行流程一句话

> **代理对象接收方法调用 → 构建拦截器链（Advisor→MethodInterceptor）→ 责任链依次执行通知 → 最后调用目标方法。**

---

**上一篇：** [Spring AOP（一）：AOP 概念与五种通知类型]({{< relref "post/spring-aop-usage" >}})

**下一篇：** [Spring AOP（三）：JDK 代理 vs CGLib 与高级主题]({{< relref "post/spring-aop-proxy" >}})
