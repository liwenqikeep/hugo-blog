---
title: "工作流（二）：Spring Boot 集成 Flowable 与工作流示例"
date: 2021-08-20
draft: false
categories: ["Java"]
tags: ["工作流", "Flowable", "Spring Boot", "BPMN", "审批流"]
toc: true
---

## 前言

本文从 Spring Boot 集成 Flowable 开始，覆盖一个完整请假流程的 API 调用——部署 BPMN、启动流程、查询任务、完成任务。

<!--more-->

## 一、Spring Boot 集成

### 1.1 依赖

```xml
<dependency>
    <groupId>org.flowable</groupId>
    <artifactId>flowable-spring-boot-starter</artifactId>
    <version>6.7.2</version>
</dependency>

<!-- MySQL 驱动 -->
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <scope>runtime</scope>
</dependency>
```

### 1.2 配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/flowable?useUnicode=true&characterEncoding=utf8
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver

flowable:
  # 启动时自动建表（生产环境建议设为 false，用脚本手动建表）
  database-schema-update: true
  # 关闭异步执行器（简单项目可关闭）
  async-executor-activate: false
  # 历史级别（full 记录所有流程历史）
  history-level: full
```

**启动后自动生成的数据库表（以 `ACT_` 开头）：**

```sql
ACT_RE_PROCDEF    -- 流程定义
ACT_RE_DEPLOYMENT -- 部署
ACT_RU_TASK       -- 运行中任务
ACT_RU_EXECUTION  -- 运行中执行流
ACT_RU_VARIABLE   -- 流程变量
ACT_HI_PROCINST   -- 历史实例
ACT_HI_TASKINST   -- 历史任务
-- ... 共 20+ 张表
```

---

## 二、核心 API 使用

### 2.1 注入引擎服务

```java
@Service
public class WorkflowService {
    
    @Autowired
    private RuntimeService runtimeService;       // 运行时服务
    
    @Autowired
    private TaskService taskService;             // 任务服务
    
    @Autowired
    private RepositoryService repositoryService; // 仓库服务
    
    @Autowired
    private HistoryService historyService;       // 历史服务
    
    @Autowired
    private IdentityService identityService;     // 身份服务
}
```

### 2.2 Service API 说明

| 服务 | 用途 | 核心操作 |
|------|------|---------|
| RepositoryService | 流程定义和部署 | deploy、查询定义、挂起/激活 |
| RuntimeService | 流程实例 | startProcess、查询实例、触发信号 |
| TaskService | 任务 | 查询/领取/完成/委派任务，设置变量 |
| HistoryService | 历史数据 | 查询已完成的流程/任务 |
| IdentityService | 用户与组 | 创建/查询用户和组 |

---

## 三、请假流程 API 示例

### 3.1 部署 BPMN

```java
// 部署流程定义
@PostConstruct
public void deployProcess() {
    Deployment deployment = repositoryService.createDeployment()
            .addClasspathResource("processes/leave.bpmn20.xml")
            .name("请假流程")
            .deploy();
    System.out.println("部署ID: " + deployment.getId());
}
```

### 3.2 启动流程

```java
public void startLeaveProcess(Long applyUserId, String reason, int days) {
    // 1. 设置发起人
    identityService.setAuthenticatedUserId(String.valueOf(applyUserId));
    
    // 2. 设置流程变量
    Map<String, Object> variables = new HashMap<>();
    variables.put("applyUser", String.valueOf(applyUserId));
    variables.put("reason", reason);
    variables.put("days", days);
    variables.put("manager", "1001");  // 组长ID
    
    // 3. 启动流程
    ProcessInstance processInstance = runtimeService
            .startProcessInstanceByKey("leaveProcess", variables);
    
    System.out.println("流程实例ID: " + processInstance.getId());
    System.out.println("流程定义ID: " + processInstance.getProcessDefinitionId());
}
```

### 3.3 查询任务

```java
// 查询用户的待办任务
public List<Map<String, Object>> getTasks(String userId) {
    List<Task> tasks = taskService.createTaskQuery()
            .taskAssignee(userId)        // 指定办理人
            .active()                    // 激活状态
            .orderByTaskCreateTime().desc()
            .list();
    
    List<Map<String, Object>> result = new ArrayList<>();
    for (Task task : tasks) {
        Map<String, Object> item = new HashMap<>();
        item.put("taskId", task.getId());
        item.put("taskName", task.getName());
        item.put("createTime", task.getCreateTime());
        item.put("processInstanceId", task.getProcessInstanceId());
        
        // 获取流程变量
        Map<String, Object> variables = taskService.getVariables(task.getId());
        item.put("variables", variables);
        
        result.add(item);
    }
    return result;
}

