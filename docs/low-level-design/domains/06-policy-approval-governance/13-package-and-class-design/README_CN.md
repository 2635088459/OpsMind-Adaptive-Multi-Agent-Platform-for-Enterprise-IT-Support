# 13 Package And Class Design

## 服务形态

建议实现为独立服务：

```text
services/policy-approval-governance-service/
```

## 技术栈

06 属于核心治理后端，使用共享技术基线中的 Java 核心后端栈：

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

## 包结构

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

## 主要服务

- `PolicyDecisionService`：policy version 选择、规则评估、decision snapshot。
- `ApprovalService`：approval request、grant/deny/cancel。
- `ApprovalExpiryService`：扫描过期审批并发布 `approval.expired.v1`。
- `PolicyAdminService`：draft/review/publish/deprecate。
- `GovernanceAuditService`：审计写入和查询。
- `OutboxDispatchService`：调度并发布治理 outbox events。

## 依赖方向

`domain` 不依赖 Spring、JPA、RabbitMQ、identity provider 或 HTTP DTO。

`application` 编排 use case，依赖 domain repository / port 接口。

`infrastructure` 实现 persistence、messaging、identity、notification、rule evaluator 和 audit integrity。

`api` 只做请求/响应转换、认证上下文提取和 validation，不包含业务规则。

测试必须用 ArchUnit 保证 domain 不依赖 Spring/JPA，避免核心治理规则被框架污染。
