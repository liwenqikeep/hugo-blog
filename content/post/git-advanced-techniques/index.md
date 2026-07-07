---
title: "Git（四）：高级技巧与最佳实践"
date: 2019-03-26
draft: false
categories: ["工具"]
tags: ["Git", "rebase", "cherry-pick", "stash", "bisect", "高级技巧"]
toc: true
---

## 前言

前三篇覆盖了 Git 的基础命令、PR 流程、冲突解决和协作规范。本篇聚焦于高级技巧——**rebase**、**cherry-pick**、**stash**、**bisect** 等，帮助你在日常工作中更高效地使用 Git。

<!--more-->

## 一、交互式变基 (rebase -i)

### 1.1 修改历史提交

```bash
# 交互式变基：修改最近 N 个提交
git rebase -i HEAD~3

# 进入编辑器后会显示：
pick 1234567 feat: 添加登录功能
pick 2345678 fix: 修复登录验证bug
pick 3456789 test: 添加登录测试

# Rebase 命令说明：
pick      = 保留该提交（默认）
reword    = 保留修改，但修改提交消息
squash   = 将该提交合并到前一个提交
fixup    = 类似 squash，但丢弃该提交的消息
drop     = 删除该提交
edit     = 暂停在该提交，允许修改

# 常用场景：合并多个小提交为一个
pick 1234567 feat: 添加登录功能
squash 2345678 fix: 登录验证bug    ← 合并到上一个
fixup 3456789 添加测试             ← 合并到上一个，丢弃消息

# 合并后：
pick 1234567 feat: 添加登录功能
```

### 1.2 典型场景：提交整理

```bash
# 场景：开发过程中有大量"wip"和"fix"提交
# 合并前整理为有意义的提交

# 原始提交
git log --oneline
a1b2c3d fix: 处理边界情况
b2c3d4e wip: 继续开发
c3d4e5f 添加单元测试
d4e5f6a wip: 开始开发新功能
e5f6a7b 初始化项目

# 交互式变基整理后
git rebase -i HEAD~5

pick e5f6a7b 初始化项目
squash d4e5f6a 开始开发新功能    ← 合并为"实现用户登录功能"
squash c3d4e5f 添加单元测试
squash b2c3d4e 继续开发
fixup a1b2c3d 处理边界情况

# 整理后
git log --oneline
a1b2c3d feat: 实现用户登录功能
e5f6a7b init: 初始化项目
```

### 1.3 变基时的冲突解决

```bash
# rebase 过程中遇到冲突：
git status                        # 查看冲突文件
# 解决冲突...
git add resolved-file.txt
git rebase --continue             # 继续变基

# 跳过当前提交
git rebase --skip

# 放弃变基，回到原始状态
git rebase --abort

# 注意：rebase 会重写历史
# 不要在已推送到远程的分支上 rebase
```

---

## 二、Cherry-Pick

### 2.1 选择性地应用提交

```bash
# cherry-pick：将某个分支的一个或多个提交应用到当前分支

# 基本用法
git cherry-pick 1234567                  # 应用单个提交
git cherry-pick 1234567..2345678         # 应用一系列提交
git cherry-pick 1234567 2345678          # 应用多个不连续的提交

# 常用选项
git cherry-pick -n 1234567               # 只应用修改，不自动提交
git cherry-pick -x 1234567               # 在提交消息中记录源提交
```

### 2.2 典型场景

```bash
# 场景 1：将 hotfix 应用到维护分支
git checkout release/1.0
git cherry-pick -x 1234567   # 应用 master 上的 hotfix

# 场景 2：从其他分支提取特定功能
git checkout feature/order
git cherry-pick feature/payment~2..feature/payment~5
# 从 payment 功能分支提取部分提交到 order 分支
```

---

## 三、Stash

### 3.1 暂存工作现场

```bash
# 临时保存当前未提交的修改（切换分支前使用）

# 暂存当前修改
git stash                        # 暂存所有已跟踪文件
git stash push -m "wip: 开发中"  # 带消息暂存
git stash -u                     # 包含未跟踪文件
git stash -a                     # 包含所有文件

# 恢复暂存
git stash pop                    # 恢复并删除暂存记录
git stash apply                  # 恢复但不删除暂存
git stash apply stash@{1}        # 恢复指定暂存

# 管理暂存列表
git stash list                   # 查看暂存列表
git stash drop stash@{0}         # 删除指定暂存
git stash clear                  # 清空所有暂存
```

### 3.2 典型场景

```bash
# 场景：正在开发 feature A，需要紧急修复线上 bug

# 1. 保存当前工作现场
git stash push -m "feature A 开发中"

# 2. 切换到 main 分支修复 bug
git checkout main
git checkout -b hotfix/urgent-fix
# 修复 bug...
git commit -m "fix: 紧急修复线上 bug"
git push -u origin hotfix/urgent-fix

# 3. 切回功能分支，恢复进度
git checkout feature-a
git stash pop      # 恢复工作现场
```

---

## 四、Bisect——二分查找

### 4.1 查找引入 Bug 的提交

```bash
# bisect 通过二分查找快速定位引入 Bug 的提交

# 开始二分查找
git bisect start
git bisect bad                    # 当前版本有 Bug
git bisect good v1.0.0            # v1.0.0 没有 Bug

# Git 会 checkout 中间的一个提交
# 测试这个提交是否有 Bug：
# 有 bug → git bisect bad
# 没有 bug → git bisect good

# 反复几次后，Git 会告诉你第一个引入 Bug 的提交
# 输出：1234567 is the first bad commit

# 结束二分查找
git bisect reset
```

