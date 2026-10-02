# 来源与第三方声明

123PanX 以 GNU GPL version 3 分发，全文见 LICENSE。版权属于 bileizhen 与各贡献者。以下上游的原有版权、许可证及来源说明继续适用；分发 APK 时应提供相应声明、许可证及完整对应源码和构建说明。

## LeiFetch 界面迁移

- 参考源：[bileizhen/LeiFetch](https://github.com/bileizhen/LeiFetch)，`main`，基准提交 `51ff7014805ef8cbdd7b2cf286b8700f620601ef`（以参考仓库实际完整提交号为准）。GPL-3.0。
- 本阶段迁移：`FloatingBottomBar.kt`；`liquid/{CombinedBackdrop,InnerShadow,Lens,Vibrancy}.kt`；`miuix/animation/{DampedDragAnimation,InteractiveHighlight}.kt`；`miuix/modifier/DragGestureInspector.kt`。包名改为 `io.github.bileizhen.pan123x`，接入本项目四标签和外观设置，移除 LeiFetch 业务耦合，保留文件头。
- 主题、Navigation 3、自适应导航、设置预览、关于页和传输工作台参考 LeiFetch 视觉及集成方式重新实现，内容改为 123PanX 的真实账户、文件与传输操作。
- 代理页及四种模式、域名/内网直连匹配、本机 HTTP CONNECT / SOCKS5 探测与五分钟缓存参考 LeiFetch `Proxy.kt`、`ui/ProxyScreen.kt`（GPL-3.0）重写。配置接入本项目 Keystore 加密存储和独立 OkHttp 客户端，保留有界代理认证，不迁移全局 Authenticator、Hook、Firefox 探测或镜像业务。

上游来源链来自 LeiFetch 原有第三方声明：

1. LeiFetch 界面壳经 [XBlocker](https://github.com/bileizhen/XBlocker)（同一作者，获其授权移植）继承 [SukiSU-Ultra v4.1.3](https://github.com/SukiSU-Ultra/SukiSU-Ultra/tree/v4.1.3)，提交 `0ca744a`，GPL-3.0。包含浮动底栏、液态玻璃辅助、拖动动画，以及主题、关于和导航设计。
2. 浮动底栏及液态玻璃原始辅助实现来自 [compose-miuix-ui/miuix 示例](https://github.com/compose-miuix-ui/miuix) 与 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，Apache-2.0。原始文件中的署名与说明保留。
3. M4 起迁移 LeiFetch 的 Kotlin NSFX 下载核心（`nsfx/NsfxPolicy.kt`、`NsfxStorage.kt`、`NsfxHttpClient.kt`、`NsfxDownloadEngine.kt`），包名改为 `io.github.bileizhen.pan123x.core.transfer.download.nsfx`，保留 GPL-3.0-only 文件头与来源注释。迁移时按本项目技术栈做了三处有意的重写：`org.json` → `kotlinx.serialization`；`android.util.AtomicFile` → 纯 JVM「临时文件 + rename」原子写；传输层 `HttpURLConnection` → OkHttp，写盘由 `RandomAccessFile.seek+write` 改为 `SegmentSink` 定位写（以支持 SAF 目标）。另有一处**行为性修正**（非技术栈替换，真机验证后做出）：LeiFetch 的断点身份包含 URL，本项目为避免短期 CDN signed URL 改变断点身份，改为只以 `size + validator`（强 ETag，回退 Last-Modified）判身份，URL 仅记入断点日志供诊断——否则每次重新取链都会导致断点被判失效并从头重下。LeiFetch 的 Hook / LSPosed / Xposed、捕获、插件、作用域、GitHub 镜像加速与通知耦合**未迁移**。
4. 本阶段仍未迁移 Hanabi 下载实现的其余部分、任何 Hook、LSPosed/Xposed、捕获、插件或作用域业务。

## 2026-10 界面与交互重设计

- 对照本地 LeiFetch main 设计，迁移通用 `SwipeActionRow.kt`（GPL-3.0），仅保留弹簧滑动、手势阻尼与操作面板；暂停、继续与取消由 123PanX 的传输状态机处理。
- `PanIcons.kt` 的矢量构造及 Download / File 路径参考 LeiFetch `TransferWorkspace.kt` 中的 `TransferIcons`，其余图标与页面内容为本项目重写。
- Miuix 标题栏、分段筛选、侧边栏、主题预览与关于页参考 LeiFetch，继续保留原有来源链。
- 设置页进一步迁移 LeiFetch `MainActivity.kt` 的 `settingsItems` / `appearanceItems` 分组与 Miuix `OverlaySpinnerPreference`、`TabRow`、`Slider` 用法；独立 `ThemePreviewCard.kt` 直接迁移 `ThemePreviewCardMiuix`，只调整包名、可见性与品牌文字。`SettingsSwitch.kt` 沿用 LeiFetch 同名组件的 `BasicComponent` + `Switch` 实现，保留来源并增加测试语义。删除 LeiFetch 专属设置与业务，换成 123PanX 已实现的参数、保存目录、账户及通知入口。
- 关于页进一步直接迁移 LeiFetch `ui/AboutScreen.kt` 的全屏 Scaffold、渐变背景、滚动分阶段淡出、模糊链接/名单卡片、AnimatedListItem 和详情弹窗；迁移通用 `ui/component/miuix/effect/{BgEffectBackground,BgEffectConfig,BgEffectModifier,BgEffectPainter,ColorBlendToken,DeviceType,OS3BgFrag}.kt` 及 `ui/util/BlurExt.kt`。来源链为 LeiFetch / XBlocker / SukiSU-Ultra / compose-miuix-ui 示例，GPL-3.0 及上游声明继续适用。改为 123PanX 品牌、本项目维护者与上游致谢、本地文字头像和对应隐私说明；未复制 LeiFetch 专属贡献者名单、QQ 群或任何 Hook 业务。无限帧动画使用 Compose 的 infinite-animation 接缝，支持测试冻结；页面离开或头部完全折叠后暂停背景动画，不支持效果时回退普通卡片。
- 关于页图标与应用名沿用 LeiFetch 的 `textureBlur`、150px 模糊、混色及 `DstIn` 遮罩。123PanX 标志来自原启动图标前景资源，使用绘制时的 ColorMatrix 清除蓝色底板并保留原字形与 X 渐变，没有复制 LeiFetch 的品牌图标或修改原 PNG；关闭模糊时按当前主题绘制同一遮罩。
- 文件快捷菜单的 Miuix 圆角、弱边界和本地 Popup 形式参考 LeiFetch `TransferWorkspace.kt`；按压坐标锚定、窗口避让、紧凑双列操作、入退场动画和 123PanX 文件业务由本项目实现。文件选中标记的淡入/宽度过渡及选中底色动画参考该上游的选中态交互；未迁移 LeiFetch 的链接复制或专属下载业务。
- 进一步迁移 LeiFetch `TransferWorkspace.kt` 的 `StaggeredEntrance` 与 `FilterBar`，用于文件列表/网格和传输列表的视口感知错峰入场，以及可横向拖动的弹簧分段指示块；任务计数改为本项目英文状态枚举。传输行沿用上游 `TransferRow` 的图标容器、主操作、细进度条与紧凑状态行布局，保留本项目上传/下载和独立详情页，不迁移链接复制、Hook 接管等业务。
- 传输搜索入口进一步沿用 LeiFetch `MainActivity.kt` 下载顶栏的按需展开、弹簧 expandVertically 和 shrinkVertically 退场方式；本项目将批量操作与完成项清理收进顶栏快捷菜单，避免正文的大按钮、常驻搜索框和重复统计挤占任务列表。
- 文件浏览页的固定搜索、目录工具条、紧凑容量摘要和多选工作台参考 LeiFetch `TransferWorkspace.kt` 的搜索及选择栏；菜单延迟执行与安全区布局由 123PanX 重写，保留原文件业务、按压处快捷菜单与入场动画。
- 检查更新的正式版本比较、项目发布页与 APK 来源校验参考 LeiFetch `AppUpdates.kt`，以 Kotlin serialization / 独立 Repository 重写，只检查 `bileizhen/123PanX` 的正式发布并打开系统浏览器；没有迁移 LeiFetch 下载镜像、自动安装或 Hook 业务。传输的动态总限速和任务配额沿用 NSFX 的通用调度思路，新增 Android 全局上传分片内存预算。

## 依赖与工具

日志容量与筛选职责参考 LeiFetch `Logs.kt`，其原有来源为 XBlocker `data/DiagnosticReport.kt` 与 `ui/SendLogDialog.kt`。本项目重写为独立内存 AppLogger 和统一 LogRedactor；通用日志导出另按下文的 XBlocker / SukiSU 来源与许可迁移，未引入 Hook。外观持久化职责参考 LeiFetch `Store.kt`，字段映射为本项目重新实现。

- Miuix UI / preference / blur / Navigation 3 UI 0.9.3：[compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)，Apache-2.0。
- AndroidX（Compose、Activity、Lifecycle、Navigation、DataStore、Room、Core 和测试库）及所使用 Material Icons：[Android Open Source Project](https://source.android.com/)，Apache-2.0。Material 图标仅作为矢量资源，不作为主 UI 组件。
- Kotlin、Kotlin Coroutines、kotlinx.serialization：[JetBrains](https://github.com/JetBrains)，Apache-2.0。
- Coil：[coil-kt/coil](https://github.com/coil-kt/coil)，Apache-2.0；M6 起经 `coil-network-okhttp` 加载预览直链图。
- Media3（ExoPlayer / UI 1.5.1）：[androidx.media3](https://android.googlesource.com/platform/frameworks/support)，Apache-2.0；M6 用于视频/音频在线播放。
- KSP：[google/ksp](https://github.com/google/ksp)，Apache-2.0，构建时用于 Room 代码生成。
- Gradle Wrapper：[gradle/gradle](https://github.com/gradle/gradle)，Apache-2.0，启动脚本保留原有版权声明。
- OkHttp 4.12.0：[square/okhttp](https://github.com/square/okhttp)，Apache-2.0。用于 123 云盘 API 请求、CDN 下载（NSFX 传输层）与测试（MockWebServer），未启用明文传输。
- androidx.documentfile 1.1.0：[AndroidX](https://developer.android.com/training/data-storage/shared/documents-files)，Apache-2.0。用于 SAF 目录树的文件创建与元数据读取。
- JUnit 4：[junit-team/junit4](https://github.com/junit-team/junit4)，EPL-1.0，仅测试。

Apache-2.0 原文见随附 `app/src/main/assets/APACHE-2.0.txt`。AndroidX、Miuix、Coil 等构件内的第三方版权及许可同样适用。项目所画应用图标为本项目占位图标，未复制 SukiSU 或 123 云盘品牌资产。

## 协议参考

[123panNextGen/123pan](https://github.com/123panNextGen/123pan)（`fluent-dev` 分支），GPL-3.0：123 云盘协议的唯一可信参考。M1 起，登录 / 用户信息 / 会话过期重登的行为按其 `session.py`、`model.py` 用 Kotlin 重写（未逐行翻译其 Python 代码）；请求头所需的设备池目录（`devices.py` 的 osversion / devicetype 清单）迁移至 `core/account/DeviceIdentity.kt` 并保留来源注释。后续文件、分享、离线与传输协议同样只以此仓库为准，不采信网络零散资料。

云盘信息与登录设备展示依据同仓库 `CloudUserInfoModel`、`DeviceItemModel`、`DeviceListResponse`、`NetSession.get_device_list` 和 `cloud_interface.py` 的行为重写：复用字段兼容规则、设备接口参数与容量口径，不迁入 Python / Qt 界面或设备删除逻辑。



## 2026-10 日志导出

日志导出迁移本地 XBlocker 的 `data/DiagnosticReport.kt` 与 `ui/SendLogDialog.kt`：ZIP 打包、限时收集当前进程 logcat、一天保留期、SAF 保存及只授予读取权限的 FileProvider 系统分享。诊断包仅含应用/设备版本、传输状态计数和统一脱敏的日志，未引入 XBlocker / Xposed 的模块状态、规则、Hook 或其他业务。界面来源链为 XBlocker / SukiSU-Ultra v4.1.3 (GPL-3.0)，XBlocker 原始通用收集实现的 MIT 许可如下；123PanX 组合发行继续采用 GPL-3.0。

MIT License

Copyright (c) 2026 XBlocker contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
