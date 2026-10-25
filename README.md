# Sii Browser

用 Kotlin 从零实现的 Android 浏览器（个人项目）。

> Sii 是独立项目，与任何第三方浏览器产品及其作者无关联。详见 [LICENSE.md](LICENSE.md)。

## 功能

- 多标签浏览（新建/切换/关闭/恢复）、底部工具栏、标签面板
- 地址栏导航（URL 直访 / 搜索）、搜索建议、页内查找、电脑模式
- 网页翻译：在线翻译、离线翻译、双语对照、站点级翻译设置
- 广告拦截：内建规则、自定义规则、规则订阅、单站开关
- 隐私保护：标准/严格/自定义、跟踪器拦截、跨站 Cookie、GPC、HTTPS 与 URL 清理选项
- 无痕浏览：历史与标签隔离、权限拒绝、退出清理
- 下载管理：分块并行、断点续传、暂停恢复、系统下载器、HLS
- 书签（文件夹树）、浏览历史、主页收藏、稍后读
- 用户脚本（UserScript metadata 解析 + GM_* API 子集）
- 扫码、二维码生成、阅读模式、网页朗读
- 自定义标签页、外部链接承接
- 设置（DataStore 持久化：外观/行为/隐私/下载/高级）

## 技术栈

- Kotlin、AndroidX（AppCompat / Room / DataStore / Lifecycle）
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
├── script/        用户脚本（解析 + GM 桥）
├── download/      下载引擎/服务/广播/JS 桥
├── data/          DataStore 设置 + Room（书签/历史/下载/收藏/规则/脚本）
└── di/            AppServices 门面
```

## 构建

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

## 许可

非商业公共许可，见 [LICENSE.md](LICENSE.md)。

## 开源组件许可说明

| 组件 | 许可 |
|---|---|
| AndroidX（core/appcompat/fragment/lifecycle） | Apache License 2.0 |
| Room / DataStore | Apache License 2.0 |
| OkHttp | Apache License 2.0 |
| Google ML Kit Translate / Language ID | Google ML Kit Terms of Service |
| Kotlin 标准库 / 协程 | Apache License 2.0 |
