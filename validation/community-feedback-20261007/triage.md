# Android 社区反馈分诊 — 2026-10-07

来源为 [Linux.do 主题 2988062](https://linux.do/t/topic/2988062) 和 [scholay/rimes GitHub issues](https://github.com/scholay/rimes/issues)。本页记录报告、去重与验收要求；**不表示这些问题已修复或已在报告者手机复测**。

审计基线为 `b61b81fc4481773ed4b846ae538eee960499ff9a`。读取了主题初始 stream 的全部 113 条可见帖及新增 /115，共 114 条；编号 /68 不在初始 stream，抓取缺失 ID 为 0。初始 GitHub 审计覆盖当时全部 15 个真实 issue（8 开、7 关）、12 条评论和 45 个 PR；随后新增问题分别记录在下表。原始公开 JSON 保留在本次任务证据目录；本文仅摘录与分诊有关的内容。

所有下列 Linux.do 用户报告均发表于 **2026-10-06**；均未明确 APK 版本或 build code，不能默认归为公开 1.1.0 / code 12。未给出型号、系统和宿主的项目仍需补环境。源码、发行记录、维护者回复与当前手机验收是不同证据。

## 明确或有上下文确认的 Android 项目

`Axx` 是本次去重编号，不是 GitHub issue 号。初始 GitHub 快照无对应具体 Android bug；已有 #3 是移动端总单。随后已创建 #62、#63、#64、#68、#69、#70、#72（创建读回已确认）。修复提交、最终APK、真实JNI/IME和30分钟混合回归结果见 [本轮验证报告](report.md)；本机已完成本地验收，原报告者与其他设备仍待复测。

| ID / 分类 | 来源与报告者 | 反馈及去重结论 | 基线事实 / 当前处置与验收 |
|---|---|---|---|
| A01 / 入口缺失 | [17](https://linux.do/t/topic/2988062/17)，CmfZst | 缺少一键清空；原文未明确 Buffer 或宿主全文。 | 已确认 Clear Buffer 只在布局/配色面板。本轮直接入口限定 **Buffer 本次草稿**，不删除宿主已提交全文；已入 [#62](https://github.com/scholay/rimes/issues/62)，需确认清空组字、retained 和插件任务不会补回内容。 |
| A02 / 基础操作缺失 | [17](https://linux.do/t/topic/2988062/17)，CmfZst；[47](https://linux.do/t/topic/2988062/47)、[62](https://linux.do/t/topic/2988062/62)，tmwl；[60](https://linux.do/t/topic/2988062/60)，Waitingfor | 长按删除不能连删。合并为一项，不逐帖建重复单。/60 只写“手机端”；[65](https://linux.do/t/topic/2988062/65) 的对应维护者回复明确承认 Android 遗漏。 | 已确认普通及并击工具键仅单击回调，无 repeat 调度。已入 [#63](https://github.com/scholay/rimes/issues/63)；需真实 DOWN/保持/UP/CANCEL，保留组字→Buffer 整块→宿主码点删除顺序，旧手指/计时器不能作用于新目标。 |
| A03 / 视觉缺陷 | [17](https://linux.do/t/topic/2988062/17)，CmfZst | 点击删除时翻译功能栏闪烁；用户明确表示可能与手机有关。 | 插件栏复用五个按钮；pending 前后切换 enabled 并变暗是**待帧级验证的原因**。已入 [#64](https://github.com/scholay/rimes/issues/64)；同设备/主题记录逐帧结果，保留点击门禁，不能用静态截图或首键 benchmark 宣布闪烁消失。 |
| A04 / 遮挡 | [19](https://linux.do/t/topic/2988062/19)，panjinxin404；[21](https://linux.do/t/topic/2988062/21) 对应回复 | 附两张图，报告界面遮住内容。原文无平台；维护者对应回复确认 Android 显示 bug。 | 本次截图审阅确认 RIMES 仅前三排可见，Space/Return 底排缺失，下方仍有 MIUI 工具栏/导航；另一图正常键盘对照。已入 [#68](https://github.com/scholay/rimes/issues/68)；原因、宿主窗口/Buffer 状态和原手机复现待查，不把“快马加鞭”回复当修复记录。验收 Native/WebView/原宿主焦点和末行可见性、IME insets、横竖屏/字体缩放/手势及三键导航，键盘隐藏后恢复窗口。 |
| A05 / 九宫格布局与发现性 | [47](https://linux.do/t/topic/2988062/47)、[62](https://linux.do/t/topic/2988062/62)，tmwl | 符号难找；左上角符号、空格居中、123 位置、候选拼音位置等不顺手。/62 明确是九键体验，26 键评价不同。 | 与 A06 在 [#69](https://github.com/scholay/rimes/issues/69) 中分别验收。基线九键包含数字、符号、选拼音与非居中的宽空格；这是需评估的交互改进，不能仅凭现有 iOS 几何就宣告好用。以完整任务验证常用符号、拼音消歧、候选、空格和 Return；两方向布局/命中面积需截图及真实触摸。 |
| A06 / 九键默认状态及宿主数字 | [47](https://linux.do/t/topic/2988062/47)、[62](https://linux.do/t/topic/2988062/62)，tmwl | 报告默认为数字、输入框自动显示候选数字，希望拼音显示。尚未区分临时 composing、实际 commit、数字字段或初始化状态。 | 已入 [#69](https://github.com/scholay/rimes/issues/69)。基线默认偏好是全拼+26键；数字/电话字段强制直接输入，九键方案使用数字输入码，普通模式把 `snapshot.preedit` 显示在宿主。需分别记录 raw/preedit/commit，不将有意数字输入码直接定为已提交数字泄漏。覆盖首次启动/重开中文文本字段、数字字段、九键 `64426→你好`、非首候选、Buffer、Return 原码与换框；未确认候选不得误提交，保护字段仍走直接输入。 |
| A07 / 高频标点入口 | [74](https://linux.do/t/topic/2988062/74)，harlan9868；[47](https://linux.do/t/topic/2988062/47)、[62](https://linux.do/t/topic/2988062/62)，tmwl | Android 主键面无直接逗号/句号；九键常用符号/中文双引号难找，英文双引号不配对。 | 已入 [#70](https://github.com/scholay/rimes/issues/70)。基线26键在数字页提供逗号/句号，九键另有标点入口；#59 是 Windows 问号，不能合并。直接常用标点与引号配对是两个验收点，不默认承诺自动配对。覆盖中英文、九键/并击、composition 与 Buffer，不把候选栏插件快捷入口误换成常驻标点栏。 |
| A08 / 滑动快捷输入请求 | [84](https://linux.do/t/topic/2988062/84)，Wep-56 | Android 缺上滑输入数字及高频标点，附参考图。 | [86](https://linux.do/t/topic/2988062/86) 仅称 iOS 已支持，不构成 Android 完成。在 [#70](https://github.com/scholay/rimes/issues/70) 中单独检查手势，不能和 A07 直接入口、A02 退格重复互相替代；明确映射/阈值/取消规则及与并击、Scroll、点击的互斥，单手/多指实测，目标变化取消旧手势。 |
| A09 / 退格命中面积 | [84](https://linux.do/t/topic/2988062/84)，Wep-56 | 删除按键小、难按。 | 在 [#70](https://github.com/scholay/rimes/issues/70) 中检查命中面积，与 A02 连发分开。基线 visual cap 与实际 touch cell 独立，需测报告布局和密度下实际 bounds，不能只放大图标。覆盖边缘和相邻键、横竖屏/字体缩放、九键及并击，取消/移出不误触；真实触摸命中率与尺寸记录后验收。 |
| A10 / 词库与联想体验 | [47](https://linux.do/t/topic/2988062/47)、[62](https://linux.do/t/topic/2988062/62)，tmwl；[51](https://linux.do/t/topic/2988062/51) 对应回复 | 默认状态“力竭”未联想，期望更完整词库、更新网络词库和后续自加方案。 | 基线实际随 APK 打包离线词典；/51“没有内置任何词库”不能当作 Android 源码事实。固定锁定词典确无“力竭”整词条，而有“理解”同音词及单字“力”“竭”。已入 [#72](https://github.com/scholay/rimes/issues/72)；本轮增加 Android 独立离线增补词表，构建阶段合并到全拼、九键和自然码共用词典，不改 iOS 或锁定上游。真实 JNI 私密方案已验证三套均可选中并提交“力竭”，同音“理解”仍首选；这是完整词条修复，不代表云更新、提交后预测或全面词库质量已验收。学习、关闭学习、私密变体和自定义导入仍须各自验收。 |

A01—A03 已分别登记为 #62—#64。A04 底排截断为 [#68](https://github.com/scholay/rimes/issues/68)，A05+A06 九键预编辑/布局为 [#69](https://github.com/scholay/rimes/issues/69)，A07+A08+A09 日常按键可用性为 [#70](https://github.com/scholay/rimes/issues/70)；同单内各检查项分别验收。A10 的完整词条问题登记为 [#72](https://github.com/scholay/rimes/issues/72)。已补独立 Darwin librime 1.17.0 基线查询：全拼 lijie/li'jie、九键54543/li'jie、自然码lijx，私密方案各前10页（90候选）均无完整“力竭”；各查询仍未到末页，因此不声称全菜单绝无或不能分字输入。临时用户目录导出学习条目为0；未接 Android 单例或实际用户词库。补词后真实 Android JNI 已验证全拼 `lijie` 与自然码 `lijx` 中“力竭”为 index 4（第5项），九键 `54543` 为 index 7（第8项），均在首个9项候选页且提交准确；“理解”仍第1项。使用独立合成用户目录和禁用学习的私密方案，未动实际用户词库。最终依据为 `final-engine-feedback.json`；本机原生/WebView社区624项通过，原报告者手机仍待验收。

## 平台待确认与跨平台需求

| 项目 | 公开来源 | 分诊 / 去重 |
|---|---|---|
| 小鹤双拼 | [60](https://linux.do/t/topic/2988062/60)，Waitingfor；[81](https://linux.do/t/topic/2988062/81) 补充 plum；[65](https://linux.do/t/topic/2988062/65) 回复 | /60 仅称手机端，且 reply_to=/52（iOS 上架讨论）；小鹤需求平台未确认。基线 Android 仅内置全拼/自然码/五笔，确无小鹤选项。可由 #3 拆 Android 方案支持请求，但需确认双拼编码还是小鹤音形/导入。维护者的版权顾虑是回复观点，许可需从实际源包核对，不能写成已认定的法律障碍。 |
| 手机图标 | [64](https://linux.do/t/topic/2988062/64)，Wep-56；[70](https://linux.do/t/topic/2988062/70) 回复 | 原文只评图标，截图文件名提示手机，未明确 Android/APK；同作者 /84 才明确 Android。维护者称 DEV 残余已改，尚无所见安装版本。记录为版本识别/发行图标核对，不能凭回复闭单；与 A09 无关。 |
| 语音输入 | [2](https://linux.do/t/topic/2988062/2)、[6](https://linux.do/t/topic/2988062/6)、[66](https://linux.do/t/topic/2988062/66)、[75](https://linux.do/t/topic/2988062/75)、[87](https://linux.do/t/topic/2988062/87) | 多帖通用需求，不都是 Android bug。已有 [#7](https://github.com/scholay/rimes/issues/7) 是桌面/Apple Speech 提案；新增手机范围应链接其历史而不声称已经支持。要另定麦克风权限、离线/显式网络、取消、就地编辑及目标撤销规则。 |
| 手机纠错/预测 | [4](https://linux.do/t/topic/2988062/4)、[5](https://linux.do/t/topic/2988062/5)、[13](https://linux.do/t/topic/2988062/13) | /5、/13 多为其他 Rime 手机输入法体验，不是本项目可复现缺陷；归入需求背景。AI 显式润色不等于常规拼音自动纠错，不用推广回复证明功能已完成。 |
| 剪贴板与跨设备同步 | [20](https://linux.do/t/topic/2988062/20)、[35](https://linux.do/t/topic/2988062/35)、[53](https://linux.do/t/topic/2988062/53)、[96](https://linux.do/t/topic/2988062/96)、[100](https://linux.do/t/topic/2988062/100)、[110](https://linux.do/t/topic/2988062/110) | 去重到既有 [#6](https://github.com/scholay/rimes/issues/6) 的同步需求；/53 的 Gboard 剪贴板还可能只是本机历史，须区分。Android 端权限/同步提供者及隐私边界另验收，不能以 macOS 历史能力证明手机可用。 |
| 搜狗皮肤 | [42](https://linux.do/t/topic/2988062/42)、[44](https://linux.do/t/topic/2988062/44) | 未明确平台/格式，记录兼容需求；Android 内置18配色不等于第三方皮肤导入。须先确认资产格式、授权与移动端范围。 |
| 自定义方案 / 声笔 / 万象 | [56](https://linux.do/t/topic/2988062/56)、[76](https://linux.do/t/topic/2988062/76)、[80](https://linux.do/t/topic/2988062/80) | /56 引用鼠须管但未明确目标；/76 未明确平台；/80 为万象、moeType 及 WebDAV/S3 请求。既有 [#4](https://github.com/scholay/rimes/issues/4) 已关闭的是 macOS 导入，不能替代 Android。Android 基线明确尚无自定义 Rime/并击导入；如接入需移动端包/依赖/隔离/回滚/升级验收。 |
| DeepSeek / AI 翻译不可用 | [111](https://linux.do/t/topic/2988062/111)，sl1008；[63](https://linux.do/t/topic/2988062/63) 询问内置免费端口 | /111 未给平台、路由、endpoint/model、错误或是否选 AI 翻译；不能直接作为 Android 网络缺陷。Android 固定源码已支持用户显式开启的 AI 翻译路由；区分本机翻译、Mock、AI 翻译开关和显式配置真实服务，此帖先待询证，不标 Android bug。仅核对脱敏配置/错误码，不要求公开密钥或正文，不为验证偷偷发付费请求。 |
| 打字测速窗口截断 | [108](https://linux.do/t/topic/2988062/108)，1157；[109](https://linux.do/t/topic/2988062/109) 回复 | 原文未明确平台；维护者称旧功能将改版，未提供修复。Android 基线未实现统计卡，不能把该截图窗口问题标为 Android 现有 UI。待确认平台后分单。 |

[11](https://linux.do/t/topic/2988062/11) 是当前使用 fcitx+rime 后打算试本项目；尚未提供 RIMES 故障。
明确 iOS 的低版本/导入/标点报告（/39、/58、/91、/94、/104、/115）不归入 Android；Windows /32 问号与设置入口对应 [#59](https://github.com/scholay/rimes/issues/59)，/38 卸载在 /54 已明确 Win11。其余声援、推广与维护者愿景不产生缺陷单。

## 既有 GitHub 问题与完成证据

- [#3](https://github.com/scholay/rimes/issues/3) 是唯一明确的 Android 总单，仍开放；刷新源码/发行事实，保留其旧 iOS 范围和社区历史，不再建另一个 go/hold/no-go 总单。
- [#59](https://github.com/scholay/rimes/issues/59) 明确 `win11 28000.3086`、`turbo broswer`、`Shift+/→/`，不与 Android A07 去重。
- [#4](https://github.com/scholay/rimes/issues/4) 的关闭来自 macOS 维护者确认，无 closing commit；[#25](https://github.com/scholay/rimes/pull/25) 为文档基线。其关闭不证明 Android 导入完成。
- [#9](https://github.com/scholay/rimes/issues/9) 关闭有实际 Swift/macOS 提交 `db67a00163b0e31c4d4f7880d89919741b36817f` 和对应成功 CI；不证明 Android 设置入口问题已验收。
- [PR #48](https://github.com/scholay/rimes/pull/48)、[PR #49](https://github.com/scholay/rimes/pull/49) 已合并，均无 closing issue 引用。公开 [Android 1.1.0 / code 12](https://github.com/scholay/rimes/releases/tag/android-v1.1.0)、[Android CI](https://github.com/scholay/rimes/actions/runs/37195877532) 与[发布记录](../release-1.1.0/delivery.md) 确认原生前端/设置/显式可选真实 AI 已建立，不确认本页新反馈已修复。

## 共用验收与性能证据

1. 每项记录 issue、修复提交、APK SHA-256、包名/build code、实际安装读回和手机/宿主环境。保留原报告未复测状态；其他厂商、旧 Android 与真实16KB运行未测不能补勾。
2. 直接操作只作用于当前已授权目标；换框、选区变化、隐藏、切输入法、旋转/布局变更时取消旧 touch、队列及插件请求。私密字段禁用 Buffer/学习/网络，密码字段无候选。
3. 编辑/清空源稿必须撤销 RUNNING/READY 输出；旧 callback 不能恢复草稿。`commitText(false)` 保留源稿和可重试输出，成功且同目标后只消费一次；清空不触发 Send、Run 或 retained 自动投递。
4. `mode=delivery` 的本地拒绝连接/保持回调夹具可验证 retained/失败提交/旧目标；这是 IPC 以下证据。`NativeTouchContract` 的 attached native DOWN/UP/CANCEL 能扩展长按真实计时；单次 AX click 不证明长按。
5. 现有真实 IME touch/plugins/layout 夹具可复用字段、选区、隐藏和旋转场景。先用合成文本、本机 Mock 验证取消，避免付费网络和私密正文日志。
6. 闪烁以相同设置的逐帧录屏和 attached UI OnPreDraw/Choreographer 快照验证 visibility、enabled 视觉、alpha、位置/尺寸；受控延迟引擎回调可跨帧放大 pending 边界，但须标为人工压力场景。仅对象复用、减少 render 计数或一张截图不是闪烁验收。
7. 现有60样本首键/选词 benchmark 是 AX→TextWatcher 宿主变化延迟，未覆盖像素呈现；删除延迟与 frame total/layout/draw 分别报告次数、单位、p50/p95/max，不从单轮数据宣称稳定提速，也不把短回归当长时 soak。

## 本次边界

本页保留报告、平台归属与去重依据；实际实现、生产提交、APK哈希、自动/真机证据及未验收项见 [本轮报告](report.md)。本轮新增7项issue并更新#3总单，最终各项修复与验收评论均已写回、读回核对，8个issue保留开放；未推送源码或发布APK。公开帖子与真实本机验证分开，不把未知报告版本归因于公开code12。

所有修改位于独立Android工作树，主工作区Android标点、iOS与方案目录并行改动保持原样。使用合成文本、本机Mock及私密方案，保留偏好与用户词库；没有论坛回帖、自动云请求或付费API调用。

最后成功论坛快照为114条。2026-10-07 03:14 JST再次访问公开JSON返回403，不能确认随后新增帖，更不能将抓取失败说成无新增。同期GitHub重新读取25个issue（18开/7关），Android仍是#3及本轮7项，没有新增Android具体单。
