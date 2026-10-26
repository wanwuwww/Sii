<h1 align="center">Sii Browser</h1>

<p align="center">用 Kotlin 写的 Android 浏览器（个人项目）<br/>原生 XML + View 界面 · 不收集用户信息</p>

<p align="center">
  <a href="LICENSE.md"><img src="https://img.shields.io/badge/license-NonCommercial-blue.svg" alt="License" /></a>
  <a href="https://github.com/wanwuwww/Sii/releases"><img src="https://img.shields.io/github/v/release/wanwuwww/Sii?display_name=tag" alt="Release" /></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/minSdk-26-orange.svg" alt="minSdk" />
</p>

## 简介

Sii 是一款从零实现的 Android 浏览器，使用 Kotlin 与原生 XML 布局开发，不依赖 Compose。

目标是把「浏览」这件事做完整：多标签与标签面板、广告拦截与隐私保护、网页翻译、下载管理，以及本地 MCP 服务与页面抓包这类可被外部工具调用的能力。应用不含统计与崩溃上报 SDK，不收集用户信息。

## 功能特性

### 浏览与界面

- 多标签：新建、切换、关闭、恢复；标签面板支持网格与缩略图
- 地址栏直访与搜索、搜索建议、页内查找、电脑模式
- 主页：玻璃 / 极简 / 杂志三种风格；常用网站收藏；Logo 支持文字、图标、时间
- 界面风格预设与界面DIY：工具栏、标签页、菜单、主题、玻璃强度
- 悬浮胶囊工具栏、菜单上下 / 左右滑动、固定菜单位置
- 自定义标签页、外部链接承接

### 隐私与拦截

- 广告拦截：内建规则、自定义规则、规则订阅、单站开关
- 隐私保护：标准 / 严格 / 自定义；追踪、社交、挖矿、指纹、内容规则包
- GPC / DNT / Save-Data 请求头、URL 追踪参数清理、HTTPS 强制
- 无痕模式：不记历史、限制权限、退出可选清除
- 站点级设置：脚本、图片、存储、弹窗、权限、请求头逐站覆盖

### 翻译与阅读

- 网页翻译：在线 / 离线 / 双语对照，站点级翻译设置
- 阅读模式、网页朗读、正文提取

### 扩展与工具

- 用户脚本：UserScript metadata 解析 + GM_* API 子集
- 下载管理：分块并行、断点续传、暂停恢复、HLS、系统下载器
- 书签（文件夹树）、浏览历史、稍后读
- 扫码与二维码生成
- 本地 MCP 服务：工具按权限组下发，令牌鉴权，可对接外部 AI 客户端
- 页面抓包：资源请求元数据与 fetch/XHR 状态、耗时、响应体
- 更新检测：读取 GitHub Releases，可静默提示

## 截图

<!-- 待补充：主页、标签面板、设置页、夜间模式 -->

## 快速开始

### 下载安装

前往 [Releases](https://github.com/wanwuwww/Sii/releases) 下载 `Sii-x.y.z.apk`。

- 仅支持 arm64-v8a，Android 8.0（API 26）及以上
- Release 版为正式签名，可直接覆盖升级；与测试包签名不同，不能相互覆盖

### 本地构建

要求：JDK 17、Android SDK 36、（可选）`local.properties` 指定 sdk.dir。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

正式包（R8 混淆 + 资源收缩，仅 arm64-v8a，需在 `local.properties` 配置签名）：

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 技术架构

- Kotlin 2.0、AndroidX（AppCompat / Room / DataStore / Lifecycle / Media3 / CameraX）
- UI：原生 XML 布局 + View（无 Compose）
- 架构：单 Activity + Fragment，`AppServices` 服务定位器
- 注解处理：KSP（Room）

## 模块结构

```
com.sii.browser/
├── ui/            原生界面（Shell、浏览器、标签、设置、书签、历史、下载、规则、脚本）
├── core/webview/  WebView 容器（Client/ChromeClient 桥、拦截链、JS 桥、文本提取接口）
├── core/adblock/  广告拦截引擎
├── core/rules/    内建规则与订阅
├── core/privacy/  隐私管理（清除数据/无痕）
├── core/mcp/      本地 MCP 服务（JSON-RPC、工具表）
├── script/        用户脚本（解析 + GM 桥）
├── download/      下载引擎/服务/广播/JS 桥
├── data/          DataStore 设置 + Room（书签/历史/下载/收藏/规则/脚本）
└── di/            AppServices 门面
```

## 第三方组件

| 组件 | 许可 |
|---|---|
| AndroidX（core/appcompat/fragment/lifecycle） | Apache License 2.0 |
| Room / DataStore | Apache License 2.0 |
| OkHttp | Apache License 2.0 |
| Google ML Kit Translate / Language ID | Google ML Kit Terms of Service |
| Kotlin 标准库 / 协程 | Apache License 2.0 |

完整清单与版权声明见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 反馈

遇到问题或有建议，请提交 [Issue](https://github.com/wanwuwww/Sii/issues)，附上复现步骤与设备信息。

## 免责声明

本项目为个人独立开发，按「现状」提供，不附带任何明示或暗示的担保。使用本软件产生的任何风险由使用者自行承担。

## 许可

源码公开，采用非商业许可（不属于 OSI 定义的开源许可）：

- 允许个人学习、研究、非商业使用与修改；
- 禁止商业使用、应用商店分发、冒用名称与图标；
- 分发需保留本许可与署名，并以相同许可共享。

详见 [LICENSE.md](LICENSE.md)。
