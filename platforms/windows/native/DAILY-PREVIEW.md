# Windows daily-use preview implementation

The October 1–3 implementation history below used `codex/windows-daily-buffer`, based on main `b02fe9c`. That work is now integrated into `main`; new work starts from current `origin/main`. See [October 4 acceptance](validation/2026-10-04-1.1.0.md) and [October 7 community feedback](validation/2026-10-07-feedback.md) for the newer source and verification boundaries.

The product's chord scheme is named **isaac2026**, retaining the historical `my_combo` schema ID. Windows 1.1.0 includes its key-down/key-up frontend alongside the five ordinary schemes. October 4 real-engine chord probes passed on both architectures; a full custom-keymap editor and the broader daily-use matrix are separate scope.

Buffer capture is explicitly bound by the shortcut or by clicking its source rail while a RIMES host input field is focused. A tray open with no live field stays unbound: select the host field, then click the source rail. Focus loss pauses capture and delivery; returning to a field publishes a fresh TSF target but does not automatically resume capture. Repeated source clicks are idempotent, and passive target/status labels do not change routing.

## Boundaries

- Protocol v2 uses authenticated per-user/per-logon named pipes, with an independent long-poll notification connection. A target is a verified peer PID, globally unique Broker session, and TSF context/focus generation.
- TSF owns actual document edits. Ordinary snapshots apply commit/preedit/end in one edit session. Deferred edits retain their COM owners and a revocable context lease. Context removal, IME deactivation and Buffer mode changes retire old leases.
- Buffer delivery is uniquely identified, rejected on target changes, and acknowledged only after `InsertTextAtSelection` succeeds in `DoEditSession`. Enqueueing an asynchronous edit is never an acknowledgement. Lost confirmation freezes delivery; no automatic resend.
- Return ownership survives context changes until key-up. Repeats cannot leak to the host. Tap sends one block, a 1.2-second hold sends the current queue, with the next block sent only after acceptance.
- Password/private input scopes, disabled/empty TSF compartments, read-only and secure contexts are excluded. Buffer observes TSF selection changes as well as context changes. A non-activating panel cannot by itself establish a new insertion target.
- Broker serializes librime, owns a bounded in-memory Buffer, persists only settings, and runs WinHTTP on a background worker. Generation and translation share one configured endpoint; source/config/model revisions are frozen and stale chunks rejected. Incremental translation preserves original-to-result block associations.
- Package versions are immutable. x86 TSF locates the adjacent x64 Broker. Registrar has transactional keyboard and display-attribute categories. Installer checks hashes, architecture, dependencies and DLL occupancy, restores previous registration on failure, and retains user data.

## Verification layers

Baseline `689e84d`: Young Windows 11 build 22631, VS 2022 17.14 / MSVC 14.44 / SDK 10.0.26100 / CMake 3.31.6. Both x64/x86 builds, CTest, full product data deployment and fake-TSF/real-Broker typing passed in an isolated directory. Baseline reports remain in `D:\AI\rimes-windows-daily-20261001`.

Development checks include portable Buffer state-machine tests; runtime peer/acknowledgement/Return tests; protocol and SSE boundary tests; deferred-context revocation in fake TSF; a loopback WinHTTP transport suite for success, disconnect, malformed events, HTTP errors, cancellation, timeout and offline; full-data probes for five schemes and traditional output. Final results and known gaps must be recorded below after running the exact final commit.

## Acceptance status

Candidate `7c33090` passed both MSVC architectures, all 11 CTest groups per architecture, product-data probes, loopback API transport, fake-TSF integration, and 19 real installation-lifecycle checks on Young. Existing user database files and language preferences were preserved. Both TSF architectures are registered, with sign-out required because a desktop process still maps the original DLL.

