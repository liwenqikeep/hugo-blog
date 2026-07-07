package com.payment.core.domain.service;

/**
 * 幂等服务
 */
public interface IdempotencyService {

    /**
     * 尝试幂等拦截
     *
     * @param idempotentKey 幂等 Key
     * @param <T>           结果类型
     * @return 非 null 表示已有结果，null 表示第一次请求
     */
    <T> T tryProcess(String idempotentKey);

    /**
     * 保存幂等结果
     */
    void saveResult(String idempotentKey, Object result);
}
