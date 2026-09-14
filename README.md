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

### ✨ 本次更新亮点（v1.0.2）

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

## 📲 下载安装

### 蛙 TV for Android

**最新版本：** v1.0.2

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
   versionCode 2
   versionName '1.0.2'
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
