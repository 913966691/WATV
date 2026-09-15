# 蛙TV 视频AI助手智能体 — 开发方案与进度

> 创建时间：2026-09-14 | 当前版本：v1.0.2

---

## 一、整体方案概述

### 1.1 目标
在蛙TV中实现一个 **纯LLM驱动** 的视频AI助手，用户可以通过自然语言对话完成：
- 搜索片源
- 播放指定电影/电视剧的特定集
- 播放最新一集
- 续播上次观看的内容
- 查看/播放收藏内容

### 1.2 技术选型

| 项目 | 选择 | 说明 |
|------|------|------|
| LLM接口 | OpenAI chat/completions 兼容 | 统一协议，适配多种LLM |
| 意图理解 | Function Calling（工具调用） | LLM自主决定调用哪个工具 |
| 预设服务商 | 千问（通义）/ 豆包 | 选服务商后自动填充API地址 |
| 自定义支持 | 任意OpenAI兼容接口 | 用户可填自定义URL |
| 配置存储 | Hawk（KV持久化） | API Key、模型ID等 |
| 网络请求 | OkHttp 3.12（项目已有） | 无额外依赖 |
| UI形态 | 底部弹出对话框 | 类聊天界面 |

### 1.3 架构设计

```
┌──────────────────────────────────────────────────┐
│              AI助手 UI 层                         │
│  AiAssistantDialog (底部弹出对话框)                 │
│  ├─ 消息列表 (RecyclerView)                       │
│  ├─ 输入框 + 发送按钮                              │
│  └─ 设置按钮 → LlmSettingsDialog                  │
└─────────────────────┬────────────────────────────┘
                      │ 用户输入
                      ▼
┌──────────────────────────────────────────────────┐
│           VideoAssistantEngine (引擎层)            │
│  ├─ 对话历史管理 (最近20条消息)                      │
│  ├─ 系统提示词 (角色设定+工具使用指南)                │
│  ├─ Function Calling 循环 (最多3轮重试)             │
│  └─ 工具执行器                                     │
│     ├─ search_videos → 调用 SourceViewModel 搜索   │
│     ├─ get_play_history → RoomDataManger 读历史     │
│     ├─ get_favorites → RoomDataManger 读收藏        │
│     ├─ play_video → 启动 DetailActivity 播放        │
│     └─ add_to_favorites → RoomDataManger 写收藏     │
└─────────────────────┬────────────────────────────┘
                      │ HTTP请求
                      ▼
┌──────────────────────────────────────────────────┐
│             LlmClient (LLM客户端层)                │
│  ├─ 请求体构建 (messages + tools)                  │
│  ├─ 异步请求 (OkHttp Callback)                     │
│  ├─ 响应解析 (ChatResponse / ToolCall)             │
│  └─ 错误处理                                       │
└─────────────────────┬────────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────┐
│             LlmConfig (配置管理层)                  │
│  ├─ Provider 枚举 (千问/豆包/自定义)                │
│  ├─ API URL / API Key / Model ID 存取              │
│  └─ Hawk 持久化                                    │
└──────────────────────────────────────────────────┘
```

---

## 二、已完成内容

### 2.1 代码文件（5个 Kotlin 文件）

| 文件 | 路径 | 状态 | 说明 |
|------|------|------|------|
| `LlmConfig.kt` | `app/.../ai/` | ✅ 完成 | LLM配置管理，Hawk存储，Provider枚举 |
| `LlmClient.kt` | `app/.../ai/` | ✅ 完成 | OpenAI兼容客户端，含Function Calling完整支持 |
| `VideoAssistantEngine.kt` | `app/.../ai/` | ✅ 完成 | 引擎+5个工具函数，search_videos 已接入全源搜索，play_video 支持 autoPlay 意图 |
| `AiAssistantDialog.kt` | `app/.../ai/` | ✅ 完成 | UI+消息列表，engine 已接通，真实调用 processInput |
| `LlmSettingsDialog.kt` | `app/.../ai/` | ✅ 完成 | Provider下拉+API Key+模型ID配置 |
| `SourceViewModel.searchSync()` | `app/.../viewmodel/` | ✅ 完成 | 新增同步搜索方法，复用 xml()/json() 解析，不触发 UI EventBus |

