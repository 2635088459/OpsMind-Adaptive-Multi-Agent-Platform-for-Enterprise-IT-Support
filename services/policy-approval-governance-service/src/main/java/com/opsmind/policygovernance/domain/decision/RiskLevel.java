package com.opsmind.policygovernance.domain.decision;

/**
 * Governance risk classification (01-domain-model §Value Objects).
 * Ordinal order is intentionally low-to-high so callers can compare
 * severity with {@code compareTo}.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
