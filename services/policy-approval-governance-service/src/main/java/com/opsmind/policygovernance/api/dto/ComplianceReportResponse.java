package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.application.GovernanceAuditService;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SPEC-PG-031 (goal: "compliance reports"). {@code countsByAction} is keyed
 * by the {@link GovernanceAuditRecord.Action} enum name rather than the enum
 * itself, so this shape serializes as a plain JSON object of string keys
 * instead of relying on Jackson's enum-key handling.
 */
public record ComplianceReportResponse(
    int totalRecords,
    int activeRecords,
    int archivedRecords,
    Instant oldestRecordedAt,
    Instant newestRecordedAt,
    Map<String, Long> countsByAction,
    boolean chainIntact,
    int chainRecordsChecked,
    String firstBrokenRecordId
) {

    public static ComplianceReportResponse from(GovernanceAuditService.ComplianceReport report) {
        Map<String, Long> countsByAction = report.countsByAction().entrySet().stream()
            .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue));
        return new ComplianceReportResponse(
            report.totalRecords(), report.activeRecords(), report.archivedRecords(),
            report.oldestRecordedAt(), report.newestRecordedAt(), countsByAction,
            report.chainVerification().intact(), report.chainVerification().recordsChecked(),
            report.chainVerification().firstBrokenRecordId()
        );
    }
}