### 2.2 布局文件（4个 XML）

| 文件 | 状态 | 说明 |
|------|------|------|
| `dialog_ai_assistant.xml` | ✅ 完成 | AI助手主界面 |
| `dialog_llm_settings.xml` | ✅ 完成 | LLM设置界面 |
| `item_message_user.xml` | ✅ 完成 | 用户消息气泡（右对齐） |
| `item_message_assistant.xml` | ✅ 完成 | 助手消息气泡（左对齐） |

### 2.3 资源文件（6个 drawable XML）

| 文件 | 状态 | 说明 |
|------|------|------|
| `bg_ai_input.xml` | ✅ 完成 | 输入框圆角背景 |
| `bg_ai_send_btn.xml` | ✅ 完成 | 发送按钮粉色圆形背景 |
| `bg_message_user.xml` | ⚠️ 引用了未定义颜色 | 用户消息气泡背景 |
| `bg_message_assistant.xml` | ⚠️ 引用了未定义颜色 | 助手消息气泡背景 |
| `bg_edit_text.xml` | ✅ 完成 | 设置页输入框背景 |
| `ic_send.xml` | ✅ 完成 | 发送图标（VectorDrawable） |

### 2.4 各模块详细完成情况

#### ✅ LlmConfig — 完全完成
- Provider枚举：千问 / 豆包 / 自定义
- 自动填充预设API地址
- Hawk存储：Provider、自定义URL、API Key、模型ID
- `isConfigured()` 完整性校验
- `clear()` 清除配置

#### ✅ LlmClient — 完全完成
- OpenAI chat/completions 请求构建
- Function Calling 完整支持（tools 定义、tool_calls 解析）
- 异步请求（OkHttp Callback）
- 响应解析（ChatResponse / ToolCall / ChatMessage）
- 错误处理（网络错误、API错误、解析错误）

#### ✅ VideoAssistantEngine — 已完成
- ✅ 5个工具函数定义（search_videos, get_play_history, get_favorites, play_video, add_to_favorites）
- ✅ 对话历史管理（最近20条）
- ✅ 系统提示词（含 episode_index 语义：-1续播/-2最新/>=0指定集）
- ✅ Function Calling 循环（最多3轮重试）
- ✅ get_play_history / get_favorites / add_to_favorites 执行器（读/写 Room 数据库）
- ✅ **search_videos 执行器** — 遍历 `ApiConfig.getSourceBeanList()` 调用 `SourceViewModel.searchSync()` 聚合结果
- ✅ **play_video 执行器** — 带 `autoPlay`/`playIndex`/`playFlag` 意图启动 DetailActivity 直接起播
- ✅ 数值参数统一 `toIntArg` 处理（Gson 解析 Map 时数字为 Double）

#### ✅ AiAssistantDialog — 已完成
- ✅ 底部弹出对话框
- ✅ 消息列表（RecyclerView + MessageAdapter，含 user/assistant/system 三类）
- ✅ 输入框 + 发送按钮
- ✅ 加载遮罩
- ✅ 设置入口
- ✅ **engine 已接通** — initEngine 用 MainActivity 构造 VideoAssistantEngine
- ✅ **sendMessage 调用真实 engine.processInput**，替换原 mock 回复
- ✅ 未配置 LLM 时引导去设置

#### ✅ LlmSettingsDialog — 完全完成
- ✅ Provider 下拉选择
- ✅ API URL（千问/豆包自动填充，自定义可编辑）
- ✅ API Key 输入（密码模式）
- ✅ 模型ID 输入
- ✅ 保存/清除配置

---

## 三、已完成内容（截至 2026-09-15 构建）

