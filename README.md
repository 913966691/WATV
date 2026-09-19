# 蛙 TV (WATV)

<div align="center">

<img src="https://github.com/913966691/WATV/blob/main/website/tvbox/images/logo.png?raw=true" width="150px" />

**一个开源免费无广告的影视聚合应用**

[![GitHub stars](https://img.shields.io/github/stars/913966691/WATV?logo=Undertale)](https://github.com/913966691/WATV/stargazers)
![forks](https://img.shields.io/github/forks/913966691/WATV.svg)
![tag](https://img.shields.io/github/tag/913966691/WATV.svg)
![release](https://img.shields.io/github/release/913966691/WATV.svg)
[![GitHub Pull Requests](https://img.shields.io/github/issues-pr/913966691/WATV)](https://github.com/913966691/WATV/pulls)

</div>

## 📖 项目介绍

蛙 TV 是一款基于 **TVBoxMobile** 最新版本二次开发的 Android 影视聚合应用。在原版基础上进行了**全界面样式重构**，采用 Bilibili 风格的暗黑主题设计，同时修复了若干体验问题。项目代码完全开源，不收集或上传任何用户数据。

**致谢：** 感谢 TVBoxMobile 项目提供的优秀基础架构和核心功能实现。

### ✨ 近期更新亮点

**v1.0.9（当前版本）**
- 🐛 **稳定性大幅提升** — 修复切换订阅后点"来源"/"下载"按钮崩溃、横屏看直播退出崩溃、点播切线路 IndexOutOfBoundsException 等多处空指针/越界崩溃；观看历史与收藏不再随订阅切换而消失。
- 🔧 **茶寮等采集源播放修复** — 修复历史写入丢失 `?ac=detail&ids=` 前缀导致二次解析返回空（"暂无播放数据"）的问题，并支持旧数据自动自愈。
- 🎬 **播放与直播体验优化** — 中转失败明确 Toast 提示；切线路独立记忆每路的播放集数（切回原线路恢复当时那一集）；直播返回箭头竖屏隐藏；修复直播全屏返回后主界面残留横屏。
- 📡 **默认源调整** — 点播默认保留饭太硬/王二小，新增肥猫/动漫城/俊佬/摸鱼儿（删除 R18/18cn）；直播默认 Guovin（另加摸鱼儿）。

**v1.0.7**
- 🔔 **收藏剧集「更新」提醒** — 收藏页卡片右上角新增粉色「更新」角标：打开 App（或进入收藏页）时后台静默遍历收藏，用该条收藏记录的源 `sourceKey` 重新拉取详情，比对「最新集数」与本地记录的已知集数，变多即打标。
  - **电影不参与**：解析出的最大集数 ≤ 1 视为电影/单集，直接跳过检测。
  - **6 小时节流**：同一部剧 6 小时内不重复请求，省流量也避免触发源站风控。
  - **进入详情即消**：点开某剧后自动同步集数基线并清除该角标。
  - 收藏表新增 `lastEpisodeCount / lastEpisodeName / hasUpdate / lastCheckTime` 字段，Room 由 v1 升到 v2 并写了显式迁移（**不清空已有收藏**）。

**v1.0.6**

> 本版本合并了原内部迭代 **v1.0.6 – v1.0.17** 的全部改动，统一归并如下。

- 🎬 **播放与直播修复**
  - 修复「先播直播再点播无法播放」：视频流统一经 App 本地代理 `RemoteServer`(127.0.0.1:9978)，原代理绑定在 `HomeFragment` 生命周期、被重建误杀且无法重启（ExoPlayer 报 `2001` 网络连接失败）；已将代理生命周期上移到 `MainActivity` 随 App 常驻，并修复 `ControlManager` 的 `startServer`/`stopServer` 重启逻辑。
  - 修复「冷启动首进直播空白」：`LiveFragment` 懒加载导致首次可见性通知早于 `onViewCreated`，加载被 `if(mLoadService!=null)` 跳过；已在 `onViewCreated` 末尾加 `pageVisible` 兜底。
  - 修复「冷启动抢进直播页误报暂无频道」：新增 `ApiConfig.isConfigLoaded()` 标记区分「配置仍在加载」与「真的没频道」，配置未就绪时显示 loading 并每 800ms 轮询重试（60s 看门狗兜底），就绪后自动出列表，不再误报。
- 🏠 **首页加载修复**
  - 修复「首页加载中切走再回来卡 loading」：`onPause` 清掉延迟 `initData` 任务导致加载链中断；新增 `pendingInitData` 标志与 `onResume` 补调机制。
  - 修复「首页切走再切回内容空白需点击才出」：外层 ViewPager2 销毁重建 HomeFragment 后，内层 ViewPager 子 Fragment 懒加载 `init` 被漏触发；建立内部 ViewPager 与 `onResume` 时主动补发幂等可见分发（`BaseLazyFragment.reattachVisibleIfNeeded()`）并强制重布局。
- ⏱️ **启动页（弹幕 + 跳过 + 10 秒）**
  - 新增独立 `SplashActivity` 作为启动入口（停留 10 秒），全屏弹幕横飘（50+ 条随机文案、颜色/字号/速度随机、可重复）、右上角带倒计时「跳过」按钮（点击立即进首页）。
  - 修复启动页崩溃：`DanmakuView.stop()` 遍历动画列表时并发修改触发 `ConcurrentModificationException`，改为先快照再 `cancel`。
  - Logo 固定 180dp 居中显示；弹幕层级置顶飘过（`ivLogo` 底 → `danmakuView` 中 → `tvSkip` 最上），跳过按钮保持最上层可点击。
- ⏳ **加载超时冷加载兜底** — 首页与直播页新增 60 秒看门狗，超时在页面中间提示一行字并提供「重新加载」刷新按钮（点击任意位置触发冷加载重刷）；直播页「暂无直播频道」由弹窗改为页面中间一行字提示（同样支持手动重刷）。
- 🔧 **诊断日志** — 补齐 `WATV_PLAY` 诊断日志（释放链/起播链/播放状态序列），便于 logcat 抓时序定位。

**v1.0.5**
- 🔧 **版本号统一** — 头像旁与底部版本号均读取 `build.gradle` 的 `versionName`，单一来源，不再出现两边不一致
- 🛡 **语音容错增强** — 澎湃 OS 等无系统语音输入服务的设备，给出明确提示并引导使用输入法语音输入

**v1.0.4**
- 🎙 **语音输入兜底** — 当设备无内联语音识别服务时，自动调起系统语音输入界面（`RecognizerIntent`），识别结果自动回填输入框

**v1.0.3**
- 🎙 **AI 助手语音输入** — 输入框新增麦克风按钮，支持应用内语音转文字，说话时文字实时回填，确认后再发送

**v1.0.2**
- 🤖 **AI 智能助手增强** — 搜索结果卡片化展示并支持点击跳转播放；失败影视源加入 5 分钟短期黑名单；统一 `WATV_AI` 日志便于排查；修复千问触发工具调用后二次请求 400 报错
- 🎨 **UI 细节打磨** — Toast 提示统一为粉红底白字风格，播放器倍速按钮颜色优化
- 🎬 **播放器优化** — 默认使用 ExoPlayer，修复倍速选择器样式，横竖屏全屏按钮改用 dkplayer 内置图标
- 🧭 **导航统一** — 设置页"订阅"入口改为 tab 内切换，不再跳转独立 Activity
- 🐛 **稳定性修复** — 修复 Fragment 进程恢复时 `ActivityResultLauncher` 偶发崩溃问题

### 基础功能

- 🎬 支持多种视频源聚合（点播 / 直播）
- 📺 直播源管理与播放
- 📱 手机/平板自适应布局
- 🔄 应用内检查更新
- 🎯 横竖屏智能切换（独立横屏/竖屏全屏按钮）
- 🤖 **AI 智能助手** — 对话式搜索影视资源，支持语音输入，搜索结果以卡片形式展示并可直接跳转播放

## 🤖 AI 智能助手使用说明

AI 智能助手位于底部导航栏中间的「AI」入口，点击即可唤起对话窗口。它基于大语言模型，可帮你**用自然语言搜片、问片**，并直接把结果以卡片形式呈现，点击卡片即可跳转播放。

### 基本用法

1. 点击底部导航栏中间的 **AI** 图标，唤起对话窗口。
2. 在底部输入框输入你想看的内容，例如：
   - `我想看流浪地球`
   - `推荐几部悬疑美剧`
   - `周星驰的喜剧电影有哪些`
3. AI 会调用影视源搜索，并把匹配结果以**可点击卡片**展示在对话列表中，点击卡片即可跳转播放详情。
4. 若某个影视源暂时失效，系统会自动将其加入 **5 分钟短期黑名单**（冷却期内跳过该源），避免反复超时，不影响其他来源的搜索。

> 💡 对话回复与搜索卡片都打印在 `WATV_AI` 日志标签下，排查问题时在 logcat 过滤该关键字即可。

### 语音输入

输入框左侧带有 **🎙 麦克风按钮**，点击即可说话，文字会实时回填到输入框，确认无误后手动发送，避免误识别直接触发搜索。

- **支持内联识别的机型**：点击麦克风直接在对话框内收音识别，并显示「聆听中…点击停止」状态条。
- **首次使用**：系统会请求 `麦克风（录音）` 权限，授权后即可使用。
- **调起系统语音输入界面的机型**：当设备无内联识别服务时，会自动调起系统语音输入界面（如 Gboard / 厂商输入法），说完返回后文字自动填入输入框。

> ⚠️ **澎湃 OS（小米 14 等）用户请注意**：澎湃 OS 3 默认未提供可用的系统语音识别服务，应用内麦克风按钮可能无法正常工作（报错「似乎出错了呢」或「系统语音引擎需要您的授权」），这是 ROM 层服务缺失，**非应用 bug**。
>
> **推荐方案：使用输入法自带的语音输入**
> 1. 点击输入框调出键盘；
> 2. 长按键盘空格键，或点击键盘上的 🎙 麦克风图标；
> 3. 说话完成后文字会自动填入输入框；
> 4. 再点击「发送」即可。
>
> 待澎湃 OS 后续版本修复/开放系统语音服务后，应用内的麦克风按钮会自动恢复可用。

## 📲 下载安装

### 蛙 TV for Android

**最新版本：** v1.0.9

前往 [Releases 页面](https://github.com/913966691/WATV/releases) 下载最新 APK。

**加速镜像（国内用户）：**
- 主镜像：`https://gh.xxooo.cf/`
- 备用镜像：`https://gh-proxy.com/`

## 🛠 协作开发指南

本项目使用 **GitHub Actions** 自动构建和发布。

### 参与方式

1. **Fork 本仓库** → 在你自己 Fork 的仓库中进行开发
2. **提交 Pull Request** → 开发完成后向 `main` 分支提交 PR
3. **代码审查合并** → 维护者 Review 通过后合并到主分支

### 自动构建流程

| 触发条件 | 行为 |
|---------|------|
| 推送代码到 `main` 分支 | 自动构建 Debug APK，上传到 Artifacts（保留 14 天） |
| PR 提交 | 自动构建 Debug APK，用于验证代码是否正常编译 |
| 推送 `v*` 格式的 Tag（如 `v1.0.2`） | 自动构建签名 APK → 创建 GitHub Release → 同步更新 `update.json` 和 README |

### 如何发布新版本

1. **更新版本号**：修改 `app/build.gradle` 中的 `versionCode` 和 `versionName`
   ```
   versionCode 107
   versionName '1.0.7'
   ```
2. **推送 Tag 触发发布**：
   ```bash
   git tag v1.0.2
   git push origin v1.0.2
   ```

## 🎁 推荐视频源仓库

- https://github.com/gaotianliuyun/gao
- https://github.com/xyq254245/xyqonlinerule/
- https://github.com/UndCover/PyramidStore/

## 𝟭. 更新记录

>* **2026/09/19 蛙 TV v1.0.9：** 大量崩溃修复与体验优化（versionCode 109）——① 崩溃修复：修复切换订阅后点"来源"按钮崩溃（SearchHelper.splitWords 空指针，新增 resolveQuickSearchTitle 回退链）；修复详情页点"下载"按钮崩溃（vodInfo.seriesMap 为 null，新增 getCurrentSeriesList/getCurrentSeries 兜底 + 全线按钮 onClick 防 null）；修复横屏看直播退出后崩溃（mConnectTimeoutChangeSourceRun 中 currentLiveChannelItem 空指针，加 null 检查与 removeCallbacks）；修复点播切线路过程中 IndexOutOfBoundsException（playIndex=-1 绕过边界检查，改为 ≥0 且 <size 双段守卫）。② 订阅/历史：观看历史与收藏不再随订阅切换而消失（删除 getAllVodRecord 中按当前订阅过滤的逻辑）；切换订阅后视频找不到时给出明确提示（源不存在弹"建议搜索其它源"，源在但无数据文案修正，失败弹窗新增"重试"按钮）。③ 茶寮等采集源"暂无播放数据"根因修复：历史写入丢失 `?ac=detail&ids=` 前缀导致 spider 二次解析返回空对象；insertVodRecord 新增 overrideVodId 重载，以入口原始 id 写历史，并新增旧数据 fallback 自愈（纯数字旧 id 自动补前缀重试）。④ 播放体验：中转播放失败 Toast 提示"代理播放失败,正在重试尝试直连播放"；切线路改为每条线路独立记忆 playIndex（切回原线路恢复当时那一集，新线路不默认选中第 1 集）；直播页左上角返回箭头竖屏隐藏（PlayerTitleView.setHideInPortraitMode）；修复直播全屏点返回后主界面残留横屏（MainActivity 分支补 setRequestedOrientation(PORTRAIT)）。⑤ 默认源调整：点播默认源保留饭太硬/王二小、删除 R18/18cn、新增肥猫/动漫城/俊佬/摸鱼儿；直播默认源 Guovin（另加摸鱼儿 fish.y456y.com）。
>* **2026/09/16 蛙 TV v1.0.7：** 新增收藏剧集「更新」提醒——收藏页卡片右上角粉色「更新」角标。打开 App（延迟 8s）或进入收藏页时，后台串行遍历收藏，用该条记录的 `sourceKey` 重新 `detailContent` 拉详情，解析 `VodInfo.seriesMap` 取最大集数与本地 `lastEpisodeCount` 比对，变多即打标；电影/单集（集数 ≤ 1）跳过，同一部剧 6 小时节流，单个请求 20s 超时，配置未就绪自动重试 2 次；进入详情页后同步基线并清除角标。收藏表新增 4 个字段，Room v1→v2 显式迁移（保留已有收藏）。
>* **2026/09/15 蛙 TV v1.0.6：** 综合更新（合并原内部迭代 v1.0.6 – v1.0.17）——① 播放/直播：修复先播直播再点播无法播放（本地代理 `RemoteServer` 生命周期上移到 `MainActivity` 常驻、修复 `ControlManager` 重启逻辑）；修复冷启动首进直播空白（`onViewCreated` 末尾补 `pageVisible` 兜底）；修复冷启动抢进直播页误报「暂无频道」（`ApiConfig.isConfigLoaded()` 标记 + 轮询重试）。② 首页：修复加载中切走再回卡 loading（`pendingInitData` + `onResume` 补调）；修复切走再切回内容空白需点击才出（内层子 Fragment 补发可见分发 + 重布局）。③ 启动页：新增 `SplashActivity` 独立启动入口、停留 10 秒、50+ 条随机弹幕横飘、右上角倒计时「跳过」按钮；修复 `DanmakuView.stop()` 并发修改崩溃；Logo 固定 180dp 居中、弹幕置顶飘过。④ 加载超时冷加载兜底：首页/直播页 60s 看门狗 + 重刷按钮；直播页「暂无频道」由弹窗改一行字。⑤ 补齐 `WATV_PLAY` 诊断日志。
>* **2026/09/15 蛙 TV v1.0.5：** 版本号统一管理（头像与底部版本号均读取 build.gradle）；增强语音输入容错，澎湃 OS 等无系统语音服务的设备给出明确提示并引导使用输入法语音输入。
>* **2026/09/15 蛙 TV v1.0.4：** 新增系统语音输入兜底，设备无内联识别服务时自动调起系统语音输入界面，识别结果回填输入框。
>* **2026/09/15 蛙 TV v1.0.3：** AI 助手新增语音输入，输入框加入麦克风按钮，支持应用内语音转文字（实时识别 + 文本回填）。
>* **2026/09/15 蛙 TV v1.0.2：** AI 智能助手增强——搜索结果卡片化展示并支持点击跳转播放，失败影视源加入 5 分钟短期黑名单，统一 WATV_AI 日志，修复千问工具调用后二次请求 400 报错；UI 细节打磨（Toast 粉红底白字、倍速按钮配色）；播放器默认 ExoPlayer、修复倍速选择器样式；设置页"订阅"改为 tab 内切换；修复 Fragment 恢复时 ActivityResultLauncher 偶发崩溃。
>* **2026/09/13 蛙 TV v1.0.1：** 基于 TVBoxMobile 最新版本二次开发；全界面样式重构，采用 Bilibili 风格暗黑主题设计；修复若干体验问题；优化播放器横竖屏切换逻辑，新增独立的横屏/竖屏全屏按钮；移除原版品牌标识，统一为蛙 TV 品牌。

## 𝟮. 使用说明

- 所有源均收集于互联网，仅供测试研究使用，不得商用
- 本项目不存储任何的流媒体内容，所有的法律责任与后果应由使用者自行承担
- 您可以 Fork 本项目，但引用本项目内容到其他仓库的情况，务必要遵守开源协议

## 📝 开源协议

本项目遵循原项目的开源协议。

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐ Star 支持！**

</div>
