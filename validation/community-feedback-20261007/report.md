# Android 社区反馈修复验证 — 2026-10-07

已把公开反馈去重为7个具体问题并逐项实现本地修复，交付 `1.1.0 / code13` 开发 APK并完成本机验收：核心70项、真实IME社区624项、30分钟混合输入622轮/10,995项断言通过。已覆盖安装到原手机，保留原词库并恢复原偏好与设备设置。源码与APK未推送或发布，issue保留开放等待发行及原报告者复测。来源与平台判断见 [triage.md](triage.md)。7项修复评论与#3总单补充已发布并按返回URL逐条读回核对，全部issue仍开放（[评论记录](evidence/issue-fix-comments.json)）。

## 交付身份与证据边界

| 项目 | 当前记录 |
|---|---|
| 审计起点 | `b61b81fc4481773ed4b846ae538eee960499ff9a`；只说明源码审计起点 |
| 性能基线 | 测试前手机实际安装的开发 APK：`1.1.0` / **code10**；不是公开 code12，也未证明它逐字对应审计起点 |
| 最终修复提交 | `cf6390a95d4d6fd7ebf6a0447578984d7198ed5f`（生产源码） |
| 最终 APK 包名 / versionCode | `org.scholay.rimes.android.debug` / `1.1.0` / **13** |
| 最终 APK SHA-256 / 字节数 | `f3189670324a55c49ae4f8b35a03fc6ec64906ca9c26261150331998caadb405` / **17,843,020 bytes** |
| 安装读回与设备恢复 | 安装 versionCode13/versionName1.1.0，拉回 APK hash与交付一致，同一开发证书覆盖安装；原有两份偏好逐项一致，词库导出词条/编码/词频一致；默认输入法、旋转/字体/硬键盘设置已恢复，测试备份标记已删除 |
| 当前真机范围 | Xiaomi `25098PN5AC`，Android16，arm64-v8a；无设备序列号入库 |

证据分为本机构建、IPC 以下的服务夹具、attached 原生组件触摸/绘制、真实手机 JNI 和真实 IME/宿主回归。前一种不自动证明后一种通过。构建资源独立分期生成；最终生产提交、APK与安装读回均已核对，证据文件哈希见 [manifest.json](evidence/manifest.json)。

## 逐项映射

