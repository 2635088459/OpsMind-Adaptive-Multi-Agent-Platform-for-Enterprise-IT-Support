# SPEC-ARO-037 — API Contract

目标：支撑 `对话式接入工作流类型`。

- 本 spec 不引入任何自己的 HTTP 端点——这是一个定义性/枚举类的 spec。
- 它的产出（固定的 `task_graph` 模板和枚举值）被 SPEC-ARO-038、SPEC-ARO-039 各自的端点读取。
- 不改变任何既有端点的请求/响应形状。
