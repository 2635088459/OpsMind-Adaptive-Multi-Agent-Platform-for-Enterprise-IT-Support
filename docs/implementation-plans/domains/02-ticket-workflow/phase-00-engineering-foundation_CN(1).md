# OpsMind Ticket Workflow — Phase 00 Engineering Foundation

> **文档编号：** IMP-TW-P00  
> **领域：** `02-ticket-workflow`  
> **阶段：** Phase 00  
> **阶段名称：** Engineering Foundation  
> **版本：** 1.1  
> **状态：** Reviewed Draft  
> **前置条件：** Ticket Workflow LLD 01–14 已完成  
> **后续阶段：** Phase 01 — Create Ticket Vertical Slice  
> **代码目录：** `services/ticket-workflow-service/`

---

# 1. 阶段目标

Phase 00 的目标是建立一个：

```text
可以编译
可以启动
可以测试
可以连接本地基础设施
可以执行 CI
可以约束架构依赖
```

的 Ticket Workflow Spring Boot 工程。

本阶段只建立实施基础，不实现 Ticket 业务功能。

---

# 2. 为什么必须先做 Phase 00

OpsMind 将采用：

```text
Spec-Driven Development
+
Test-Driven Development
+
Vertical Slice Delivery
```

TDD 需要一个可靠的测试执行环境。

在编写 `SPEC-TW-001 Create Ticket` 的失败测试前，必须先具备：

- Java 21
- Spring Boot
- Maven Wrapper
- JUnit 5
- AssertJ
- Testcontainers
- PostgreSQL Driver
- RabbitMQ Client
- ArchUnit
- CI
- 稳定 Package Structure
- 配置文件
- Health / Readiness

如果跳过 Phase 00，后续测试失败时无法判断：

```text
是业务代码错误，
还是工程、依赖、容器、配置或 CI 环境错误。
```

因此 Phase 00 的作用，是先把“测试执行平台”变成可信基础。

---

# 3. 本阶段不是什么

Phase 00 不是：

- Create Ticket 功能
- Ticket 状态机实现
- Ticket 业务表实现
- RabbitMQ 业务事件消费
- Keycloak 完整 Realm 配置
- Transactional Outbox 实现
- Agent Runtime 集成
- Tool Gateway 集成
- Reconciliation 实现

本阶段完成后，业务 API 仍然不存在。

这是有意设计，而不是遗漏。

---

# 4. 设计引用

本阶段主要执行以下设计：

## `13-package-and-class-design`

落实：

- Root Package
- Package-by-Feature
- Hexagonal Dependency Direction
- Configuration Package
- Test Package
- ArchUnit

## `14-testing-strategy`

落实：

- Test Tags
- Static Analysis
- Unit Test Environment
- Testcontainers
- CI Fast Verify
- Architecture Test
- Test Report

## `12-observability-and-audit`

本阶段只建立：

- Actuator
- Basic Health
- Structured Logging 基础
- OpenTelemetry 配置占位

不实现业务 Audit。

## `11-security-and-authorization`

本阶段只建立：

- Spring Security Dependency
- Security Configuration 基线
- 测试环境安全策略

不实现完整 Keycloak Authorization Matrix。

## Technology Baseline

落实：

- Java 21
- Spring Boot
- PostgreSQL
- RabbitMQ
- Docker Compose
- Maven

---

# 5. 阶段原则

## 5.1 不提前实现业务

不创建：

```text
Ticket.java
TicketStatus.java
CreateTicketController.java
TicketJpaEntity.java
```

这些属于 Phase 01。

## 5.2 不使用无职责占位类

允许建立必要 Package，但不创建：

```text
CommonService
BaseController
GenericRepository
Utils
Helper
```

## 5.3 每个工程能力必须有验证

例如：

- PostgreSQL Testcontainer 必须有连接测试。
- RabbitMQ Testcontainer 必须有连接测试。
- ArchUnit 必须实际执行规则。
- Spring Context 必须有启动测试。
- Actuator 必须有 Health 测试。
- Docker Image 必须实际启动验证。

## 5.4 版本固定

禁止使用：

```text
latest
```

用于基础设施 Image。

Java、Spring Boot、PostgreSQL、RabbitMQ 和 Keycloak Version 必须明确。

