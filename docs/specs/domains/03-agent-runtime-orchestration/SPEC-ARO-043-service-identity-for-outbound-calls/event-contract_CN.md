# SPEC-ARO-043 — Event Contract

目标：支撑 `外呼服务身份`。

- 没有发布或消费任何事件。token 获取是一次同步的 OIDC client_credentials 交换（对 Keycloak），不是事件。