| 问题 | 本轮实现与已有证据 | 尚待验收 |
|---|---|---|
| [#62 一键清空](https://github.com/scholay/rimes/issues/62)（/17） | 键盘内直接清空本次 Buffer；256条服务合同覆盖 retained、组字、RUNNING/READY、已排队/已投递迟到回调、目标门禁与失败提交；不会删宿主全文 | 原生与WebView清空已通过；原报告者复测待确认 |
| [#63 长按退格](https://github.com/scholay/rimes/issues/63)（/17、/47、/60、/62） | attached 原生触摸 repeat 72条通过；组字、Buffer 整块、宿主码点及取消/目标撤销分别验证；有 pending 时保留串行顺序 | 真实宿主连删/码点/整块/换框及30分钟混合回归通过；原报告者复测待确认 |
| [#64 删除时插件栏闪烁](https://github.com/scholay/rimes/issues/64)（/17） | 无组字、无排队工作时退格直接执行，服务合同确认不向引擎排队且五插件按钮保持 enabled；有 pending 时仍门禁 | 本机9次ROI像素/状态/几何采样稳定；连续所有帧和报告者宿主尚未复测 |
| [#68 底排截断](https://github.com/scholay/rimes/issues/68)（/19、/21） | 根据可用窗口高度约束键盘；height-fit 319条通过，覆盖组件尺寸/缩放边界 | 首次整机测试的 Enter 颜色白名单与系统截图配色不一致；修正后完整可见范围、底缘实际绘制和中心点按均通过。原报告者宿主、导航/厂商环境未复测 |
| [#69 九键布局与数字预编辑](https://github.com/scholay/rimes/issues/69)（/47、/62） | 九键功能键/居中空格及较大删除区；保留数字 raw code，宿主/Buffer 展示输入码对应的拼音；核心70条与真实 JNI 输入各阶段验证通过 | 本机真实IME组字/选词/原码Return/换框与布局触摸已通过；数字/电话字段及报告者初始偏好复测仍待补 |
| [#70 常用标点、滑动数字、删除面积](https://github.com/scholay/rimes/issues/70)（/74、/84、/47、/62） | QWERTY 直接逗号/句号，中文数字/符号页可直接输入中文符号/引号；上滑数字58条 attached 触摸合同与原生/WebView真实字面输入通过，扩大删除触摸区域 | 真实原生/WebView标点、数字与触摸已通过；**未实现自动引号配对** |
| [#72 离线完整词条](https://github.com/scholay/rimes/issues/72)（/47、/62） | Android 独立增补 `力竭 / li jie / 150`，仅修改 Android staging 的词典导入；三方案真实 JNI 首页选中并提交通过 | 原报告者体验；更多词条/预测/云词库/导入不在本条修复证明范围 |

## 已完成自动检查

已将合成文本日志、基准JSON、核心测试计数和七张仅含键盘的截图保存在 [evidence](evidence/)；不入库私有数据备份、偏好、密钥或完整 API 内容。文本日志仅规范化换行与行尾空白，保留原始结果；完整原始构建/测试日志在本机，摘要均来自对应通过日志。

| 检查 | 实际结果 / 证据 | 范围 |
|---|---|---|
| Core JVM | **70 tests，0失败/错误/跳过**；`final-build.log` 与当前7份 `TEST-*.xml` | `NineKeyPreeditTest`14条；早期 `core-test.log`65条中1失败，后续已修正，旧失败记录保留 |
| Build / Lint | **BUILD SUCCESSFUL，lintDebug通过**；`final-build.log` | app APK、androidTest APK、core及资源检查；不是所有设备兼容证明 |
| 资源 / 许可 | **PASS**；`final-native-check.txt` | 20预编译 Rime 资源、方案/许可证、26个锁定图标与完整许可；离线翻译 CC-CEDICT hash/重建检查通过 |
| 服务交付合同 | **256 checks PASS**；`service-delivery.txt` | 真实生产服务方法与本地拒绝 InputConnection，显式 fake engine；不是跨 IPC 的宿主拒绝行为证明 |
| 原生退格 repeat | **72 checks PASS**；`delete-repeat.txt` | attached 原生 DOWN/保持/UP/CANCEL 与取消规则 |
| 上滑数字 | **58 checks PASS**；`upward-number.txt` | attached原生触摸；真实原生/WebView字面数字与Buffer输入通过 |
| 绘制 / 高度 | **PASS**；`rendering.txt` | touch18、vector872、chord23、height-fit319；16,384块 Buffer 仅绘制可见块。1000次组件 render `440.33349ms` 不等于实际打字帧率 |
| 真实 JNI 词条/九键 | **PASS**；`final-feedback-engine.txt` / `final-engine-feedback.json` | 三套私密方案；独立合成用户目录，未读取实际用户词库 |
| x86_64实际ABI/引擎 | **PASS**；[日志](evidence/x86-linux-contract.txt)、[摘要](evidence/x86-linux-contract-summary.json) | 私密已有用户数据导出逐项一致；fresh private不学习，运行页4096，不是Android UI或16KB系统 |
| 最终真实 IME 社区回归 | **624 checks PASS**；[final-community.txt](evidence/final-community.txt) | 实际测试整套结束PASS；核心/本地夹具与真实宿主不互相替代 |
| 原有宿主/插件/并击回归 | 普通/私密文本框/原码Return/候选/方案/选区/隐藏/WebView **331通过**（密码OEM接管，显式待验）；布局398、并击117、touch90、插件358、宿主benchmark787项通过 | 单独记录每套合同与OEM密码边界 |
| 30分钟混合 soak | **PASS，1800秒/622轮/10,995项断言**；[日志](evidence/final-soak.txt)、[摘要](evidence/final-soak-summary.json) | 普通/正交/分体并击、Buffer、标点与两个输入目标；同一进程，无新增退出记录、重复上屏或跨框提交 |

原生库构建 receipt 使用 NDK `29.0.14206865`、CMake `3.22.1`。两种 ABI ELF LOAD 段均为16,384字节对齐；`final-apk-alignment.txt` 的 APK native ZIP 对齐检查成功。真实 JNI 已运行 arm64-v8a；x86_64 Android `.so` 已用锁定AOSP Bionic在隔离Linux容器实际加载，三方案dlopen与四方案静态引擎/分页/标点/学习持久/禁用学习合同通过；运行页大小4096。**未运行x86 Android模拟器UI、16 KB页大小系统或旧 Android/其他厂商设备**。本轮code13宿主验收为独立原生输入框与WebView；系统搜索框和应用内试打页没有重新执行整套验收，不沿用旧版本结果补勾。

| 构建对象 | SHA-256 |
|---|---|
| arm64-v8a JNI | `adf8a88346583bbc9a9320586ea8e2e12d4244f715e76b39391e99e9652e15d0` |
| x86_64 JNI | `35cfe8dd33ab3841140da148e5e2acba25d2d7163855d2f98d77c137c729e7fe` |
| Android 增补词表 | `652d317a4590d6d819fd4b362015341aa4e7e871ce1ec20f7a1caf8601f7bf92` |

## 真实引擎候选与九键展示

`reported_word_index` 为零起始索引，不能当作人类计数的候选位次。三套均保留“理解”为首候选：

| 方案 / 输入码 | “力竭”索引 / 首页位次 | 实际确认文本 |
|---|---|---|
| 全拼 `lijie` | 4 / 第5项 | 力竭 |
| 自然码 `lijx` | 4 / 第5项 | 力竭 |
| 九键全拼 `54543` | 7 / 第8项 | 力竭 |

最新实际 JNI `6 / 64 / 644 / 6442 / 64426` 保留原始数字输入码，展示分别为 `n / ni / ni'h / ni'ha / ni'hao`；末音节仅展示已经键入的前缀。早期 `engine-feedback.json` 在 `644` 回退到数字，后续修正已由 `final-engine-feedback.json` 与新增核心用例确认。未知或非法分段仍需保守回退，不将推测的完整音节误当已经输入的字符。

独立 Darwin 基线每查询前10页、90候选未找到完整“力竭”，未到末页，不能证明全部菜单不存在或无法分字输入。修复把完整词条加入 Android 构建合并后的同一个 `pinyin_simp` 词典，避免改变用户词库名；不改 iOS 资源或锁定上游词典。这不是通用词库质量、云更新、输入后预测或学习持久性的全面证明。

## 性能测量

基线为实际安装 **code10开发 APK**；本轮为 `1.1.0 / code13` 开发包。`baseline-engine-benchmark.json` 与 `final-engine-benchmark.json` 均为真实 JNI + 专用串行 EngineWorker，禁用学习、私密方案、独立合成用户目录。每方案20条预热、200条实测短语；key样本数因输入码长度不同。时钟为 `elapsedRealtimeNanos`，百分位为 nearest rank。单轮运行受 CPU、调度和缓存影响，**不宣称稳定提速**。

下表为 key 的串行往返延迟（提交任务→worker执行/快照→测试线程唤醒），单位 **ms**；包括排队/线程唤醒，**不包含主线程渲染、触摸分发、InputConnection或宿主文字变化**。

| 方案 | 测量版本 | key样本 | mean | p50 | p95 | max |
|---|---|---:|---:|---:|---:|---:|
| 全拼 | 基线 code10 | 1000 | 0.148873 | 0.145365 | 0.204323 | 0.431927 |
| 全拼 | 本轮构建 | 1000 | 0.154338 | 0.152708 | 0.223542 | 0.426615 |
| 自然码 | 基线 code10 | 800 | 0.225746 | 0.262917 | 0.408177 | 0.606823 |
| 自然码 | 本轮构建 | 800 | 0.117933 | 0.104531 | 0.177135 | 0.234063 |
| 五笔 | 基线 code10 | 400 | 0.051959 | 0.049375 | 0.071042 | 0.115365 |
| 五笔 | 本轮构建 | 400 | 0.051515 | 0.045573 | 0.077291 | 0.330729 |
| 九键全拼 | 基线 code10 | 1000 | 0.166396 | 0.153646 | 0.240000 | 0.827656 |
| 九键全拼 | 本轮构建 | 1000 | 0.076848 | 0.073802 | 0.103646 | 0.436510 |

候选确认 JNI 执行 p95（每方案200条，ms）基线→本轮：全拼 `0.040521→0.040416`，自然码 `0.029896→0.010730`，五笔 `0.013490→0.015469`，九键 `0.043542→0.015729`。这些数值仅对应引擎执行，不能说明按键到候选像素的体感改善。

两轮资源目录已存在；资源校验准备 worker 为 `52.785417→51.388177ms`，library load 为 `6.758177→3.604740ms`，初始化 API 为 `0.316459→0.080052ms`，ready_total 为 `61.115208→56.040520ms`（各仅1次）。初始化 API 指进程单例 API 阶段，**不包括首次选方案时的词典加载**，也不是手机首次解包的总启动时间；首用会话/方案值单独记录于 JSON。

真实 IME 的 AX→TextWatcher 60样本测量：**60 samples PASS**，首键 mean/p50/p95/max=`12.800/12.666/18.375/19.003ms`；确认候选=`10.464/10.476/14.349/16.479ms`。计量为AX请求至宿主TextWatcher，包括IPC/调度，不含硬件触摸或帧呈现。组件渲染、引擎执行和宿主变化属于不同计量，不能相互替代；9次功能栏ROI像素采样通过；这不替代所有帧或报告者宿主录屏。

## 数据、隐私与未实现范围

覆盖安装后23个原私有文件逐字节保持一致（`upgrade-data-check.json`）。最终恢复后，两份原偏好逐项一致；导出仅针对私有临时副本、未建输入会话，拼音91条和五笔1条的词条、编码及词频元数据哈希前后一致（[语义核对](evidence/final-userdict-semantic-check.json)）。LevelDB日志、CURRENT/MANIFEST与user.yaml管理元数据正常轮换，不宣称整个目录最终逐字节相同。默认输入法、旋转、字体和硬键盘设置已读回恢复；测试备份标记已删除（[恢复记录](evidence/final-device-restoration.json)）。实际用户数据备份、词条正文、偏好内容和密钥不入库。测试使用合成文本、本机 Mock/私密方案，不自动发付费 AI 请求或上传输入史。

30分钟soak起、中、结束为同一进程；前后16条历史退出记录内容一致，无新增退出。PSS三次快照分别83.35/90.38/88.35MiB，Views=202、AppContexts=4；这三次采样不能作为内存泄漏判定。早期测试设置阶段的自动化服务冲突记录保留，并未抹除后以“从未崩溃”描述整段开发过程。

原报告均缺 APK code；本轮不能把其归因于公开 code12，也不能声称已在原报告者手机复测。小鹤、DeepSeek、皮肤、打字统计等平台或配置待确认；Android 的可选真实 AI 翻译路由已存在，但不代表未确认平台的网络报告已解决。Android 自定义 Rime/并击导入、云词库/提交后预测及自动引号配对仍未实现。iOS 原始范围与并行源码未在本轮更改。

## 本地提交

- `11038eb`：布局、中文标点表、九键预编辑及核心测试。
- `c105273`：清空/连删/空闲删除、限高和真实宿主合同。
- `cf6390a`：离线补词和真实JNI验证。
- 插件源仓本地 `e56aeac`、`c276dce`；本体锁定 `c276dce6cf19a62fa055affc8e274036d8ffcf1d`，未推送。发布前须先同步依赖再同步本体。

均包含 Codex attribution；本轮未推送、发布或触发远端CI。APK来自上述生产源码，后续 `162eec1` 仅补实时密码验收诊断；不改变交付APK。

## 密码框验证边界

本机实测密码字段 `inputType=0x81`，focus/IMM/insets均正常；可见IME窗口由 `com.miui.securityinputmethod` 接管，RIMES q/n不可见。配置默认IME与API返回仍为RIMES，不能据此声明实际密码键盘来自RIMES。独立密码合同保持失败，原有普通回归仅通过既有显式skipPassword参数隔离该OEM边界；不改变安全键盘设置、不采密码正文或受保护屏幕。[实时诊断](evidence/password-diagnostic.txt)。因此RIMES实际密码上屏继续待验收，私密文本框中文/Buffer禁用与插件权限另有通过证据。

## 键盘像素证据

截图仅裁出IME区域，避开宿主正文；真实原生/WebView上屏结果另以合同断言记录。

| 状态 | 图像 |
|---|---|
| 26键竖屏 | [portrait](evidence/community-portrait.png) |
| 26键横屏完整底排 | [landscape](evidence/community-landscape.png) |
| 空候选的Buffer插件快捷栏 | [idle shortcuts](evidence/community-idle-shortcuts.png) |
| 九键候选/居中Space/较大退格 | [native nine-key](evidence/community-native-nine-key.png) |
| WebView九键 | [WebView nine-key](evidence/community-webview-nine-key.png) |
| 正交并击 | [orthogonal](evidence/community-orthogonal-chord.png) |
| 分体并击 | [split](evidence/community-split-chord.png) |
