---
title: "Git（二）：Pull Request 与 Code Review"
date: 2019-03-22
draft: false
categories: ["工具"]
tags: ["Git", "Pull Request", "Code Review", "PR", "协作"]
toc: true
---

## 前言

Pull Request（PR）是现代 Git 协作的核心机制。它不仅是代码合并的入口，更是**代码审查（Code Review）**的载体。一个良好的 PR 流程能显著提升代码质量、促进知识分享。

本文覆盖 PR 的创建流程、Code Review 的最佳实践，以及常见的协作模式。

<!--more-->

## 一、Pull Request 流程

### 1.1 标准 PR 工作流

```
1. 从 main 创建功能分支
2. 开发并提交代码
3. 推送到远程仓库
4. 创建 Pull Request
5. 进行 Code Review
6. 修改反馈
7. 合并到 main
8. 删除功能分支
```

```bash
# 完整 PR 流程
# 1. 拉取最新代码
git checkout main
git pull --rebase

# 2. 创建功能分支
git checkout -b feat/user-login

# 3. 开发代码，多次提交
git add -A && git commit -m "feat: 添加用户登录接口"
git add -A && git commit -m "feat: 添加登录验证逻辑"
git add -A && git commit -m "test: 添加登录测试用例"

# 4. 推送到远程
git push -u origin feat/user-login

# 5. 在 GitHub/GitLab 上创建 PR
#    选择源分支 feat/user-login → 目标分支 main

# 6. 审查通过后，合并
#    建议使用 "Squash and Merge" 或 "Rebase and Merge"
```

### 1.2 PR 的三种合并方式

```bash
# 1. Create a Merge Commit（创建合并提交）
#    保留所有提交历史
#    适用：多人协作的长分支
git merge --no-ff feat/user-login

# 2. Squash and Merge（压缩合并）
#    将所有提交压缩为一个提交
#    适用：功能分支的多次小提交
git merge --squash feat/user-login
git commit -m "feat: 添加用户登录功能"

# 3. Rebase and Merge（变基合并）
#    线性历史，保持提交粒度
#    适用：每个提交都有意义
git rebase main feat/user-login
git checkout main
git merge feat/user-login
```

---

## 二、PR 编写规范

### 2.1 PR 标题

```
PR 标题格式：[类型] 简短描述

类型：
  feat:    新功能
  fix:     修复 Bug
  refactor: 重构
  test:    测试
  docs:    文档
  style:   格式（不影响代码运行）
  chore:   构建/工具
```

### 2.2 PR 描述模板

```markdown
## 变更内容
简要描述这次 PR 做了什么

## 背景/原因
为什么需要这个变更？

## 变更清单
- [x] 添加用户登录接口
- [x] 添加 Token 验证
- [ ] 添加登录日志

## 如何测试
1. 启动服务
2. 调用 POST /api/login
3. 验证返回 Token

## 相关 Issue
Closes #123

## 截图（可选）
<!-- UI 变更请附截图 -->

## 检查清单
- [ ] 代码符合规范
- [ ] 已添加/更新测试
- [ ] 文档已更新
- [ ] 本地测试通过
```

---

## 三、PR 的粒度

### 3.1 什么是一个好的 PR 粒度？

```
✅ 好的 PR：
  - 功能单一，只做一件事
  - 改动量适中（建议 < 400 行）
  - 有清晰的描述
  - 包含测试

❌ 不好的 PR：
  - 改动上千行
  - 同时包含多个不相关的功能
  - 没有测试
  - 没有描述
```

### 3.2 过大 PR 的拆分

```
如果一次改动太大，拆分为多个 PR：

PR 1：定义接口和数据模型
PR 2：实现核心逻辑
PR 3：添加测试
PR 4：集成和文档

每个 PR 可以独立审查和合并。
```

---

## 四、Code Review 最佳实践

### 4.1 Reviewer 清单

