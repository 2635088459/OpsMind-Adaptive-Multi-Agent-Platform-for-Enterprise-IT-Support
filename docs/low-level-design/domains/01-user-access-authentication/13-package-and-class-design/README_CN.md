# 13 包与类设计

采用六边形架构，依赖方向 `domain <- application <- adapters/api`：

```text
com.opsmind.identity
├── domain
│   ├── user/{UserIdentity,UserStatus,ExternalSubject}
│   ├── role/{RoleAssignment,RoleCode,ResourceScope}
│   ├── session/{UserSession,SessionStatus,AuthenticationAssurance}
│   ├── stepup/{StepUpChallenge,StepUpStatus,AuthorizationTarget}
│   ├── workload/{ServiceIdentity,ServiceIdentityStatus}
│   └── decision/{AuthorizationDecision,DecisionEffect,ReasonCode}
├── application
│   ├── command, query, service, dto, exception
│   └── port/{in,out}
├── infrastructure
│   ├── persistence/{jpa,mapper,adapter}
│   ├── keycloak, jwt, messaging, cache, audit, observability
├── api
│   ├── browser, internal, admin, event, error
└── config
```

Domain 为纯 Java，不依赖 Spring/JPA/JWT/HTTP。Application 只依赖 domain 和 port；adapter 实现 port；Controller 不直接访问 repository。JPA entity 与 domain aggregate 分离，Mapper 显式转换，避免 annotation 泄漏与 mass assignment。

核心输入端口：`ProvisionUserUseCase`, `ManageRoleAssignmentUseCase`, `EvaluateAuthorizationUseCase`, `ManageSessionUseCase`, `ManageStepUpUseCase`, `ManageServiceIdentityUseCase`。输出端口：repository、`OidcProviderPort`, `TokenVerifierPort`, `EventPublisherPort`, `AuditPort`, `ClockPort`, `HashingPort`。

Spring Security `Authentication` 在 API adapter 转换为不可变 `TrustedPrincipal`; application 不接受任意 claims map。ArchUnit 强制 package 依赖、domain 无框架依赖、Controller 无 repository 依赖。

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-001`
