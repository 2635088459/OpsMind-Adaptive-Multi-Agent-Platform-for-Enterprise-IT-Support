# 11 Security

## 访问控制

Search 请求必须携带：

- requester type；
- requester role；
- ticket id / workflow id；
- tenant / application / queue scope；
- correlation id。

检索前必须计算 access scope，并应用到：

- Memory classification；
- Knowledge Document ACL；
- Graph node / edge classification；
- source ticket visibility；
- role capability，例如 `knowledge_base_read`。

## 数据保护

禁止进入长期记忆的内容：

- password、token、API key、cookie、session id；
- 完整 SSN、DOB、个人邮箱、电话号码；
- 原始 tool response 中的敏感字段；
- 未脱敏用户输入；
- 高敏审批理由全文。

允许保存：

- redacted summary；
- hashed identifier；
- source reference；
- evidence checksum；
- classification label；
- minimal operational fact。

## Redaction Pipeline

1. Pattern redaction。
2. Structured field redaction。
3. Policy rule redaction。
4. Human review for high-risk candidate。
5. RedactionReport 持久化。

## Prompt Injection 防护

Knowledge documents 和 memories 都是不可信输入：

- 返回给 Agent 时必须标记 source type。
- 不允许 document content 覆盖 system/developer/runtime 指令。
- 不允许 retrieval result 触发 tool execution。
- 不允许 graph path 被解释为指令链或自动执行计划。
- Agent 使用检索内容前必须经过 Runtime 的 context builder。

## Graph Security

- graph traversal 必须先过滤 node/edge visibility，再扩展邻接节点。
- 如果 path 中任一节点或边不可见，整个 path 不返回。
- high sensitivity node 不能通过低敏 neighbor 泄漏名称。
- `OWNED_BY` / `POLICY_RULE` 等组织关系默认只对 authorized role 返回。

## Audit

必须审计：

- admin document ingestion；
- candidate approve / reject；
- memory publish / supersede / delete；
- retention override；
- access denied；
- high sensitivity search。

## 删除和保留

Deletion request 必须经过 authorization。删除成功后，默认保留 tombstone、audit、source hash，不保留可恢复原文。
