# Support Console — 安全与授权

> **Document ID:** LLD-SC-011
> **Domain:** `10-support-console`
> **状态:** Draft

---

## 1. 身份认证：与 employee-portal 同一套机制，不同角色/scope

同样复用 `01-user-access-authentication` 真实的 Keycloak OIDC 会话机制，不重新实现。区别在于坐席/管理员账号被授予的 scope 不同（均为后端已真实存在的 scope，不是本 LLD 新造的）：

```text
ticket:triage
ticket:assign
ticket:transition
governance 相关审批 scope（06-policy-approval-governance 的 SecurityConfig 目前只要求已认证，无细分 scope——本 LLD 如实反映现状，不假设存在尚未真正实现的细粒度权限）
```

## 2. 一个需要如实指出的现状：审批端点目前没有细粒度授权

`06-policy-approval-governance` 的 `ApprovalController` 目前的鉴权是"任何已认证的调用者"（`.anyRequest().authenticated()`），**没有**区分"哪些角色的坐席才能批准高风险请求"这样的细粒度规则（该 domain 自己 LLD/代码现状如此，2026-09-01 集成验证时确认过）。这意味着：

- support-console **不能**在前端假装存在一个后端并不真正执行的权限边界（比如"只有管理员能看到批准按钮"这种纯前端隐藏，本质上不是安全边界，只是 UX 引导）
- 如果业务上真的需要"只有特定角色能批准 CRITICAL 风险的请求"，这是 `06-policy-approval-governance` 自己需要补的后端能力，本 LLD 只标注这个现状缺口，不在前端伪造安全边界

## 3. AiLogEntry 聚合视图的信息泄露风险

`AiLogEntry` 跨三个 domain 聚合，坐席能看到的信息面比单独查询任何一个 domain 都广。必须确认：坐席对这三个源 domain 各自的数据都已经有真实授权（而不是因为聚合视图把无权限看到的数据也顺带展示出来）。前端职责：三路请求各自独立带真实 JWT，任何一路因为无权限返回 403 时，`PARTIAL` 态如实展示"无权限查看"而不是显示空数据（避免和"暂时不可用"混淆，两者对坐席的含义完全不同）。

## 4. XSS 与内容渲染

与 09 号 domain 同一原则——`AiLogEntry.step` 等来自后端聚合的文本内容一律按纯文本/受限 Markdown 渲染，不注入原始 HTML。

## 5. 与 LangSmith/OpenTelemetry 的边界

同 09 号 domain（见 `11-security-and-authorization` §5 的对应内容）：support-console 从不持有 LangSmith Key，可观测性页面的两个板块都只是"外链到已有系统"，不在前端直接调用这两个可观测性后端的写入类 API。
