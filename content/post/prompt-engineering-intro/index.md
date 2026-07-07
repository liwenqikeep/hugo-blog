---
title: "提示词工程入门：如何让 LLM 输出你想要的结果"
date: 2024-04-12
draft: false
categories: ["AI"]
tags: ["LLM", "Prompt Engineering", "提示词工程", "ChatGPT"]
toc: true
---

## 前言

在理解了 LLM 的基本原理之后，你可能会遇到一个很现实的问题：**同样一个模型，别人用起来得心应手，自己用却常常答非所问。** 这背后往往不是模型本身的问题，而是输入——即提示词（Prompt）——的质量差距。

提示词工程（Prompt Engineering）就是研究 **如何设计输入文本，引导 LLM 生成高质量、符合预期的输出** 的一门方法论。它不需要修改模型，不需要训练数据，只需要你掌握一套系统化的沟通技巧。

本文将带你建立一套完整的提示词工程方法论，并用一个 Java 示例演示落地方式。

<!--more-->

## 提示词的核心原则

在进入具体技巧之前，先建立三个最根本的原则：

### 1. 清晰具体 > 模糊宽泛

LLM 本质上是一个"概率补全器"。你给它模糊的指令，它就会给出模糊的、平均化的回应。

```
❌ 差： "给我写点代码"
✅ 好： "用 Java 写一个基于 ConcurrentHashMap 的简单本地缓存实现"
```

模糊的 prompt 会让模型去"猜"你的意图，而清晰的 prompt 直接锁定输出空间。

### 2. 提供上下文 > 零背景提问

模型没有你对业务场景的理解，你需要告诉它**你是谁、要做什么、给谁用**。

```
❌ 差： "这个 SQL 怎么优化？"
✅ 好： "我有一个 MySQL 8.0 的表，数据量 500 万行，以下是慢查询 SQL……请分析并给出优化建议"
```

### 3. 指定格式 > 自由发挥

如果你有特定的输出格式要求，一定要在 prompt 中明确指定。

```
❌ 差： "列出 Java 中的并发工具"
✅ 好： "以 Markdown 表格的形式列出 Java 5 个核心并发工具类，格式为：| 类名 | 用途 | 适用场景 |"
```

## 五大核心技巧

### 技巧 1：角色设定

让 LLM 扮演一个特定的角色，可以显著提升输出质量。角色设定本质上是在为模型**缩小输出空间**，让它在某个专业领域内生成更精准的回答。

```
你是一名资深的 Java 后端工程师和技术面试官，有 10 年分布式系统开发经验。
请以面试官的身份，给出一份 Java 并发编程的中级面试题，包含 3 道题目和参考答案。
```

角色越具体，效果越好。可以指定：专业领域、经验年限、交付风格、受众群体。

### 技巧 2：思维链

对于需要多步推理的复杂问题，直接让 LLM 输出答案往往容易出错。**思维链（Chain-of-Thought, CoT）** 引导模型一步步推理，显著提升准确率。

```
❌ 直接提问：
"商品原价 200 元，打 8 折后再满 100 减 20，最终价格是多少？"

✅ 思维链：
"商品原价 200 元，打 8 折后再满 100 减 20，请一步步计算：
1. 先计算打 8 折后的价格：200 × 0.8 = 160
2. 判断是否满足满减条件：160 ≥ 100，满足
3. 计算最终价格：160 - 20 = 140"

✅ 加入"让我们一步步思考"（Let's think step by step）也可触发推理链
```

### 技巧 3：Few-Shot 示例

在 prompt 中给出几个输入-输出示例，模型就能"照葫芦画瓢"。

```
请将以下中文短语翻译成地道的英文技术用语，格式为：
中文 -> 英文

示例：
"数据库连接池" -> "Database Connection Pool"
"分布式事务" -> "Distributed Transaction"
"消息队列" -> "Message Queue"

请翻译：
"服务降级" ->
"限流熔断" ->
```

3~5 个示例通常效果最佳。示例太少模型抓不住规律，太多则浪费 token。

### 技巧 4：分步构建与迭代

不要期望一个 prompt 就能得到完美结果。正确的做法是**由简单到复杂，逐步迭代**：

```
第一轮： "请帮我写一个 Java 工具类，功能是从 URL 下载文件"
第二轮： "增加超时控制和断点续传功能"
第三轮： "把日志改为 SLF4J 格式，并增加异常处理"
```

与在同一个 prompt 中塞入所有需求相比，分步构建能让模型在每一步都聚焦，输出质量更高。

