---
title: "Spring IoC（二）：配置方式的底层解析原理"
date: 2018-05-04
draft: false
categories: ["Java"]
tags: ["Spring", "IoC", "源码分析", "BeanDefinition", "XML解析", "注解扫描", "Java Config"]
toc: true
---

## 前言

上一篇文章从使用层面对比了三种配置方式。这篇文章深入底层，看每种配置方式是如何被解析为 `BeanDefinition` 的。

无论你用 XML、注解还是 Java Config，最终在 `DefaultListableBeanFactory.beanDefinitionMap` 中存储的都是同一个东西——**BeanDefinition**。区别只在于"从哪里读"和"怎么解析"。

<!--more-->

> **源码版本：** Spring Framework 5.x

## 一、整体架构

三种配置方式的解析路径：

```
┌─────────────────────────────────────────────────────────────────┐
│                      BeanDefinitionRegistry                      │
│              (DefaultListableBeanFactory.beanDefinitionMap)      │
│                    ┌─────── BeanDefinition ──────┐               │
│                    │ beanClass, scope, properties │               │
│                    │ initMethod, dependsOn ...    │               │
│                    └─────────────────────────────┘               │
└─────────────────────────────────────────────────────────────────┘
          ▲                    ▲                      ▲
          │                    │                      │
   ┌──────┴──────┐    ┌───────┴────────┐    ┌────────┴────────┐
   │ XML         │    │ 注解扫描        │    │ Java Config     │
   │             │    │               │    │                 │
   │ XmlBeanDefi │    │ ClassPathBean │    │ Configuration-  │
   │ nitionReader│    │ Definition    │    │ ClassPostProces-│
   │             │    │ Scanner       │    │ sor             │
   └─────────────┘    └───────────────┘    └─────────────────┘
```

## 二、XML 配置解析原理

### 2.1 入口：XmlBeanDefinitionReader

```java
// 从 XML 启动容器
ClassPathXmlApplicationContext context = 
    new ClassPathXmlApplicationContext("applicationContext.xml");
```

```java
// AbstractXmlApplicationContext — 加载 XML 配置
@Override
protected void loadBeanDefinitions(DefaultListableBeanFactory beanFactory) {
    // 1. 创建 XmlBeanDefinitionReader
    XmlBeanDefinitionReader beanDefinitionReader = new XmlBeanDefinitionReader(beanFactory);
    // 2. 加载配置
    loadBeanDefinitions(beanDefinitionReader);
}

protected void loadBeanDefinitions(XmlBeanDefinitionReader reader) {
    // 根据配置路径加载 XML 资源
    Resource[] configResources = getConfigResources();
    if (configResources != null) {
        reader.loadBeanDefinitions(configResources);  // 核心入口
    }
}
```

### 2.2 XmlBeanDefinitionReader 的解析链路

```java
// XmlBeanDefinitionReader.java
public int loadBeanDefinitions(Resource resource) {
    // 1. 将 Resource 封装为 EncodedResource（处理编码）
    EncodedResource encodedResource = new EncodedResource(resource);
    // 2. 获取 XML 文件的 InputStream
    try (InputStream inputStream = encodedResource.getResource().getInputStream()) {
        // 3. 封装为 InputSource（SAX 解析标准）
        InputSource inputSource = new InputSource(inputStream);
        // 4. 核心解析
        return doLoadBeanDefinitions(inputSource, encodedResource);
    }
}

protected int doLoadBeanDefinitions(InputSource inputSource, Resource resource) {
    // 1. 解析 XML 为 Document 对象
    Document doc = doLoadDocument(inputSource, resource);
    // 2. 注册 BeanDefinition
    return registerBeanDefinitions(doc, resource);
}

public int registerBeanDefinitions(Document doc, Resource resource) {
    // 创建 BeanDefinitionDocumentReader（默认实现：DefaultBeanDefinitionDocumentReader）
    BeanDefinitionDocumentReader documentReader = createBeanDefinitionDocumentReader();
    // 获取已注册的 BeanDefinition 数量
    int countBefore = getRegistry().getBeanDefinitionCount();
    // ★ 核心：解析 Document 并注册
    documentReader.registerBeanDefinitions(doc, createReaderContext(resource));
    return getRegistry().getBeanDefinitionCount() - countBefore;
}
```

