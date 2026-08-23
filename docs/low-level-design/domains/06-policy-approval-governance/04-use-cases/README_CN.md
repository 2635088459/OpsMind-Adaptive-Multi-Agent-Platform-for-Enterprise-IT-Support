# 04 Use Cases

## UC-PG-001：Tool Gateway 请求 risk decision

1. 05 提交 action、actor、resource、ticket/workflow refs 和 input hash。
2. 06 选择有效 policy version。
3. Rule evaluator 计算 effect、risk、approvalRequired、constraints。
4. 06 保存 PolicyDecision 和 audit。
5. 返回 decision snapshot。

## UC-PG-002：创建审批请求

1. 05/02/03 提交 approval request。
2. 06 校验 request hash、source linkage、approver policy。
3. 保存 ApprovalRequest。
4. 发布 `approval.requested.v1`。

## UC-PG-003：审批通过

1. Approver 提交 grant。
2. 06 校验权限、职责分离、request 是否仍有效。
3. 保存 ApprovalDecision。
4. ApprovalRequest 进入 `APPROVED`。
5. 发布 `approval.granted.v1`。

## UC-PG-004：审批拒绝/过期/取消

拒绝、过期、取消必须产生不同 final status 和不同事件，供下游区分处理。

## UC-PG-005：Policy 发布

1. Admin 创建 draft。
2. Reviewer 审核规则。
3. Publisher 发布新 version。
4. 06 发布 `policy.published.v1`。
5. 下游刷新 policy cache。

## UC-PG-006：高风险 override

Override 只能在有限 scope 和时间窗内生效，必须有独立审批人和更高审计等级。

