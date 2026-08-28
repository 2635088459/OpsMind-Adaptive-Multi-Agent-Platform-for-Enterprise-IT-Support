# 11 Security

## 身份与权限

07 依赖 01 提供 actor、service identity、tenant scope 和 role claims。

角色：

- `EVALUATION_VIEWER`：读取 report 和非敏感 score。
- `EVALUATION_AUTHOR`：创建 dataset draft 和 candidate draft。
- `EVALUATION_REVIEWER`：review/publish dataset。
- `EVALUATION_ADMIN`：运行 benchmark、配置 gate、发起 candidate approval。
- `RELEASE_APPROVER`：由 06 校验，批准 candidate 进入 Canary。

## 数据保护

- Raw ticket text、tool output、memory snippet 必须先脱敏再进入 dataset 或 online sample。
- Secret、access token、session cookie、MFA recovery code 不得进入 evaluation payload。
- Artifact URI 访问必须授权，不能把 LangSmith 链接当作公开证据。
- Report 默认展示聚合分数；case-level evidence 需要更高权限。

## 改进控制

- 07 不能直接写生产 prompt/config。
- Candidate 必须绑定 proposed change、风险等级、source failures、benchmark result 和 approval request。
- 高风险 candidate 必须走 06 的职责分离与审批。
- 自动生成的 candidate 不能自我审批。

## 审计

以下行为必须写 audit：

- dataset publish/deprecate；
- run create/cancel/finalize；
- gate policy change；
- candidate create/reject/approval request/canary/rollback；
- report sensitive evidence access。