## 5.5 Local 与 CI 尽量一致

本地主要通过 Docker Compose，CI 主要通过 Testcontainers。

环境变量名称保持一致。

---

# 6. 阶段交付目录

Phase 00 实际创建最小工程结构：

```text
services/
└── ticket-workflow-service/
    ├── .mvn/
    ├── mvnw
    ├── mvnw.cmd
    ├── pom.xml
    ├── Dockerfile
    ├── README.md
    │
    ├── src/
    │   ├── main/
    │   │   ├── java/
    │   │   │   └── dev/opsmind/ticketworkflow/
    │   │   │       ├── TicketWorkflowApplication.java
    │   │   │       ├── configuration/
    │   │   │       └── platform/
    │   │   └── resources/
    │   │       ├── application.yml
    │   │       ├── application-local.yml
    │   │       └── logback-spring.xml
    │   │
    │   └── test/
    │       ├── java/dev/opsmind/ticketworkflow/
    │       │   ├── TicketWorkflowApplicationTest.java
    │       │   ├── architecture/
    │       │   ├── infrastructure/
    │       │   └── support/
    │       └── resources/
    │           └── application-test.yml
```

以下业务 Package 结构由 Document 13 定义，但在 Phase 01 按需创建：

```text
ticket/api
ticket/application
ticket/domain
ticket/infrastructure
reconciliation
audit
```

规则：

- 不为展示目录创建大量空 Package。
- 不把 `application-test.yml` 放进 `src/main/resources`。
- Monorepo 通用 `.gitignore` 优先放仓库根目录。
- `docker/entrypoint.sh` 只有存在实际启动需求时才增加。

---

# 7. 冻结技术基线

本实现线采用：

```text
Java: 21
Spring Boot: 3.5.16
Maven Wrapper: 3.9.16
PostgreSQL: 18.4
RabbitMQ: 4.3.4-management
Keycloak: 26.7.0
Testcontainers: 2.0.5
```

决策说明：

- Java 21 与现有设计一致。
- Spring Boot 3.5.16 与当前 LLD 的 `3.5.x` 基线一致。
- Spring Boot 4.1.0 属于新的 Major Line，不在 Phase 00 静默切换。
- Testcontainers 版本使用 Spring Boot 3.5.16 的 Dependency Management。
- PostgreSQL、RabbitMQ 和 Keycloak Image 必须固定 Patch Version；生产进一步建议固定 Image Digest。
- Maven 4 尚未作为本实现线的 GA 基线，因此使用 Maven 3.9.16 Wrapper。

版本升级必须通过：

```text
Technology Baseline Update
→ Compatibility Test
→ ADR when major or behavior-changing
→ CI Verification
```

---

# 8. Maven Dependencies

使用 Spring Boot Parent / BOM 管理受支持的依赖版本，除非有经过 Review 的兼容性原因，否则不单独覆盖 Spring 生态版本。

## 8.1 Runtime Foundation

Phase 00 加入：

```text
spring-boot-starter-web
spring-boot-starter-security
spring-boot-starter-oauth2-resource-server
spring-boot-starter-data-jpa
spring-boot-starter-amqp
spring-boot-starter-actuator
org.postgresql:postgresql
org.flywaydb:flyway-core
org.flywaydb:flyway-database-postgresql
io.micrometer:micrometer-registry-prometheus
io.micrometer:micrometer-tracing-bridge-otel
io.opentelemetry:opentelemetry-exporter-otlp
```

说明：

- `spring-boot-starter-validation` 在 Phase 01 第一个 API Spec 中加入。
- Flyway 对 PostgreSQL 的支持使用独立 `flyway-database-postgresql` 模块。
- Metrics 使用 Micrometer + Prometheus。
- Tracing 使用 Micrometer Observation / Tracing + OpenTelemetry Bridge + OTLP Export。
- Phase 00 不同时启用 OpenTelemetry Java Agent，避免重复 Instrumentation；未来引入 Agent 必须有独立 ADR 和重复 Span 测试。

## 8.2 Test Foundation

