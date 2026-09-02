# `ConversationReasoningPort` 真实 LLM 接入 — 设计报告

> 配套本 spec 自己的 `traceability-entry.yaml`（2026-09-02 追加的两条记录）。之所以单独写这份报告，是因为口头总结把范围说得不够清楚——这里是关于"到底做了什么、为什么这么做、以及刻意没有做什么"的详细技术说明。

## 1. 先把一句话的更正说清楚

**没有构建 LangGraph。** 这次改动完全没有涉及多步 agent 编排。真正做的事情是：把两个真实的 LLM API 接入（Anthropic、OpenAI）接到本代码库**早就已经定义好**的接口（`ConversationReasoningPort`）后面——用一次真实的模型调用，替换（准确说是补充）了原来那个基于关键词匹配的占位实现，去做**完全相同、范围很窄的单轮决策**。

冻结的技术基线文档（`docs/low-level-design/shared/technology-baseline`）把"Agent Orchestration: LangGraph behind internal abstractions"标记为 **Provisional**（暂定/规划中，尚未构建）——这次改动之后这个状态没有变化。原因是结构性的，不只是"没来得及做"：`ConversationReasoningPort.decide()` 的签名就是 `(message_text: str, knowledge_snippets: list[KnowledgeSnippet])`，返回一个 `ReasoningOutcome`。这里面没有对话历史参数、没有多步计划、没有工具调用循环、没有节点/边组成的图——根本没有任何东西是 LangGraph 真正需要去编排的。真要把 LangGraph 做出来，意味着要重新设计这个端口的签名以及它唯一的调用方（`SendMessageService`），这是一项实质上更大、完全独立的工作，远不是"把现有决策做得更聪明一点"这么简单。

## 2. 现在真实存在的东西——它长什么样

```
ConversationReasoningPort (application/ports_out.py)
        │
        │  .decide(message_text, knowledge_snippets) -> ReasoningOutcome
        │
   ┌────┴────────────────┬──────────────────────┐
   │                     │                      │
StaticConversationReasoningAdapter   AnthropicConversationReasoningAdapter   OpenAIConversationReasoningAdapter
（关键词匹配，不发起任何网络请求）      （真实调用 anthropic SDK）              （真实调用 openai SDK）
```

这三个实现都在 `infrastructure/conversation_reasoning.py` 里。`SendMessageService`——唯一真实的调用方，它在 `POST /api/v1/conversations/{id}/messages` 这个 HTTP 请求内部同步执行——完全不知道背后接的是哪一个；它只是调用 `.decide(...)`，然后根据 `outcome.kind` 分支处理。

### 2.1 用哪一个，是配置项决定的，不是改代码决定的

```python
conversation_reasoning_mode: Literal["static", "anthropic", "openai"] = "static"
```

`"static"` 在任何地方都是默认值——本服务里所有的单元测试、以及现在真实运行中的 Docker 容器，用的都是它。切换供应商是改一个环境变量，不是部署一份不同的代码：

| 环境变量 | 效果 |
|---|---|
| `CONVERSATION_REASONING_MODE=static`（默认） | 关键词占位实现，零网络调用，零成本 |
| `CONVERSATION_REASONING_MODE=anthropic` + `ANTHROPIC_API_KEY=...` | 真实调用 Claude |
| `CONVERSATION_REASONING_MODE=openai` + `OPENAI_API_KEY=...` | 真实调用 GPT |

如果配置成了某个真实供应商，但客户端构造不出来（比如密钥缺失、包导入失败、任何原因），`container.py` 里的 `_build_conversation_reasoning_port()` 会捕获这个异常、记一条警告日志，然后**自动回退到 static 占位实现**，而不是让服务在启动时直接崩溃。这个做法沿用的是本代码库自己的兄弟服务（`evaluation-improvement-service`）为它自己的 LLM 评审功能（`llm_judge_mode`）已经建立好的同一套模式——不是这次临时发明的新想法。

### 2.2 两个真实供应商到底在判断什么

两个 adapter 都是从模型里提取一个**结构化、强类型**的响应——而不是先拿到自由文本再用正则去解析——用的是各自 SDK 自己原生的结构化输出机制：

- Anthropic：`client.messages.parse(..., output_format=ConversationDecision)` → 解析结果在 `response.parsed_output`。
- OpenAI：`client.chat.completions.parse(..., response_format=ConversationDecision)` → 解析结果在 `response.choices[0].message.parsed`。

这是**两种确实不同的真实形状**（现场对照实际已安装的 `openai` 包自身的类型定义确认过，不是凭空假设的）——所以每个 adapter 都有自己专属的单元测试 fake，而不是共用一套。

