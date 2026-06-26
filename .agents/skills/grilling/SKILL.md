---
name: grilling
description: Interview the user relentlessly about a plan or design. Use when the user wants to stress-test a plan before building, or uses any 'grill' trigger phrases.
---

# grilling

逐步拷打用户的设计或计划。每轮使用 `AskUserQuestion` 工具提供 2~4 个结构化选项让用户选择，而不是等待手动输入。

## 流程

1. 从最宽泛的问题开始（目标、受众、规模），逐步深入到细节（技术选型、实现策略、边界情况）。
2. 每轮只问 **一个问题**，用 `AskUserQuestion` 提供 2~4 个选项。
3. 选项要覆盖合理的主要方向，包含一个"其他（自行补充）"选项以便用户自由输入。
4. 根据用户的选择，决定下一轮的问题方向。
5. 持续追问直到方案足够清晰、决策树收敛。

## 核心原则

- **使用 AskUserQuestion 工具**：永远不要要求用户手动打字回答。选项应结构清晰、互斥。
- **一问一答**：每次只追问一个问题，不要同时问多个。
- **可以深挖代码库**：如果某个问题可以通过阅读代码回答，先去读代码再提问。
- **收敛为止**：持续缩小范围，直到所有关键决策都确定下来。

## 推荐选项格式

每个选项包含 `label`（简短显示文本）和 `description`（补充说明），例如：

```json
{
  "question": "你想写什么类型的文章？",
  "header": "文章类型",
  "options": [
    { "label": "教程型", "description": "面向初学者，step-by-step 实操" },
    { "label": "原理深度", "description": "面向进阶读者，深入源码和机制" },
    { "label": "综合型", "description": "兼顾原理和实操" }
  ],
  "multiSelect": false
}
```

对需要多项选择的场景（如"你想包含哪些主题"），设置 `multiSelect: true`。
