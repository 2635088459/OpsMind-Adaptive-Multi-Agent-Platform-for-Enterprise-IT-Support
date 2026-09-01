# Employee Portal — 安全与授权

> **Document ID:** LLD-EP-011
> **Domain:** `09-employee-portal`
> **状态:** Draft

---

## 1. 身份认证：完全复用已建成、已验证的机制

登录走 `01-user-access-authentication` 真实的 Authorization Code + PKCE 流程（Keycloak），本 domain **不实现任何自己的登录逻辑**，也不自己持有/刷新 JWT——服务端会话是 cookie-based（`OPSMIND_SESSION`），这一点已经在 2026-09-01 的集成验证里现场跑通（见 `project-level-integration-verification` memory）。

浏览器端的 API 调用带上这个会话 cookie（`SameSite=Lax`, `HttpOnly`, `Secure`），前端 JS 从不直接读取或操作这个 cookie 的值。

## 2. 已知需要的新 scope

`05-api-contracts` §2 的新端点需要新的 JWT/session scope（具体命名由 `03-agent-runtime-orchestration` 在自己的立项 spec 里定义，本 domain 只列出需求）：

```text
conversations:create
conversations:message
conversations:confirm-action
```

已有的 `tickets:create`（`02-ticket-workflow` 真实存在的 scope）复用给 §1 的兜底直连创建路径。

## 3. 附件安全

- 上传前端校验：文件类型白名单（图片/PDF/常见文档格式）、大小上限——但**前端校验不是安全边界**，真正的校验（含未来可能的病毒扫描钩子）必须在共享附件能力的服务端强制（呼应 `05-api-contracts` §3 的归属决定）
- 附件的 `objectRef` 是不透明引用，从不把真实的对象存储直链暴露给前端渲染层之外的任何地方（比如不写进日志、不出现在 URL query string 里）

## 4. XSS 防护

agent 返回的文本内容（`Message.text`、`ProposedAction.summary`）**永远**当作纯文本/受限 Markdown 渲染，不允许注入原始 HTML——即使内容看起来"可信"（毕竟来自我们自己的 agent），也不能假设它绝对安全，因为最终内容可能间接受知识库/工具输出影响。

## 5. 与 LangSmith/OpenTelemetry 的边界（呼应 shared baseline §10）

前端**从不**持有 LangSmith API Key，也不直接向 LangSmith 发送任何数据——agent 的可观测性数据完全在服务端（`03-agent-runtime-orchestration`/`07-evaluation-improvement`）产生和上报。前端只参与 OpenTelemetry 的分布式追踪（生成/透传 `trace_id`/`correlation_id`，作为每次 API 调用的 header），这部分是纯工程可观测性，见 `12-observability-and-audit`。

## 6. 会话固定与跨站请求伪造

复用 `01-user-access-authentication` 已经做好的防护（PKCE 本身防止授权码拦截攻击，`SameSite=Lax` cookie 防止大部分 CSRF 场景）。本 domain 不重新发明这套机制，只需要确保所有产生副作用的请求使用非 GET 方法（已经是 REST 惯例），不通过 GET query string 触发任何状态变更。
