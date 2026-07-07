---
title: "工作流（一）：选型与 Flowable 核心概念"
date: 2021-08-18
draft: false
categories: ["Java"]
tags: ["工作流", "Flowable", "BPMN", "Activiti", "Camunda"]
toc: true
---

## 前言

工作流引擎将业务流程的定义和执行分离，允许业务人员通过 BPMN 图设计流程，开发者负责集成和扩展。在国内 Java 生态中，Flowable、Activiti 和 Camunda 是最主流的三个引擎。

本文先对工作流引擎选型做对比，再以 Flowable 为主线，覆盖 BPMN 核心元素和基础概念。

<!--more-->

## 一、工作流引擎选型

### 1.1 三大引擎对比

| 对比维度 | Flowable | Activiti | Camunda |
|---------|----------|----------|---------|
| 起源 | Activiti 6 分支 | 首个开源 BPMN 引擎 | 同样从 Activiti 分支 |
| 当前版本 | 6.x / 7.x | 5.x / 6.x / 7.x | 7.x / 8.x |
| 社区活跃度 | 活跃 | 一般 | 活跃 |
| BPMN 支持 | BPMN 2.0 | BPMN 2.0 | BPMN 2.0 + DMN + CMMN |
| 轻量性 | 适中 | 轻量 | 较重 |
| Spring Boot 集成 | spring-boot-starter | 需手动配置 | spring-boot-starter |
| REST API | ✅ | ✅ | ✅ |
| 建模工具 | Flowable Modeler | Activiti Modeler | Camunda Modeler（桌面版）|
| 国内生态 | 较多 | 较少 | 一般 |
| 文档质量 | 良好 | 一般 | 优秀 |

### 1.2 选型建议

```bash
# 选型决策

小型项目、简单审批流 → Flowable（轻量，集成方便）
大中型企业级流程 → Camunda（功能全，建模工具好）
遗留项目维护 → 跟随已有技术栈

# 本文以 Flowable 6.x 为主线
```

---

## 二、BPMN 核心概念

BPMN（Business Process Model and Notation）是工作流引擎通用的流程定义标准。

### 2.1 核心元素

```
BPMN 的核心元素：

┌──────────┐         ┌──────────┐         ┌──────────┐
│ 开始事件  │────▶    │ 用户任务  │────▶    │ 结束事件  │
│ (Start)  │  UserTask│ 审批     │  End    │          │
└──────────┘         └──────────┘         └──────────┘
                          │
                    ┌─────┴─────┐
                    │           │
                    ▼           ▼
              ┌──────────┐ ┌──────────┐
              │ 通过      │ │ 驳回     │
              │ Exclusive │ │          │
              │ Gateway   │ │          │
              └──────────┘ └──────────┘
```

### 2.2 BPMN 元素说明

| 元素 | 符号 | 说明 |
|------|------|------|
| **Start Event** | ○（圆圈）| 流程开始 |
| **End Event** | ●（粗圆圈）| 流程结束 |
| **User Task** | □（圆角矩形）| 人工任务，由用户完成 |
| **Service Task** | □（齿轮图标）| 自动任务，由系统执行 |
| **Exclusive Gateway**| ◇（X 标记）| 排他网关，条件分支 |
| **Parallel Gateway**| ◇（+ 标记）| 并行网关，并行分支/汇合 |
| **Sequence Flow** | →（箭头）| 连线，任务间的流转 |

---

## 三、Flowable 核心概念

### 3.1 引擎架构

```
Flowable 引擎架构：

┌───────────────────────────────────────────┐
│             Flowable Engine               │
│  ┌─────────┐ ┌──────────┐ ┌───────────┐  │
│  │ Process  │ │ Runtime  │ │ Identity  │  │
│  │Engine    │ │Service   │ │Service    │  │
│  ├─────────┤ ├──────────┤ ├───────────┤  │
│  │ History │ │ Manager  │ │ Form      │  │
│  │Service  │ │Service   │ │Service    │  │
│  └─────────┘ └──────────┘ └───────────┘  │
└───────────────────────────────────────────┘
```

### 3.2 核心对象

| 对象 | 说明 | 对应数据库表 |
|------|------|-------------|
| **ProcessDefinition** | 流程定义（BPMN 文件）| ACT_RE_PROCDEF |
| **ProcessInstance** | 流程实例（定义的一次运行）| ACT_RU_EXECUTION |
| **Task** | 任务（流程中的某个环节）| ACT_RU_TASK |
| **Execution** | 执行流（流程的运行路径）| ACT_RU_EXECUTION |
| **Variable** | 流程变量（流程中的数据）| ACT_RU_VARIABLE |
| **Deployment** | 部署（部署 BPMN 文件）| ACT_RE_DEPLOYMENT |

