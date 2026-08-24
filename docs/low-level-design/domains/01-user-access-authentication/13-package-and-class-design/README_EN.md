# 13 Package and Class Design

Use hexagonal architecture with dependency direction `domain <- application <- adapters/api`:

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

Domain is pure Java with no Spring/JPA/JWT/HTTP dependency. Application depends only on domain and ports; adapters implement ports; controllers never access repositories. JPA entities are separate from domain aggregates with explicit mappers to prevent annotation leakage and mass assignment.

Primary input ports: `ProvisionUserUseCase`, `ManageRoleAssignmentUseCase`, `EvaluateAuthorizationUseCase`, `ManageSessionUseCase`, `ManageStepUpUseCase`, and `ManageServiceIdentityUseCase`. Output ports cover repositories, `OidcProviderPort`, `TokenVerifierPort`, `EventPublisherPort`, `AuditPort`, `ClockPort`, and `HashingPort`.

The API adapter converts Spring Security `Authentication` into immutable `TrustedPrincipal`; application code never accepts arbitrary claim maps. ArchUnit enforces package dependencies, framework-free domain, and no controller-to-repository dependency.

---

> Domain: `01-user-access-authentication`  
> Service: `user-access-authentication-service`  
> Technology: `Java 21 / Spring Boot 3.5.x / Spring Security 6 / OAuth2 Resource Server + Client / Spring Data JPA / Flyway / PostgreSQL / RabbitMQ / Micrometer + OpenTelemetry Java / Keycloak (external IdP)`  
> Status: Detailed LLD  
> Spec mapping: `SPEC-UA-001`
