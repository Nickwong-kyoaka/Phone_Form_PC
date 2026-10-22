# PhoneForm AI PC

Windows 11 x64 独立离线问卷处理程序 / Standalone offline questionnaire processing for Windows 11 x64.

**建立项目 → 确认模板 → 逐份导入 → 本地辨识 → 人工覆核 → 导出 Excel**

**Create a project → confirm its template → import one questionnaire → recognize locally → review → export Excel**

## 下载与版本 / Downloads

| Version | Description | Release |
|---|---|---|
| **[0.3.0-dev](docs/releases/0.3.0-dev.md)** | 当前开发源码；辨识设置版本、整组校准及分类标记辨识 / Current development source; versioned recognition settings, group calibration and separate mark detectors | 本机 MSI；本轮不新增 Release / Local MSI; no new Release |
| 0.2.0-dev | 蓝白桌面布局归档 / Desktop redesign archive | [Windows x64 MSI](https://github.com/Nickwong-kyoaka/Phone_Form_PC/releases/tag/v0.2.0-dev) |
| 0.1.0-dev | 原始安装包归档 / Original installer archive | [Windows x64 MSI](https://github.com/Nickwong-kyoaka/Phone_Form_PC/releases/tag/v0.1.0-dev) |

从 Release 下载 `.msi`，双击安装；随包 Java、OCR 模型和本地引擎，使用者无需另装 Java，首次使用不下载资源。每个安装包附 `.sha256` 校验文件。

Download the `.msi` from a Release and run it. Java, OCR models and local engines are bundled, with no first-run resource download. Each installer has a matching SHA256 sidecar.

以上均为内部试用开发版。0.1 标签仅保存历史安装包说明和证据，原完整源码快照未保留；`main` 提供当前源码，`v0.2.0-dev` 保留该版本源码。0.3 不改动原两个 Release、标签或附件。

These are internal evaluation builds. The 0.1 tag contains archival metadata and evidence only because its complete historical source snapshot was not retained. Current source is on `main`; `v0.2.0-dev` preserves that version. The 0.3 development delivery does not alter either existing Release, tag or asset.

## 功能 / Features

- 繁中、简中、英文；界面语言、问卷语言和 Excel 标题语言独立设置，不自动翻译题目或答案。
- PDF、JPG、PNG 逐份导入，固定页槽、模板编辑、本地 OCR/OMR、人工覆核、四表 Excel 和历史快照。
- 蓝白侧栏、可搜索表格、图像/属性三栏工作区、自适应窄窗口、浅色/深色/系统主题。
- 本地加密数据库和文件、Windows CurrentUser DPAPI 密钥保护、加密编辑恢复、版本与人工答案保护。
- 整组选项／矩阵分格、拖动微调、印刷数字核心确认、独立设置版本与预览；不要求额外空白问卷参考。
- 方框勾叉与圈印刷数字分别分析；歧义转人工，候选保存来源版本，采用仍须明确操作。中文手写与自由文字继续人工输入。

- Traditional Chinese, Simplified Chinese and English, with independent interface, questionnaire and Excel-heading languages; questions and answers are not automatically translated.
- One-questionnaire-at-a-time PDF/JPG/PNG import, fixed page slots, template editing, local OCR/OMR, human review, four-sheet Excel export and historical snapshots.
- Blue-and-white navigation, searchable tables, image/inspector workspaces, narrow-window layouts and light/dark/system themes.
- Encrypted local storage, Windows CurrentUser DPAPI key protection, encrypted edit recovery, revision checks and human-answer protection.
- Group and matrix partitioning, draggable calibration, printed-core confirmation, immutable settings versions and preview, without an additional blank-reference document.
- Separate checkbox and circled-digit analysis; ambiguity requires human entry. Candidate adoption is explicit and records its actual source version. Chinese handwriting and free text remain manual.

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

0.3.0-dev 在 2026-10-22 完成 **137 项自动化回归，零失败、错误或跳过**，并成功构建自包含程序及本机 MSI。匿名题组独立保留集中，方框产生 115/120 个正确候选、圈数字 118/120 个，均为零错误候选；各有 32 个歧义题组，全部转人工。这些是生成像素的候选覆盖结果，不能推论真实扫描准确率，候选仍须人工核对。

On 2026-10-22, **all 137 automated regression tests passed**, with no failures, errors or skips, and the self-contained application and local MSI built successfully. Anonymous question-group holdouts produced 115/120 correct checkbox candidates and 118/120 correct circled-digit candidates, with zero wrong candidates; all 32 additional ambiguous groups per style required human handling. These generated-pixel coverage results do not establish real-scan accuracy; candidates still require human review.

实际引擎整合测试要求两种标记均产生正确、非空候选，并核对两笔来源引用、换版及重跑后的人工答案保护、三语快照和重启恢复。另对三语单笔匿名工作簿进行独立答案及四表 OOXML 核对，144 项全部通过。GUI 仅在繁中、浅色下抽查项目、模板、校准与触发预览，未验证预览结果显示。

Actual-engine integration requires correct, non-null candidates for both mark styles and verifies two source references, human-answer protection through recalibration/rerun, three-language snapshots and reopening. Independent answer/four-sheet OOXML auditing passed all 144 checks across three anonymous single-record workbooks. GUI checks were limited to opening a project, template and calibration, and triggering preview in Traditional Chinese/light theme; preview-result display remains unverified.

另通过 1,000 份后端人工覆核资料负载测试：每份 2 页、9 个答案目标，共用 2 张匿名影像；总耗时 510.658 秒，三语导出合计 3.948 秒，JVM heap pool 峰值约 264.9 MiB。三语工作簿合计 3,000 行、27,000 个答案值，独立 42,168 项检查全过；重开后核对 1,000 笔清单并抽核 3 份共 27 个答案。此测试不代表独立扫描、OCR 吞吐或 GUI 延迟；仓储尚无分页/数据库搜索 API，搜索计时只量完整读取后的程式端筛选。规模变体不累加到 137 项回归总数。

A separate 1,000-record backend human-review workload passed: 2 pages and 9 answer targets per record, sharing 2 anonymous images. The run took 510.658 seconds, three-language export took 3.948 seconds combined, and peak JVM heap pools were approximately 264.9 MiB. Independent workbook auditing passed 42,168 checks across 3,000 response rows and 27,000 answer values; reopening verified the 1,000-record list and sampled 27 answers from 3 records. This does not measure independent scans, OCR throughput or GUI latency. Paging/database-search APIs are not implemented; search timings cover client-side filtering after full reads. The scale variant is not added to the 137-test regression total.

依使用者目前偏好，后续以答案与 OOXML 核对为主，不在每次检查时自动打开 Microsoft Excel；本次没有新增原生 Excel 开启通过声明。完整三语人工流程、100%/150%/200% DPI、完整鼠标键盘操作、干净 Windows 安装/升级及剩余实机故障验收仍待完成。

Following the user's current preference, subsequent checks focus on answers and OOXML rather than automatically opening Microsoft Excel every time; no new native-Excel opening pass is claimed. Full manual journeys in three languages, the DPI matrix, complete mouse/keyboard coverage, clean-Windows installation/upgrade and remaining physical-machine failure checks are pending.

0.2.0-dev 的[历史构建汇总](output/validation/build-summary-0.2.0.json)记录：2026-10-21 有 92 项不同自动化测试通过，并完成指定三语、明暗主题、窄窗口、快捷保存和重启恢复抽查。当时三份合成工作簿经原生 Microsoft Excel 开启检查，无修复提示，独立 XLSX 核对通过。

Historical execution records for 0.2.0-dev show 92 distinct automated tests passed on 2026-10-21, along with selected language/theme/layout, save and restart checks. At that time, three synthetic workbooks opened in native Microsoft Excel without repair prompts and passed independent XLSX checks.

2026-10-22 按使用者要求，从最新版 `main` 清除测试问卷、派生 Excel、截图和相关逐项验证证据；保留源码、自动化测试及构建汇总。历史提交、标签和两个 MSI Release 保留。旧测试产物已不能从最新版 `main` 取得，构建汇总不能替代已清除的实测证据。

On 2026-10-22, test questionnaires, derived workbooks, screenshots and their detailed validation evidence were removed from the latest `main` at the user's request. Source code, automated tests and build summaries remain. Historical commits, tags and both MSI Releases are preserved. The removed test artifacts are no longer available from the latest `main`; build summaries do not replace that evidence.

历史规模测试重用合成图片，不代表真实扫描吞吐或 OCR 准确率。2026-10-22 新建的匿名案例与摘要独立记录，不依赖已清除的问卷与产物。

Historical scale tests reused synthetic images and do not establish real-scan throughput or OCR accuracy. Newly generated anonymous cases and summaries dated 2026-10-22 are recorded separately and do not depend on the removed questionnaires or artifacts.

- [工作阶段 / Progress and evidence](工作阶段.md)
- [规划 / Product and architecture plan](规划.md)
- [0.3 local delivery notes / 本机交付说明](docs/releases/0.3.0-dev.md) / [0.2 release notes](docs/releases/0.2.0-dev.md) / [0.1 archive notes](docs/releases/0.1.0-dev.md)
- [0.3 regression](output/validation/0.3.0-regression.json) / [Anonymous recognition](output/validation/0.3.0-recognition.json) / [Independent answer and OOXML checks](output/validation/0.3.0-answer-workbooks.json) / [Package checks](output/validation/0.3.0-package.json)
- [Limited desktop smoke](output/validation/0.3.0-desktop-smoke.json)
- [1,000-record workload](output/validation/0.3.0-scale.json) / [Scale answer and OOXML checks](output/validation/0.3.0-scale-workbooks.json)
- [Current build summary](output/validation/build-summary.json) / [0.2 historical build summary](output/validation/build-summary-0.2.0.json)
- [Verified GitHub publication](output/validation/github-publication.json)
- [Third-party inventory](runtime/THIRD_PARTY_NOTICES.txt); component licenses/manifests are retained under `runtime/`. Source provenance is recorded in each applicable module.