// 查询用户发起的流程实例
public List<HistoricProcessInstance> getStartedProcesses(String userId) {
    return historyService.createHistoricProcessInstanceQuery()
            .startedBy(String.valueOf(userId))
            .finished()          // 已结束的
            .orderByProcessInstanceEndTime().desc()
            .list();
}
```

### 3.4 完成任务

```java
public void completeTask(String taskId, boolean approved, String comment) {
    // 1. 设置流程变量（传递给流程引擎）
    Map<String, Object> variables = new HashMap<>();
    variables.put("approved", approved);
    variables.put("comment", comment);
    
    // 2. 完成任务（引擎会根据变量的条件自动流转到下一节点）
    taskService.complete(taskId, variables);
}
```

### 3.5 完整调用演示

```java
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {
    
    @Autowired
    private WorkflowService workflowService;
    
    // 1. 员工提交请假
    @PostMapping("/start")
    public String startLeave(@RequestBody LeaveRequest request) {
        workflowService.startLeaveProcess(
                request.getUserId(), 
                request.getReason(), 
                request.getDays());
        return "流程已启动";
    }
    
    // 2. 组长查询待办
    @GetMapping("/tasks/{userId}")
    public List<Map<String, Object>> getTasks(@PathVariable String userId) {
        return workflowService.getTasks(userId);
    }
    
    // 3. 组长审批
    @PostMapping("/tasks/{taskId}/complete")
    public String completeTask(@PathVariable String taskId, 
                                @RequestBody ApproveRequest request) {
        workflowService.completeTask(taskId, 
                request.isApproved(), request.getComment());
        return "审批完成";
    }
}
```

---

## 四、流程处理

### 4.1 流程状态

```java
public void checkProcessStatus(String processInstanceId) {
    // 是否运行中
    ProcessInstance instance = runtimeService
            .createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();
    
    if (instance != null) {
        System.out.println("流程运行中");
        // 当前活动节点
        List<String> activeActivityIds = runtimeService
                .getActiveActivityIds(processInstanceId);
        System.out.println("当前活动节点: " + activeActivityIds);
    } else {
        System.out.println("流程已结束");
        // 查询历史
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        System.out.println("结束时间: " + historic.getEndTime());
        System.out.println("持续时间: " + historic.getDurationInMillis() + "ms");
    }
}
```

### 4.2 挂起与激活

```java
// 挂起流程定义（新流程不再启动）
repositoryService.suspendProcessDefinitionByKey("leaveProcess");

// 激活
repositoryService.activateProcessDefinitionByKey("leaveProcess");
```

### 4.3 拒绝时的重新提交

```java
// 当组长驳回时，流程回到"修改申请"或"填写请假单"节点
// 员工修改后再次提交：直接 complete 任务
// 流程自动继续到组长审批节点

public void resubmit(String taskId, String newReason) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("reason", newReason);
    taskService.complete(taskId, variables);
    // 流程回到组长审批节点
}
```

---

## 五、监听器与扩展

### 5.1 任务监听器

```java
@Component
public class TaskCreateListener implements TaskListener {
    
    @Override
    public void notify(DelegateTask delegateTask) {
        System.out.println("任务创建: " + delegateTask.getName());
        System.out.println("办理人: " + delegateTask.getAssignee());
        
        // 发送通知给办理人
        notifyUser(delegateTask.getAssignee(), 
                   "有新的审批任务: " + delegateTask.getName());
    }
    
    private void notifyUser(String userId, String message) {
        // 发送短信、站内信、企业微信等
    }
}
```

### 5.2 执行监听器

```java
@Component
public class ProcessEndListener implements ExecutionListener {
    
    @Override
    public void notify(DelegateExecution execution) {
        String eventName = execution.getEventName();
        
        if ("end".equals(eventName)) {
            System.out.println("流程结束: " + execution.getProcessInstanceId());
            // 清理相关资源、通知发起人等
        }
    }
}
```

### 5.3 在 BPMN 中配置监听器

```xml
<userTask id="managerApprove" name="组长审批"
          assignee="${manager}">
    <extensionElements>
        <flowable:taskListener event="create"
            class="com.example.listener.TaskCreateListener"/>
        <flowable:taskListener event="complete"
            class="com.example.listener.TaskCompleteListener"/>
    </extensionElements>
</userTask>

<process id="leaveProcess">
    <extensionElements>
        <flowable:executionListener event="end"
            class="com.example.listener.ProcessEndListener"/>
    </extensionElements>
</process>
```

---

## 六、部署与模型管理

### 6.1 动态部署

```java
@RestController
@RequestMapping("/api/deploy")
public class DeployController {
    
    @Autowired
    private RepositoryService repositoryService;
    
    // 上传 BPMN 文件部署
    @PostMapping("/upload")
    public String deploy(@RequestParam("file") MultipartFile file) throws IOException {
        Deployment deployment = repositoryService.createDeployment()
                .addBytes(file.getOriginalFilename(), file.getBytes())
                .name(file.getOriginalFilename())
                .deploy();
        return "部署成功: " + deployment.getId();
    }
    
    // 查询已部署的流程
    @GetMapping("/list")
    public List<Map<String, Object>> listDeployments() {
        List<ProcessDefinition> definitions = repositoryService
                .createProcessDefinitionQuery()
                .latestVersion()
                .list();
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProcessDefinition pd : definitions) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", pd.getId());
            item.put("key", pd.getKey());
            item.put("name", pd.getName());
            item.put("version", pd.getVersion());
            item.put("deploymentId", pd.getDeploymentId());
            item.put("suspended", pd.isSuspended());
            result.add(item);
        }
        return result;
    }
}
```

---

## 七、总结

### 核心 API 速查

| 操作 | 方法 |
|------|------|
| 部署 BPMN | repositoryService.createDeployment().addClasspathResource().deploy() |
| 启动流程 | runtimeService.startProcessInstanceByKey(key, variables) |
| 查询指定人待办 | taskService.createTaskQuery().taskAssignee(userId).list() |
| 完成任务 | taskService.complete(taskId, variables) |
| 查询历史 | historyService.createHistoricProcessInstanceQuery() |
| 挂起定义 | repositoryService.suspendProcessDefinitionByKey(key) |

**上一篇：** [工作流（一）：选型与 Flowable 核心概念]({{< relref "post/workflow-flowable-overview" >}})

**下一篇：** [工作流（三）：Flowable 原理深度解析]({{< relref "post/workflow-flowable-principles" >}})
