# 13 Package And Class Design

## Service Shape

Recommended independent service:

```text
services/policy-approval-governance-service/
```

## Technology Stack

06 is a core governance backend and uses the Java core backend stack from the shared technology baseline:

```text
Java 21
Spring Boot 3.5.x
Spring Web MVC
Spring Security
Spring Data JPA
JdbcTemplate
Jakarta Validation
Flyway
Spring AMQP
Resilience4j
springdoc-openapi
Micrometer + OpenTelemetry Java
JUnit 5
Mockito
Testcontainers
ArchUnit
```

## Package Structure

```text
src/main/java/com/opsmind/policygovernance/
  PolicyApprovalGovernanceApplication.java
  api/
    PolicyDecisionController.java
    ApprovalController.java
    PolicyAdminController.java
    GovernanceAuditController.java
    dto/
  application/
    PolicyDecisionService.java
    ApprovalService.java
    PolicyAdminService.java
    ApprovalExpiryService.java
    GovernanceAuditService.java
    OutboxDispatchService.java
  domain/
    policy/
      Policy.java
      PolicyVersion.java
      PolicyRule.java
      PolicyStatus.java
    decision/
      PolicyDecision.java
      DecisionEffect.java
      RiskLevel.java
      Constraint.java
      ReasonCode.java
    approval/
      ApprovalRequest.java
      ApprovalDecision.java
      ApprovalStatus.java
      ApprovalType.java
    audit/
      GovernanceAuditRecord.java
    shared/
      DomainEvent.java
      DomainException.java
  infrastructure/
    persistence/
      entity/
      repository/
      mapper/
    messaging/
      RabbitGovernanceEventPublisher.java
      GovernanceEventConsumer.java
      OutboxPublisher.java
    evaluator/
      RuleEvaluatorAdapter.java
    identity/
      IdentityAuthorizationAdapter.java
    notification/
      ApprovalNotificationAdapter.java
    audit/
      AuditIntegrityAdapter.java
  config/
    SecurityConfig.java
    RabbitConfig.java
    OpenApiConfig.java
    ObservabilityConfig.java
src/main/resources/
  db/migration/
  application.yml
src/test/java/com/opsmind/policygovernance/
  architecture/
  domain/
  application/
  integration/
  contract/
```

## Main Services

- `PolicyDecisionService`: policy version selection, rule evaluation, decision snapshot.
- `ApprovalService`: approval request, grant/deny/cancel.
- `ApprovalExpiryService`: scans expired approvals and publishes `approval.expired.v1`.
- `PolicyAdminService`: draft/review/publish/deprecate.
- `GovernanceAuditService`: audit write and query.
- `OutboxDispatchService`: dispatches and publishes governance outbox events.

## Dependency Direction

`domain` does not depend on Spring, JPA, RabbitMQ, identity provider, or HTTP DTOs.

`application` orchestrates use cases and depends on domain repository / port interfaces.

`infrastructure` implements persistence, messaging, identity, notification, rule evaluator, and audit integrity.

`api` only performs request/response mapping, authentication context extraction, and validation. It contains no business rules.

Tests must use ArchUnit to ensure domain does not depend on Spring/JPA, keeping core governance rules free from framework coupling.
