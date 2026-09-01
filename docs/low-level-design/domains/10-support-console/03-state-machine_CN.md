# Support Console — 交互状态机

> **Document ID:** LLD-SC-003
> **Domain:** `10-support-console`
> **状态:** Draft

---

## 3.1 队列视图的加载/刷新状态机

```text
LOADING
  → (首次拉取成功) → LIVE_POLLING       // MVP：轮询，非 SSE（见 §5 的新增依赖标注）
LIVE_POLLING
  → (轮询失败) → DEGRADED（展示"数据可能不是最新"提示，继续用最后一次成功结果）
  → (下一次轮询成功) → LIVE_POLLING
```

## 3.2 工单详情面板

```text
UNSELECTED（未选中任何工单，队列右侧空白提示）
  → (点击队列一行) → LOADING_DETAIL
LOADING_DETAIL
  → (三路聚合请求全部成功：ticket timeline + tool-request 详情 + governance audit) → READY
  → (任一路失败) → PARTIAL（展示已成功的部分 + 明确提示哪一部分加载失败，不是整体报错空白）
```

`PARTIAL` 态是本 domain 特有的、因为 `AiLogEntry` 是三路聚合（`01-domain-model` BI-SC-003）而必须设计的状态——不能因为其中一个后端 domain 暂时不可用就让坐席看不到另外两路已经成功的信息。

## 3.3 审批操作状态机

```text
PENDING（等待坐席决策）
  → (点击批准) → SUBMITTING_GRANT
  → (点击拒绝) → SUBMITTING_DENY
SUBMITTING_GRANT / SUBMITTING_DENY
  → (后端确认) → DECIDED（不可逆，卡片转为只读展示历史决策）
  → (后端拒绝/冲突，如已被其他坐席处理) → CONFLICT（提示"该请求已被处理，当前状态：{X}"，刷新为最新真实状态）
```

对应 BI-SC-002：`DECIDED` 只能来自后端确认，不能来自前端乐观切换。

## 3.4 乐观锁冲突提示（工单编辑类操作，如分诊/指派）

```text
EDITING
  → (提交) → SUBMITTING
SUBMITTING
  → (成功) → SAVED
  → (版本冲突 409) → VERSION_CONFLICT（BI-SC-005：明确提示"已被他人修改"，展示对方最新版本，坐席决定是否覆盖或放弃）
```

`VERSION_CONFLICT` 不自动重试、不自动合并——这是坐席协作场景独有的、必须让真人介入决策的状态，09 号 domain 完全没有对应物（员工自助场景不存在这种多人协作冲突）。
