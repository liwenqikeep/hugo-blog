---
title: "支付系统架构设计（七）：资金账户系统设计"
date: 2021-10-22
draft: false
categories: ["支付系统"]
tags: ["支付", "资金账户", "余额设计", "流水"]
toc: true
---

## 前言

资金账户系统是支付系统**最敏感**的部分——它记录和计算每一分钱的归属。账户系统的核心要求是：**在任何情况下，账务数据都不应该出错。**

本文设计一个满足支付系统要求的资金账户体系，涵盖账户模型、复式记账、余额变更、并发控制等关键设计。

> **系列导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（六）：对账系统设计]({{< relref "post/payment-architecture-06" >}})
> - 本文：资金账户系统设计
> - [支付安全与风控]({{< relref "post/payment-architecture-08" >}})

<!--more-->

## 一、账户模型

### 1.1 核心账户分类

```
┌─────────────────────────────────────────────────────────┐
│                   账户分类体系                             │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  用户账户              ── 每个用户一个账户                  │
│  商户账户              ── 每个商户一个账户                  │
│  平台账户              ── 平台手续费收入账户                │
│  在途账户              ── 未结算资金暂存                   │
│  冻结账户              ── 冻结/锁定中的资金                 │
│  银行账户              ── 对公银行账户映射                  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 1.2 账户表设计

```sql
CREATE TABLE `account` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `account_no` varchar(32) NOT NULL COMMENT '账号',
    `account_type` varchar(20) NOT NULL COMMENT '账户类型：USER/MERCHANT/PLATFORM',
    `user_id` varchar(32) DEFAULT NULL COMMENT '用户 ID',
    `balance` bigint NOT NULL DEFAULT '0' COMMENT '可用余额（分）',
    `frozen` bigint NOT NULL DEFAULT '0' COMMENT '冻结金额（分）',
    `total_income` bigint NOT NULL DEFAULT '0' COMMENT '累计收入（分）',
    `total_expense` bigint NOT NULL DEFAULT '0' COMMENT '累计支出（分）',
    `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
    `status` varchar(10) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    `create_time` datetime NOT NULL,
    `update_time` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_no` (`account_no`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 1.3 流水表设计（append-only）

```sql
CREATE TABLE `account_journal` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `journal_no` varchar(32) NOT NULL COMMENT '流水号',
    `account_no` varchar(32) NOT NULL COMMENT '账号',
    `transaction_id` varchar(32) NOT NULL COMMENT '关联交易流水号',
    `amount` bigint NOT NULL COMMENT '变动金额（分）',
    `balance_before` bigint NOT NULL COMMENT '变动前余额',
    `balance_after` bigint NOT NULL COMMENT '变动后余额',
    `journal_type` varchar(10) NOT NULL COMMENT '类型：CREDIT/DEBIT',
    `biz_type` varchar(20) NOT NULL COMMENT '业务类型：PAYMENT/REFUND/WITHDRAW',
    `status` varchar(10) NOT NULL DEFAULT 'SUCCESS',
    `remark` varchar(255) DEFAULT NULL,
    `create_time` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_journal_no` (`journal_no`),
    KEY `idx_account_no` (`account_no`),
    KEY `idx_transaction_id` (`transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 二、复式记账

每笔资金变动同时产生两条流水：一条借方、一条贷方。

```
示例：用户 A 支付 100 元给商户 B

借方流水：用户 A 账户  -100 元  (DEBIT)
贷方流水：商户 B 账户  +100 元  (CREDIT)

任何时候：所有账户借方总额 = 贷方总额
          所有账户余额之和 = 0（有正有负）
```

```java
@Service
@Slf4j
public class DoubleEntryBookkeepingService {

    @Transactional
    public void bookkeeping(String transactionId, String fromAccount, String toAccount, long amount) {
        // 扣减方
        Account fromAcct = accountMapper.selectByNoForUpdate(fromAccount);
        long fromBalanceBefore = fromAcct.getBalance();
        fromAcct.setBalance(fromBalanceBefore - amount);
        accountMapper.updateByNoWithLock(fromAcct);

        // 入账方
        Account toAcct = accountMapper.selectByNoForUpdate(toAccount);
        long toBalanceBefore = toAcct.getBalance();
        toAcct.setBalance(toBalanceBefore + amount);
        accountMapper.updateByNoWithLock(toAcct);

        // 写入流水
        insertJournal(transactionId, fromAccount, -amount, fromBalanceBefore, fromAcct.getBalance(), "DEBIT");
        insertJournal(transactionId, toAccount, +amount, toBalanceBefore, toAcct.getBalance(), "CREDIT");
    }
}
```

## 三、并发控制

账户余额变更必须使用**乐观锁**或`SELECT ... FOR UPDATE`：

```java
@Update("UPDATE account SET balance = balance + #{amount}, version = version + 1 " +
        "WHERE account_no = #{accountNo} AND version = #{version} " +
        "AND (#{amount} >= 0 OR balance >= -#{amount})")
int updateBalanceWithLock(String accountNo, long amount, int version);

// 使用示例
public boolean changeBalance(String accountNo, long amount) {
    Account account = accountMapper.selectByNo(accountNo);
    int rows = accountMapper.updateBalanceWithLock(accountNo, amount, account.getVersion());
    if (rows == 0) {
        throw new ConcurrentModificationException("余额变更冲突");
    }
    return true;
}
```

## 四、资金安全设计原则

| 原则 | 说明 |
|------|------|
| **append-only** | 流水只增不改，不允许 UPDATE/DELETE |
| **先记账后入账** | 流水写入成功后才更新余额 |
| **乐观锁** | 所有余额变更带 version 校验 |
| **对账兜底** | 每日自动对账，发现差异立即告警 |
| **人工审核** | 大额操作（>N 万）需二次确认 |