### 技巧 5：负面提示

告诉模型**不要做什么**，和告诉它要做什么同样重要。

```
请用 Java 写一个订单服务接口。
要求：
- 包含创建订单、取消订单、查询订单功能
- 使用 Spring Boot + MyBatis
- 接口命名遵循 RESTful 规范

不要做：
- 不要使用 Lombok（请手写 getter/setter）
- 不要写单元测试（只写业务逻辑）
- 不要用 WebFlux 或响应式编程
```

## Prompt 设计的通用框架

结合以上技巧，一个高质量的 prompt 通常包含以下要素：

```
┌─────────────────────────────────────────────────────┐
│             高质量 Prompt 结构                        │
├─────────────────────────────────────────────────────┤
│                                                     │
│  [角色]     你是一名……资深工程师                      │
│  [上下文]   我在做一个……项目，遇到了……问题            │
│  [任务]     请帮我……（具体描述要做什么）               │
│  [约束]     要求……限定……                              │
│  [格式]     请用 Markdown 表格 / JSON / 代码块输出    │
│  [示例]     比如：                                     │
│             输入 -> 输出                              │
│  [负面]     不要做……请不要……                          │
│                                                     │
└─────────────────────────────────────────────────────┘
```

并不是每个 prompt 都需要包含所有要素，但**角色、上下文、任务**三要素是基础配置。

## 常见误区

| 误区 | 说明 | 改进 |
|------|------|------|
| **过度复杂** | 一个 prompt 塞入太多需求 | 拆分为多轮对话 |
| **假设模型有常识** | 认为模型了解你的业务上下文 | 主动提供上下文 |
| **忽视格式约束** | 不给输出格式，得到非结构化结果 | 明确格式要求 |
| **信任盲从** | 不加验证直接使用模型输出 | 关键输出需要人工审查 |
| **一次定稿** | 期望一次写出完美 prompt | 多次迭代优化 |

## Java 集成示例

最后，通过一个简单的 Java 程序演示如何通过 OpenAI API 调用带有结构化 prompt 的 LLM。这里以 Spring Boot 的 `RestTemplate` 为例：

```java
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 简单的 LLM Prompt 调用示例
 */
public class PromptDemo {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    public static void main(String[] args) throws Exception {
        // 构建 prompt
        String userPrompt = buildPrompt();
        String response = callLLM(userPrompt);
        System.out.println("LLM 回复：\n" + response);
    }

    static String buildPrompt() {
        return """
                你是一名资深的 Java 后端工程师。
                请用简洁的方式解释 ConcurrentHashMap 和 Hashtable 的区别。
                请使用表格对比，包含：线程安全机制、性能、null 值支持三个维度。
                """;
    }

    static String callLLM(String userPrompt) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        ObjectMapper mapper = new ObjectMapper();

        // 构造请求体
        Map<String, Object> requestBody = Map.of(
            "model", "gpt-3.5-turbo",
            "messages", List.of(
                Map.of("role", "user", "content", userPrompt)
            ),
            "temperature", 0.7,
            "max_tokens", 500
        );

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(API_KEY);

        HttpEntity<String> request = new HttpEntity<>(
            mapper.writeValueAsString(requestBody), headers
        );

        // 发送请求
        ResponseEntity<JsonNode> response = restTemplate.exchange(
            API_URL,
            HttpMethod.POST,
            request,
            JsonNode.class
        );

        // 提取回复内容
        return response.getBody()
            .path("choices")
            .get(0)
            .path("message")
            .path("content")
            .asText();
    }
}
```

这个示例展示了：

- **角色设定**：你是一名资深的 Java 后端工程师
- **任务明确**：解释 ConcurrentHashMap 和 Hashtable 的区别
- **格式约束**：使用表格对比，指定三个维度
- **参数控制**：temperature=0.7 控制创造性，max_tokens=500 控制输出长度

## 总结

提示词工程不是玄学，而是一套**系统化的沟通方法论**。它的核心理念是：**你需要主动帮助模型理解你的意图**，而不是期望模型自动猜中你的心思。

记住三个关键原则：

1. **清晰具体** — 模糊的输入必然得到模糊的输出
2. **提供上下文** — 不给上下文就是让模型自由发挥
3. **迭代优化** — 好 prompt 是改出来的，不是写出来的

掌握了这些技巧，你会发现同一个模型，在你手里能发挥出完全不同的水平。下一次，我们可以聊聊 RAG（检索增强生成）——当 LLM 的知识不够用时，如何让它可以"查资料"。