### 2.3 DefaultBeanDefinitionDocumentReader 的解析

```java
// DefaultBeanDefinitionDocumentReader.java
@Override
public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
    this.readerContext = readerContext;
    // 获取 <beans> 根元素
    Element root = doc.getDocumentElement();
    // 递归解析
    doRegisterBeanDefinitions(root);
}

protected void doRegisterBeanDefinitions(Element root) {
    // 1. 处理 profile 属性
    String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
    if (StringUtils.hasText(profileSpec)) {
        String[] specifiedProfiles = StringUtils.tokenizeToStringArray(profileSpec, ",; ");
        if (!this.readerContext.getEnvironment().acceptsProfiles(specifiedProfiles)) {
            return;  // 不匹配当前 profile，跳过
        }
    }

    // 2. 解析前置和后置处理
    BeanDefinitionParserDelegate parent = this.delegate;
    this.delegate = createHelper(this.readerContext, root);
    
    // 3. 处理 <beans> 标签自身的属性
    preProcessXml(root);
    // 4. ★ 核心：解析 <bean> 子元素
    parseBeanDefinitions(root, this.delegate);
    postProcessXml(root);
    
    this.delegate = parent;
}
```

### 2.4 逐个解析 <bean> 元素

```java
// DefaultBeanDefinitionDocumentReader.java
protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
    // 遍历所有子节点
    NodeList nl = root.getChildNodes();
    for (int i = 0; i < nl.getLength(); i++) {
        Node node = nl.item(i);
        if (node instanceof Element) {
            Element ele = (Element) node;
            if (delegate.isDefaultNamespace(ele)) {
                // ★ 标准元素：<bean> <import> <alias>
                parseDefaultElement(ele, delegate);
            } else {
                // 自定义元素：<context:component-scan> <aop:aspectj-autoproxy>
                delegate.parseCustomElement(ele);
            }
        }
    }
}

private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
    if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
        // <import resource="..." />
        importBeanDefinitionResource(ele);
    } else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
        // <alias name="..." alias="..." />
        processAliasRegistration(ele);
    } else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
        // ★ <bean id="..." class="..." />
        processBeanDefinition(ele, delegate);
    } else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
        // 嵌套 <beans>
        doRegisterBeanDefinitions(ele);
    }
}
```

### 2.5 BeanDefinitionParserDelegate 解析具体 <bean> 标签

```java
// BeanDefinitionParserDelegate.java
public BeanDefinitionHolder parseBeanDefinitionElement(Element ele, BeanDefinition containingBean) {
    // 1. 提取 id 和 name 属性
    String id = ele.getAttribute(ID_ATTRIBUTE);
    String nameAttr = ele.getAttribute(NAME_ATTRIBUTE);
    
    // 2. 提取 class 属性
    String className = null;
    if (ele.hasAttribute(CLASS_ATTRIBUTE)) {
        className = ele.getAttribute(CLASS_ATTRIBUTE).trim();
    }
    
    // 3. 解析 parent 属性
    String parent = null;
    if (ele.hasAttribute(PARENT_ATTRIBUTE)) {
        parent = ele.getAttribute(PARENT_ATTRIBUTE);
    }

    try {
        // 4. 创建 BeanDefinition（GenericBeanDefinition）
        AbstractBeanDefinition bd = createBeanDefinition(className, parent);
        
        // 5. ★ 解析各种子元素和属性
        //    解析 scope、lazy-init、abstract、autowire、depends-on、primary
        parseBeanDefinitionAttributes(ele, beanName, containingBean, bd);
        //    解析 <description>
        parseDescriptionElement(ele, bd);
        //    解析 <meta>
        parseMetaElements(ele, bd);
        //    解析 <lookup-method>
        parseLookupOverrideSubElements(ele, bd);
        //    解析 <replaced-method>
        parseReplacedMethodSubElements(ele, bd);
        //    解析 <constructor-arg>
        parseConstructorArgElements(ele, bd);
        //    解析 <property>
        parsePropertyElements(ele, bd);
        //    解析 <qualifier>
        parseQualifierElements(ele, bd);

        return new BeanDefinitionHolder(bd, beanName, aliasesArray);
    } catch (ClassNotFoundException ex) {
        throw new BeanDefinitionStoreException(...);
    }
}
```

