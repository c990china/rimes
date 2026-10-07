# TestFlight 社区反馈优化 — 2026-10-07

本轮收集 TestFlight 页面统计、公开社区反馈和 Xcode Organizer 可下载的崩溃样本，并据可验证的问题修改源码。本文仅保留聚合数量、版本、系统、硬件型号、终止类型和相关应用栈；不保存测试者姓名、邮箱、设备或事件标识、API Key、输入正文、私人截图或原始崩溃日志。

## TestFlight 页面快照

本轮主任务从 App Store Connect 读取到 `RIMES Community` 外部组 **126 人**。页面上的分 build 统计如下：

| 版本 | 邀请 | 安装 | 会话 | 页面统计崩溃 |
| --- | ---: | ---: | ---: | ---: |
| 1.1.0 (48) | 128 | 3 | 4 | 5 |
| 1.1.0 (40) | 129 | 7 | 37 | 18 |

这些是读取时显示的独立字段。邀请不是安装、安装不是同时活跃人数；外部组成员数与 build 邀请数也不是同一统计。页面未提供足够的共同时间窗口或完整使用分母，因此不由这些数字计算崩溃率、留存率或版本优劣。

本轮没有读到新的 TestFlight 截图反馈或用户主动提交的崩溃反馈。下文的 Organizer 自动崩溃样本是另一条证据来源。

## 公开反馈与本轮修改

