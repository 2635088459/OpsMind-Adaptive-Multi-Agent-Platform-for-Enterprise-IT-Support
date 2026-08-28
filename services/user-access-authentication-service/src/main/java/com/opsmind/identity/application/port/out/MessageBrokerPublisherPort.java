package com.opsmind.identity.application.port.out;

import com.opsmind.identity.application.model.OutboxEventRecord;

/** 13-package-and-class-design §Output Ports. The real, over-the-wire half of 08-transaction-and-outbox — only {@code OutboxDispatchService} calls this, never an application service directly. */
public interface MessageBrokerPublisherPort {

    void publish(OutboxEventRecord record);
}
