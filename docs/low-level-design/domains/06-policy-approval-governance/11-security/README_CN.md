# 11 Security

## 权限模型

06 使用 RBAC + ABAC：

- RBAC 决定用户是否可审批、发布 policy、查看 audit；
- ABAC 决定特定 ticket、tenant、resource、risk level 下是否允许该主体操作。

## 职责分离

默认禁止：

- requester 审批自己的请求；
- tool execution worker 审批对应 tool request；
- policy author 直接发布自己未 review 的 policy；
- admin repair 发起人直接批准高风险 override。

## Approval Authenticity

Approval command 必须包含：

- authenticated actor；
- session/device metadata；
- idempotency key；
- reason；
- optional MFA/step-up marker；
- correlation id。

## Override Guard

Override 必须：

- 有明确 scope；
- 有过期时间；
- 有独立审批人；
- 有高优先级 audit；
- 可被 revoke；
- 不能作为永久 policy 替代品。

## 敏感数据

Policy input 可以包含敏感上下文摘要，但不得保存原始 secret。Audit API 默认只返回 metadata 和 hash。

## 审计防篡改

Audit record 应包含 hash chain 或 append-only marker。普通 admin 不能删除 audit，只能按 retention 归档。

