# 键盘 session 与词库文件锁验证

2026-10-07，针对 TestFlight build 48 的 `RUNNINGBOARD 0xdead10cc` 日志检查键盘退场行为。日志本身没有报告被锁文件；以下探针证明的是应用存在的锁生命周期及本次修复效果，不代表已在报告者手机复现或消除系统终止。

使用实际 iOS `RimeMobile.mm` 桥接代码、项目内 librime 1.17.0 和内置 EngineData，在 macOS 主机运行独立探针。每次运行只创建新的验证目录，不接触真实用户词库。第二进程由 `posix_spawn` + exec 启动，使用 `fcntl(F_SETLK, F_WRLCK)` 验证所有新建词库的 `LOCK` 文件；同进程另开 descriptor 不足以检验进程所有的 POSIX 锁。

| 阶段 | 第二进程结果 | 结论 |
| --- | --- | --- |
| 活动 Rime session | 拒绝加锁（EAGAIN/EACCES） | DB 正在使用 |
| 仅 `clear_composition` | 仍拒绝加锁 | 清组字不能释放 DB |
| 销毁本实例 session / `suspend()` | 加锁成功 | 此实例的 DB owner 已释放 |
| 重建 session | 再次拒绝加锁 | 引擎恢复使用 DB |
| 同目录两个实例，只暂停一个 | 仍拒绝加锁，另一实例正常输入 | 暂停不破坏其他活 session |
| 最后一个实例暂停 | 加锁成功 | 所有 owner 都已释放 |

探针 46 项通过：包括 10 轮 suspend/恢复、双拼方案自动恢复、未决组字丢弃、非首候选学习、真正新进程重新读取学习排序、旧 generation 不销毁新 session，以及两个资源目录最终全部解锁。报告见 [engine-session-lock-report.json](engine-session-lock-report.json)。

实现只增加实例级 `RimeMobile.suspend()`，持现有 engineMutex，销毁当前 generation 的 session 后置零。保留 schema/resources/user directory，由已有 `ensureSession()` 在下一次调用时重建。`MobileEngine.suspend()` 清空显示快照、原码来源和错误。未增加全局 finalize 策略。

控制器接入 `protect()` 的引擎退场操作。该入口已有 `viewWillDisappear` 和 `NSExtensionHostWillResignActive` 调用；重新出现及 host 活跃路径由 `choose()` 恢复所选方案。可见会话内切输入框、移动光标等原有 `clear()` 保持清组字语义。

复跑（在仓库根目录）：

```sh
bash validation/community-feedback-20261007/run-engine-session-lock-probe.sh
```

探针源码与编译入口：`platforms/ios/scripts/engine-lifecycle-lock-probe.mm`、`platforms/ios/scripts/engine-lifecycle-lock-smoke.sh`。脚本默认使用已准备的内置 EngineData 与 host librime，证据保存在忽略的 `platforms/ios/build/engine-lifecycle-lock.*` 新目录。相关 iOS 回归位于 `EngineTests.testSuspendedEngineDropsCompositionAndReopensSelectedSchema`；模拟器集成和真机后台行为由整体测试另行记录。