> 构建版本 v1.0.2，`assembleDebug` 通过。原 §3.1/§3.2 的编译阻断与功能缺失项已全部解决：

### 3.1 ✅ 编译阻断问题（已全部修复）
- 颜色 `bili_pink_10` 已在 colors.xml 定义（`#1AFB7299`）
- `item_message_system.xml` 已创建
- 布局图标引用已修正为 `@drawable/ic_close_24` / `@drawable/ic_settings`

### 3.2 ✅ 核心功能（已全部实现）
- search_videos：遍历所有源调用 `SourceViewModel.searchSync()` 聚合结果
- engine 集成：AiAssistantDialog 接通 `VideoAssistantEngine.processInput()`
- 真实对话：sendMessage 替换 mock，走完整 LLM + Function Calling 链路
- "我的"页面入口：fragment_my.xml 新增 `tvAiAssistant`，MyFragment 启动 `AiAssistantDialog(requireActivity())`
- play_video：通过 DetailActivity 的 `autoPlay` / `playIndex` / `playFlag` 意图直接起播

### 3.3 🟢 优化项（后续迭代，非阻塞）
| # | 内容 | 说明 | 状态 |
|---|------|------|------|
| 10 | 搜索结果卡片展示 | 搜索到视频后以卡片形式展示，点击跳详情 | ✅ 已完成 (2026-09-15) |
| 11 | 语音输入 | 集成系统语音识别转文字 | ⬜ 待排期 |
| 12 | 多轮对话/流式响应 | 当前一次性返回，可改 SSE 流式 | ⬜ 待排期 |
| 13 | 搜索结果缓存 | 避免重复搜索相同关键词 | ⬜ 待排期 |
| 14 | 错误重试/限流 | LLM 异常自动重试 | ⬜ 待排期 |
| 15 | 失败源短期黑名单 | 搜索时跳过最近 5 分钟内连续失败的源，成功则恢复 | ✅ 已完成 (2026-09-15) |

---

## 四、关键数据流

### 4.1 "继续看庆余年" 完整流程

```
用户输入"继续看庆余年"
    │
    ▼
AiAssistantDialog.sendMessage()
    │ 添加用户消息到列表
    │ 调用 engine.processInput()
    ▼
VideoAssistantEngine.callLLM()
    │ 构建请求：[system_prompt, ..., user:"继续看庆余年"]
    │ tools: [5个工具定义]
    │ POST /chat/completions
    ▼
LlmClient → LLM API
    │ 返回：tool_calls = [{name: "get_play_history", args: {title: "庆余年"}}]
    ▼
VideoAssistantEngine.executeToolCalls()
    │ 调用 RoomDataManger.getAllVodRecord(100)
    │ 过滤 title.contains("庆余年")
    │ 返回 JSON: {count:1, history:[{vod_id:"xxx", source_key:"api1", episode:5, ...}]}
    ▼
VideoAssistantEngine.callLLM() (第二轮)
    │ 将工具结果作为 tool message 发回
    │ LLM 返回：tool_calls = [{name: "play_video", args: {vod_id:"xxx", source_key:"api1", episode_index:-1}}]
    ▼
VideoAssistantEngine.executePlayVideo()
    │ 构造 VodInfo
    │ 启动 DetailActivity (id=xxx, sourceKey=api1)
    │ DetailActivity 自动续播第5集
    ▼
LLM 生成最终回复："正在为您续播《庆余年》第5集 🎬"
    ▼
AiAssistantDialog 显示回复
```

### 4.2 "找一下流浪地球" 完整流程

```
用户输入"找一下流浪地球"
    │
    ▼
VideoAssistantEngine.callLLM()
    │ LLM 返回：tool_calls = [{name: "search_videos", args: {keyword:"流浪地球"}}]
    ▼
VideoAssistantEngine.executeSearchVideos()
    │ 调用 SourceViewModel 全源搜索
    │ 聚合搜索结果
    │ 返回 JSON: {count:5, results:[{title, source, vod_id, ...}]}
    ▼
VideoAssistantEngine.callLLM() (第二轮)
    │ LLM 生成回复："找到5个来源的《流浪地球》：\n1. 源A - 高清\n2. 源B - ..."
    ▼
AiAssistantDialog 显示搜索结果
```