```text
spring-boot-starter-test
org.springframework.security:spring-security-test
org.springframework.boot:spring-boot-testcontainers
org.testcontainers:testcontainers-junit-jupiter
org.testcontainers:testcontainers-postgresql
org.testcontainers:testcontainers-rabbitmq
com.tngtech.archunit:archunit-junit5
org.awaitility:awaitility
```

`AssertJ` 已由 `spring-boot-starter-test` 提供，不单独重复声明。

## 8.3 后续按需加入

```text
MapStruct
JSON Schema Validator
PIT
WireMock
Cucumber
Pact
```

每个新增 Dependency 必须对应实际 Spec、测试或实现需求。

---

# 9. Maven Plugin

建议配置：

```text
maven-compiler-plugin
maven-surefire-plugin
maven-failsafe-plugin
jacoco-maven-plugin
spring-boot-maven-plugin
maven-enforcer-plugin
spotbugs-maven-plugin
```

可选：

```text
spotless-maven-plugin
```

## 9.1 Test Naming

Surefire：

```text
*Test.java
```

Failsafe：

```text
*IT.java
```

命令：

```bash
./mvnw test
```

运行快速测试。

```bash
./mvnw verify
```

运行 Integration Test 和完整质量检查。

---

# 10. Maven Enforcer

必须验证：

- Java 21
- Maven 最低版本
- 禁止依赖版本冲突
- 禁止 Snapshot Dependency 进入 Release
- 可选：Dependency Convergence

目标：

```text
构建环境错误必须尽早失败，
不能等到运行阶段才暴露。
```

---

# 11. Spring Boot Application

创建：

```text
dev.opsmind.ticketworkflow.TicketWorkflowApplication
```

职责仅为：

```text
启动 Spring Boot。
```

禁止放入：

- Queue Binding
- Security Rule
- Scheduler
- Seed Data
- Domain Logic
- Test Fixture

---

# 12. Configuration Strategy

配置文件：

```text
application.yml
application-local.yml
application-test.yml
```

## 12.1 `application.yml`

只放环境无关默认值：

- Application Name
- Actuator
- Jackson
- JPA Validate
- Basic Logging
- Graceful Shutdown

## 12.2 `application-local.yml`

从环境变量读取：

```text
DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD
RABBITMQ_HOST
RABBITMQ_PORT
RABBITMQ_USERNAME
RABBITMQ_PASSWORD
KEYCLOAK_ISSUER_URI
OTEL_EXPORTER_OTLP_ENDPOINT
```

## 12.3 `application-test.yml`

适用于测试：

- 禁用真实外部 Telemetry Export。
- 使用 Testcontainers Dynamic Property。
- 禁用非必要 Scheduler。
- 保持 Flyway Enabled。
- 使用明确测试 Security 配置。

## 12.4 Secret

禁止把真实密码写入 Git。

提供：

```text
.env.example
```

但不提交：

```text
.env
```

---

# 13. Configuration Properties

优先使用 Spring Boot 已提供的：

```text
spring.datasource.*
spring.rabbitmq.*
spring.security.oauth2.resourceserver.*
management.*
```

只有出现项目自定义配置时才建立 `@ConfigurationProperties`，例如：

```text
OpsMindServiceProperties
TelemetryRedactionProperties
```

禁止为了“未来可能会用”提前创建大量空配置类，也避免散落的 `@Value`。

---

# 14. Local Infrastructure

仓库已有顶层：

```text
infrastructure/
```

推荐结构：

```text
infrastructure/
├── docker-compose/
│   └── local-platform.yml
├── postgres/
├── rabbitmq/
├── keycloak/
└── observability/
```

Phase 00 最低强制：

- PostgreSQL
- RabbitMQ

Keycloak 和 OTel Collector 可以建立基本容器配置，但完整 Realm、Dashboard 和 Alert 可在后续强化。

---

# 15. Docker Compose

本地 Compose 最低提供：

```text
postgres
rabbitmq
```

可选同时提供：

```text
keycloak
otel-collector
prometheus
grafana
```

Compose 要求：

- 固定 Image Version
- Health Check
- Named Volume
- 明确 Port
- 从环境变量读取 Credential
- 不使用生产 Secret
- 服务名称稳定
- 支持一条命令启动

示例：

```bash
docker compose -f infrastructure/docker-compose/local-platform.yml up -d
```

