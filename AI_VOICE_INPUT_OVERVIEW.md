# AI 助手语音转文字 — 交付概览

## 做了什么
为 AI 助手对话框接入了系统语音转文字。支持两种路径：
1. **内联识别**（优先）：不离开对话框，实时识别回填。
2. **系统识别 Activity 兜底**：针对澎湃 OS 等没有内联识别服务的设备，调起系统 `RecognizerIntent` 语音输入界面，识别结果返回后填入输入框。

## 技术选型
- 优先使用 Android 原生 `android.speech.SpeechRecognizer` 做应用内联语音识别。
- 当设备未提供内联服务（`isRecognitionAvailable=false`，典型如小米澎湃 OS / 部分精简 ROM）时，由 `MainActivity` 启动 `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`，通过 `onActivityResult` 回传结果给 `AiAssistantDialog`。
- 录音权限 `RECORD_AUDIO` 用项目已有的 `XXPermissions` 库运行时申请。

## 改动文件
| 文件 | 改动 |
|------|------|
| `app/src/main/AndroidManifest.xml` | 新增 `RECORD_AUDIO` 权限声明 |
| `app/src/main/res/drawable/ic_mic.xml` | 新增麦克风图标 |
| `app/src/main/res/layout/dialog_ai_assistant.xml` | 输入区改垂直，新增麦克风按钮 + 聆听状态条 |
| `app/src/main/java/com/github/tvbox/osc/ai/AiAssistantDialog.kt` | 语音识别初始化 / 权限 / 识别 / 回填 / 错误处理 / 销毁 / 兜底入口 |
| `app/src/main/java/com/github/tvbox/osc/ui/activity/MainActivity.kt` | `startSpeechRecognition()` + `onActivityResult` + 保存当前 AI Dialog |
| `app/build.gradle` | versionCode 102→104，versionName 1.0.2→1.0.4 |

## 交互流程
**内联路径（Google/华为/OPPO/vivo 等）：**
1. 点麦克风 → 首次请求麦克风权限。
2. 授权后隐藏键盘，显示"🎤 聆听中…点击停止"。
3. 实时识别文字填入输入框，停止后保留文本。
4. 用户确认后点发送。

**澎湃 OS / 无内联服务路径（兜底）：**
1. 点麦克风 → 调起系统语音输入界面。
2. 用户说话，系统识别。
3. 识别完成后返回 AI 助手，文字自动填入输入框。
4. 用户确认后点发送。

## 已知限制 / 注意事项
- **双路径均依赖设备语音能力**：内联路径依赖 `SpeechRecognizer` 服务；兜底路径依赖系统是否有支持 `RecognizerIntent` 的 Activity。若两者都没有，会提示"当前设备不支持语音输入，请使用键盘语音"。
- **在线识别需网络**：系统语音识别多数走云端，弱网/无网会提示错误。
- 识别结果不自动发送，留待用户确认，避免误触发搜索。

## 验证方式
重装 `WATV-v1.0.4.apk` 后：打开 AI 助手 → 点麦克风 → 说"我想看流浪地球" → 文字出现在输入框 → 手动发送 → 应正常触发搜索卡片与 AI 回复。
logcat 过滤 `WATV_AI` 可观察识别日志。
