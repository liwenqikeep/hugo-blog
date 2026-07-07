package com.payment.core.domain.model;

import com.payment.common.exception.PaymentException;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 交易流水 - 一次渠道调用记录
 */
@Getter
public class Transaction {

    /** 流水号 */
    private String transactionId;

    /** 所属支付单 ID */
    private String paymentOrderId;

    /** 渠道 */
    private String channel;

    /** 渠道手续费 */
    private Long channelFee;

    /** 状态 */
    private String status;

    /** 渠道返回的原始数据 */
    private String channelResponse;

    /** 渠道回调时间 */
    private LocalDateTime notifyTime;

    /** 失败原因 */
    private String failReason;

    /** 扩展参数 */
    private Map<String, String> extra;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ========== 工厂方法 ==========

    public static Transaction create(String transactionId, String paymentOrderId,
                                      String channel) {
        Transaction tx = new Transaction();
        tx.transactionId = transactionId;
        tx.paymentOrderId = paymentOrderId;
        tx.channel = channel;
        tx.status = "INIT";
        tx.extra = new HashMap<>();
        tx.createTime = LocalDateTime.now();
        tx.updateTime = LocalDateTime.now();
        return tx;
    }

    // ========== 领域行为 ==========

    public void initCall() {
        this.status = "CALLING";
        this.updateTime = LocalDateTime.now();
    }

    public void markSuccess(String channelResponse) {
        this.status = "SUCCESS";
        this.channelResponse = channelResponse;
        this.notifyTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public void markFail(String reason) {
        this.status = "FAIL";
        this.failReason = reason;
        this.updateTime = LocalDateTime.now();
    }
}