| 反馈或证据 | 已核实情况 | 本轮源码改动 | 验证状态 |
| --- | --- | --- | --- |
| [社区帖 111：添加 DeepSeek 后翻译不可用](https://linux.do/t/topic/2988062/111) | 当前翻译固定使用 Apple 本地模型；新增 AI 服务保存时已自动选用，服务用于快问、润色、作诗和字符画。仅凭这句反馈不能判断 DeepSeek HTTP 请求失败。现行三语商店说明曾错误承诺 provider 翻译。 | AI 服务列表和编辑页补用途、完全访问与 ▶ 执行指引；语言包和键盘翻译设置注明 Apple 本地来源；修正现行三语 metadata。保持翻译后端和历史 submission 资料。 | 文案 Swift 语法与 diff 检查通过；完整 iOS 模拟器构建/测试已通过，真机 UI 尚待验收。 |
| [社区帖 115：雾凇导入被 user_dict 路径检查拒绝](https://linux.do/t/topic/2988062/115) | 仓库固定版本的雾凇 `en_dicts/cn_en` 是合法的 `stabledb` 只读表引用；旧检查一律将其按简单 ID 拒绝，且漏收子目录 TXT/源词典。 | 受限放开包内相对源词典和 `stabledb` TXT；继续拒绝绝对路径、遍历、空路径片段、写入型 DB 子路径等。检查包含 custom patch 后的有效设置。 | 修改前重现原错误；修复后 importer XCTest **20/20**、真实包检查/暂存 **1/1**；主机与 iOS 26.5 模拟器分别从源冷部署，各 **7/7** 候选/上屏/退格用例通过。尚未真机 UI 导入或发布新版 TF。 |
| Organizer：AI 首次发送同意时 `UIAlertController` 初始化 abort | 9 个 build 48 独立样本的应用栈均指向 AI `generate` 中的系统 alert 创建；部分系统栈明确为 `_UIApplicationAssertForExtensionType`。 | 同意界面改为键盘内部面板；明确同意才执行；原文、服务、插件授权、输入框或生命周期变化使待同意状态失效。 | 新增 `KeyboardReturnTests` 三项授权/失效回归，含旧按钮取消后不能同意新提示；最终相关套件 16/16 通过。仍需受影响系统版本与真机回归。 |
| Organizer：`RUNNINGBOARD 0xdead10cc` | 20 个独立样本；部分后台线程可见 LevelDB。原 engine `clear` 保留 session 和词库文件锁。 | `RimeMobile.suspend` 销毁本实例的 session；`MobileEngine.suspend` 清快照；键盘退场接入 suspend，下一次使用惰性重建。学习数据库保留。 | 实际 bridge + librime 1.17.0 的独立主机探针 **46/46**：跨进程锁前后、反复重建、方案保持、学习数据保留、最后 owner 释放、旧 generation 隔离。未重现 OS RunningBoard kill，不能断言20例均由此同一根因引起。 |

方案导入的独立证据位于忽略目录 `platforms/ios/build/tf-import-compat-20261007/validation-report.json` 与 `final-test.log`。锁验证的脱敏结果：[engine-session-lock-report.json](engine-session-lock-report.json)、[验证范围](engine-session-locks.md)、[复跑脚本](run-engine-session-lock-probe.sh)。

旧 TF build 的 importer 不会随本地源码改变。本轮尚未生成“扁平化文件名并同步改引用”的旧版兼容雾凇交付 ZIP；不能将新 importer 的通过结果宣传为群友现有旧版已经可导入原包。

## Organizer 样本去重与范围

8 个 Organizer 分组均已下载后，Xcode 本地缓存有 **36 个 `.crash` 文件**。以日志事件标识在内存中去重，并优先读取对应的本地符号化副本，得到 **31 个独立样本**；5 个文件是同事件的符号化副本。事件标识不写入本文或任何交付资料。

按版本为 **build 48：27、build 40：3、旧 build 32：1**；按终止类型为 **0xdead10cc：20、SIGABRT：10、SIGSEGV：1**。这些是可下载样本的范围，不是 TestFlight 当前统计窗口：不得把27份 build 48样本与页面“5次崩溃”合并、相减、累计或拿来计算崩溃率。

| 分组 | 独立样本 | 版本 | 日志系统版本 | 日志硬件型号 | 终止与应用栈 |
| --- | ---: | --- | --- | --- | --- |
| 0xdead10cc，iOS 17 | 5 | 40 ×3，48 ×2 | 17.0 (21A329) | iPhone15,3 | SIGKILL / RUNNINGBOARD；部分后台线程为 `leveldb::PosixEnv::BackgroundThreadEntryPoint`，主线程停在系统事件循环。 |
| 0xdead10cc，26.x | 5 | 48 | 26.5.2 (23F84)、26.6.2 (23G90)、26.7 (23H24) | iPhone15,4、iPhone18,1、iPhone16,1 | SIGKILL / RUNNINGBOARD；崩溃主线程没有可归因的 RIMES 应用帧。 |
| 0xdead10cc，27.2 | 5 | 48 | 27.2 (24B5089g) | iPhone16,1、iPhone17,2 | SIGKILL / RUNNINGBOARD；部分后台线程可见 LevelDB，主线程为系统事件循环。 |
| 0xdead10cc，PlugInKit 分组 | 5 | 48 | 17.7 (21H16)、26.2.1 (23C71)、27.0 (24A437) | iPhone14,5、iPhone18,3、iPhone17,5、iPhone16,2 | SIGKILL / RUNNINGBOARD；系统服务循环，没有明确应用崩溃帧。 |
| AI 授权 alert，18.7.1 | 4 | 48 | 18.7.1 (22H31) | iPhone15,3 | SIGABRT / SIGNAL 6；`UIAlertController` 初始化 → `generate` → `runSelectedPlugin` → 按钮回调。 |
| AI 授权 alert，27.0 | 5 | 48 | 27.0 (24A437) | iPhone16,2、iPhone18,3 | SIGABRT / SIGNAL 6；同一应用路径，系统栈包含 extension-type assert。 |
| 启动时 DocumentIdentity getter | 1 | 48 | 27.0.1 (24A446) | iPhone19,2 | SIGSEGV / SIGNAL 11；`DocumentIdentity.readObject` → `DocumentIdentity.read` → `viewWillAppear`；上层为 UIKit getter / Objective-C 消息调用。 |
| 历史 build 32 abort | 1 | 0.1.0 (32) | 26.6 (23G71) | iPhone16,2 | SIGABRT / SIGNAL 6；未获得明确 RIMES 应用帧，不能归入已定位的 AI 同意问题。 |

硬件型号使用日志中的型号串，未推断测试者身份或唯一设备数。系统事件循环的位置本身不能证明卡顿、候选选择、高度或内存压力导致终止；LevelDB 出现在后台线程也不自动证明该线程就是被系统终止的根因。

## 保留开放的问题

**DocumentIdentity 启动 SIGSEGV 尚待复现。** 原代码已有 nullable Objective-C getter / 无目标时拒绝插入的保护，并有 nil 回归；该保护处理空返回，不等于能保护 getter 内部的失效对象。使用与日志应用镜像匹配的 build 48 archive/dSYM 核对应用帧，并检查实际二进制指令：异常时尚未从 UIKit 的 getter 调用返回；返回值 retain 与 Swift UUID 动态桥接在后续指令，尚未执行。因此本例不支持“`takeUnretainedValue` 返回值所有权错误”的结论，也不支持将 ARC nullable bridge 替换声称为修复。

“`viewWillAppear` 早期远程 UIKit 上下文尚未就绪”仅是后续调查假设。本轮未贸然延迟读取、造 fallback UUID、使用私有接口或宣称此项已修复。需复现不同启动/返回前台/切换输入框的生命周期后再选择修法。

此前已知的首次展开闪烁、扩展候选时远程宿主避让、以及闲鱼候选提交/光标问题仍需实际宿主复测。本轮统计、导入和授权修改不会自动关闭这些问题。历史边界见 `platforms/ios/validation/build48/README.md` 与 `platforms/ios/validation/xianyu-host-commit-20261005.md`。

## 集成验证与交付

| 检查 | 结果 |
| --- | --- |
| 完整 iOS simulator build / hosted test | **通过**：iPhone 17 Pro / iOS 26.5；`xcodebuild test` 为 **211 总项、1 跳过、0 失败（210 通过）**，73.45秒。跳过项不计为通过。完整结果见下方收据；最终补充的提示复用防护另跑相关套件 16/16 通过。 |
| 资源和源文件一致性 / 权限及隐私配置检查 | `platforms/ios/scripts/verify.py` **PASS**。 |
| 独立 Shared core suite | 未独立执行；本次反馈修复未修改 Shared，iOS 集成使用当前 Shared 依赖。 |
| 键盘内部 AI 同意：明确同意、取消、原文/服务/插件/输入框/生命周期变化 | 三项授权回归 **通过**；最终 `KeyboardReturnTests` 套件 **16/16**，含取消/编辑后重开和重复运行时旧按钮拒绝新授权。测试不使用真实 API Key 或付费请求。受影响 iOS 18.7.1/27.0 真机仍待验收。 |
| suspend 后重开：普通输入、导入方案、Buffer、学习词频和锁释放 | `EngineTests` suspend/方案重建 **通过**；独立探针 **46/46**。实际系统 kill、真机切换/退场仍未复测通过。 |
| 雾凇导入 | 完整 App 集成中的 importer **20/20** 通过；独立真实包与 engine 已通过。真实包主 App 导入 → 键盘启用 → 上屏的真机 UI **待验收**。 |
| DocumentIdentity 启动崩溃复现 | **开放**。 |
| 首次展开/旋转/扩展候选/微信和闲鱼实际宿主 | **待复测**。 |
| 新 build 构建号、安装、TF 上传/审核/可用状态 | **尚未由本文证明**；这些是分别的交付阶段。 |

本轮本地测试收据（忽略的构建目录）：

- 完整集成日志：`platforms/ios/build/community-feedback-20261007/full-tests.log`。
- 完整集成结果：`platforms/ios/build/DerivedData/Logs/Test/Test-RIMES-2026.10.07_02-36-01-+0900.xcresult`。
- 最终授权复用防护日志：`platforms/ios/build/community-feedback-20261007/consent-final-tests.log`，16 项、0 失败，12.69 秒。
- 最终授权结果：`platforms/ios/build/DerivedData/Logs/Test/Test-RIMES-2026.10.07_02-39-51-+0900.xcresult`。

此次优化须保留当前已有中英标点/九键标点栏和跨平台同步改动，不将它们误计为新收集到的反馈修复。没有向社区或测试者发送回复，也没有保存线上 metadata、上传或自动发布。

测试尾部的诊断收集出现 `xcrun simctl` 不可定位的附加提示；完整 test result 已为 `TEST SUCCEEDED`，该提示不被误记成测试用例失败。本轮未因此改变全局 Xcode 选择。

## 测试反馈模板

请按下面几项提交，文字即可，复现视频可选：

- RIMES 版本与 **build 号**：
- iOS 版本、机型：
- 哪个 App、哪个输入框：
- 内置或导入的输入方案名称、键位布局：
- Buffer 是否开启、RIMES 是否开启完全访问：
- 具体操作顺序、原先期望、实际发生什么、每次还是偶发：
- 若为 AI 功能：选的是苹果翻译、快问、润色、作诗还是字符画；填服务名称/模型与可见错误即可：
- 可选复现视频或已遮挡隐私的截图：

**不要填写或拍入 API Key。** 使用无私人内容的示例文字；也无需提供账号、邮件、聊天正文或其他设备标识。
