package com.payment.core.domain.repository;

import com.payment.core.domain.model.PaymentOrder;
import com.payment.core.domain.model.Transaction;

import java.util.Optional;

/**
 * 支付单仓储接口
 */
public interface PaymentOrderRepository {

    void save(PaymentOrder order);

    Optional<PaymentOrder> findById(String orderId);

    Optional<PaymentOrder> findByBizOrderNo(String bizOrderNo);

    void saveTransaction(Transaction transaction);

    void updateTransaction(Transaction transaction);
}
