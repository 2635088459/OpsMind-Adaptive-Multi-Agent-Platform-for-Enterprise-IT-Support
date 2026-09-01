# SPEC-ARO-043 — Acceptance Criteria

目标：支撑 `外呼服务身份`。

- 真实获取一个 client_credentials token，并成功为一次调用 `02-ticket-workflow` 建单端点的真实请求鉴权。
- token 过期通过透明刷新处理——从不作为可见失败暴露给调用方的对话轮次。
- 任何密钥都从不出现在日志、trace 或版本控制里。
- Keycloak 临时不可用时，外呼干净失败并给出明确、可操作的错误——从不悄悄以未鉴权方式绕过。
