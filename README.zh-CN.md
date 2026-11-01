# Friendai

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Español](README.es.md) · [Português (Brasil)](README.pt-BR.md) · **简体中文**

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Fabric-Loader-DBB69C)
![Java 25](https://img.shields.io/badge/Java-25-ED8B00)
![MIT](https://img.shields.io/badge/License-MIT-blue)

<img src="src/main/resources/assets/friendai/icon.png" alt="Friendai 图标" width="128" align="right">

一个开源的 Fabric 模组，为 Minecraft 添加了一位 AI 同伴。你可以在聊天中或通过语音与它交谈，它会跟随你、帮你取物、走到指定地点并为你挖掘方块。

> “Friendai，挖 10 个原木”——同伴会在聊天中回应，走到树旁，把树砍倒，捡起掉落物，然后向你汇报。

## 目录

- [它能做什么](#what-it-can-do)
- [环境要求](#requirements)
- [快速开始](#quick-start)
- [配置服务商](#setting-up-a-provider)
- [语音](#voice)
- [目标与规划](#goals-and-planning)
- [交易](#trading)
- [资源认知与搜索](#resource-knowledge-and-search)
- [语言](#languages)
- [配置升级](#configuration-upgrades)
- [创造模式](#creative-mode)
- [下载与发布](#download-and-releases)
- [构建](#building)
- [参与贡献](#contributing)

## 它能做什么

| 方面 | 同伴能做什么 |
|---|---|
| 跟随与停留 | 以步行的距离跟随，在你指定的位置停留，走到某个地点后再返回。 |
| 取物与交付 | 捡起掉落物，从已检查过的容器中取出物品，并把物品交到你手上。 |
| 挖掘与建造 | 挖掘指定数量的某种方块，砍倒整棵树，放置蓝图并建造楼梯。 |
| 探索 | 环顾四周，在限定区域内搜索某种资源，并能经由水路和洞穴寻路。 |
| 战斗 | 保护你，锁定敌对生物，受伤时后撤或进食。 |
| 持续工作 | 让自身周围的区块保持加载，在重启后依然存在，并恢复上一个任务。 |
| 记忆 | 保存按主人隔离的记忆、地点、资源发现、目标和交易记录。 |
| 说话 | 在聊天中以文字或语音回复，并可用本地或云端语音把回复说出来。 |

## 环境要求

- Minecraft **26.3**
- [Fabric Loader](https://fabricmc.net/) 和 [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**
- 可选：[Mod Menu](https://modrinth.com/mod/modmenu) 和 [YACL](https://modrinth.com/mod/yacl)，用于游戏内设置界面。不安装它们模组也能正常工作，此时可在配置文件中修改设置。

该模组必须同时安装在客户端和服务端（单人游戏中的集成服务端也算在内）。

## 快速开始

1. 安装模组并配置一个服务商（见下文）。
2. 进入世界后运行 `/friendai summon`。你的同伴会出现在你身边。
3. 和它交谈：
   - 在聊天中带前缀：`@ come here`
   - 在聊天中叫它的名字：`Friendai, mine 10 logs` 或 `Friendai, chop the tree next to me`
   - 用语音：按住 **V**（按键发言，可在“控制”中重新绑定）并说话。
4. `/friendai stop` 取消它正在做的事，`/friendai dismiss` 让它离开，`/friendai status` 显示当前服务商和当前任务。

同样的操作也在**暂停菜单（Esc）**中：报错图标旁边的同伴图标会打开一个界面，可用于召唤、解散和停止你的同伴，并查看它的当前任务和最近任务。管理员还能看到大脑日志（消息、工具调用、错误）以及调试工具：同伴看到的内容、它当前的提示词、手动发送一条消息和手动运行一次工具。

它能做的事：跟随你、停留、前往某个地点、捡起物品、把物品给你、环顾四周、展示自己的物品栏、停止，以及挖掘 N 个某种方块。

同伴会让自身周围的区块保持加载，因此当你走远时它仍能继续工作，并且在服务器重启后从上一次的位置恢复。`companion.chunkLoading.radius` 设置它周围有多少区块保持运算（0–4）；默认值 1 会在 5×5 的已加载区域中让 3×3 的区域保持运算，维度本身也保持活跃。可以在 `config/friendai-server.json` 中用 `companion.chunkLoading.enabled` 关闭该功能。

## 配置升级

受支持的旧版配置文件会自动升级：服务端结构版本 3–15 升级到 16，客户端结构版本 1–3 升级到 4。服务商配置档、API 密钥、模型、设备和显式设置都会保留；新增字段会获得经过校验的默认值。被改名的设置会被显式转换。未知的扩展字段会在升级和之后的保存中保留下来，包括重新排序过的路由规则。如果多个被编辑过的数组条目都携带额外字段、其身份变得无法确定，则会拒绝保存，而不是去猜测这些字段属于哪一条目；请在游戏/服务器停止时直接编辑该文件。

在替换之前，模组会校验结果，并把原始字节原样保存为文件旁边的 `<filename>.v<old-version>.<unique-id>.bak`。备份不会因另一次升级而被覆盖。写入采用原子替换；配置文件大小限制为 8 MiB。如果升级、备份或替换失败，原文件会保持完好并变为只读，同时在内存中使用默认值。解决问题后，重新打开服务器设置界面或重启游戏/服务器即可重试。

未知的、无版本号的以及未来版本的格式绝不会被猜测或降级。请使用与之匹配的模组版本，或保留备份后自行决定替换文件。要还原升级备份，请先停止游戏/服务器，并使用能够识别该备份结构版本的模组版本。备份与原文件包含相同的凭据；请像保护配置文件一样保护它们。主人记录和世界存档各自使用独立的持久化规则。

## 创造模式

从暂停菜单打开同伴界面，并在“控制”标签页中开启**创造模式**。该功能默认关闭。在专用服务器上，主人必须是管理员才能开启；在单人游戏中，即使关闭作弊，世界所有者也可以开启。主人可以随时关闭它，该偏好设置在解散、重新召唤和重新加载世界后依然保留。

在创造模式下，同伴无视普通伤害和饥饿，可以飞越碰撞体，瞬间破坏允许的方块且不产生常规掉落物，也不消耗建筑材料。保护规则仍然生效。使用仅限主人使用的 `create_item` 工具，可以把带默认组件的已注册物品交付给同伴或附近的主人；交付会在目标物品栏容量处停止，并报告剩余的数量。`fly_to` 会沿一条有界的路线穿过已加载的区块飞行。

仅限主人使用的 `execute_command` 工具只接受一份有限的可玩性白名单：传送同伴、修改或查询时间/天气、给主人物品、对同伴施加或清除效果，以及为主人手持的物品附魔。命令执行时，主人必须在线并保有正常的命令权限。在关闭作弊的单人游戏中开启创造模式不会授予命令权限。该工具会拒绝 `execute`、`function`、`schedule`、其他命令根，以及主人以外的玩家目标。关闭创造模式会取消已排队的创造模式工作，并恢复生存模式的移动、饥饿和伤害规则；已经创造出来的物品仍留在物品栏中。

## 配置服务商

打开 **Mod Menu → Friendai → Provider**，或编辑 `config/friendai-server.json`。在专用服务器上，编辑该文件并运行 `/friendai reload`。

| 预设 id | 服务 | 说明 |
|---|---|---|
| `openai` | OpenAI | Responses API |
| `anthropic` | Anthropic | |
| `gemini` | Google Gemini | |
| `openrouter` | OpenRouter | 一个密钥即可使用多个模型 |
| `deepseek` | DeepSeek | |
| `groq` | Groq | 托管的高速模型 |
| `mistral` | Mistral | |
| `xai` | xAI | |
| `together` | Together AI | |
| `fireworks` | Fireworks AI | |
| `cerebras` | Cerebras | |
| `github_models` | GitHub Models | 需要 GitHub 令牌 |
| `openai_compat` | 任何兼容 OpenAI 的端点（LM Studio、vLLM 等） | 必须提供 `baseUrl` |
| `ollama` | Ollama（原生 API） | 无需 API 密钥 |
| `opencode_zen` | OpenCode Zen | 按模型名称路由 |
| `command_code` | Command Code | 按模型名称路由 |

通过引用环境变量，可以让 API 密钥不出现在文件里：

| 如果你想要… | 选择 |
|---|---|
| 免费本地、无需密钥 | `ollama` |
| 一个密钥使用多种模型 | `openrouter` |
| 快速托管回复 | `groq` |
| 强大的工具调用 | `anthropic` 或 `openai` |
| 语音识别 | `google`、`deepgram`、`openai` 或 `groq` |
| 语音合成 | `google`、`elevenlabs` 或 `openai` |

```json
"apiKey": "env:ANTHROPIC_API_KEY"
```

密钥绝不会写入日志或聊天。设置界面中的“Test connection”会检查服务商是否有响应，以及该模型是否支持工具调用。

## 语音

按住 **V** 说话。打开 **设置 → 语音 → 识别**，选择把语音转换为文字的方式：

- **本地（Whisper，离线）**——[whisper.cpp](https://github.com/ggerganov/whisper.cpp) 会在你的 CPU 上运行 `tiny`、`base` 或 `small` 模型之一，完全免费。按 **下载模型**；模型保存在 `<game dir>/friendai/models/`。
- **OpenAI（付费）**——兼容 OpenAI 的转写 API（默认为 `gpt-transcribe`）。Base URL 可以指向任何兼容 OpenAI 的服务器，包括本地服务器，此时无需密钥。
- **Groq（付费）**——通过 Groq 使用同一 API（默认为 `whisper-large-v3`）。
- **Google Cloud Speech-to-Text（付费）** — 密钥和语言；录音以 LINEAR16 发送。
- **Deepgram（付费）** — 密钥、模型和可选语言。
- **Yandex SpeechKit（付费）**——需要 API 密钥、云端文件夹 id 和一个具体的语言标签。

转写结果会作为聊天消息发送到服务器。本地识别会让音频留在你的机器上；云端服务商会上传录音，且仅在选择该服务商时才会上传。

无论是文字聊天还是按键发言，都只能传达给同一维度内、位于服务器听觉半径（默认 32 格，`chat.hearingRadius`）之内的同伴。距离更远时，同伴既听不到你也不会回答，并且会明确告知你，而不是保持沉默。

### 语音输出

同伴也可以把回复说出来。语音合成默认关闭；在 **设置 → 语音 → 合成** 中开启 **朗读回复**，并选择一个服务商：

- **本地（免费，离线）**——[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 会在你的 CPU 上运行 Piper 语音。共提供五个语音：Irina 和 Dmitri（俄语）、Lessac 和 LibriTTS-R（英语）以及 Alba（英式英语）。选择一个并按 **下载语音**；语音保存在 `<game dir>/friendai/models/tts/`（每个约 67–82 MB）。俄语系统默认使用 Irina，其他系统默认使用 Lessac。每个语音都有自己的许可，显示在下拉菜单下方：模型文件大多为 MIT，LibriTTS-R 语音为 CC BY 4.0，而训练数据的条款各不相同。
- **OpenAI（付费）**——OpenAI 语音 API，需要密钥、声音和语速。Base URL 可以指向任何兼容 OpenAI 的服务器，包括本地服务器，此时无需密钥。
- **Google Cloud Text-to-Speech（付费）** — 密钥、语音、语言和语速；音频以 LINEAR16 返回。
- **ElevenLabs（付费）**——ElevenLabs 文本转语音 API；需要密钥、语音 id 和语速。
- **Yandex SpeechKit（付费）**——需要 API 密钥、云端文件夹 id、一个语音、一个语言标签、语速，以及 16 kHz PCM 输出。

只有在使用云端合成时，回复文本才会离开你的机器；本地语音绝不会把它发送到任何地方。使用云端识别时，只有录音会离开。两个方向的密钥都保存在客户端侧：它们存放在客户端配置中，可以引用环境变量（`env:OPENAI_API_KEY`），仅通过 HTTP 头传输，并且绝不会出现在日志、聊天或错误消息中。Friendai 绝不会自动重试付费请求。

音量、输出设备、单句朗读的最大长度，以及是否把回复朗读给其他玩家听，都在同一个界面中。**声音来自同伴** 默认开启：每条回复都从同伴的位置播放，会随着你的转身在左右声道之间移动，并随距离衰减，在听觉半径处完全静音。关闭后，回复会以全音量居中播放。**测试语音** 会朗读一句示例。按下按键发言键或 **停止朗读** 键（默认未绑定；请在“控制”中设置）会中断正在播放的回复。

## 资源认知与搜索

服务器会在 `<world>/friendai/knowledge/<owner-uuid>.json` 中为每个主人保存一份独立的资源图鉴。只有在同伴拥有清晰的视线，或确认自己成功挖掘破坏之后，才会记录方块位置。数量属于历史观测；记录过的地点可能被掩埋或采空，因此 `known_resources` 会报告数据的新旧程度和不确定性，绝不会承诺旧的数量仍然可用。查询只使用已保存的证据，不会加载区块。

只有在同伴于一次被允许的检查或转移过程中打开某个容器之后，该容器的内容才会被缓存。`obtain_item` 可以使用 16 格以内、同一维度中较新的快照来选定某个箱子作为来源，随后它会打开该容器，在取走物品之前核实实时内容。在探索过程中，绝不会检查未打开的容器。

`search_resource` 允许受信任的玩家发起一次有边界的搜索。同伴会选择确定性的地表观察点，并且只有在直接目视之后才报告资源。它不会根据地形推断出隐藏的矿脉。当拥有合适的耐久镐和 16 支火把时，`strategy=mine` 可以使用现有的受保护挖掘路线。`strategy=cave` 要求存在最近观测到的、无遮挡的露天视线通道，该通道经一个入口向下延伸到可见的内部墙面；若缺少这样的证据，它会拒绝出发。入口证据是临时的、按主人隔离的，五分钟后过期，且本身不会揭示任何资源。对同一主人、同一维度和同一目标的重复请求会复用已保存的预算。进行中的搜索工作会在主人登出或重新加载世界时被中断，而不会自动重放。

搜索策略位于 `config/friendai-server.json` 中的 `tasks.resources` 下：本地搜索默认范围为 64 格、2,400 tick。远征默认禁用，并且同时需要 `allowExpeditions` 和同伴区块加载。远征最多只持有下一段路程的区块票据；若区块加载被禁用，同伴会停在当前已加载的边界处。图鉴、覆盖范围和已执行的轨迹都按主人隔离，并且有边界。

## 目标与规划

对于包含多个部分的目标（“盖一座房子并带回铁”），同伴会记录一个目标，并记录它如何判断目标已经完成；该目标能在计划、失败、死亡和重启之后依然保留。它会通过任务队列持续推进这个目标，读取每个已完成任务的事实（获得和失去的物品、仍未满足的条件），并且当某一步失败时，会得到诊断结果和一个它尚未尝试过的补救办法。经过几次修复失败后，它会停下来问你是什么阻碍了它。

复杂的请求，或者在仍有重规划次数时失败的方案，会先由一次独立的规划调用起草并返回一份经过校验的计划；该计划会以你的权限级别入队，绝不会使用你无权使用的工具。相关设置位于 `config/friendai-server.json` 中的 `agent` 下：

| 设置 | 默认值 | 含义 |
|---|---|---|
| `toolRouting` | `true` | 只把请求所需的工具族发送给模型；它可以通过 `request_tools` 解锁更多工具。 |
| `autonomy` | `assistive` | `passive` 从不自行开始；`assistive` 会引导空闲的同伴朝未完成的目标努力；`autonomous` 还可以主动询问接下来做什么（它仍然绝不会未经提示就行动）。 |
| `maxReplans` | `3` | 同伴求助之前，每个目标允许的重规划次数（0–10）。 |
| `goalTickSec` | `30` | 朝未完成目标引导的间隔秒数（10–600）。 |
| `promptBudget` | `24000` | 系统提示词的字符预算；`0` 表示不限制。 |
| `planner` | none | 用于规划的服务商配置档名称；留空则使用当前配置档。 |
| `plannerTimeoutSec` | `60` | 一次规划调用允许的最长时间（秒，10–300）。 |

## 交易

主人可以让同伴去寻找已加载的村民、流浪商人和成年猪灵，查看原版报价，查询记住的价格，并预览明确的消费上限。市场记录按主人保存在 `<world>/friendai/trading/` 下；报价和位置各自独立过期，每次购买都会重新核对商人的实时报价。订单使用同伴自身的声望和物品栏，绝不会刷新库存或用强制手段改变职业。

商人订单需要按物品设置上限和保留量。队列重试共享原始主人回合的范围，且不能提高其限制；未完成的购买在重启后不会被重放。只有当同一个主人回合已经提供了明确的交易预算和支付指纹时，`obtain_item` 才会考虑商人。它只规划与组件无关的产出；涉及具体组件的交易使用 `trade_item`。

`barter` 工具使用真实的成年猪灵和原版金锭交互。它的产出是随机的，因此同伴只报告归属于该次尝试的掉落物；如果一块已被接受的金锭没有观测到结果，它会停下而不是再次支付。默认每个订单最多使用八块金锭。交易控制在“商人”标签页中仅主人可见可操作。服务端默认值位于 `config/friendai-server.json` 中的 `tasks.trading` 下；`enabled` 可以关闭所有交易工具和界面操作。

## 语言

界面支持英语、俄语、德语（`de_de`）、法语（`fr_fr`）、西班牙语（`es_es`）、巴西葡萄牙语（`pt_br`）、简体中文（`zh_cn`）和乌克兰语（`uk_ua`）。同伴会用你书写的语言回复；如果你指定了固定语言，则会用该语言回复。本地 Piper 语音只有俄语和英语；界面翻译不会增加本地语音，因此其他语言仍需使用云端合成或英语/俄语语音。要添加一种语言，请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

| 语言 | 代码 | 界面 | 本地语音 |
|---|---|---|---|
| 英语 | `en_us` | 是 | 是 |
| 俄语 | `ru_ru` | 是 | 是 |
| 德语 | `de_de` | 是 | 否 |
| 法语 | `fr_fr` | 是 | 否 |
| 西班牙语 | `es_es` | 是 | 否 |
| 巴西葡萄牙语 | `pt_br` | 是 | 否 |
| 简体中文 | `zh_cn` | 是 | 否 |
| 乌克兰语 | `uk_ua` | 是 | 否 |

## 下载与发布

发布版本发布在 [Releases 页面](https://github.com/NemoKing1210/Friendai/releases) 上。每个版本都恰好包含一个模组 jar `friendai-<version>.jar`，以及 `friendai-<version>.jar.sha256`；请把两者都下载下来，校验校验和
（`sha256sum -c friendai-<version>.jar.sha256`，macOS 上使用 `shasum -a 256 -c`），然后把 jar 放到客户端
和服务端的 `mods/` 目录中。发布说明就是该版本对应的 `CHANGELOG.md` 章节。

只有维护者可以创建标签。推送 `vX.Y.Z` 会启动发布工作流，它会拒绝与
`mod_version` 不匹配的标签、不在 `main` 上的提交，以及 CI 运行未通过的提交；随后它会执行完整构建并
发布经过验证的 jar。试运行和本地检查请参阅 `CONTRIBUTING.md`。

## 参与贡献

关于分支、提交、测试和翻译的规则，请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 构建

```bash
./gradlew build        # compile, unit tests and in-game tests
./gradlew test         # unit tests only
./gradlew runGameTest  # Fabric GameTests only
./gradlew runClient    # start a development client
```

## 许可证

[MIT](LICENSE)
