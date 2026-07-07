package com.payment.core.domain.model;

import com.payment.common.exception.PaymentException;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 支付单 - 支付上下文的聚合根
 */
@Getter
public class PaymentOrder {

    /** 全局唯一订单号 */
    private String orderId;

    /** 商户订单号 */
    private String bizOrderNo;

    /** 支付金额 */
    private Money amount;

    /** 状态 */
    private String status;

    /** 支付渠道 */
    private String channel;

    /** 用户 ID */
    private String userId;

    /** 商户 ID */
    private String merchantId;

    /** 支付完成时间 */
    private LocalDateTime paidTime;

    /** 交易流水列表 */
    private List<Transaction> transactions;

    /** 失败原因 */
    private String failReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ========== 工厂方法 ==========

    public static PaymentOrder create(String orderId, String bizOrderNo,
                                       Money amount, String userId,
                                       String merchantId) {
        PaymentOrder order = new PaymentOrder();
        order.orderId = orderId;
        order.bizOrderNo = bizOrderNo;
        order.amount = amount;
        order.userId = userId;
        order.merchantId = merchantId;
        order.status = "INIT";
        order.transactions = new ArrayList<>();
        order.createTime = LocalDateTime.now();
        order.updateTime = LocalDateTime.now();
        return order;
    }

    // ========== 领域行为 ==========

    /**
     * 发起支付
     */
    public void startPay(String channel) {
        if (!"INIT".equals(status)) {
            throw PaymentException.paramError("支付单状态异常，无法发起支付");
        }
        this.channel = channel;
        this.status = "PAYING";
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 支付成功
     */
    public void complete() {
        if (!"PAYING".equals(status)) {
            throw PaymentException.paramError("支付单状态异常，无法完成支付");
        }
        this.status = "SUCCESS";
        this.paidTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 支付失败
     */
    public void fail(String reason) {
        if (!"PAYING".equals(status)) {
            throw PaymentException.paramError("支付单状态异常");
        }
        this.status = "FAIL";
        this.failReason = reason;
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 关闭支付单
     */
    public void close() {
        if ("SUCCESS".equals(status) || "REFUNDED".equals(status)) {
            throw PaymentException.paramError("已成功的支付单不可关闭");
        }
        this.status = "CLOSED";
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 添加流水
     */
    public void addTransaction(Transaction transaction) {
        this.transactions.add(transaction);
    }
}
