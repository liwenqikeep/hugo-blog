---
title: "Git（一）：基础命令与分支策略"
date: 2019-03-20
draft: false
categories: ["工具"]
tags: ["Git", "基础命令", "分支策略", "Git Flow", "Trunk Based"]
toc: true
---

## 前言

Git 是现代软件开发中必不可少的版本控制工具。理解 Git 的核心概念——**工作区、暂存区、本地仓库、远程仓库**——以及常见分支策略，是团队协作的基础。

本文从 Git 的本地工作流程出发，覆盖日常最常用的命令，并对比主流的三种分支策略。

<!--more-->

## 一、Git 的核心模型

### 1.1 四个区域

```
工作区（Working Directory）
    │  git add
    ▼
暂存区（Staging Area / Index）
    │  git commit
    ▼
本地仓库（Local Repository）
    │  git push
    ▼
远程仓库（Remote Repository）
```

### 1.2 文件状态

```
Untracked（未跟踪）→ git add → Staged（已暂存）
Modified（已修改） → git add → Staged（已暂存）
Staged（已暂存）   → git commit → Unmodified（未修改）
```

```bash
git status             # 查看当前文件状态
git status -s          # 简短输出
```

---

## 二、日常基础命令

### 2.1 配置

```bash
# 首次使用配置
git config --global user.name "Your Name"
git config --global user.email "your@email.com"
git config --global core.editor vim

# 常用配置
git config --global pull.rebase true       # pull 时使用 rebase
git config --global push.default current   # push 当前分支
git config --global alias.lg "log --oneline --graph --all"  # 别名

# 查看配置
git config --list
```

### 2.2 仓库操作

```bash
# 初始化新仓库
git init

# 克隆远程仓库
git clone git@github.com:user/repo.git
git clone https://github.com/user/repo.git

# 克隆指定分支
git clone -b develop git@github.com:user/repo.git
```

### 2.3 基本操作

```bash
# 添加文件到暂存区
git add file.txt             # 添加单个文件
git add .                    # 添加所有变更
git add -p                   # 交互式选择修改片段

# 提交
git commit -m "feat: 添加用户登录功能"
git commit -am "fix: 修复空指针异常"   # 跳过暂存区（仅跟踪的文件）

# 推送
git push origin main         # 推送到远程
git push -u origin feature   # 首次推送到远程并建立追踪

# 拉取
git pull                     # 拉取并合并
git pull --rebase            # 拉取并变基（推荐）

# 查看状态和日志
git status
git log --oneline --graph --all
git log -p                   # 查看详细 diff
git log --author="name"      # 按作者筛选
```

### 2.4 分支操作

```bash
# 查看分支
git branch                   # 本地分支
git branch -r                # 远程分支
git branch -a                # 所有分支

# 创建和切换
git branch feature           # 创建分支
git checkout feature         # 切换分支
git checkout -b feature      # 创建并切换（推荐）
git switch -c feature        # Git 2.23+ 新语法

# 删除分支
git branch -d feature        # 删除本地分支（已合并）
git branch -D feature        # 强制删除（未合并）
git push origin --delete feature  # 删除远程分支

# 重命名分支
git branch -m old new
```

---

## 三、分支策略

### 3.1 三种主流分支模型

| 分支模型 | 复杂度 | 发布频率 | 适用场景 |
|---------|:------:|:--------:|---------|
| **Git Flow** | 高 | 低频（版本发布）| 固定版本发布 |
| **GitHub Flow** | 低 | 高频（持续发布）| CI/CD 成熟 |
| **Trunk Based** | 最低 | 极高（每日多次）| 极限编程 |

### 3.2 Git Flow

```
master ───●─────────●──────────●──── 生产发布
           \        /          /
develop ───●──●──●──●──●──●──●──── 开发主线
            \    /  \    /
feature ─────●──●    ●──●─────── 功能分支
                    \
release ─────────────●──●─────── 发布准备
                    /
hotfix ─────────────●──────────── 紧急修复
```

**分支说明：**

| 分支 | 用途 | 来源 | 合并到 |
|------|------|------|--------|
| master | 生产代码 | — | — |
| develop | 开发主线 | master | master |
| feature | 新功能 | develop | develop |
| release | 发布准备 | develop | master + develop |
| hotfix | 紧急修复 | master | master + develop |

```bash
# Feature 分支工作流
git checkout -b feature/login develop
# 开发...
git commit -m "feat: 添加登录功能"
git push -u origin feature/login
# 创建 PR → 审查 → 合并到 develop
git checkout develop
git merge --no-ff feature/login
git branch -d feature/login

# Release 分支
git checkout -b release/1.0.0 develop
# 修复 bug，更新版本号...
git checkout master
git merge --no-ff release/1.0.0
git tag -a v1.0.0 -m "v1.0.0"
git checkout develop
git merge --no-ff release/1.0.0
git branch -d release/1.0.0
```

### 3.3 GitHub Flow（推荐）

```
main ───●──────────●──────────●───────
         \        /          \
feature──●──●────  ●──●────  ●──●───
         PR      PR        PR

核心原则：
1. main 分支始终可部署
2. 新功能从 main 创建分支
3. 通过 Pull Request 提交变更
4. PR 审查后合并到 main
5. 合并后立即部署
```

```bash
# GitHub Flow 工作流
git checkout -b feature/login
# 开发...
git add .
git commit -m "feat: 添加登录功能"
git push -u origin feature/login
# 创建 Pull Request → 审查 → 合并到 main
git checkout main
git pull
git branch -d feature/login
```

### 3.4 Trunk Based Development

```
main ───●──●──●──●──●──●──●──●──●──
         |  |  |  |  |  |  |  |  |
        PR PR PR PR PR PR PR PR PR

特点：
- 所有开发者在 main 分支上工作
- 分支生命周期极短（< 1 天）
- 频繁提交、频繁集成
- 通过 Feature Flag 控制功能发布
```

---

## 四、合并 vs 变基

### 4.1 merge

```bash
# 创建合并提交，保留完整历史
git checkout main
git merge feature

# 结果：      main ──●──●────●──
#                   \      /
#               feature ●──●
```

```bash
# --no-ff：强制创建合并提交（保留分支历史）
git merge --no-ff feature
# 结果：      main ──●──●────●──
#                   \      /
#               feature ●──●
```

### 4.2 rebase

```bash
# 将当前分支的提交嫁接到目标分支顶部
git checkout feature
git rebase main

# 结果：      main ──●──●──●──
#                           \
#                     feature ●──●
```

### 4.3 什么时候用？

```bash
# 合并到公共分支时 → 使用 merge（保留历史）
# 更新本地分支时  → 使用 rebase（线性历史）

# 推荐流程
git checkout feature
git rebase main        # 将 feature 变基到最新 main
git checkout main
git merge feature      # 合并到 main
```

---

## 五、总结

### 命令速查

| 场景 | 命令 |
|------|------|
| 创建分支 | `git checkout -b branch-name` |
| 提交代码 | `git add -A && git commit -m "msg"` |
| 推送 | `git push -u origin branch-name` |
| 拉取更新 | `git pull --rebase` |
| 查看历史 | `git log --oneline --graph --all` |
| 暂存修改 | `git stash` |
| 恢复暂存 | `git stash pop` |

### 分支策略选择

```
小型团队 + CI/CD 成熟 → GitHub Flow（推荐）
固定版本发布 + 复杂环境 → Git Flow
追求极致集成速度 → Trunk Based
```

**下一篇：** [Git（二）：Pull Request 与 Code Review]({{< relref "post/git-pull-request-code-review" >}})
