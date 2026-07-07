package com.payment.core.service;

import com.payment.core.domain.model.Money;
import com.payment.core.domain.model.PaymentOrder;
import com.payment.core.domain.repository.PaymentOrderRepository;
import com.payment.core.state.PayOrderEvent;
import com.payment.core.state.PayOrderStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付回调处理服务（幂等）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayCallbackService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PayOrderStateMachineService stateMachineService;

    /**
     * 处理支付成功回调（幂等）
     */
    @Transactional
    public PaymentOrder handlePayCallback(String transactionId, String channelResult) {
        // 实际项目中：从数据库查询 Transaction
        // Transaction tx = transactionRepository.findById(transactionId);
        // PaymentOrder order = paymentOrderRepository.findById(tx.getPaymentOrderId())
        //         .orElseThrow(() -> new RuntimeException("支付单不存在"));

        // 此处为示例骨架，仅展示逻辑
        log.info("处理支付回调: transactionId={}, channelResult={}", transactionId, channelResult);

        // TODO: 实现完整逻辑
        // 1. 查询流水和支付单
        // 2. 幂等校验：已经成功的不再处理
        // 3. 更新流水状态
        // 4. 入账（幂等，利用唯一键）
        // 5. 状态机：驱动 PAYING → SUCCESS
        // 6. 发布领域事件

        return null;
    }
}