---

# 16. PostgreSQL Foundation

Phase 00 不创建 Ticket 业务表。

只验证：

- Driver 可用
- Datasource 配置可用
- Testcontainer 可启动
- Flyway 可以初始化
- JPA Context 可以启动

可以建立：

```text
src/main/resources/db/migration/
```

但第一批业务 Migration 属于 Phase 01。

Phase 00 不创建占位 Migration。

只建立：

```text
src/main/resources/db/migration/
```

并验证 Flyway 在当前环境中可以启动。第一份正式 Migration 从 Phase 01 开始，并按照 `07-data-model` 的版本计划执行。

---

# 17. RabbitMQ Foundation

Phase 00 不定义完整 Ticket 业务 Queue Topology。

只验证：

- Spring AMQP 可以连接
- RabbitMQ Testcontainer 可以启动
- Connection Factory 可以建立连接
- Listener Infrastructure 可以初始化

完整的：

```text
opsmind.events
retry queues
DLQ
bindings
```

根据 Event Contract 在 Phase 01 或 Phase 03 实现。

如果全局 Exchange 已由 HLD 冻结，可以由 Infrastructure 提供声明，但 Ticket Service 本阶段不发布业务事件。

---

# 18. Security Foundation

Phase 00 加入 Spring Security，但不实现完整 Role / Scope Matrix。

推荐策略：

```text
Default Deny
+
Explicit Health Access
```

至少明确：

- `/actuator/health`
- `/actuator/info`

的访问策略。

禁止为了启动方便：

```text
permitAll()
```

覆盖全部未来 API。

未来业务 API 必须通过 Keycloak JWT。

测试环境可以使用：

- Mock JWT
- Test Security Configuration
- Stable test claims

但不能绕过 Application Authorization 的设计。

---

# 19. Observability Foundation

Phase 00 实现：

- `spring.application.name=ticket-workflow-service`
- Actuator
- Prometheus Endpoint
- Micrometer Observation / Tracing
- OpenTelemetry Bridge + OTLP Export
- Basic Structured Logging
- W3C Trace Context 基础
- Health Indicator

本阶段不要求：

- Ticket Business Metrics
- Outbox Dashboard
- Security Audit
- LangSmith Integration

## 19.1 Logging

至少包含：

```text
timestamp
level
service
environment
logger
message
traceId when available
```

禁止记录：

- Environment Secret
- Authorization Header
- Raw Configuration
- Full Exception Environment Dump

---

# 20. Health and Readiness

最低端点：

```text
/actuator/health
/actuator/info
/actuator/prometheus
```

Health 应区分：

- Liveness
- Readiness

本地和测试中验证：

- Application Up
- PostgreSQL Readiness
- RabbitMQ Readiness

是否将所有依赖直接纳入 Kubernetes Readiness，需要在平台设计中谨慎确认，避免短暂依赖故障触发不必要的重启。

---

# 21. Architecture Tests

创建：

```text
src/test/java/.../architecture/LayerDependencyTest.java
```

Phase 00 最低规则：

```text
domain does not depend on Spring
domain does not depend on JPA
application does not depend on infrastructure implementation
api does not access persistence repository
```

即使业务 Package 尚未包含实际类，也应先建立规则和测试入口。

Phase 01 开始后，ArchUnit 会对真实业务类提供保护。

---

# 22. Testcontainers Foundation

创建共享测试支持：

```text
src/test/java/.../support/PostgresContainerSupport.java
src/test/java/.../support/RabbitMqContainerSupport.java
```

或统一为：

```text
InfrastructureContainerSupport
```

## 22.1 PostgreSQL

验证：

- Container 启动
- JDBC 连接成功
- Flyway 执行
- Spring Context 使用 Dynamic Property

## 22.2 RabbitMQ

验证：

- Container 启动
- Connection 建立
- Spring AMQP 初始化

禁止依赖开发者本机固定 Port。

---

# 23. 基础测试清单

必须创建：

```text
TicketWorkflowApplicationTest
PostgresConnectivityIT
RabbitMqConnectivityIT
LayerDependencyTest
ActuatorHealthIT
ConfigurationPropertiesTest
```

## 23.1 `TicketWorkflowApplicationTest`

