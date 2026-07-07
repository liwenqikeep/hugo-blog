package com.payment.core.domain.model;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 金额值对象 - 不可变
 */
@Getter
public class Money {

    /** 金额，单位：分 */
    private final long amount;

    /** 币种 */
    private final Currency currency;

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("CNY");

    public Money(long amount) {
        this.amount = amount;
        this.currency = DEFAULT_CURRENCY;
    }

    public Money(long amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    // ========== 运算 ==========

    public Money add(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount + other.amount, this.currency);
    }

    public Money subtract(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount - other.amount, this.currency);
    }

    public Money multiply(int times) {
        return new Money(this.amount * times, this.currency);
    }

    // ========== 工具方法 ==========

    public String toYuanString() {
        BigDecimal yuan = BigDecimal.valueOf(amount)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return yuan.toString();
    }

    public static Money ofYuan(String yuan) {
        long fen = new BigDecimal(yuan)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        return new Money(fen);
    }

    public static Money zero() {
        return new Money(0L);
    }

    private void validateSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "币种不一致: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return amount == money.amount && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }
}