### 2.6 <property> 子元素的解析

```java
// BeanDefinitionParserDelegate.java
public void parsePropertyElement(Element ele, BeanDefinition bd) {
    // 1. 提取 name 和 value/ref
    String propertyName = ele.getAttribute(NAME_ATTRIBUTE);
    
    if (StringUtils.hasLength(propertyName)) {
        // 2. 解析 property 的值（可能是 value、ref、list、map 等）
        Object val = parsePropertyValue(ele, bd, propertyName);
        PropertyValue pv = new PropertyValue(propertyName, val);
        // 3. 添加到 BeanDefinition 的 PropertyValues 中
        bd.getPropertyValues().addPropertyValue(pv);
    }
}
```

### 2.7 自定义命名空间的解析

对于 `<context:component-scan>` 这类非默认命名空间的元素，由 `parseCustomElement()` 处理：

```java
// BeanDefinitionParserDelegate.java
public BeanDefinition parseCustomElement(Element ele, BeanDefinition containingBd) {
    // 1. 获取命名空间 URI
    String namespaceUri = getNamespaceURI(ele);
    // 2. 根据 URI 找到 NamespaceHandler
    NamespaceHandler handler = this.readerContext.getNamespaceHandlerResolver()
            .resolve(namespaceUri);
    // 3. 委托给 NamespaceHandler 解析
    return handler.parse(ele, new ParserContext(this.readerContext, this, containingBd));
}
```

**常见的 NamespaceHandler：**

| 命名空间 | Handler | 作用 |
|---------|---------|------|
| `context` | `ContextNamespaceHandler` | 组件扫描、注解驱动 |
| `aop` | `AopNamespaceHandler` | AOP 配置 |
| `tx` | `TxNamespaceHandler` | 事务配置 |
| `mvc` | `MvcNamespaceHandler` | Spring MVC 配置 |

## 三、注解扫描解析原理

### 3.1 入口：ClassPathBeanDefinitionScanner

```java
// <context:component-scan base-package="com.example"/>
// 由 ContextNamespaceHandler 内部的 ComponentScanBeanDefinitionParser 处理
// 最终创建 ClassPathBeanDefinitionScanner 执行扫描
```

```java
// ComponentScanBeanDefinitionParser.java
public BeanDefinition parse(Element element, ParserContext parserContext) {
    // 1. 获取 base-package 属性
    String basePackage = element.getAttribute(BASE_PACKAGE_ATTRIBUTE);
    
    // 2. 创建 ClassPathBeanDefinitionScanner
    ClassPathBeanDefinitionScanner scanner = 
            configureScanner(parserContext, element);
    
    // 3. 执行扫描
    Set<BeanDefinitionHolder> beanDefinitions = scanner.doScan(basePackage);
    
    // 4. 注册扫描结果
    registerComponents(parserContext.getReaderContext(), beanDefinitions, element);
    
    return null;
}
```

### 3.2 doScan 执行扫描

```java
// ClassPathBeanDefinitionScanner.java
protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
    Set<BeanDefinitionHolder> beanDefinitions = new LinkedHashSet<>();
    
    for (String basePackage : basePackages) {
        // 1. ★ 扫描包下的所有 .class 文件，找到候选组件
        Set<BeanDefinition> candidates = findCandidateComponents(basePackage);
        
        for (BeanDefinition candidate : candidates) {
            // 2. 解析 @Scope 注解
            ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(candidate);
            candidate.setScope(scopeMetadata.getScopeName());
            
            // 3. 生成 Bean 名称
            String beanName = this.beanNameGenerator.generateBeanName(candidate, this.registry);
            
            // 4. ★ 处理通用注解：@Lazy @Primary @DependsOn @Description @Role
            if (candidate instanceof AbstractBeanDefinition) {
                AnnotationConfigUtils.processCommonDefinitionAnnotations(candidate);
            }
            
            // 5. 检查是否已有同名的 Bean
            if (checkCandidate(beanName, candidate)) {
                BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(candidate, beanName);
                // 6. 注册
                registerBeanDefinition(definitionHolder, this.registry);
                beanDefinitions.add(definitionHolder);
            }
        }
    }
    return beanDefinitions;
}
```

