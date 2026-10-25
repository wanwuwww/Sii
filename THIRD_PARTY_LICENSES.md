# 开源组件许可清单（Sii）

Sii 以 [LICENSE.md](LICENSE.md) 中的非商业公共许可发布。下列第三方组件按其各自许可使用：

| 组件 | 用途 | 许可 |
|---|---|---|
| Kotlin Standard Library | 语言运行时 | Apache-2.0 |
| Kotlin Coroutines | 异步与并发 | Apache-2.0 |
| AndroidX Core / AppCompat / Fragment / Lifecycle / Activity | 基础 UI 与生命周期 | Apache-2.0 |
| AndroidX DataStore (Preferences) | 设置存储 | Apache-2.0 |
| AndroidX Room (Runtime / KTX / Compiler) | 结构化数据（书签/历史/下载/规则/脚本/密码等） | Apache-2.0 |
| AndroidX Browser | 自定义标签页服务 | Apache-2.0 |
| AndroidX DocumentFile | SAF 目录读写（下载目录、导入导出） | Apache-2.0 |
| AndroidX Biometric | 应用锁与生物识别 | Apache-2.0 |
| AndroidX CameraX (Core / Camera2 / Lifecycle / View) | 扫码相机预览与帧分析 | Apache-2.0 |
| AndroidX Media3 (ExoPlayer / HLS / UI) | 视频播放器 | Apache-2.0 |
| ZXing Core (`com.google.zxing:core`) | 二维码/条码解码 | Apache-2.0 |
| OkHttp (`com.squareup.okhttp3:okhttp`) | 网络请求（下载/订阅/HLS/脚本 API） | Apache-2.0 |
| Okio (`com.squareup.okio:okio`) | OkHttp 的 I/O 依赖 | Apache-2.0 |
| Google ML Kit Translate (`com.google.mlkit:translate`) | 设备端离线翻译 | Google ML Kit Terms of Service |
| Google ML Kit Language ID (`com.google.mlkit:language-id`) | 设备端语言识别 | Google ML Kit Terms of Service |

## 维护规则

新增或移除任何第三方依赖时，必须同步更新：
1. `app/build.gradle.kts` 依赖清单；
2. 本文件的组件、用途、许可与传递依赖；
3. 设置 → 关于 → 开源许可中的展示文本。

说明：
- 除 Google ML Kit 外，上述组件均为 Apache License 2.0，允许商业与非商业使用、修改与再分发，需保留版权与许可声明。
- Google ML Kit 依其服务条款使用，翻译与语言识别在设备端运行。
- 本项目不含任何统计/广告 SDK，不收集用户信息。