验证 Spring Context 启动。

## 23.2 `PostgresConnectivityIT`

使用真实 PostgreSQL Testcontainer。

## 23.3 `RabbitMqConnectivityIT`

使用真实 RabbitMQ Testcontainer。

## 23.4 `LayerDependencyTest`

执行 ArchUnit。

## 23.5 `ActuatorHealthIT`

验证 Health、Liveness 和 Readiness。

## 23.6 `ConfigurationPropertiesTest`

验证：

- 必要配置缺失时安全失败。
- 配置错误不输出 Secret。
- Profile 选择正确。

---

# 24. CI Fast Verify

建议使用：

```text
.github/workflows/ticket-workflow-ci.yml
```

如果仓库已有统一 CI，可在现有 Workflow 增加 Ticket Workflow Job。

PR 最低步骤：

```text
checkout
setup Java 21
cache Maven
./mvnw -B clean verify
upload unit and integration test reports
```

Phase 00 可以先使用一个 `verify` Job。

后续逐步拆分：

- Fast Verify
- Integration
- Contract
- E2E

---

# 25. CI Path Filter

Ticket Workflow Job 建议在以下路径变化时运行：

```text
services/ticket-workflow-service/**
packages/event-contracts/**
packages/api-contracts/**
infrastructure/**
docs/specs/domains/02-ticket-workflow/**
docs/low-level-design/domains/02-ticket-workflow/**
docs/implementation-plans/domains/02-ticket-workflow/**
```

不要过度限制 Path Filter，避免共享配置变更未触发测试。

---

# 26. Static Analysis

Phase 00 最低加入：

- Java Compiler Warning
- Maven Enforcer
- ArchUnit
- Dependency Vulnerability Scan 或 Dependabot
- Secret Scan

SpotBugs 和 Checkstyle 可在 Phase 00 建立，但规则不应一次过度严格，导致大量无价值配置工作。

原则：

```text
先建立可持续运行的质量门禁，
再逐步提高严格度。
```

---

# 27. Dockerfile

创建多阶段构建：

```text
Build Stage
→ Runtime Stage
```

要求：

- 使用固定 Java 21 Image。
- Runtime 使用非 Root User。
- 只复制运行所需 Artifact。
- 不复制 Maven Cache、Source 和 Secret。
- 暴露应用 Port。
- 支持容器 Health Check。
- JVM 参数通过环境变量配置。

Phase 00 必须验证：

```text
Docker Image 可以 Build。
Container 可以启动。
Health Check 可以通过。
```

---

# 28. README

`services/ticket-workflow-service/README.md` 至少包含：

- Service Purpose
- Current Phase
- Prerequisites
- Run Locally
- Run Tests
- Run Integration Tests
- Start Local Infrastructure
- Build Docker Image
- Configuration Variables
- Current Non-goals
- Design Links
- Next Phase

示例：

```bash
./mvnw test
./mvnw verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
docker build -t opsmind/ticket-workflow-service:local .
```

---

# 29. Phase 00 的 TDD 执行方式

Phase 00 虽然不实现业务，但仍采用测试优先。

## RED 1

先写：

```text
TicketWorkflowApplicationTest
```

此时项目尚不能启动。

## GREEN 1

创建 Main Class 和最小 Spring 配置。

## RED 2

编写 PostgreSQL Connectivity Integration Test。

## GREEN 2

加入 Driver、Testcontainers、Datasource 和 Flyway。

## RED 3

编写 RabbitMQ Connectivity Test。

## GREEN 3

加入 Spring AMQP 和 Container 配置。

## RED 4

编写 ArchUnit 规则。

## GREEN 4

建立 Package Structure。

## RED 5

编写 Actuator Health Test。

## GREEN 5

加入 Actuator 和 Health 配置。

## RED 6

编写 Docker Startup Smoke Test 或脚本验证。

## GREEN 6

完成多阶段 Dockerfile。

## REFACTOR

- 统一测试支持。
- 提取 Configuration Properties。
- 改善命名。
- 更新 README。
- 清理未使用依赖。

---

# 30. 实施任务清单

## P00-T01 创建 Service 目录

```text
services/ticket-workflow-service/
```