### 3.3 findCandidateComponents 的完整路径

```java
// ClassPathScanningCandidateComponentProvider.java
public Set<BeanDefinition> findCandidateComponents(String basePackage) {
    Set<BeanDefinition> candidates = new LinkedHashSet<>();
    
    // 1. 将包名转为资源路径：com.example → classpath*:com/example/**/*.class
    String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
            + resolveBasePackage(basePackage) + '/' + this.resourcePattern;
    
    // 2. 加载所有匹配的 .class 资源
    Resource[] resources = getResourcePatternResolver().getResources(packageSearchPath);
    
    for (Resource resource : resources) {
        if (resource.isReadable()) {
            // 3. ★ 使用 Asm 元数据读取器，不加载类到 JVM
            MetadataReader metadataReader = getMetadataReaderFactory().getMetadataReader(resource);
            
            // 4. 检查是否匹配过滤器（@Component 的存在）
            if (isCandidateComponent(metadataReader)) {
                // 5. 创建 ScannedGenericBeanDefinition
                ScannedGenericBeanDefinition sbd = new ScannedGenericBeanDefinition(metadataReader);
                sbd.setSource(resource);
                
                // 6. 检查是否具体的类（非接口、非抽象）
                if (isCandidateComponent(sbd)) {
                    candidates.add(sbd);
                }
            }
        }
    }
    return candidates;
}
```

**关键点：ASM 字节码读取，不加载类到 JVM**

`SimpleMetadataReader` 使用 ASM 直接读取 `.class` 文件的字节码，从中提取注解信息（如 `@Component`、`@Service` 等），**不需要将类加载到 JVM 中**。这样既快又避免了类加载的副作用。

```java
// SimpleMetadataReader 的 ASM 读取
SimpleMetadataReader(Resource resource, ClassLoader classLoader) {
    InputStream is = new BufferedInputStream(resource.getInputStream());
    // 使用 ASM ClassReader 读取字节码
    ClassReader classReader = new ClassReader(is);
    // 访问者模式：提取类注解、父类、接口等信息
    AnnotationMetadataReadingVisitor visitor = new AnnotationMetadataReadingVisitor(classLoader);
    classReader.accept(visitor, ClassReader.SKIP_CODE);
    this.annotationMetadata = visitor;
    // 只读类元数据，不加载类
}
```

### 3.4 @Component 的筛选逻辑

```java
// ClassPathScanningCandidateComponentProvider.java
protected boolean isCandidateComponent(MetadataReader metadataReader) {
    // 1. 排除 @excludeFilters
    for (TypeFilter tf : this.excludeFilters) {
        if (tf.match(metadataReader, getMetadataReaderFactory())) {
            return false;
        }
    }
    
    // 2. 检查 @includeFilters（默认注册了 @Component 过滤器）
    for (TypeFilter tf : this.includeFilters) {
        if (tf.match(metadataReader, getMetadataReaderFactory())) {
            return true;  // 标注了 @Component 或其派生注解
        }
    }
    
    return false;
}

// AnnotationTypeFilter 的匹配逻辑
// 检查 @Component → @Service 是否传递
public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
    // 读取类的注解元数据
    AnnotationMetadata annotationMetadata = metadataReader.getAnnotationMetadata();
    // 检查是否标注了目标注解（含派生）
    return annotationMetadata.hasAnnotation(this.annotationType.getName())
            || annotationMetadata.hasMetaAnnotation(this.annotationType.getName());
}
```

