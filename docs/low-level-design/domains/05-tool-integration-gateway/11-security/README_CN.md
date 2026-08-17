# 11 Security

## 安全边界

Tool Gateway 是凭据和外部副作用的安全边界。任何跨出平台边界的动作都必须在这里完成认证、授权、脱敏、审计和网络控制。

## 凭据管理

- 凭据存放在外部 secret/vault 系统。
- Gateway 数据库只保存 `vault_ref`、scope、rotation version 和 status。
- Connector invocation 时按需获取短时凭据。
- 凭据不得写入日志、事件、result、memory、checkpoint。
- 凭据读取必须产生 audit record。

## 授权模型

执行授权结合：

- tenant
- actor type/id
- ticket scope
- workflow purpose
- capability risk
- connector policy
- approval decision
- credential binding scope

低风险只读工具也必须通过授权检查。

## Agent 隔离

Agent 只能看到：

- capability name
- input schema
- allowed parameter hints
- redacted result summary

Agent 不能看到：

- connector credential
- vault reference
- network endpoint secret
- raw output
- admin-only connector metadata

## Output Redaction

所有 connector result 必须经过 classification/redaction：

- secret/token/key
- PII
- customer data
- infrastructure internal address
- privileged diagnostic output

redaction 后才能进入 event payload 或 Memory Knowledge。

## Network Policy

Connector manifest 必须声明允许访问的 host、protocol、port 和 egress class。默认 deny 未声明 endpoint。

高风险 connector 应运行在隔离 worker pool。

## Audit

审计记录必须包含：

- who requested
- what capability
- why requested
- who approved
- which connector
- which credential binding
- what operation key
- result status
- redaction status

审计记录不能被普通 admin 删除，只能按 retention policy 归档。