On October 2, Windows App control became usable. Candidate `c99a3aa` (preview.4) passed both architectures, 11 CTest groups each, full-data probes and fake-TSF/real-Broker integration, including a new candidate-guard regression after unhandled KeyUp. It is installed in an immutable version directory. Real x64/x86 hosts and Notepad successfully typed Chinese and selected candidates by mouse; actual loaded DLL hashes match the package. Native Edit focus changes now clear old preedit. Basic Buffer delivery and explicit rebind passed on preview.1.

The Windows-local Cursor CLI workflow is verified: source transfer, local development and x64/x86 tests, retrieval, review and final retesting passed. Commit `995751f` added unavailable/reconnect regressions. Styling commit `efec80f7e06d2ec613e80398bc463be35ec66a16` then aligned candidates, Buffer, settings, tray and menus with macOS palettes and geometry. Its fresh final build passes 15 CTest groups per architecture, complete product-data probes and simulated TSF/live Broker integration. Package `0.2.0-preview.5` is installed; real x64/x86 candidate clicks, Buffer capture/delivery, four-theme preview and saved-theme propagation passed. The same x64 Broker serves both TSF architectures. See [style, package and real-host evidence](STYLE-ACCEPTANCE-20261002.md).

Second-round styling commit `6ec52332b304d8a6952e526f96c48d1e3f44c621` adds persistent Buffer controls, tighter block spacing, primary-rail action placement, theme details, owned settings clicks and palette-aware Windows 11 captions. Fresh x64/x86 builds each pass 16 CTest groups, full-data probes and fake TSF integration. Package `0.2.0-preview.6` is installed on Young; loaded module paths and hashes match. Real x64/x86 candidate clicks, Buffer capture/delivery, theme details, four-theme preview and saved-theme persistence passed. The final theme is restored to Night. See [October 3 source, package and installed-host evidence](STYLE-ACCEPTANCE-20261003.md).

Buffer binding repairs `be788f7` and `48c435b` add explicit source-rail binding, truthful paused state, focus/connection-ready target publication, and deferred target refresh when Chromium reuses a context for another field. Final package `0.2.0-preview.8` is installed on Young. Fresh x64/x86 builds each pass 16 CTest groups, full-data probes and fake-TSF integration. Installed x64/x86 hosts pass source-click capture and delivery; Edge passes textarea capture, paused field switching and explicit rebind/delivery to contenteditable. Loaded DLL hashes match the package. See [Buffer repair and installed-host evidence](BUFFER-BINDING-ACCEPTANCE-20261003.md).

Daily-use acceptance is **not complete**. The October 4 source repairs composition termination and passes 25 simulated termination/retype cycles; the earlier real Edge residue report still requires a current-package desktop retest. Earlier cold x86 activation passed initial letters through before the Broker was ready; warm-Broker success does not close that issue. Broader contenteditable behavior, VS Code, WeChat, Office, real OS scaling/multi-monitor, lock/resume, elevated windows, broker restart, a configured API provider and a working-day trial remain open. Existing desktop processes may require sign-out to unload old DLLs. Font rasterization, native caption geometry/shadows and OS menu borders differ from macOS. See [October 2 earlier real-host and Cursor evidence](ACCEPTANCE-20261002.md) and [October 1 automated/lifecycle evidence](ACCEPTANCE-20261001.md). The unsigned Windows 1.1.0 package was subsequently published on October 4; publication does not complete this daily-use matrix.

## Reproduction

`Build-RimesWindows.ps1 -Architecture x64 -Configuration Release` and x86 run CTest. `RimesTsfE2E.exe` needs a real Broker using isolated e2e schemas. `RimesProductProbe.exe <rime.dll> <shared> <isolated-user> <logs>` verifies product schemas. `python tests/test_provider_transport.py <RimesProviderProbe.exe>` uses disposable loopback endpoints and dummy keys; it never reads Credential Manager.

Use `New-RimesNativePackage.ps1` with an exact source commit, verified complete shared data, pinned x64 librime and a new output directory. Package README documents installation, explicit Buffer operation, credentials, upgrade, rollback and uninstall.