**所以 `@Service` 能被识别的原因：**

```
@Service → 标注了 @Component（元注解）
         → hasMetaAnnotation("org.springframework.stereotype.Component") = true
         → 通过 @Component 过滤器匹配
```

### 3.5 processCommonDefinitionAnnotations

```java
// AnnotationConfigUtils.java
public static void processCommonDefinitionAnnotations(AnnotatedBeanDefinition abd) {
    processCommonDefinitionAnnotations(abd, abd.getMetadata());
}

static void processCommonDefinitionAnnotations(AnnotatedBeanDefinition abd, AnnotatedTypeMetadata metadata) {
    // 处理 @Lazy
    AnnotationAttributes lazy = attributesFor(metadata, Lazy.class);
    if (lazy != null) {
        abd.setLazyInit(lazy.getBoolean("value"));
    }
    
    // 处理 @Primary
    if (metadata.isAnnotated(Primary.class.getName())) {
        abd.setPrimary(true);
    }
    
    // 处理 @DependsOn
    AnnotationAttributes dependsOn = attributesFor(metadata, DependsOn.class);
    if (dependsOn != null) {
        abd.setDependsOn(dependsOn.getStringArray("value"));
    }
    
    // 处理 @Role
    AnnotationAttributes role = attributesFor(metadata, Role.class);
    if (role != null) {
        abd.setRole(role.getNumber("value").intValue());
    }
    
    // 处理 @Description
    AnnotationAttributes description = attributesFor(metadata, Description.class);
    if (description != null) {
        abd.setDescription(description.getString("value"));
    }
}
```

## 四、Java Config 解析原理

### 4.1 入口：ConfigurationClassPostProcessor

Java Config 的解析不在 `refresh()` 的初始扫描阶段产生，而是在 `invokeBeanFactoryPostProcessors()` 阶段由 `ConfigurationClassPostProcessor` 处理。

```java
// ConfigurationClassPostProcessor 是一个 BeanDefinitionRegistryPostProcessor
public class ConfigurationClassPostProcessor 
        implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {
    
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        // ★ 核心：处理所有 @Configuration 类
        processConfigBeanDefinitions(registry);
    }
}
```

### 4.2 processConfigBeanDefinitions

```java
// ConfigurationClassPostProcessor.java
public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {
    // 1. 找出所有已注册的候选 @Configuration 类
    List<BeanDefinitionHolder> configCandidates = new ArrayList<>();
    String[] candidateNames = registry.getBeanDefinitionNames();
    
    for (String beanName : candidateNames) {
        BeanDefinition bd = registry.getBeanDefinition(beanName);
        // 检查是否标注了 @Configuration 或关键注解
        if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, this.metadataReaderFactory)) {
            configCandidates.add(new BeanDefinitionHolder(bd, beanName));
        }
    }

    // 2. 使用 ConfigurationClassParser 解析
    ConfigurationClassParser parser = new ConfigurationClassParser(...);
    Set<BeanDefinitionHolder> candidates = new LinkedHashSet<>(configCandidates);
    
    // 递归解析（处理 @Import、@ComponentScan 触发的新配置类）
    parser.parse(candidates);
    parser.validate();

    // 3. 获取所有解析出的配置类
    Set<ConfigurationClass> configClasses = parser.getConfigurationClasses();
    
    // 4. 使用 ConfigurationClassBeanDefinitionReader
    //    将 @Bean 方法转换为 BeanDefinition
    this.reader.loadBeanDefinitions(configClasses);
    
    // 5. 新发现的配置类可能产生更多 BeanDefinition，递归处理
    if (this.metadataReaderFactory instanceof CachingMetadataReaderFactory) {
        // 如果此次解析产生了新的 @Configuration 类，递归
    }
}
```

### 4.3 ConfigurationClassParser 的解析过程

