package com.payment.core.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域事件基类
 */
public abstract class DomainEvent {

    private final String eventId;
    private final LocalDateTime occurredOn;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID().toString().replace("-", "");
        this.occurredOn = LocalDateTime.now();
    }

    public String getEventId() {
        return eventId;
    }

    public LocalDateTime getOccurredOn() {
        return occurredOn;
    }
}