## P00-T02 初始化 Java 21 Spring Boot

- Maven Wrapper
- Dependency Management
- Application Class

## P00-T03 建立最小 Package Skeleton

按 Document 13，不创建无意义空目录。

## P00-T04 配置 Runtime Dependencies

只加入当前阶段和近期阶段需要的核心依赖。

## P00-T05 配置 Test Dependencies

JUnit、AssertJ、Testcontainers、ArchUnit、Awaitility。

## P00-T06 配置 Profiles

```text
default
local
test
```

## P00-T07 PostgreSQL Testcontainer

Connectivity + Flyway Foundation。

## P00-T08 RabbitMQ Testcontainer

Connectivity Foundation。

## P00-T09 Security Baseline

Default Deny + Health exceptions。

## P00-T10 Actuator and Logging

Health、Info、Prometheus、Structured Logging。

## P00-T11 Architecture Test

Package Dependency Rules。

## P00-T12 CI Workflow

Pull Request Verify。

## P00-T13 Dockerfile

Build and startup verification。

## P00-T14 README

Local development instructions。

---

# 31. 推荐 PR 划分

可以使用一个 PR 完成 Phase 00，也可以拆为两个小 PR。

## PR 1：Project Bootstrap

```text
build(ticket): bootstrap Spring Boot service
test(ticket): add context and architecture tests
docs(ticket): add service README
```

## PR 2：Infrastructure Test Foundation

```text
test(ticket): add PostgreSQL and RabbitMQ containers
build(ticket): add actuator and container image
ci(ticket): add verify workflow
```

不建议把 Phase 00 和 Create Ticket 功能放进同一个 PR。

---

# 32. 本阶段交付物

代码：

```text
services/ticket-workflow-service/
```

基础设施调整：

```text
infrastructure/docker-compose/
```

CI：

```text
.github/workflows/
```

文档：

```text
services/ticket-workflow-service/README.md
docs/implementation-plans/domains/02-ticket-workflow/phase-00-engineering-foundation_CN.md
```

测试报告：

- Context Start
- PostgreSQL Integration
- RabbitMQ Integration
- ArchUnit
- Health

---

# 33. 风险与处理

## 风险 1：过度搭建基础设施

表现：

- Phase 00 做数周。
- 提前配置所有生产平台。
- 没有业务进展。

处理：

```text
只实现 Phase 01 必需的工程能力。
```

## 风险 2：一次加入过多依赖

处理：

- Optional Dependency 延后。
- 每个 Dependency 必须有使用场景。

## 风险 3：为了启动将 Security 全部放行

处理：

- Default Deny。
- 只允许明确 Health Endpoint。
- 测试使用明确 Security Profile。

## 风险 4：Testcontainers 在 CI 不稳定

处理：

- 固定 Image。
- 输出 Container Log。
- 区分 Infrastructure Startup Failure 和业务失败。
- 不依赖固定 Host Port。

## 风险 5：Package Skeleton 变成大量空目录

处理：

- 只创建 Root 和必要 Package。
- 其余 Package 随 Feature Spec 增加。

## 风险 6：Phase 00 偷偷进入业务实现

处理：

- Non-goals 明确。
- PR Review 检查是否出现 Ticket Aggregate、Controller 或业务表。

---

# 34. Cross-domain Dependency Policy

Phase 00 不要求其他 Domain 的真实服务运行。

后续 Ticket Workflow Phase 在其他服务尚未完成时使用：

```text
Approved Event / API Contract
→ Golden Fixture
→ Deterministic Stub
→ Ticket Workflow Integration Test
→ Real Service Compatibility Test
```

规则：

- Stub 必须遵守已批准 Contract。
- Stub 不能访问 Ticket Workflow 数据库。
- Stub 结果必须由 Scenario ID 控制，不能随机。
- 真实 Agent、Approval、Tool 和 Verification Service 接入后复用同一组 Contract Test。
- Consumer 不得为了兼容错误 Producer 而静默接受任意 Payload。

---

# 35. Phase 00 Scope Review

本次 Review 的决定：

## 保留

- Spring Boot Skeleton
- PostgreSQL 和 RabbitMQ Testcontainers
- JPA、Flyway 和 AMQP 基础依赖
- Spring Security Default Deny
- Actuator、Prometheus 和 Trace Foundation
- ArchUnit
- CI
- Docker Image
- README

