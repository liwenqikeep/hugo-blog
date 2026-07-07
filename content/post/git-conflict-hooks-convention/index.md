---
title: "Git（三）：冲突解决、Hooks 与协作规范"
date: 2019-03-24
draft: false
categories: ["工具"]
tags: ["Git", "冲突解决", "Git Hooks", "提交规范", "协作规范"]
toc: true
---

## 前言

冲突解决、Git Hooks 和协作规范是团队 Git 使用的进阶能力。冲突不可避免，关键在于如何高效解决；Hooks 可以在代码提交前自动检查问题；规范则让团队协作更加顺畅。

<!--more-->

## 一、冲突解决

### 1.1 冲突的产生

```
冲突发生在合并（merge）或变基（rebase）时，
两个分支修改了同一个文件的同一部分。

# 模拟冲突场景

# 分支 A：
git checkout -b branch-a
# 修改 README.md 第 10 行
git add -A && git commit -m "branch-a 修改"

# 分支 B：
git checkout main
git checkout -b branch-b
# 也修改 README.md 第 10 行，内容不同
git add -A && git commit -m "branch-b 修改"

# 尝试合并分支 B 到分支 A：
git checkout branch-a
git merge branch-b
# → 冲突：CONFLICT in README.md
```

### 1.2 冲突标记

```bash
# 冲突文件内容示例：
<<<<<<< HEAD
这是分支 A 的修改内容
=======
这是分支 B 的修改内容
>>>>>>> branch-b
```

```
<<<<<<< HEAD      ← 当前分支的代码
=======           ← 分隔线
>>>>>>> branch-b  ← 被合并分支的代码

需要手动选择保留哪部分，或修改为新的内容。
```

### 1.3 解决冲突的步骤

```bash
# 1. 查看冲突文件
git status
# 显示：both modified: README.md

# 2. 查看冲突详情
git diff
# 或使用可视化工具
git mergetool

# 3. 手动编辑冲突文件
# 删除冲突标记，保留需要的代码

# 4. 标记为已解决
git add README.md

# 5. 完成合并
git commit    # merge 冲突解决后自动进入提交
# 或
git rebase --continue  # rebase 冲突解决后
```

### 1.4 解决策略

```
策略 1：保留当前分支（accept ours）
  git checkout --ours -- conflicted-file.txt

策略 2：保留合并分支（accept theirs）
  git checkout --theirs -- conflicted-file.txt

策略 3：手动编辑（推荐）
  打开文件，编辑冲突区域
  删除冲突标记

策略 4：使用可视化工具
  git mergetool
  支持：vimdiff、meld、kdiff3 等
```

### 1.5 避免冲突的最佳实践

```
1. 经常拉取更新
   git pull --rebase （每天至少拉取一次）

2. 小粒度提交，频繁推送
   避免长时间不推送，导致冲突集中爆发

3. 单一职责的分支
   一个分支只改一件事

4. 良好的沟通
   修改公共文件时通知团队

5. 使用 .gitattributes 处理特定文件
   *.lock -merge  # 锁定文件不合并
```

```bash
# .gitattributes 配置示例
# 指定某些文件使用特定的合并策略
*.pdf -diff
*.png binary
*.lock -merge
```

---

## 二、Git Hooks

### 2.1 Hooks 概述

```
Git Hooks 是在 Git 执行特定操作时触发的脚本。
存放在 .git/hooks/ 目录下（以 .sample 结尾的示例文件）。

分为两类：
客户端 Hooks：在开发者本地触发
服务端 Hooks：在远程仓库触发
```

### 2.2 常用客户端 Hooks

```bash
# 客户端 Hooks 列表

pre-commit（提交前）：
  ├── 检查代码格式（eslint、checkstyle）
  ├── 运行单元测试
  └── 检查敏感信息

commit-msg（提交消息）：
  ├── 检查提交消息格式
  └── 验证是否符合约定

pre-push（推送前）：
  ├── 运行测试
  └── 检查分支命名
```

### 2.3 pre-commit 示例

```bash
#!/bin/bash
# .git/hooks/pre-commit

# 检查是否遗留了调试代码
if git diff --cached | grep -q "console.log\|debugger"; then
    echo "错误：提交中包含调试代码 (console.log / debugger)"
    exit 1
fi

# 检查敏感信息
if git diff --cached | grep -qi "password\|secret\|api_key"; then
    echo "警告：提交中可能包含敏感信息"
    # 只是警告，不阻止提交
fi

exit 0
```

