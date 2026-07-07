---
title: "工作流（三）：Flowable 原理深度解析"
date: 2021-08-22
draft: false
categories: ["Java"]
tags: ["工作流", "Flowable", "命令拦截器", "流程引擎", "BPMN解析"]
toc: true
---

## 前言

前两篇覆盖了 Flowable 的选型、概念和 Spring Boot 集成。本文深入引擎内部，解析 Flowable 的**引擎架构**、**命令拦截器模式**、**BPMN 解析流程**以及**流程实例的执行链路**。

<!--more-->

## 一、引擎架构

### 1.1 ProcessEngine 的创建

```java
// Spring Boot 自动配置时创建 ProcessEngine 的过程

// 1. Spring Boot Starter 加载 FlowableAutoConfiguration
// 2. 创建 SpringProcessEngineConfiguration（初始化数据源）
// 3. 调用 ProcessEngineConfiguration.buildProcessEngine()

// buildProcessEngine() 的核心步骤：
public ProcessEngine buildProcessEngine() {
    // 1. 初始化配置
    initConfig();
    
    // 2. 初始化数据库
    initDatabase();
    
    // 3. 初始化服务工厂（创建所有 Service）
    initServiceFactory();
    
    // 4. 初始化命令执行器
    initCommandExecutor();
    
    // 5. 部署自动扫描的流程
    initDeployment();
    
    // 6. 返回 ProcessEngine 实例
    return new ProcessEngineImpl(this);
}
```

### 1.2 核心组件

```
ProcessEngine（流程引擎）
    │
    ├── ProcessEngineConfiguration（引擎配置）
    │     ├── 数据源
    │     ├── 历史级别
    │     ├── 异步执行器配置
    │     └── 自定义配置（监听器、事件处理器等）
    │
    ├── CommandExecutor（命令执行器）
    │     └── 所有 API 调用最终会封装为 Command 执行
    │
    ├── ServiceFactory（服务工厂）
    │     ├── RepositoryService
    │     ├── RuntimeService
    │     ├── TaskService
    │     ├── HistoryService
    │     ├── IdentityService
    │     ├── ManagementService
    │     └── DynamicBpmnService
    │
    └── DeploymentManager（部署管理器）
          └── 扫描和部署 BPMN 文件
```

---

## 二、命令拦截器模式

Flowable 所有 API 调用统一通过**命令拦截器链**执行。

### 2.1 架构

```
客户端调用（如 runtimeService.startProcessInstanceByKey()）
    │
    ▼
Service 封装为 Command
    │
    ▼
CommandExecutor（命令执行器）
    │
    ▼
拦截器链（Interceptor Chain）
    │
    ├── LogInterceptor（日志）
    ├── TransactionInterceptor（事务）  
    ├── CommandContextInterceptor（上下文）
    └── CommandInvoker（最终执行器）
          │
          ▼
Command 执行（实际的业务逻辑）
```

### 2.2 命令模式源码

```java
// 所有 Service 中的操作都封装为 Command
// 例如启动流程：

public ProcessInstance startProcessInstanceByKey(String processDefinitionKey,
                                                   Map<String, Object> variables) {
    // 封装命令 → 提交到命令执行器
    return commandExecutor.execute(
            new StartProcessInstanceCmd(processDefinitionKey, variables));
}

// Command 接口
public interface Command<T> {
    T execute(CommandContext commandContext);
}

// 命令执行器
public class CommandExecutorImpl implements CommandExecutor {
    
    private CommandInterceptor first;   // 拦截器链头部
    
    @Override
    public <T> T execute(Command<T> command) {
        // 交给拦截器链执行
        return first.execute(command);
    }
}
```

### 2.3 拦截器链

```java
// 命令拦截器接口
public interface CommandInterceptor {
    
    <T> T execute(Command<T> command);
    
    CommandInterceptor getNext();
    void setNext(CommandInterceptor next);
}

// 事务拦截器
public class TransactionInterceptor implements CommandInterceptor {
    
    private CommandInterceptor next;
    private TransactionManager transactionManager;
    
    @Override
    public <T> T execute(Command<T> command) {
        // 在事务中执行命令
        return transactionManager.execute(status -> next.execute(command));
    }
}

// 命令上下文拦截器（设置 CommandContext 到 ThreadLocal）
public class CommandContextInterceptor implements CommandInterceptor {
    
    @Override
    public <T> T execute(Command<T> command) {
        CommandContext context = new CommandContext(command);
        context.open();  // 设置 CommandContext 到 ThreadLocal
        try {
            return next.execute(command);
        } finally {
            context.close();  // 清理 ThreadLocal
            context.flush();  // 刷出未提交的修改到数据库
        }
    }
}
```

