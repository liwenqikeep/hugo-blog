---
name: grill-me
description: A relentless interview to sharpen a plan or design.
disable-model-invocation: true
---

# grill-me

逐步拷打用户的设计或计划。**你必须严格遵守以下结构化提问规则**。

## 核心规则

1. **每轮只用 AskUserQuestion 工具提一个问题**，提供 2~4 个结构化选项让用户选择。
2. **绝不允许**在文本中要求用户手动打字回答（如"请选 A/B/C/D"或写开放问题）。
3. 每个选项包含 `label`（简短）和 `description`（补充说明）。
4. **必须包含一个"其他（自行补充）"选项**，以便用户自由输入。
5. 根据用户的选择，决定下一轮的问题方向，逐步深入。
6. 持续追问直到方案足够清晰、决策树收敛。

## 推荐流程

1. 从最宽泛的问题开始：目标、受众、范围
2. 逐步深入到：技术选型、内容结构、具体细节
3. 可以读代码库来验证，不要问代码里已有答案的问题

## 禁止行为

- ❌ 一次问多个问题
- ❌ 用文本列出选项让用户手动输入
- ❌ 问代码中可以直接读到的信息
- ❌ 不提供"其他"选项

## AskUserQuestion 格式示例

```json
{
  "questions": [{
    "question": "你想写什么类型的文章？",
    "header": "文章类型",
    "options": [
      { "label": "教程型", "description": "面向初学者，step-by-step 实操" },
      { "label": "原理深度", "description": "面向进阶读者，深入源码和机制" },
      { "label": "综合型", "description": "兼顾原理和实操" },
      { "label": "其他", "description": "自行补充" }
    ],
    "multiSelect": false
  }]
}
```

对多项选择场景（如"你想包含哪些主题"），设置 `multiSelect: true`。
