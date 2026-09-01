# SPEC-ARO-043 — API Contract

目标：支撑 `外呼服务身份`。

- 本 spec 不暴露任何自己的 HTTP 端点——是一个被 SPEC-ARO-038/040/041 内部消费的支撑性客户端能力。
- 定义一个内部客户端接口（例如 `OutboundServiceTokenProvider`），供那些 spec 自己的外呼 HTTP 调用依赖。
