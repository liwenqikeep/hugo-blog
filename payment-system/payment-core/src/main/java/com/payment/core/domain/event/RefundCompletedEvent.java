package com.payment.core.domain.event;

import com.payment.core.domain.model.Money;

/**
 * 退款完成事件
 */
public class RefundCompletedEvent extends DomainEvent {

    private final String refundOrderId;
    private final String originalPaymentOrderId;
    private final Money refundAmount;

    public RefundCompletedEvent(String refundOrderId, String originalPaymentOrderId,
                                 Money refundAmount) {
        this.refundOrderId = refundOrderId;
        this.originalPaymentOrderId = originalPaymentOrderId;
        this.refundAmount = refundAmount;
    }

    public String getRefundOrderId() { return refundOrderId; }
    public String getOriginalPaymentOrderId() { return originalPaymentOrderId; }
    public Money getRefundAmount() { return refundAmount; }
}