**它们的关系：**

```
流程定义（模板） → 部署 → 流程实例（运行）
  leave-process         leave-apply:01

流程实例 → 包含 → 任务（环节）
                    ├── 张三审批
                    └── 李四审批
```

---

## 四、数据库表结构

Flowable 使用多张表来存储流程运行时和历史的全部数据。

### 4.1 表命名规则

| 前缀 | 说明 |
|------|------|
| ACT_RE_* | 静态信息（Repository）：流程定义、模型 |
| ACT_RU_* | 运行时信息（Runtime）：正在执行的流程实例、任务、变量 |
| ACT_HI_* | 历史信息（History）：已完成的流程实例、任务 |
| ACT_ID_* | 身份信息（Identity）：用户、组（一般不使用）|
| ACT_GE_* | 通用信息（General）：属性、事件日志 |

### 4.2 核心表

```sql
-- 流程定义表
ACT_RE_PROCDEF      -- 流程定义的元数据
ACT_RE_DEPLOYMENT   -- 部署记录
ACT_RE_MODEL        -- 模型

-- 运行时表
ACT_RU_EXECUTION    -- 执行流（流程实例）
ACT_RU_TASK         -- 运行中的任务
ACT_RU_VARIABLE     -- 流程变量
ACT_RU_IDENTITYLINK -- 参与者
ACT_RU_JOB          -- 定时任务

-- 历史表
ACT_HI_PROCINST     -- 已完成的流程实例
ACT_HI_TASKINST     -- 历史任务
ACT_HI_VARINST      -- 历史变量
ACT_HI_ACTINST      -- 活动实例（流程经过的节点）
```

---

## 五、BPMN 文件示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://flowable.org/bpmn">
    
    <process id="leaveProcess" name="请假流程" isExecutable="true">
        <!-- 开始事件 -->
        <startEvent id="startEvent" name="开始"/>
        
        <!-- 用户任务：填写请假单 -->
        <userTask id="fillForm" name="填写请假单"
                  assignee="${applyUser}"/>
        
        <!-- 用户任务：组长审批 -->
        <userTask id="managerApprove" name="组长审批"
                  assignee="${manager}"/>
        
        <!-- 排他网关 -->
        <exclusiveGateway id="gateway" name="审批结果"/>
        
        <!-- 结束事件：通过 -->
        <endEvent id="approvedEnd" name="审批通过"/>
        
        <!-- 用户任务：驳回修改 -->
        <userTask id="rework" name="修改申请"
                  assignee="${applyUser}"/>
        
        <!-- 连线 -->
        <sequenceFlow id="flow1" sourceRef="startEvent" targetRef="fillForm"/>
        <sequenceFlow id="flow2" sourceRef="fillForm" targetRef="managerApprove"/>
        <sequenceFlow id="flow3" sourceRef="managerApprove" targetRef="gateway"/>
        <sequenceFlow id="flow4" sourceRef="gateway" targetRef="approvedEnd">
            <conditionExpression xsi:type="tFormalExpression">
                <![CDATA[${approved == true}]]>
            </conditionExpression>
        </sequenceFlow>
        <sequenceFlow id="flow5" sourceRef="gateway" targetRef="rework">
            <conditionExpression xsi:type="tFormalExpression">
                <![CDATA[${approved == false}]]>
            </conditionExpression>
        </sequenceFlow>
        <sequenceFlow id="flow6" sourceRef="rework" targetRef="fillForm"/>
    </process>
</definitions>
```

**流程演示：**

```
开始 → 填写请假单 → 组长审批 → 审批通过？→ Yes → 结束
                                  │
                                  └→ No → 修改申请 → 回到填写请假单
```

---

## 六、总结

### 核心概念速查

| 概念 | 一句话 |
|------|--------|
| BPMN | 流程定义的标准 XML 格式 |
| ProcessDefinition | 一个 BPMN 文件解析后的流程模板 |
| ProcessInstance | 流程的一次运行 |
| Task | 流程中的一个环节（用户任务、服务任务）|
| Variable | 流程中携带的数据 |
| Deployment | 部署 BPMN 文件到引擎 |

**下一篇：** [工作流（二）：Spring Boot 集成 Flowable 与工作流示例]({{< relref "post/workflow-flowable-spring-boot" >}})