两者解析出来的是完全同一套 schema：

```python
class ConversationDecision(BaseModel):
    kind: Literal["text", "proposed_action", "escalation"]
    text: str | None = None
    action_summary: str | None = None
    action_risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"] | None = None
    escalation_reason: str | None = None
```

这和 `ReasoningOutcome`（本来就存在的返回类型）用的是同一种判别式联合（discriminated union）形状——模型被要求填的，正是 static 占位实现原来靠关键词匹配所做出的那同一个三选一决策，只不过换成了真实的判断力。

### 2.3 系统提示词是刻意收窄的，而不是"做一个乐于助人的助手"

提示词告诉模型：合法的结果只有 3 种，并且——这一点很关键——**明确点名了这个平台今天在下游真正存在的唯一一个自助操作**：发送密码重置邮件，风险等级永远是 `LOW`。提示词明确要求模型不要提出任何其他操作。

这个限制不是风格选择，而是本代码库自身现状逼出来的一个诚实性要求。`agent-runtime-service` 自己的工具派发适配器（`ToolGatewayPort`）本身也还是个占位实现（`LoggingToolGatewayPort`），只会记一条假的"DISPATCHED"确认日志，从来没有真正调用过 tool-integration-gateway 的真实 API。如果放任 LLM 去随意提议某个操作（比如"我帮你重启一下 VPN 服务"），系统会接受用户的确认，然后……什么真实的事都不会发生。把提示词收窄到那唯一一个至少在结构上端到端接通了的操作（哪怕最终执行环节还是假的），是为了让这个助手自己声称能做的事情始终是真的，这和本代码库在别处一贯遵循的"绝不编造一个不存在的能力"原则是同一套纪律（例如 `RawOutputForbiddenException` 自己的理由、`TicketWorkflowClientPort` 关于一次调用到底携带谁的身份的理由）。

### 2.4 失败处理：宁可故障开放，绝不编造

如果 API 调用本身抛出异常（网络错误、鉴权失败、限流、响应格式不对）——`decide()` 里的 `except Exception` 分支会捕获它、记一条警告日志，然后返回一个诚实、朴素的 `ReasoningOutcome(kind="text", text="我现在处理这个有点困难……")`。它绝不会返回一个瞎猜出来的决策，也绝不会让一次 LLM 故障拖垮员工正在等待的那整个 HTTP 请求。

### 2.5 兼顾成本和延迟的默认模型选择

`send_message()` 是**同步地、内联地、在员工自己发起的那个 HTTP 请求内部**执行的（直接读 `SendMessageService` 自己的模块级 docstring 确认过："绕过了既有的异步 claim/complete worker 队列"）。这和 `evaluation-improvement-service` 自己的 LLM 评审场景不一样——那是离线批量给测试用例打分，可以容忍更慢、更贵的模型。这就是为什么这里默认选的模型（Anthropic 用 `claude-sonnet-5`，OpenAI 用 `gpt-5-mini`）是各自产品线里更快、更便宜的档位，而不是旗舰级、重推理的档位——毕竟真实的员工正盯着"……"的输入指示器在等这次调用返回。

## 3. 你问的多模态问题，如实回答

**是的——OpenAI 确实有真实的多模态模型，而且现在默认用的 `gpt-5-mini` 本身就是其中之一。** OpenAI 当前这代模型系列里的"mini"档位，保留了原生的图像（并且越来越多地支持音频）输入能力，同时价格比旗舰档位明显更低——它是"经济实惠但仍然多模态"这个诉求下正确的选择，而不是一个阉割掉多模态、只剩文本的精简版。如果你想要更低的成本下限、并且愿意为此牺牲一些质量，OpenAI 同一系列里"mini"档位下面还有一个"nano"档位；我没有把默认值设成它，是因为既然已经有回退到 static 的安全网兜底，一个质量不够好的真实决策，其实比占位实现更糟——在没有现场把两档都跑一遍对比之前，"mini"是更站得住脚的默认选择。

**但这里有一个必须如实说明的重要前提：上述多模态能力今天其实完全够不着**，原因和配置的具体模型名字没有关系，是结构性的：

