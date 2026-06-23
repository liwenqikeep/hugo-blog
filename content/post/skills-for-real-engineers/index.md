---
title: Skills For Real Engineers - 真实工程师的 AI 技能库
date: 2026-06-23
draft: false
categories: ["AI"]
tags: ["AI 编程", "Agent", "工程技能"]
---

# Skills For Real Engineers

[![skills.sh](https://skills.sh/b/mattpocock/skills)](https://skills.sh/mattpocock/skills)

这是 Matt Pocock 每天使用的 AI Agent 技能库，用于完成真正的工程工作——而不是"感觉编程"。

开发真正的应用程序很难。GSD、BMAD 和 Spec-Kit 等方法试图通过掌控流程来提供帮助。但这样做会剥夺你的控制权，并使过程中的 bug 难以解决。

这些技能被设计成小巧、易于适配和可组合的。它们适用于任何模型。基于数十年的工程经验。随意折腾它们，让它们成为你自己的。享受吧。

如果你想了解这些技能的更新和新技能，可以加入约 6 万名开发者的 Newsletter：

[订阅 Newsletter](https://www.aihero.dev/s/skills-newsletter)

## 快速开始（30 秒设置）

1. 运行 skills.sh 安装程序：

```bash
npx skills@latest add mattpocock/skills
```

2. 选择你想要的技能，以及你想安装到哪个编码 Agent 上。**确保选择 `/setup-matt-pocock-skills`**。

3. 在你的 Agent 中运行 `/setup-matt-pocock-skills`。它会：
   - 询问你想使用哪个问题跟踪器（GitHub、Linear 或本地文件）
   - 询问你在分类问题时应用什么标签（`/triage` 使用标签）
   - 询问你想在哪里保存我们创建的文档

4. 完成——你准备好了。

## 为什么这些技能存在

我构建这些技能是为了解决我在 Claude Code、Codex 和其他编码 Agent 中看到的常见失败模式。

### #1: Agent 没有做我想做的事

> "没有人确切知道自己想要什么"
>
> David Thomas & Andrew Hunt，[《程序员修炼之道》](https://www.amazon.co.uk/Pragmatic-Programmer-Anniversary-Journey-Mastery/dp/B0833F1T3V)

**问题**：软件开发中最常见的失败模式是对齐不当。你认为开发者知道你要什么。然后你看到他们构建的东西——意识到它根本没有理解你。

在 AI 时代也是如此。你和 Agent 之间存在沟通鸿沟。解决这个问题的方法是**深入访谈**——让 Agent 询问你关于你要构建的东西的详细问题。

**解决方案**是使用：

- [`/grill-me`](./skills/productivity/grill-me/SKILL.md) - 用于非代码场景
- [`/grill-with-docs`](./skills/engineering/grill-with-docs/SKILL.md) - 与 [`/grill-me`](./skills/productivity/grill-me/SKILL.md) 相同，但增加了更多好东西（见下文）

这些是我最受欢迎的技能。它们帮助你在开始之前与 Agent 对齐，并深入思考你要做的改变。每次你想做改变时都使用它们。

### #2: Agent 太过冗长

> "有了通用语言，开发人员之间的对话和代码表达式都源自同一个领域模型。"
>
> Eric Evans，[《领域驱动设计》](https://www.amazon.co.uk/Domain-Driven-Design-Tackling-Complexity-Software/dp/0321125215)

**问题**：在项目开始时，开发人员和为他们构建软件的人（领域专家）通常说不同的语言。

我在 Agent 中感受到了同样的紧张。Agent 通常被丢到一个项目中，被要求自己弄清楚行话。所以它们用 20 个词来表达 1 个词就够的意思。

**解决方案**是共享语言。这是一份帮助 Agent 解码项目中使用的行话的文档。

<details>
<summary>
示例
</summary>

这是我的 `course-video-manager` 仓库中的示例 [`CONTEXT.md`](https://github.com/mattpocock/course-video-manager/blob/076a5a7a182db0fe1e62971dd7a68bcadf010f1c/CONTEXT.md)。哪个更容易阅读？

- **之前**："当课程某部分中的课程被'实现'时（即在文件系统中获得位置）出现问题"
- **之后**："materialization cascade 出现问题"

这种简洁性在每次会话中都会带来回报。

</details>

这内置于 [`/grill-with-docs`](./skills/engineering/grill-with-docs/SKILL.md) 中。这是一种深入访谈，但有助于你与 AI 建立共享语言，并在 ADR 中记录难以解释的决策。

很难解释这有多强大。它可能是这个仓库中最酷的技巧。试一试，看看效果。

> [!TIP]
> 共享语言除了减少冗长之外还有许多其他好处：
>
> - **变量、函数和文件的命名保持一致**，使用共享语言
> - 因此，**Agent 更容易浏览代码库**
> - Agent **在思考上花费更少的 token**，因为它可以访问更简洁的语言

### #3: 代码不工作

> "始终采取小而明确的步骤。反馈率是你的速度限制。永远不要接受太大的任务。"
>
> David Thomas & Andrew Hunt，[《程序员修炼之道》](https://www.amazon.co.uk/Pragmatic-Programmer-Anniversary-Journey-Mastery/dp/B0833F1T3V)

**问题**：假设你和 Agent 在构建什么上对齐了。当 Agent _仍然_ 产出糟糕的代码时会发生什么？

是时候检查你的反馈循环了。如果没有关于其生成的代码实际如何运行的反馈，Agent 将在黑暗中飞行。

**解决方案**：你需要一套常规的反馈循环：静态类型、浏览器访问和自动化测试。

对于自动化测试，red-green-refactor 循环至关重要。这就是 Agent 先编写一个失败的测试，然后修复测试的地方。这有助于给 Agent 一致的反馈水平，从而产生更好的代码。

我构建了一个 **[`/tdd`](./skills/engineering/tdd/SKILL.md) 技能**，你可以插入任何项目中。它鼓励 red-green-refactor，并为 Agent 提供大量关于什么是好测试和坏测试的指导。

对于调试，我还构建了一个 **[`/diagnosing-bugs`](./skills/engineering/diagnosing-bugs/SKILL.md)** 技能，将最佳调试实践包装成一个简单的循环。

### #4: 我们构建了一团乱麻

> "_每天_都要投资于系统设计。"
>
> Kent Beck，[《极限编程解析》](https://www.amazon.co.uk/Extreme-Programming-Explained-Embrace-Change/dp/0321278658)

> "最好的模块是深度的。它们允许通过简单的接口访问大量功能。"
>
> John Ousterhout，[《软件设计哲学》](https://www.amazon.co.uk/Philosophy-Software-Design-2nd/dp/173210221X)

**问题**：大多数使用 Agent 构建的应用程序都很复杂且难以更改。因为 Agent 可以极大地加速编码，它们也会加速软件熵。代码库以前所未有的速度变得复杂。

**解决方案**是一种全新的 AI 驱动开发方法：关心代码的设计。

这内置于这些技能的每一层：

- [`/to-prd`](./skills/engineering/to-prd/SKILL.md) 在创建 PRD 之前询问你正在触及哪些模块

关键的是，[`/improve-codebase-architecture`](./skills/engineering/improve-codebase-architecture/SKILL.md) 帮助拯救已经变成一团乱麻的代码库。我建议每隔几天在代码库上运行一次。

### 总结

软件工程基础知识比以往任何时候都更重要。这些技能是我将基础知识浓缩为可重复实践的最佳尝试，帮助你交付职业生涯中最好的应用程序。享受吧。

## 参考

这些按一个轴线划分——谁可以调用它们。**用户调用的**技能只有在你输入时才能访问（例如 `/grill-me`）；它们的工作是编排。**模型调用的**技能可以由你_或_在任务适合时由 Agent 自动调用；它们持有可重用的纪律。用户调用的技能可能调用模型调用的技能，但永远不会调用另一个用户调用的技能。

### 工程类

我每天用于代码工作的技能。

**用户调用**

- **[ask-matt](./skills/engineering/ask-matt/SKILL.md)** — 询问哪种技能或流程适合你的情况。用户调用技能的路由器。
- **[grill-with-docs](./skills/engineering/grill-with-docs/SKILL.md)** — 同时构建项目领域模型的深入访谈，锐化术语并内联更新 `CONTEXT.md` 和 ADR。
- **[triage](./skills/engineering/triage/SKILL.md)** — 通过分类角色的状态机移动问题。
- **[improve-codebase-architecture](./skills/engineering/improve-codebase-architecture/SKILL.md)** — 扫描代码库寻找深化机会，以可视化 HTML 报告呈现，然后深入访谈你选择的任何一个。
- **[setup-matt-pocock-skills](./skills/engineering/setup-matt-pocock-skills/SKILL.md)** — 为工程技能配置此仓库（问题跟踪器、分类标签、领域文档布局）。在使用其他工程技能之前每个仓库运行一次。
- **[to-issues](./skills/engineering/to-issues/SKILL.md)** — 使用垂直切片将任何计划、规范或 PRD 分解为可独立获取的问题。
- **[to-prd](./skills/engineering/to-prd/SKILL.md)** — 将当前对话转换为 PRD 并发布到问题跟踪器。不进行访谈——只是综合你已经在讨论的内容。
- **[prototype](./skills/engineering/prototype/SKILL.md)** — 构建一个可丢弃的原型来充实设计——对于状态/业务逻辑问题，可以是一个可运行的终端应用；对于 UI 问题，可以是多个可从一条路由切换的截然不同的 UI 变体。

**模型调用**

- **[diagnosing-bugs](./skills/engineering/diagnosing-bugs/SKILL.md)** — 用于困难 bug 和性能回归的严格诊断循环：复现 → 最小化 → 假设 → 插桩 → 修复 → 回归测试。
- **[tdd](./skills/engineering/tdd/SKILL.md)** — 使用 red-green-refactor 循环的测试驱动开发。一次构建一个垂直切片的功能或修复 bug。
- **[domain-modeling](./skills/engineering/domain-modeling/SKILL.md)** — 积极构建和锐化项目的领域模型——根据词汇表挑战术语，用边缘情况场景进行压力测试，并内联更新 `CONTEXT.md` 和 ADR。
- **[codebase-design](./skills/engineering/codebase-design/SKILL.md)** — 用于设计深度模块的共享纪律和词汇：大量行为隐藏在小型接口后面，放置在干净的接缝处，通过该接口可测试。

### 生产力

通用工作流工具，非代码特定。

**用户调用**

- **[grill-me](./skills/productivity/grill-me/SKILL.md)** — 毫不留情地采访你的计划或设计，直到决策树的每个分支都得到解决。
- **[handoff](./skills/productivity/handoff/SKILL.md)** — 将当前对话压缩成交接文档，以便另一个 Agent 可以继续工作。
- **[teach](./skills/productivity/teach/SKILL.md)** — 在多个会话中向用户教授新技能或概念，使用当前目录作为有状态的教学工作区。
- **[writing-great-skills](./skills/productivity/writing-great-skills/SKILL.md)** — 编写和编辑技能的参考：使技能可预测的词汇和原则。

**模型调用**

- **[grilling](./skills/productivity/grilling/SKILL.md)** — 毫不留情地采访用户关于计划或设计，直到决策树的每个分支都得到解决。`grill-me` 和 `grill-with-docs` 背后的可重用循环。

### 杂项

我保留但很少使用的工具。

- **[git-guardrails-claude-code](./skills/misc/git-guardrails-claude-code/SKILL.md)** — 设置 Claude Code 钩子，在危险 git 命令（push、reset --hard、clean 等）执行之前阻止它们。
- **[migrate-to-shoehorn](./skills/misc/migrate-to-shoehorn/SKILL.md)** — 将测试文件从 `as` 类型断言迁移到 @total-typescript/shoehorn。
- **[scaffold-exercises](./skills/misc/scaffold-exercises/SKILL.md)** — 创建具有部分、问题、解决方案和解释器的练习目录结构。
- **[setup-pre-commit](./skills/misc/setup-pre-commit/SKILL.md)** — 使用 lint-staged、Prettier、类型检查和测试设置 Husky pre-commit 钩子。