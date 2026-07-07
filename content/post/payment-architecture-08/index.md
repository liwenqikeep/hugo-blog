---
title: "支付系统架构设计（八）：支付安全与风控"
date: 2021-10-24
draft: false
categories: ["支付系统"]
tags: ["支付", "安全", "风控", "签名", "加密"]
toc: true
---

## 前言

支付系统是黑客攻击的**首要目标**。一次安全漏洞就可能造成直接的资金损失和不可挽回的声誉损害。支付安全不是某个单一技术的防护，而是一个多层防御体系。

本文覆盖支付系统的安全架构设计，包括通信安全、数据安全、风控引擎等核心主题。

> **系列导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（七）：资金账户系统设计]({{< relref "post/payment-architecture-07" >}})
> - 本文：支付安全与风控
> - [支付高可用与容灾]({{< relref "post/payment-architecture-09" >}})

<!--more-->

## 一、支付安全分层防御

```
┌─────────────────────────────────────────────────────────┐
│  第七层：业务风控        规则引擎、机器学习模型              │
│  第六层：数据安全        加密存储、脱敏展示、密钥管理        │
│  第五层：身份认证        OAuth2、API Key、双因素认证       │
│  第四层：应用安全        SQL 注入/XSS/CSRF 防护           │
│  第三层：传输安全        HTTPS/TLS 1.3、证书固定           │
│  第二层：网络安全        WAF、DDoS 防护、IP 黑白名单        │
│  第一层：物理安全        机房安全、网络隔离                 │
└─────────────────────────────────────────────────────────┘
```

## 二、通信安全

### 2.1 签名与验签

```java
@Component
public class SignUtil {

    private static final String SECRET_KEY = "merchant_secret_key";

    /**
     * 生成签名
     */
    public static String sign(Map<String, String> params) {
        // 1. 排序
        String sorted = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        // 2. 拼接密钥
        String payload = sorted + "&key=" + SECRET_KEY;

        // 3. HMAC-SHA256
        return HmacUtils.hmacSha256Hex(SECRET_KEY, payload).toUpperCase();
    }

    /**
     * 验签
     */
    public static boolean verify(Map<String, String> params, String sign) {
        String expected = sign(params);
        return expected.equals(sign);
    }
}
```

## 三、数据安全

### 3.1 敏感信息加密

```java
/**
 * 敏感数据加密存储
 * 使用 AES-256-GCM 加解密
 */
@Component
public class SensitiveDataEncryptor {

    private static final String KEY = System.getenv("ENCRYPT_KEY"); // 从环境变量获取

    public String encryptCardNo(String plainCardNo) {
        // AES-GCM 加密银行卡号
        return aesEncrypt(plainCardNo, KEY);
    }

    public String decryptCardNo(String encryptedCardNo) {
        return aesDecrypt(encryptedCardNo, KEY);
    }

    /**
     * 脱敏展示（仅显示后四位）
     */
    public static String maskCardNo(String cardNo) {
        if (cardNo == null || cardNo.length() < 4) return "****";
        return "**** **** **** " + cardNo.substring(cardNo.length() - 4);
    }
}
```

## 四、风控引擎

### 4.1 风控规则

```
典型风控规则：

金额规则：
  单笔金额 > 50000       → 拦截，人工审核
  单日累计 > 100000      → 拦截
  频繁小额（1 分钟内 > 5 次）→ 临时冻结

用户行为规则：
  新注册用户首次支付 > 5000  → 加强验证
  设备指纹匹配失败          → 拦截
  IP 异地登录              → 二次验证

商户规则：
  新商户首日交易 > 10000   → 延迟结算
  商户退款率 > 30%          → 风控审查
```

### 4.2 风控引擎实现

```java
@Component
@Slf4j
public class RiskControlEngine {

    private final List<RiskRule> rules;

    public RiskControlEngine(List<RiskRule> rules) {
        this.rules = rules;
    }

    /**
     * 风控检查
     */
    public RiskResult evaluate(RiskContext context) {
        for (RiskRule rule : rules) {
            RiskResult result = rule.evaluate(context);
            if (!result.isPass()) {
                log.warn("风控拦截: rule={}, orderId={}, reason={}",
                        rule.getRuleName(), context.getOrderId(), result.getReason());
                return result;
            }
        }
        return RiskResult.pass();
    }
}

public interface RiskRule {
    String getRuleName();
    RiskResult evaluate(RiskContext context);
}

@Component
public class AmountLimitRule implements RiskRule {
    @Override
    public String getRuleName() { return "单笔金额限制"; }

    @Override
    public RiskResult evaluate(RiskContext ctx) {
        if (ctx.getAmount() > 50_000_00L) { // 50 万元
            return RiskResult.reject("超过单笔限额");
        }
        return RiskResult.pass();
    }
}
```

## 五、安全审计

所有安全相关操作必须记录审计日志：

| 审计事件 | 记录内容 |
|---------|---------|
| 登录认证 | 用户、IP、时间、结果 |
| 敏感操作 | 操作人、操作类型、操作对象 |
| 风控拦截 | 规则、原因、请求详情 |
| 密钥操作 | 密钥轮转、访问记录 |
| 权限变更 | 角色、权限、操作人 |
