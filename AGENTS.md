# Hugo 博客项目目录规范

## 目录结构

```
hugo-blog/
├── content/              # 内容目录 - 所有文章和页面
├── layouts/              # 模板目录 - HTML 模板
├── static/               # 静态资源 - 直接复制到网站
├── assets/               # 资源目录 - 待处理的资源
├── archetypes/           # 内容模板 - 新建文章的默认模板
├── hugo.yaml             # 配置文件 - 站点配置
├── static/css/           # 自定义 CSS
├── static/js/            # 自定义 JS
└── static/img/           # 图片资源
```

---

## 目录详解

### content/ - 内容目录

存放所有文章和页面内容。

```
content/
├── _index.md             # 首页内容（Markdown）
├── _index.zh-cn.md       # 中文首页内容
├── post/                 # 文章目录
│   ├── _index.md         # 文章列表页入口
│   └── java-basics/      # 单篇文章
│       └── index.md
├── page/                 # 页面目录（关于、归档等）
│   ├── about/            # 关于页面
│   ├── archives/         # 归档页面
│   └── search/           # 搜索页面
└── category/             # 分类目录（可选）
    └── _index.md
```

**说明**：
- `content/post/` - 博客文章，后台 front matter 指定 `categories` 和 `tags`
- `content/page/` - 独立页面，如关于、友链等
- `content/category/` - 分类说明页，可选

### layouts/ - 模板目录

存放所有 HTML 模板文件。

```
layouts/
├── _default/             # 默认模板
├── partials/             # 可复用组件
├── section/              # 列表页模板
├── shortcodes/           # 短代码
└── index.html            # 首页模板
```

### static/ - 静态资源

直接复制到网站根目录，不经过 Hugo 处理。

```
static/
├── css/style.css         # 自定义样式
├── js/main.js            # 自定义脚本
├── img/avatar.png        # 头像等图片
└── fav.ico               # 网站图标
```

### assets/ - 资源目录

需要 Hugo 处理的资源文件（如 SCSS、TypeScript）。

```
assets/
├── scss/custom.scss      # 自定义 SCSS 样式
└── img/                  # 图片资源
```

### hugo.yaml - 配置文件

站点配置，包括：
- 站点标题、描述
- 社交链接
- 分页设置
- 永久链接格式

---

## layouts/ 模板文件详解

### _default/ - 默认模板

| 文件 | 作用 | 访问路径 |
|------|------|----------|
| `baseof.html` | 基础模板，所有页面的父模板 | - |
| `list.html` | 文章列表模板 | `/post/` |
| `single.html` | 文章详情模板 | `/post/xxx/` |
| `page.html` | 独立页面模板 | `/about/` 等 |
| `section.html` | 区块列表模板 | - |
| `term.html` | 分类/标签页模板 | `/category/xxx/` |

### partials/ - 可复用组件

| 文件 | 作用 |
|------|------|
| `nav.html` | 导航栏 |
| `footer.html` | 页脚 |
| `pagination.html` | 分页导航 |
| `comments.html` | 评论区 |

### section/ - 列表页模板

| 文件 | 作用 | 访问路径 |
|------|------|----------|
| `post.html` | 文章列表 | `/post/` |
| `category.html` | 分类列表 | `/category/xxx/` |
| `tag.html` | 标签列表 | `/tag/xxx/` |
| `posts.html` | 所有文章列表 | - |

### shortcodes/ - 短代码

| 文件 | 作用 | 使用方式 |
|------|------|----------|
| `social.html` | 获取社交链接 | `{{< social "github" >}}` |

### index.html - 首页模板

首页布局，展示个人介绍和最新文章列表。

---

## front matter 规范

文章和页面的头部元数据：

```yaml
---
title: 文章标题
date: 2024-01-01              # 日期
draft: false                  # 是否草稿
categories: ["Java"]          # 分类
tags: ["Spring", "教程"]      # 标签
---
```

---

## 常用命令

```bash
# 本地预览
hugo server -D

# 生成静态文件
hugo

# 新建文章
hugo new post/my-article/index.md
```

---

## 注意事项

1. **模板优先级**：`layouts/section/` > `layouts/_default/`
2. **分类/标签**：在 front matter 中定义，无需手动创建
3. **多语言**：使用 `index.zh-cn.md` 后缀
4. **静态资源**：图片等放在 `static/` 目录
5. **自定义样式**：编辑 `static/css/style.css`

---

## 文章链接规范

在文章中引用其他文章时，必须使用 Hugo 的 `relref` shortcode 语法：

```markdown
# ✅ 正确格式
[文章标题]({{< relref "post/article-slug" >}})

# ❌ 错误格式
[文章标题](/post/article-slug/)
[文章标题](post/article-slug/)
```

**示例**：

```markdown
> 📚 **推荐阅读**
> - [Java 集合框架全景图]({{< relref "post/java-collections-01" >}})
> - [ArrayList 原理与扩容]({{< relref "post/java-collections-02" >}})
```

**注意事项**：
- 路径是相对于 `content/` 目录的路径
- 不需要包含文件扩展名 `.md`
- 系列文章之间应相互添加相关阅读链接

---

## Agent skills

### Issue tracker

GitHub Issues。See `docs/agents/issue-tracker.md`.

### Triage labels

默认标签词汇（needs-triage、needs-info、ready-for-agent、ready-for-human、wontfix）。See `docs/agents/triage-labels.md`.

### Domain docs

Single-context 结构，CONTEXT.md 在仓库根目录。See `docs/agents/domain.md`.