### 2.4 CommandContext——引擎的核心上下文

```java
// CommandContext 持有引擎运行时的所有数据
public class CommandContext {
    
    // 当前命令
    protected Command<?> command;
    
    // 引擎配置
    protected ProcessEngineConfigurationImpl processEngineConfiguration;
    
    // 所有的 Service
    protected RepositorySession repositorySession;
    protected RuntimeSession runtimeSession;
    protected TaskSession taskSession;
    protected HistorySession historySession;
    
    // 脏数据缓存（事务提交时写入数据库）
    protected Map<Class<?>, Map<String, PersistentObject>> transactionContext;
    
    // 命令执行过程中的异常
    protected Throwable exception;
}
```

---

## 三、BPMN 解析流程

### 3.1 部署时的解析

```java
// 部署 BPMN 文件时，Flowable 将其解析为内存模型

// 1. DeploymentManager.deploy()
// 2. BpmnDeployer.deploy()
// 3. BpmnParser.parse()

// BpmnParser 的核心流程：
public Process parseBpmnModel(BpmnModel bpmnModel) {
    Process process = new Process();
    
    // 1. 解析流程属性（id、name、isExecutable）
    process.setId(bpmnModel.getMainProcess().getId());
    
    // 2. 解析开始事件
    StartEvent startEvent = parseStartEvent(bpmnModel);
    process.addFlowElement(startEvent);
    
    // 3. 解析用户任务（UserTask）
    for (UserTask userTask : bpmnModel.getMainProcess().getUserTasks()) {
        process.addFlowElement(parseUserTask(userTask));
    }
    
    // 4. 解析服务任务（ServiceTask）
    for (ServiceTask serviceTask : bpmnModel.getMainProcess().getServiceTasks()) {
        process.addFlowElement(parseServiceTask(serviceTask));
    }
    
    // 5. 解析网关（Gateway）
    for (Gateway gateway : bpmnModel.getMainProcess().getGateways()) {
        process.addFlowElement(parseGateway(gateway));
    }
    
    // 6. 解析连线（SequenceFlow）
    for (SequenceFlow flow : bpmnModel.getMainProcess().getSequenceFlows()) {
        process.addFlowElement(parseSequenceFlow(flow));
    }
    
    // 7. 注册到 ProcessEngineConfiguration
    processEngineConfiguration.getDeploymentManager()
            .getProcessCache().add(process.getId(), process);
    
    return process;
}
```

---

## 四、流程实例执行链路

### 4.1 startProcessInstanceByKey 的完整链路

```java
// 1. RuntimeService.startProcessInstanceByKey()
//    → 封装为 StartProcessInstanceCmd

// 2. CommandExecutor.execute(cmd)
//    → 拦截器链执行

// 3. StartProcessInstanceCmd.execute()
//    a. 通过 processDefinitionKey 查询 ProcessDefinition
//    b. 创建 ProcessInstance（数据库 ACT_RU_EXECUTION 插入记录）
//    c. 初始化流程变量（插入 ACT_RU_VARIABLE）
//    d. 执行流程：从开始事件走到第一个 Task
//    e. 创建第一个 Task（插入 ACT_RU_TASK）
//    f. 返回 ProcessInstance

// 4. ProcessInstance 包含了：
//    - processInstanceId（流程实例 ID）
//    - processDefinitionId（流程定义 ID）
//    - businessKey（业务 key）
//    - variables（流程变量）
```

### 4.2 任务完成的执行链路

```java
// 1. TaskService.complete(taskId, variables)
//    → 封装为 CompleteTaskCmd

// 2. CompleteTaskCmd.execute()
//    a. 查询当前 Task
//    b. 设置变量
//    c. 删除当前 Task（从 ACT_RU_TASK 移除 → 写入 ACT_HI_TASKINST）
//    d. 获取当前 Activity 的出向连线（SequenceFlow）
//    e. 计算连线条件（conditionExpression）
//    f. 找到满足条件的下一个节点
//    g. 如果下一节点是 UserTask → 创建新 Task
//    h. 如果下一节点是 EndEvent → 结束流程实例（ACT_RU → ACT_HI）
//    i. 返回
```

