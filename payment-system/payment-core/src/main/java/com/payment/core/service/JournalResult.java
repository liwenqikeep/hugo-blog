package com.payment.core.service;

import lombok.Getter;

/**
 * 入账结果
 */
@Getter
public class JournalResult {

    private final boolean success;
    private final boolean duplicated;

    private JournalResult(boolean success, boolean duplicated) {
        this.success = success;
        this.duplicated = duplicated;
    }

    public static JournalResult success() {
        return new JournalResult(true, false);
    }

    public static JournalResult duplicated() {
        return new JournalResult(true, true);
    }
}