### 4.2 自动化 bisect

```bash
# 编写脚本自动测试，让 bisect 自动执行
git bisect start HEAD v1.0.0
git bisect run npm test        # 使用测试命令自动判断
git bisect reset

# 自动化 bisect 过程：
# 1. Git checkout 一个中间提交
# 2. 执行 npm test
# 3. 如果测试失败（exit 非0）→ git bisect bad
# 4. 如果测试通过（exit 0）→ git bisect good
# 5. 继续二分，直到找到第一个坏的提交
```

---

## 五、Reset、Revert、Restore

### 5.1 撤销操作

```bash
# 撤销工作区的修改
git restore file.txt              # 撤销未暂存的修改
git checkout -- file.txt          # 旧语法

# 撤销暂存区的修改
git restore --staged file.txt     # 取消暂存
git reset HEAD file.txt           # 旧语法

# 撤销提交
git reset --soft HEAD~1           # 撤销提交，保留修改在暂存区
git reset --mixed HEAD~1          # 撤销提交，保留修改在工作区
git reset --hard HEAD~1           # 撤销提交，丢弃修改（危险！）

# 用新的提交撤销之前的提交（推荐用于远程分支）
git revert 1234567                # 创建新的提交，反转 1234567
git revert HEAD~3..HEAD           # 反转最近 3 个提交
```

### 5.2 reset vs revert

```bash
# reset：移动 HEAD 指针，会重写历史
#        只能用在本地未推送的分支

# revert：创建新的提交来撤销，不会重写历史
#        可以用于已推送的远程分支

# 场景：需要撤销已推送到远程的提交
git revert 1234567    # ✅ 安全
git push              # 推送到远程

git reset --hard HEAD~1   # ❌ 危险，需要 force push
git push --force-with-lease  # 不推荐
```

---

## 六、日志与搜索

### 6.1 查找提交

```bash
# 按条件搜索提交
git log --author="Tom"                        # 按作者
git log --since="2024-01-01"                  # 按时间
git log --grep="feat: 添加登录"               # 按提交消息
git log -S "functionName"                     # 按代码内容（pickaxe）
git log -p -- path/to/file                    # 查看文件的修改历史

# 查看某行代码是谁修改的
git blame file.txt                            # 每行代码的提交信息
git blame -L 10,20 file.txt                   # 只看 10-20 行

# 查看分支的提交差异
git log --oneline main..feature               # feature 有但 main 没有的提交
git log --oneline feature..main               # main 有但 feature 没有的提交
```

### 6.2 查找丢失的提交

```bash
# 使用 reflog 找回丢失的提交
# reflog 记录了 HEAD 的所有移动历史（本地，不会推送到远程）

git reflog
# 输出：
# 1234567 HEAD@{0}: reset: moving to HEAD~1
# 2345678 HEAD@{1}: commit: feat: 添加功能
# 3456789 HEAD@{2}: merge: branch-a

# 找回通过 reset 丢失的提交
git reset --hard HEAD@{1}        # 恢复到 reset 之前的状态

# reflog 记录默认保留 90 天
git config gc.reflogExpire 90.days
```

---

## 七、Git 配置与别名

### 7.1 常用别名

```bash
# ~/.gitconfig

[alias]
    lg = log --oneline --graph --all
    lgg = log --oneline --graph --all --decorate
    st = status -s
    br = branch -a
    co = checkout
    cb = checkout -b
    ci = commit
    pl = pull --rebase
    ps = push -u origin HEAD
    last = log -1 --stat
    unstage = restore --staged .
    undo = reset --soft HEAD~1
    amend = commit --amend --no-edit
    aliases = config --get-regexp alias

[diff]
    tool = vimdiff

[core]
    editor = vim
    autocrlf = input       # 使用 LF 换行符

[pull]
    rebase = true          # pull 默认使用 rebase

[push]
    default = current      # push 当前分支
```

---

## 八、总结

### 常用命令速记

```
交互式变基     git rebase -i HEAD~3
选择提交      git cherry-pick 1234567
暂存工作      git stash
恢复暂存      git stash pop
二分查找      git bisect start → bad/good → reset
撤销提交      git revert 1234567（安全） / git reset --hard（危险）
查看历史      git log --oneline --graph
搜索代码      git log -S "function"
找回丢失      git reflog
```

### 高级技巧原则

```
- rebase 整理提交：推送前使用，不要 rebase 已推送的分支
- cherry-pick：取其他分支的特定提交，注意不要打乱提交顺序
- stash 临时保存：切换分支前使用，记得 pop 恢复
- revert 撤销已推送的提交：安全，不会重写历史
- reflog 找回丢失的提交：90 天内都可以找回来
```

**上一篇：** [Git（三）：冲突解决、Hooks 与协作规范]({{< relref "post/git-conflict-hooks-convention" >}})

**系列索引：**
- [Git（一）：基础命令与分支策略]({{< relref "post/git-basics-branch-strategy" >}})
- [Git（二）：Pull Request 与 Code Review]({{< relref "post/git-pull-request-code-review" >}})
- [Git（三）：冲突解决、Hooks 与协作规范]({{< relref "post/git-conflict-hooks-convention" >}})
- [Git（四）：高级技巧与最佳实践]({{< relref "post/git-advanced-techniques" >}})
