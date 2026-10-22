# PhoneForm AI PC

Windows 11 x64 独立离线问卷处理程序 / Standalone offline questionnaire processing for Windows 11 x64.

**建立项目 → 确认模板 → 逐份导入 → 本地辨识 → 人工覆核 → 导出 Excel**

**Create a project → confirm its template → import one questionnaire → recognize locally → review → export Excel**

## 下载与版本 / Downloads

| Version | Description | Release |
|---|---|---|
| **0.2.0-dev** | 当前源码、蓝白桌面布局 / Current source and desktop redesign | [Windows x64 MSI](https://github.com/Nickwong-kyoaka/Phone_Form_PC/releases/tag/v0.2.0-dev) |
| 0.1.0-dev | 原始安装包归档 / Original installer archive | [Windows x64 MSI](https://github.com/Nickwong-kyoaka/Phone_Form_PC/releases/tag/v0.1.0-dev) |

从 Release 下载 `.msi`，双击安装；随包 Java、OCR 模型和本地引擎，使用者无需另装 Java，首次使用不下载资源。每个安装包附 `.sha256` 校验文件。

Download the `.msi` from a Release and run it. Java, OCR models and local engines are bundled, with no first-run resource download. Each installer has a matching SHA256 sidecar.

两个版本均为内部试用预览版。0.1 标签仅保存历史安装包说明和证据，原完整源码快照未保留；`main` 和 `v0.2.0-dev` 提供当前完整源码。

Both releases are internal evaluation prereleases. The 0.1 tag contains archival metadata and evidence only because its complete historical source snapshot was not retained. Current application source is published on `main` and at `v0.2.0-dev`.

## 功能 / Features

- 繁中、简中、英文；界面语言、问卷语言和 Excel 标题语言独立设置，不自动翻译题目或答案。
- PDF、JPG、PNG 逐份导入，固定页槽、模板编辑、本地 OCR/OMR、人工覆核、四表 Excel 和历史快照。
- 蓝白侧栏、可搜索表格、图像/属性三栏工作区、自适应窄窗口、浅色/深色/系统主题。
- 本地加密数据库和文件、Windows CurrentUser DPAPI 密钥保护、加密编辑恢复、版本与人工答案保护。

- Traditional Chinese, Simplified Chinese and English, with independent interface, questionnaire and Excel-heading languages; questions and answers are not automatically translated.
- One-questionnaire-at-a-time PDF/JPG/PNG import, fixed page slots, template editing, local OCR/OMR, human review, four-sheet Excel export and historical snapshots.
- Blue-and-white navigation, searchable tables, image/inspector workspaces, narrow-window layouts and light/dark/system themes.
- Encrypted local storage, Windows CurrentUser DPAPI key protection, encrypted edit recovery, revision checks and human-answer protection.

默认工作区为 `%LOCALAPPDATA%\PhoneFormAI-PC`。`PHONEFORM_DATA_DIR` 可指定独立测试工作区。密钥绑定 Windows 用户；本版本不提供跨用户迁移或备份恢复功能。

The default workspace is `%LOCALAPPDATA%\PhoneFormAI-PC`; `PHONEFORM_DATA_DIR` selects a separate test workspace. Keys are tied to the Windows user. Cross-user migration and backup/restore are not implemented in this version.

## 构建 / Build

在 Windows x64 PowerShell 中执行。首次准备和依赖解析需要联网，完成后的应用离线运行。

Run in PowerShell on Windows x64. Initial dependency/runtime preparation requires network access; the packaged application operates offline.

```powershell
.\scripts\Prepare-Runtime.ps1
.\scripts\Invoke-Build.ps1 -Tasks 'test',':desktop:createDistributable',':desktop:packageMsi'
.\scripts\Verify-Runtime.ps1 -ApplicationDirectory '.\desktop\build\compose\binaries\main\app\PhoneForm AI PC'
```

Kotlin 2.1.20 / Compose Desktop 1.8.2 / JDK 21 / JVM target 17 / Gradle 8.9. `Prepare-Runtime.ps1` prepares a full JDK, OpenCV 4.12 and pinned three-language OCR models with checksum verification. Downloaded runtimes/models, caches and installers are excluded from Git. The original installer assets are attached to Releases rather than committed as large Git blobs.

| Module | Purpose |
|---|---|
| `domain` | Models, validation and platform contracts |
| `excel` | Four-sheet XLSX generation |
| `data` | Encrypted storage, repositories and worker coordination |
| `vision` | OpenCV, Tesseract and recognition |
| `desktop` | Compose Desktop UI and application lifecycle |
| `pdf-worker` | Isolated PDF helper process |

## 验证与文档 / Verification and documentation

0.2.0-dev 的历史执行记录：2026-10-21 有 92 项不同自动化测试通过，并完成指定三语、明暗主题、窄窗口、快捷保存和重启恢复抽查。当时三份合成工作簿经原生 Microsoft Excel 开启检查，无修复提示，独立 XLSX 核对通过。

Historical execution records for 0.2.0-dev show 92 distinct automated tests passed on 2026-10-21, along with selected language/theme/layout, save and restart checks. At that time, three synthetic workbooks opened in native Microsoft Excel without repair prompts and passed independent XLSX checks.

2026-10-22 按使用者要求，从最新版 `main` 清除测试问卷、派生 Excel、截图和相关逐项验证证据；保留源码、自动化测试及构建汇总。历史提交、标签和两个 MSI Release 保留。旧测试产物已不能从最新版 `main` 取得，构建汇总不能替代已清除的实测证据。

On 2026-10-22, test questionnaires, derived workbooks, screenshots and their detailed validation evidence were removed from the latest `main` at the user's request. Source code, automated tests and build summaries remain. Historical commits, tags and both MSI Releases are preserved. The removed test artifacts are no longer available from the latest `main`; build summaries do not replace that evidence.

完整三语人工流程、100%/150%/200% DPI、新界面 1,000 份数据、干净 Windows 安装/升级及剩余故障与授权声明审计仍待完成。历史规模测试重用合成图片，不代表真实扫描吞吐或 OCR 准确率。

Full manual journeys in all three languages, the DPI matrix, 1,000 submissions in the new UI, clean-Windows installation/upgrade and remaining failure/notice audits are pending. Historical scale tests reused synthetic images and do not establish real-scan throughput or OCR accuracy.

- [工作阶段 / Progress and evidence](工作阶段.md)
- [规划 / Product and architecture plan](规划.md)
- [0.2 release notes](docs/releases/0.2.0-dev.md) / [0.1 archive notes](docs/releases/0.1.0-dev.md)
- [Build summary](output/validation/build-summary.json)
- [Verified GitHub publication](output/validation/github-publication.json)
- [Third-party inventory](runtime/THIRD_PARTY_NOTICES.txt); component licenses/manifests are retained under `runtime/`. Source provenance is recorded in each applicable module.