### 2.4 commit-msg 示例

```bash
#!/bin/bash
# .git/hooks/commit-msg

commit_msg=$(cat "$1")

# 检查提交消息格式：type: message
if ! echo "$commit_msg" | grep -qE "^(feat|fix|refactor|test|docs|style|chore)\(.*\): .+"; then
    echo "错误：提交消息格式不正确"
    echo "正确格式：type(scope): message"
    echo "示例：feat(user): 添加用户登录功能"
    exit 1
fi

# 检查提交消息长度（第一行不超过 72 字符）
first_line=$(echo "$commit_msg" | head -n1)
if [ ${#first_line} -gt 72 ]; then
    echo "错误：提交消息第一行超过 72 字符"
    exit 1
fi
```

### 2.5 使用 Husky 管理 Hooks

```bash
# 使用 Node.js 的 Husky 更方便管理 Hooks

# 安装
npm install husky --save-dev

# package.json 配置
{
  "husky": {
    "hooks": {
      "pre-commit": "npm run lint-staged",
      "commit-msg": "commitlint -E HUSKY_GIT_PARAMS"
    }
  }
}

# husky.config.js
module.exports = {
  hooks: {
    'pre-commit': 'lint-staged',
    'commit-msg': 'commitlint -E HUSKY_GIT_PARAMS'
  }
};
```

---

## 三、协作规范

### 3.1 提交信息规范

```bash
# 推荐：Conventional Commits（约定式提交）
# https://www.conventionalcommits.org/

<type>(<scope>): <subject>
                 │
                 └── 简短描述（不超过 50 字符）

<body>（可选）
     │
     └── 详细描述（72 字符换行）

<footer>（可选）
        │
        └── Breaking Change、关联 Issue

# 示例
feat(user): 添加用户登录功能

实现了基于 JWT 的用户登录，支持 Token 刷新。
新增接口：POST /api/login、POST /api/refresh

Closes #123
BREAKING CHANGE: 登录接口返回数据结构变更
```

### 3.2 分支命名规范

```
# 通用规范：<type>/<short-description>

feature/user-login          # 新功能
bugfix/null-pointer-fix     # Bug 修复
release/1.2.0               # 发布版本
hotfix/urgent-security-fix  # 紧急修复
refactor/database-layer     # 重构
test/integration-tests      # 测试
```

### 3.3 最佳实践清单

```
1. 提交规范
   ├── 每个提交只做一件事
   ├── 提交消息清晰描述"做了什么"和"为什么"
   ├── 提交粒度适中（一个功能 = 一个提交）
   └── 不要提交未完成的代码（WIP 除外）

2. 代码管理
   ├── 不要直接提交到 main/master
   ├── 定期整理提交（rebase -i）
   ├── 推送前先拉取最新代码（pull --rebase）
   └── 删除已合并的分支

3. 敏感信息管理
   ├── 不要提交密码、Token、密钥
   ├── 使用 .gitignore 管理忽略文件
   └── 不慎提交后立即轮换密钥
```

### 3.4 .gitignore 配置

```bash
# .gitignore — 常见配置

# Maven
target/
*.jar
*.war

# Node
node_modules/
npm-debug.log*

# IDE
.idea/
*.iml
.vscode/

# OS
.DS_Store
Thumbs.db

# 环境配置
.env
application-local.yml

# 构建
dist/
build/
*.log
```

---

## 四、总结

### 冲突解决流程

```
冲突发生 → git status 查看冲突文件 → 手动编辑 → git add → 完成合并
                                                            │
                                                            ├── git commit（merge）
                                                            └── git rebase --continue（rebase）
```

### 规范速查

| 规范项 | 推荐 |
|--------|------|
| 提交格式 | `type(scope): message` |
| 分支命名 | `feature/user-login` |
| 分支策略 | GitHub Flow |
| Hook 管理 | Husky + lint-staged |
| 忽略文件 | .gitignore（IDE / 构建 / 密钥）|

**上一篇：** [Git（二）：Pull Request 与 Code Review]({{< relref "post/git-pull-request-code-review" >}})

**下一篇：** [Git（四）：高级技巧与最佳实践]({{< relref "post/git-advanced-techniques" >}})
