package com.payment.core.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateMachine;
import org.springframework.stereotype.Service;

/**
 * 支付单状态机服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderStateMachineService {

    private final StateMachine<PayOrderState, PayOrderEvent> stateMachine;

    /**
     * 发送事件，驱动状态变更
     *
     * @param orderId 支付单号
     * @param event   触发事件
     * @return true 表示转换接受，false 表示拒绝
     */
    public boolean sendEvent(String orderId, PayOrderEvent event) {
        stateMachine.start();

        log.debug("状态机发送事件: orderId={}, event={}", orderId, event);
        boolean accepted = stateMachine.sendEvent(event);

        if (accepted) {
            log.info("状态转换成功: orderId={}, 新状态={}", orderId, stateMachine.getState().getId());
        } else {
            log.warn("状态转换被拒绝: orderId={}, event={}", orderId, event);
        }

        return accepted;
    }

    /**
     * 获取当前状态
     */
    public PayOrderState getCurrentState() {
        return stateMachine.getState().getId();
    }
}
