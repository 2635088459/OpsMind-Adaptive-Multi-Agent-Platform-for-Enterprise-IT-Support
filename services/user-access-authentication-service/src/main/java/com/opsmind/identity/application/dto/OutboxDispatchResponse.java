package com.opsmind.identity.application.dto;

public record OutboxDispatchResponse(int published, int retried, int failed) {
}
