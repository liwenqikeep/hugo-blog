package com.payment.core.service;

import com.payment.core.domain.model.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 账户入账服务（幂等）
 */
@Slf4j
@Service
public class AccountJournalService {

    private final TransactionTemplate transactionTemplate;

    public AccountJournalService(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 入账（幂等）
     * 利用 UNIQUE KEY 保证同一个流水不会重复入账
     */
    public JournalResult credit(String transactionId, String accountNo, Money amount) {
        try {
            // 实际开发中调用 Mapper 插入 journal 表
            // accountJournalMapper.insert(journal);
            log.info("入账成功: transactionId={}, accountNo={}, amount={}",
                    transactionId, accountNo, amount.toYuanString());
            return JournalResult.success();

        } catch (DuplicateKeyException e) {
            // 唯一键冲突 → 已入账，直接返回成功
            log.info("入账幂等命中: transactionId={}", transactionId);
            return JournalResult.duplicated();
        }
    }
}