```java
// ConfigurationClassParser.java
public void parse(Set<BeanDefinitionHolder> configCandidates) {
    for (BeanDefinitionHolder holder : configCandidates) {
        BeanDefinition bd = holder.getBeanDefinition();
        // 根据 BeanDefinition 类型选择解析方式
        if (bd instanceof AnnotatedBeanDefinition) {
            parse(((AnnotatedBeanDefinition) bd).getMetadata(), holder.getBeanName());
        } else if (bd instanceof AbstractBeanDefinition) {
            parse(((AbstractBeanDefinition) bd).getBeanClass(), holder.getBeanName());
        } else {
            parse(bd.getBeanClassName(), holder.getBeanName());
        }
    }
}

// 从配置类元数据开始解析
protected void processConfigurationClass(ConfigurationClass configClass) {
    // 递归处理：处理 @ComponentScan、@Import、@Bean 等
    do {
        // 1. 递归处理 @PropertySource
        processPropertySource(configClass);
        
        // 2. ★ 检查 @ComponentScan → 执行扫描 → 新类可能也是 @Configuration
        Set<BeanDefinitionHolder> scannedBeanDefinitions = 
                doProcessConfigurationClass(configClass, sourceClass);
        
        if (scannedBeanDefinitions.isEmpty()) {
            // 3. 处理 @Import → 获取导入的配置类
            processImports(configClass, sourceClass, ...);
            
            // 4. 处理 @ImportResource → 加载 XML 配置
            processImportResources(configClass, sourceClass);
            
            // 5. ★ 收集 @Bean 方法（记录，尚未注册）
            retrieveBeanMethodMetadata(sourceClass);
        }
        
    } while (sourceClass != null);
}
```

### 4.4 @ComponentScan 在 Java Config 中的处理

```java
// ConfigurationClassParser.java — doProcessConfigurationClass 中
Set<BeanDefinitionHolder> scannedBeanDefinitions = new LinkedHashSet<>();

// 处理 @ComponentScan
Set<AnnotationAttributes> componentScans = 
        AnnotationConfigUtils.attributesForRepeatable(
                sourceClass.getMetadata(), ComponentScans.class, ComponentScan.class);

for (AnnotationAttributes componentScan : componentScans) {
    // 创建 ClassPathBeanDefinitionScanner 执行扫描
    ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(...);
    
    // 使用 scopedProxyMode 和 ResourcePattern 等配置 scanner
    // ...
    
    // 执行扫描：扫描结果中可能包含新的 @Configuration 类 → 递归
    Set<BeanDefinitionHolder> scannedDefinitions = scanner.doScan(basePackages);
    scannedBeanDefinitions.addAll(scannedDefinitions);
}
```

### 4.5 @Bean 方法注册为 BeanDefinition

当 `ConfigurationClassParser` 处理完所有注解后，`ConfigurationClassBeanDefinitionReader` 将 `@Bean` 方法转换为 `BeanDefinition`：

```java
// ConfigurationClassBeanDefinitionReader.java
public void loadBeanDefinitions(Set<ConfigurationClass> configurationModel) {
    for (ConfigurationClass configClass : configurationModel) {
        loadBeanDefinitionsForConfigurationClass(configClass);
    }
}

private void loadBeanDefinitionsForConfigurationClass(ConfigurationClass configClass) {
    // 1. 处理 @ImportResource → 加载 XML
    for (String importedResource : configClass.getImportedResources()) {
        // 用 XmlBeanDefinitionReader 加载 XML 配置
    }
    
    // 2. ★ 处理 @Bean 方法
    for (BeanMethod beanMethod : configClass.getBeanMethods()) {
        // 为每个 @Bean 方法创建一个 ConfigurationClassBeanDefinition
        ConfigurationClassBeanDefinition beanDef = new ConfigurationClassBeanDefinition(configClass, beanMethod);
        // 解析 @Bean 的属性：name、autowire、initMethod、destroyMethod
        // 注册到 BeanDefinitionRegistry
        this.registry.registerBeanDefinition(beanName, beanDef);
    }
}
```

**`ConfigurationClassBeanDefinition` 的特殊之处：**