---

## 五、关键接口说明

### 5.1 LLM API 请求格式

```json
{
  "model": "qwen-plus",
  "temperature": 0.7,
  "messages": [
    {"role": "system", "content": "你是蛙TV的视频助手..."},
    {"role": "user", "content": "继续看庆余年"},
    {"role": "assistant", "content": null, "tool_calls": [...]},
    {"role": "tool", "tool_call_id": "xxx", "name": "get_play_history", "content": "{...}"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "search_videos",
        "description": "搜索视频内容",
        "parameters": {"type": "object", "properties": {...}}
      }
    }
  ],
  "tool_choice": "auto"
}
```

### 5.2 预设服务商地址

| 服务商 | API地址 |
|--------|--------|
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| 豆包 | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` |

### 5.3 Function Calling 工具列表

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `search_videos` | keyword, type(movie/tv/all) | 全源搜索视频 |
| `get_play_history` | limit, title(可选) | 获取播放历史，可按标题过滤 |
| `get_favorites` | limit | 获取收藏列表 |
| `play_video` | vod_id, source_key, episode_index | 播放指定视频，-1=续播 |
| `add_to_favorites` | vod_id, source_key, title | 添加到收藏 |

---

## 六、开发优先级建议

### Phase 1：修复编译 + 打通基础链路 ✅ 已完成（2026-09-15）
1. ✅ 修复 3 个编译阻断问题（颜色/布局/图标）
2. ✅ 修复 VideoAssistantEngine 中的错误引用
3. ✅ 完成 AiAssistantEngine 的 engine 集成
4. ✅ 在"我的"页面添加入口按钮
5. ✅ 构建验证通过（v1.0.2）

### Phase 2：实现搜索功能 ✅ 已完成（2026-09-15）
1. ✅ 实现 search_videos 执行器（接入 SourceViewModel.searchSync 全源搜索）
2. 🟢 搜索结果以卡片形式展示（待优化）
3. 🟢 用户点击卡片可播放（待优化）

### Phase 3：体验优化（待排期）
1. 🟢 语音输入
2. 🟢 Streaming 响应
3. 🟢 更多错误处理和重试

---

## 七、文件清单汇总

### 已创建文件

```
app/src/main/java/com/github/tvbox/osc/ai/
├── LlmConfig.kt              (2.5 KB) ✅
├── LlmClient.kt              (8.5 KB) ✅
├── VideoAssistantEngine.kt   (15.8 KB) ⚠️
├── AiAssistantDialog.kt      (7.2 KB) ⚠️
└── LlmSettingsDialog.kt      (4.5 KB) ✅

app/src/main/res/layout/
├── dialog_ai_assistant.xml    ⚠️ (图标引用需修正)
├── dialog_llm_settings.xml    ✅
├── item_message_user.xml      ✅
└── item_message_assistant.xml ✅

app/src/main/res/drawable/
├── bg_ai_input.xml            ✅
├── bg_ai_send_btn.xml         ✅
├── bg_message_user.xml        ⚠️ (颜色未定义)
├── bg_message_assistant.xml   ⚠️ (颜色未定义)
├── bg_edit_text.xml           ✅
└── ic_send.xml                ✅
```

### 待创建文件

```
app/src/main/res/layout/
└── item_message_system.xml    ❌ (Adapter引用但未创建)
```

### 待修改文件

```
app/src/main/res/values/colors.xml
└── 添加 bili_pink_10 颜色     ❌

app/src/main/res/layout/dialog_ai_assistant.xml
└── ic_close → ic_close_24     ❌

app/src/main/java/.../MyFragment.java
└── 添加"AI助手"入口按钮        ❌

app/src/main/res/layout/fragment_my.xml
└── 添加"AI助手"列表项          ❌
```