### 4.3 条件表达式的计算

```java
// 连线上的 conditionExpression
// ${approved == true}
// ${days > 3}

// Flowable 用自定义的表达式引擎进行求值

public class ConditionUtil {
    
    public static boolean evaluateCondition(String expression, 
                                             DelegateExecution execution) {
        // 1. 从 execution 中获取流程变量
        Map<String, Object> variables = execution.getVariables();
        
        // 2. 使用表达式引擎计算
        // Flowable 使用自己实现的 SimpleEL 或 JUEL
        Object result = ExpressionManager.evaluate(expression, variables);
        
        // 3. 返回 Boolean 结果
        return Boolean.TRUE.equals(result);
    }
}
```

---

## 五、流程定义缓存

```java
// Flowable 将解析后的 Process 对象缓存在内存中
// 避免每次执行流程都重新解析 BPMN

public class ProcessDefinitionCache {
    
    // 一级缓存：流程定义 ID → Process
    private final Map<String, Process> processCache = new ConcurrentHashMap<>();
    
    // 二级缓存：流程定义 Key → 最新版本的 ProcessDefinition
    private final Map<String, ProcessDefinition> processDefinitionCache = 
            new ConcurrentHashMap<>();
    
    // 根据流程定义 Key 获取 Process
    public Process getProcess(String processDefinitionKey) {
        String processDefinitionId = processDefinitionCache
                .get(processDefinitionKey).getId();
        return processCache.get(processDefinitionId);
    }
}
```

---

## 六、历史数据管理

```java
// HistoryManager 负责将运行时数据复制到历史表

public class DefaultHistoryManager implements HistoryManager {
    
    @Override
    public void recordProcessInstanceEnd(String processInstanceId, 
                                          String deleteReason, 
                                          String activityId) {
        // 1. 查询运行时执行流
        // 2. 复制到 ACT_HI_PROCINST
        // 3. 删除 ACT_RU_EXECUTION 中的记录
        // 4. 记录结束时间
    }
    
    @Override
    public void recordTaskEnd(String taskId, String deleteReason) {
        // 1. 从 ACT_RU_TASK 复制到 ACT_HI_TASKINST
        // 2. 设置结束时间
        // 3. 删除 ACT_RU_TASK 中的记录
    }
}
```

**历史级别对比：**

| 级别 | 记录内容 | 影响 |
|------|---------|:----:|
| none | 不记录历史 | 性能最好，无法查询历史 |
| activity | 记录流程活动实例 | 可查询流程经过的节点 |
| audit | 活动 + 变量（默认）| 可查询变量变化 |
| full | 所有内容 | 可完整追溯 |

---

## 七、总结

### 核心机制速记

| 机制 | 说明 |
|------|------|
| 命令拦截器 | 所有 API 通过 Command + 拦截器链执行 |
| 解析流程 | BPMN XML → BpmnModel → Process（内存模型）|
| 流程执行 | 开始事件 → 任务 → 网关(条件) → 任务 → 结束 |
| 历史迁移 | 运行时表 → 历史表（已经结束的记录移到历史表）|
| 流程缓存 | Process 对象缓存在内存中，避免重复解析 |

### 源码文件索引

| 类 | 作用 |
|---|------|
| CommandExecutorImpl | 命令执行器实现 |
| CommandContext | 命令执行上下文 |
| StartProcessInstanceCmd | 启动流程命令 |
| CompleteTaskCmd | 完成任务命令 |
| BpmnParser | BPMN 解析器 |
| DefaultHistoryManager | 历史数据管理器 |
| ProcessDefinitionCache | 流程定义缓存 |

**上一篇：** [工作流（二）：Spring Boot 集成 Flowable 与工作流示例]({{< relref "post/workflow-flowable-spring-boot" >}})

**系列索引：**
- [工作流（一）：选型与 Flowable 核心概念]({{< relref "post/workflow-flowable-overview" >}})
- [工作流（二）：Spring Boot 集成 Flowable 与工作流示例]({{< relref "post/workflow-flowable-spring-boot" >}})
- [工作流（三）：Flowable 原理深度解析]({{< relref "post/workflow-flowable-principles" >}})