```
检查清单：

功能
├── 代码是否实现了需求？
├── 有没有遗漏边界情况？
└── 异常处理是否完善？

设计
├── 是否符合 SOLID 原则？
├── 是否有过度设计？
├── 接口设计是否合理？
└── 是否有重复代码？

性能
├── 是否有不必要的数据库查询？
├── 循环中是否做了耗时操作？
└── 是否可以加缓存？

安全
├── 用户输入是否做了校验？
├── 是否存在 SQL 注入风险？
├── 敏感信息是否正确处理？
└── 权限校验是否到位？

测试
├── 是否有单元测试？
├── 测试覆盖率是否合理？
└── 边界情况是否覆盖？
```

### 4.2 评论规范

```
✅ 好的评论：

"这里可能会抛出 NullPointerException，
建议加一个空值检查。"

"这个方法已经超过 100 行了，
建议拆分成几个小方法。"

❌ 不好的评论：

"这里不行。"（没有理由）

"改一下。"（没有具体建议）
```

### 4.3 审查速度

```
建议时间线：
- 创建 PR 后 4 小时内开始审查
- 24 小时内完成首轮审查
- 每次变更后 4 小时内复审

为什么快？
- 延迟越久，上下文切换成本越高
- 延迟越久，冲突风险越大
- 延迟越久，团队效率越低
```

---

## 五、代码审查工具

### 5.1 GitHub 的 Review 功能

```markdown
# GitHub Code Review 的三种状态

Comment（评论）：
  一般性建议，不需要强制修改

Approve（批准）：
  同意合并，可以合并 PR

Request Changes（请求变更）：
  必须修改后才能合并
```

### 5.2 GitLab 的 Review 功能

```markdown
# GitLab 的 Approval 规则

1. 可以配置最少批准人数（如 2 人）
2. 可以配置指定 reviewer
3. 合并前必须达到批准数量

# Merge Request 的 WIP 标记
在标题前加 "WIP:" 表示"正在进行中"
WIP: 添加用户登录功能
```

---

## 六、常见协作模式

### 6.1 Fork 模式（开源项目）

```
1. Fork 主仓库到自己的账号
2. Clone 自己的仓库到本地
3. 添加 upstream（原始仓库）
4. 创建分支开发
5. 推送到自己的仓库
6. 创建 Pull Request 到上游
```

```bash
# Fork 模式工作流
# Fork 后在本地配置
git clone git@github.com:my-account/repo.git
git remote add upstream git@github.com:original/repo.git

# 同步上游
git fetch upstream
git checkout main
git merge upstream/main

# 创建功能分支
git checkout -b bugfix/fix-typo
git add -A && git commit -m "fix: 修复拼写错误"
git push -u origin bugfix/fix-typo
# 在 GitHub 上创建 PR → 上游仓库
```

### 6.2 共享仓库模式（企业团队）

```
1. 所有成员使用同一个仓库
2. 从 main 创建分支
3. 推送到 origin
4. 创建 PR
5. 审查后合并
```

### 6.3 Release 分支模式

```bash
# 版本发布流程
# 1. 从 develop 创建 release 分支
git checkout -b release/1.2.0 develop

# 2. 在 release 分支上修复小 bug
git commit -m "fix: 修复发布前的 UI 问题"

# 3. 合并到 master 并打 tag
git checkout master
git merge --no-ff release/1.2.0
git tag -a v1.2.0 -m "v1.2.0"

# 4. 合并回 develop
git checkout develop
git merge --no-ff release/1.2.0

# 5. 删除 release 分支
git branch -d release/1.2.0
```

---

## 七、总结

### PR 流程速记

```
创建分支 → 开发 → 提交 → 推送 → 创建 PR → 审查 → 修改 → 合并
```

### Code Review 原则

```
1. 及时审查（4 小时内开始）
2. 关注设计而非格式（格式交给 linter）
3. 给出具体建议而非模糊评价
4. 改动量适中（< 400 行）
5. PR 描述清晰（为什么 + 怎么做）
```

**上一篇：** [Git（一）：基础命令与分支策略]({{< relref "post/git-basics-branch-strategy" >}})

**下一篇：** [Git（三）：冲突解决、Hooks 与协作规范]({{< relref "post/git-conflict-hooks-convention" >}})