## 延后

- Ticket 业务代码
- Business Migration
- Business RabbitMQ Topology
- 完整 Keycloak Realm
- Dashboard 和 Alert
- Audit Table
- Outbox Publisher
- Feature Contract Test

## 删除或修正

- `application-test.yml` 只放在 `src/test/resources`。
- 不创建占位 `V000` Migration。
- 不预建全部空业务 Package。
- 不同时使用 OTel Java Agent 和 Micrometer OTel Bridge。
- 不重复声明 AssertJ。
- CI 使用一次 `clean verify`，避免重复执行测试。

---

# 36. Non-goals

本阶段不交付：

```text
POST /api/v1/tickets
Ticket Aggregate
TicketStatus
Ticket business tables
Outbox business tables
Business RabbitMQ event
Keycloak production realm
Agent integration
Tool integration
Verification
Reconciliation
```

这些功能属于后续 Phase。

---

# 37. 阶段退出条件

必须全部满足。

## Build

```text
./mvnw clean verify
```

通过。

## Application

- Spring Boot Context 启动成功。
- `spring.application.name` 正确。
- Local Profile 可以启动。
- 无 Ticket 业务 Endpoint。

## PostgreSQL

- Testcontainer 启动。
- Datasource 连接。
- Flyway Foundation 执行。
- 无 H2 依赖。

## RabbitMQ

- Testcontainer 启动。
- Connection 建立。
- Spring AMQP 初始化。

## Architecture

- ArchUnit 运行。
- Domain 无 Spring / JPA 依赖。
- Application 无 Infrastructure Implementation 依赖。
- API 不直接依赖 Persistence。

## Security

- 默认未授权请求不会被全部放行。
- Health 的访问策略明确。
- 测试 Credential 不进入 Git。

## Observability

- Actuator Health 可用。
- Prometheus Endpoint 配置完成。
- Log 不输出 Secret。

## CI

- Pull Request Workflow 可以运行。
- 测试报告可查看。

## Docker

- Image Build 成功。
- Container 启动。
- Health Check 通过。

## Documentation

- Service README 完成。
- 环境变量说明完成。
- Current Non-goals 明确。
- 下一阶段链接明确。

---

# 38. Exit Review Checklist

- [ ] Java 21 已冻结。
- [ ] Spring Boot Version 已冻结。
- [ ] Maven Wrapper 已提交。
- [ ] Root Package 符合 Document 13。
- [ ] `./mvnw test` 通过。
- [ ] `./mvnw verify` 通过。
- [ ] PostgreSQL Testcontainer 通过。
- [ ] RabbitMQ Testcontainer 通过。
- [ ] ArchUnit 通过。
- [ ] Health / Readiness 通过。
- [ ] Prometheus Endpoint 可用。
- [ ] Docker Image 可启动。
- [ ] CI 通过。
- [ ] 无 Ticket 业务代码提前进入。
- [ ] README 完成。
- [ ] Secret Scan 通过。
- [ ] 未使用 `latest` Image。
- [ ] Traceability 标记 Phase 00 状态。

---

# 39. Phase 00 完成后允许做什么

只有通过 Exit Review 后，才能进入：

```text
Phase 01 — Create Ticket Vertical Slice
```

下一步文档和执行顺序：

```text
1. 编写 SPEC-TW-001-create-ticket_CN.md
2. 编写 Phase 01 Plan
3. 编写失败的 Domain Tests
4. 实现最小 Ticket Creation Domain
5. 创建 Flyway Business Migrations
6. 实现 Persistence Adapter
7. 实现 Create Ticket API
8. 实现 Transaction + History + Audit + Outbox
9. 完成 Integration and Contract Verification
10. 更新 Traceability Matrix
```

---

# 40. Phase 00 Definition of Done

Phase 00 完成意味着：

```text
OpsMind 已经拥有一个可信、可重复、受架构约束的 Ticket Workflow 开发平台，
可以正式开始按照 Feature Spec 和 TDD 实现第一个业务 Vertical Slice。
```

它不意味着 Ticket Workflow 已经拥有业务功能。