```java
// ConfigurationClassBeanDefinition 继承自 GenericBeanDefinition
// 它的特殊之处在于：beanClass 不是目标类，而是工厂方法信息
class ConfigurationClassBeanDefinition extends GenericBeanDefinition {
    
    // 存储 @Bean 方法的元数据
    private AnnotationAttributes beanAttributes;
    
    public ConfigurationClassBeanDefinition(ConfigurationClass configClass, BeanMethod method) {
        // beanClass 设置为配置类本身（如 AppConfig）
        setBeanClass(configClass.getBeanClass());
        // 设置工厂方法名称（如 "userService"）
        setFactoryMethodName(method.getMethod().getMethodName());
        // 工厂 Bean 名称（配置类的 Bean 名称）
        setBeanClass(configClass.getBeanClass());
    }
}
```

**所以，当 IoC 容器实例化这个 Bean 时，它的执行路径是：**

```
@Bean 方法 → ConfigurationClassBeanDefinition
           → beanClass = AppConfig.class（配置类）
           → factoryMethodName = "userService"（@Bean 方法名）
           
调用 getBean("userService") 时：
  → 通过 beanClass 获取 AppConfig 的单例
  → 通过 factoryMethodName 反射调用 userService() 方法
  → 返回方法返回值作为 Bean 实例
```

## 五、统一模型

不管是哪种配置方式，最终都汇入同一个模型：

```java
// DefaultListableBeanFactory 中的核心数据结构
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
        implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {

    // ★ 所有配置方式最终都注册到这里
    private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);
    
    // Bean 名称列表（按注册顺序）
    private volatile List<String> beanDefinitionNames = new ArrayList<>(256);
    
    // 已注册的单例缓存
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);
}
```

### 三个入口的最终汇聚点

```
XML 配置
  └─ XmlBeanDefinitionReader
       └─ DefaultBeanDefinitionDocumentReader
            └─ BeanDefinitionParserDelegate.parseBeanDefinitionElement()
                 └─ GenericBeanDefinition
                      └─ registry.registerBeanDefinition()    ← 统一入口

注解扫描
  └─ ClassPathBeanDefinitionScanner.doScan()
       └─ findCandidateComponents() → ScannedGenericBeanDefinition
            └─ registry.registerBeanDefinition()              ← 统一入口

Java Config
  └─ ConfigurationClassParser.parse()
       └─ ConfigurationClassBeanDefinitionReader
            └─ ConfigurationClassBeanDefinition
                 └─ registry.registerBeanDefinition()          ← 统一入口
```

## 六、三种 BeanDefinition 的实现差异

| 实现类 | 来源 | 元数据来源 |
|-------|------|-----------|
| `GenericBeanDefinition` | XML `<bean>` 解析 | XML 标签属性 |
| `ScannedGenericBeanDefinition` | 组件扫描 | ASM 字节码读取 |
| `ConfigurationClassBeanDefinition` | `@Bean` 方法 | 反射 + ASM 元数据 |

它们在最终的 Bean 实例化阶段行为**完全一致**——`AbstractAutowireCapableBeanFactory.createBean()` 对它们一视同仁。

## 七、总结

### 三种配置方式的解析入口速查

| 配置方式 | 解析器 | 读取方式 | 生成的 BeanDefinition 类型 |
|---------|--------|---------|--------------------------|
| XML | `XmlBeanDefinitionReader` | SAX 解析 DOM | `GenericBeanDefinition` |
| 注解扫描 | `ClassPathBeanDefinitionScanner` | ASM 字节码读取 | `ScannedGenericBeanDefinition` |
| Java Config | `ConfigurationClassPostProcessor` | ASM + 反射 | `ConfigurationClassBeanDefinition`+ `GenericBeanDefinition` |

### 一句总结

> **三种配置方式的本质差异在"BeanDefinition 如何生成"，一旦注册到 beanDefinitionMap，后续的实例化、注入、初始化流程完全一致。**

---

**上一篇：** [Spring IoC（一）：三种配置方式概述与对比]({{< relref "post/spring-ioc-config-overview" >}})

**下一篇：** [Spring IoC（三）：选型指南与迁移实践]({{< relref "post/spring-ioc-config-migration" >}})
