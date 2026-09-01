# SPEC-EP-010 — Attachment Upload（附件上传）

> Domain: `09-employee-portal` | Phase: 04 — 证据文件切片 | 状态：Spec Planning

## 1. Spec 身份
`SPEC-EP-010`，实现 `UC-EP-02` 的附件部分。

## 2. 目标
让员工能给消息附上照片/文件，驱动附件状态机（`03-state-machine` §3.2）一路走到 `READY`。

## 3. 设计依据
`01-domain-model` §"Attachment"；`03-state-machine` §3.2；`05-api-contracts` §3。

## 4. Actor
正在写消息的员工。

## 5. 范围
文件选择器交互、上传进度 UI、`useUploadAttachment` hook。

## 6. 非目标
客户端文件类型/大小校验本身（SPEC-EP-011）；新建的共享附件能力自己的后端实现（单独立项，不属于本 domain）。

## 7. 前置条件
轮次状态为 `IDLE`（附件可以在发送前先暂存）。

## 8. 输入
员工选择的文件。

## 9. 详细行为
选文件 → `VALIDATING`（SPEC-EP-011）→ `UPLOADING` → `READY`/`FAILED`，遵循 `03-state-machine` §3.2。

## 10. 交互状态迁移
`03-state-machine` §3.2 的完整附件状态机。

## 11. 业务不变量
BI-EP-002（本 spec 自身存在的理由）——只有 `READY` 状态的附件才能被 SPEC-EP-005 的发送调用引用。

## 12. 幂等策略
同一文件的重试上传是一次全新的尝试（不是 HTTP 意义上的幂等 key）——失败上传的重试是新上传调用，不是回放。

## 13. 消费/依赖的契约
`POST /api/v1/attachments`（新建独立共享能力，尚未设计——本 spec 测试用 MSW mock）。

## 14. 安全
客户端校验明确不是安全边界（`11-security-and-authorization` §3）——真正的强制属于共享能力自己的服务端设计。

## 15. 可观测性
上传成功率指标是未来仪表盘的输入之一（`12-observability-and-audit` §4）。

## 16. 错误场景
上传失败 → `FAILED`，可重试或可移除，从不阻塞消息其余部分（`10-error-handling-and-reconciliation` §2.2）。

## 17. 验收场景
选择一张图片，依次经过 `VALIDATING → UPLOADING → READY`，并能被后续发送引用。

## 18. 先写测试
针对 MSW mock，为每个附件状态迁移写组件/hook 测试。

## 19. 完成定义
完整附件状态机针对 mock 测试通过；共享附件能力真实存在后追加兼容性测试。