- `ConversationDecision` 的提示词纯粹是从 `message_text` 和 `knowledge_snippets` 构建出来的（`conversation_reasoning.py` 里的 `_build_prompt()`）。这次调用里根本没有任何图像内容。
- `SendMessageCommand` **确实**带了一个 `attachment_refs: tuple[str, ...]` 字段——说明 API 契约里本来就留了附件的接口——但顺着这个字段的每一处使用追下去会发现，它只做了两件事：(a) 被记录进 `PRE_KNOWLEDGE_RETRIEVAL` checkpoint 的 JSON payload 里，(b) 除此之外完全没被用到。它从来没有被传给 `KnowledgeRetrievalPort.search()`，也没有被传给 `ConversationReasoningPort.decide()`。
- 再往下追一层：`commands.py` 里 `attachment_refs` 自己的 docstring 直接写明了，这些是"不透明的、已经上传完成、处于 `ready` 状态的引用，遵循**共享附件能力自己的契约——这个能力已经立项，但还没有真正构建**"。这整个平台目前任何地方都还没有一个真实的附件上传/存储服务（本 session 早些时候已经核实过，横跨全部 8 个后端 domain 都没有）——所以就算我把 reasoning adapter 改造成能接受一个附件引用并去取它的字节内容，现在也没有任何真实的东西可以去取。

所以结论是：**选的模型本身具备多模态能力、也经济实惠；但围绕它的整个系统今天还没有端到端打通多模态。** 要把这个变成一个真正的功能（员工附上一张报错截图，助手真的"看"了它）需要依次做到：(1) 共享附件能力真正被构建出来（上传+存储+一个真实的读回 API——目前按项目记忆只是立项，还没建），(2) `SendMessageService` 把真实的附件内容（而不只是那个不透明的引用）传给 `decide()`，(3) 两个 adapter 自己的 `_build_prompt()` 都要扩展成能在真实请求体里带上图像内容块（两家 SDK 都支持这么做；但今天两个 adapter 都没有这么做）。这是一项真实的、独立的、由多个部分组成的工作——这次没有去做，也没有含糊地做一半——把它标记出来，交给你决定值不值得优先做。

## 4. 验证过什么，没有验证什么

**真实验证过的：**
- 548 个单元测试通过（比这次改动前的 537 个多）,包括针对两个 adapter 各自的 text/proposed_action/escalation 映射、以及各自的故障开放行为新增的测试，每一个都是对照一个和真实 SDK 响应对象形状完全一致的、鸭子类型的 fake 客户端（现场对照实际已安装的包确认过形状，不是凭空假设的）。
- 一个容器层测试确认了 `"anthropic"` 和 `"openai"` 两种模式各自都能构造出正确的 adapter 类。
- 架构测试（5/5）和 import-linter 分层契约（3/3：interfaces→application→domain、domain 不得依赖 web/ORM 框架、application 不得依赖 infrastructure）全部依然通过——这次新增没有越过任何不该越过的分层边界。
- 真实的 Docker 镜像重新构建过两次（每加一个供应商构建一次），带上了新的 `anthropic`/`openai` 运行时依赖，容器确认健康、行为没有变化（依然默认走 static 占位实现）。

**如实说明没有验证过的：** 这个环境里没有配置真实的 `ANTHROPIC_API_KEY` 或 `OPENAI_API_KEY`，所以没有对任何一家的真实 API 发起过真正的网络调用。上面这些"编译通过、路由正确"层面之上的东西——也就是真实模型对这个场景的判断到底好不好用——在你提供真实凭证并且实际试用之前，都是没有验证过的。

## 5. 改动到的文件

| 文件 | 改了什么 |
|---|---|
| `infrastructure/conversation_reasoning.py` | 新增 `ConversationDecision` schema、`AnthropicConversationReasoningAdapter`、`OpenAIConversationReasoningAdapter`、共用的提示词/映射辅助函数 |
| `application/ports_out.py` | `ConversationReasoningPort` 自己的 docstring 更新，说明现在有 3 个真实 adapter |
| `settings.py` | `conversation_reasoning_mode`（`static`/`anthropic`/`openai`）、`anthropic_api_key`、`openai_api_key`、`conversation_reasoning_anthropic_model`、`conversation_reasoning_openai_model` |
| `container.py` | `_build_conversation_reasoning_port()`——模式切换/失败回退到安全默认值的接线逻辑 |
| `pyproject.toml` / `uv.lock` | 新增 `anthropic>=0.69`、`openai>=3.0` 作为真实的运行时依赖 |
| `tests/infrastructure/test_conversation_reasoning.py` | 新增 8 个测试（每个供应商 4 个），针对鸭子类型 fake |
| `tests/test_container.py` | 新文件——针对模式切换接线逻辑的 3 个测试 |
| `infrastructure/docker-compose/full-platform.yml` | `CONVERSATION_REASONING_MODE`/`ANTHROPIC_API_KEY`/`OPENAI_API_KEY` 环境变量，默认全部为空、安全 |
| 本 spec 自己的 `traceability-entry.yaml` | 追加了两条记录，记的就是这些内容 |
