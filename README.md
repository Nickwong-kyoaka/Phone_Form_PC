# PhoneForm AI PC

Windows 11 x64 独立离线问卷处理程序 / Standalone offline questionnaire processing for Windows 11 x64.

**建立项目 → 确认模板 → 逐份导入 → 本地辨识 → 人工覆核 → 导出 Excel**

**Create a project → confirm its template → import one questionnaire → recognize locally → review → export Excel**

## 下载与版本 / Downloads

| Version | Description | Release |
|---|---|---|
| **[0.3.2-dev](docs/releases/0.3.2-dev.md)** | 当前开发源码；日常编辑恢复、按需影像与完整任务中心 / Current source; everyday edit recovery, bounded image display and task-center paging | [本机 MSI / Local MSI](output/PhoneForm-AI-PC-0.3.2-dev.msi) · [SHA256](output/PhoneForm-AI-PC-0.3.2-dev.msi.sha256) |
| [0.3.1-dev](docs/releases/0.3.1-dev.md) | SQL 分页搜索、轻量摘要及编辑恢复保护归档 / SQL paging/search and recovery-protection archive | 前版本机 MSI 保留 / Earlier local MSI preserved |
| [0.3.0-dev](docs/releases/0.3.0-dev.md) | 辨识设置版本、整组校准及分类标记辨识 / Versioned recognition, group calibration and separate mark detectors | 前版本机 MSI 保留 / Earlier local MSI preserved |
| 0.2.0-dev | 蓝白桌面布局归档 / Desktop redesign archive | [Windows x64 MSI](https://github.com/Nickwong-kyoaka/Phone_Form_PC/releases/tag/v0.2.0-dev) |
| 0.1.0-dev | 原始安装包归档 / Original installer archive | [Windows x64 MSI](https://github.com/Nickwong-kyoaka/Phone_Form_PC/releases/tag/v0.1.0-dev) |

从 Release 下载 `.msi`，双击安装；随包 Java、OCR 模型和本地引擎，使用者无需另装 Java，首次使用不下载资源。每个安装包附 `.sha256` 校验文件。

Download the `.msi` from a Release and run it. Java, OCR models and local engines are bundled, with no first-run resource download. Each installer has a matching SHA256 sidecar.

0.3.2 的本机 MSI 连结指本次交付电脑的 `output` 文件；本轮不新增 GitHub Release，也不将 MSI 提交至 Git。原两个 Release 继续提供历史安装包。

The 0.3.2 local MSI link points to the delivery computer's `output` file. This update creates no GitHub Release and does not commit installers to Git. The original two Releases continue to host their historical installers.

以上均为内部试用开发版。0.1 标签仅保存历史安装包说明和证据，原完整源码快照未保留；`main` 提供当前源码，`v0.2.0-dev` 保留该版本源码。0.3 不改动原两个 Release、标签或附件。

These are internal evaluation builds. The 0.1 tag contains archival metadata and evidence only because its complete historical source snapshot was not retained. Current source is on `main`; `v0.2.0-dev` preserves that version. The 0.3 development delivery does not alter either existing Release, tag or asset.

## 功能 / Features

- 繁中、简中、英文；界面语言、问卷语言和 Excel 标题语言独立设置，不自动翻译题目或答案。
- PDF、JPG、PNG 逐份导入，固定页槽、模板编辑、本地 OCR/OMR、人工覆核、四表 Excel 和历史快照。
- 蓝白侧栏、可搜索表格、图像/属性三栏工作区、自适应窄窗口、浅色/深色/系统主题。
- 本地加密数据库和文件、Windows CurrentUser DPAPI 密钥保护、加密编辑恢复、版本与人工答案保护。
- 整组选项／矩阵分格、拖动微调、印刷数字核心确认、独立设置版本与预览；不要求额外空白问卷参考。
- 方框勾叉与圈印刷数字分别分析；歧义转人工，候选保存来源版本，采用仍须明确操作。中文手写与自由文字继续人工输入。
- 数据库分页搜索、固定修订重跑、按需读取匯出历史；恢复载入失败可重试，未套用草稿不能绕过确认。
- 日常表单统一恢复屏障、保存前读取最新输入；覆核保存后的重新载入阻止旧答案版本继续输入，损坏恢复索引可查看并明确处理。
- 显示影像共用有限解码名额，缩图按需载入及安全修复；任务中心可搜索、筛选和分页全部目前任务，并打开其所属问卷或草稿。

- Traditional Chinese, Simplified Chinese and English, with independent interface, questionnaire and Excel-heading languages; questions and answers are not automatically translated.
- One-questionnaire-at-a-time PDF/JPG/PNG import, fixed page slots, template editing, local OCR/OMR, human review, four-sheet Excel export and historical snapshots.
- Blue-and-white navigation, searchable tables, image/inspector workspaces, narrow-window layouts and light/dark/system themes.
- Encrypted local storage, Windows CurrentUser DPAPI key protection, encrypted edit recovery, revision checks and human-answer protection.
- Group and matrix partitioning, draggable calibration, printed-core confirmation, immutable settings versions and preview, without an additional blank-reference document.
- Separate checkbox and circled-digit analysis; ambiguity requires human entry. Candidate adoption is explicit and records its actual source version. Chinese handwriting and free text remain manual.
- SQL paging/search, revision-pinned reruns and on-demand export snapshots; failed recovery can be retried, and confirmation cannot bypass pending editor drafts.
- Everyday forms wait for recovery and commit current values. Review reloads block edits bound to an old answer version, and corrupt recovery indexes can be inspected and explicitly handled.
- Image display uses a shared decoder budget with demand-driven thumbnails and guarded repair. The task center searches, filters and pages all current tasks, and opens their owning questionnaire or draft.

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

0.3.2-dev 于 2026-10-22 完成 **216 项不同自动化回归，零失败、错误或跳过**（domain 37、excel 8、data 47、vision 33、desktop 91）。覆盖日常表单恢复、换版时的旧回呼、损坏恢复索引、影像取消／关闭、缩图认证及完整任务分页，并保留答案、来源与四表契约检查。自包含程序与 MSI 构建成功；7 个资源校验值一致、6 个应用 JAR 已检查，前四版本机 MSI 校验值未变。[回归证据](output/validation/0.3.2-regression.json)与[打包核对](output/validation/0.3.2-package.json)记录实际范围。

On 2026-10-22, **all 216 distinct 0.3.2-dev regression tests passed**, with zero failures, errors or skips (domain 37, excel 8, data 47, vision 33, desktop 91). Coverage includes everyday recovery, stale review callbacks, corrupt recovery indexes, image cancellation/shutdown, authenticated thumbnails and task paging, alongside answer/provenance/four-sheet contracts. The distribution and MSI built successfully; seven resource hashes matched, six application jars were checked, and all four earlier MSI hashes are unchanged. See the [regression evidence](output/validation/0.3.2-regression.json) and [package checks](output/validation/0.3.2-package.json).

实际 GUI 仅确认匿名工作区启动及 260 份覆核清单可见；任务中心和答案编辑的 GUI 检查中断，尚未验证。本版没有调整辨识器／参数，也没有新增云端服务或 schema；未开启原生 Excel。

GUI checks confirmed only anonymous-workspace launch and visibility of a 260-record review list. Task-center and answer-editor GUI checks were interrupted and remain unverified. The detector, parameters and schema are unchanged, no cloud service was added, and native Excel was not opened.

两份原始测试 PDF 的独立答案实测**未通过辨识验收**：共 202 个目标，198 个真值可评分，4 个不确定；已配置自动辨识的 151 个可评分目标均未产生整题候选，覆盖率 **0/151 = 0%**。另有 47 个可评分人工模式目标。所有题型候选数均为零，因此候选准确率无分母、无法计算，不能称为 100%。两次任务均到达 `READY` 并保存完整报告与摘要，随后原生测试进程均以 `-1073740791` 退出，完整生命周期测试失败，故障模块及原因仍未知。[分题型测量](output/validation/0.3.2-input-accuracy.json)与[诊断摘要](output/validation/0.3.2-recognition-diagnostics.json)独立于上述 216 项匿名基线回归。

The separate original-PDF measurement **failed recognition acceptance**: 202 targets, 198 independently scorable and four uncertain. None of the 151 scorable targets configured for automatic recognition produced a complete question candidate: **0/151 = 0% coverage**. Another 47 scorable targets were intentionally manual. Every type produced zero candidates, so candidate precision has no denominator and is undefined, not 100%. Both jobs reached `READY` and saved complete reports/digests before their native test processes exited with `-1073740791`; the full lifecycle tests failed, and the fault module/cause remains unknown. See the [per-type measurement](output/validation/0.3.2-input-accuracy.json) and [diagnostics](output/validation/0.3.2-recognition-diagnostics.json), reported separately from the 216-test anonymous baseline.

实测采用一次固定人工区域／核心设置，144 DPI、原始 PDF 方向，同一份已填写文件分别导入为模板与问卷；没有人工答案回填。这是有人工校准条件的单轮测量，不证明新扫描的对齐或泛化能力。主要缺口包括小方框低于固定 14 像素门槛、数字核心边界／表格线／裁切干扰、数字日期 OCR，以及未随包的中文竖排子模型警告；警告不能证明原生退出原因。原始文件和含答案的派生资料不进入 Git。

Each document used one frozen set of manually selected regions/cores, 144 DPI and raw PDF orientation, with the same filled document imported as template and submission. No human answers were credited as machine output. This conditional single-pass measurement does not establish alignment or generalization to new scans. Gaps include the fixed 14-pixel checkbox minimum, printed-core boundaries/table lines/cropping, number/date OCR and missing bundled Chinese vertical submodels. That warning does not establish the native-exit cause. Original documents and answer-bearing derivatives are excluded from Git.

0.3.1-dev 的历史记录：2026-10-22 完成 **166 项不同自动化回归，零失败、错误或跳过**，并成功构建自包含程序及 MSI。新增分页／搜索、真实任务计数、跨页选择、过期响应、固定修订重跑、快照摘要及 12 项编辑恢复安全测试；既有答案、候选来源与四表契约回归通过。该轮没有重复开启 Microsoft Excel。

Historical 0.3.1-dev results: on 2026-10-22, **all 166 distinct regression tests passed**, with no failures, errors or skips. The self-contained application and MSI built successfully. Paging/search, global task counts, cross-page selection, stale responses, pinned reruns, snapshot summaries and 12 recovery-safety tests are covered, alongside existing answer/provenance/four-sheet contracts. Native Microsoft Excel was not opened.

新的 1,000 份加密记录读负载逐笔对账及重开检查通过：每份 10 个页槽、100 个答案目标，142 份缺末页，无实体影像。SQL 首页 50 笔用时 1,053.1ms，同负载旧式完整工作台摘要 5,696.0ms；五种搜索 919.2–1,050.4ms。先完整读取后查 SQL，暖缓存、每操作一次；不代表冷启动、GUI、影像、OCR 或采集速度。详见 [0.3.1 交付说明](docs/releases/0.3.1-dev.md) 与[量测摘要](output/validation/0.3.1-query-1000.json)。

A separate 1,000-record encrypted metadata workload passed complete list comparison and reopening checks: 10 page slots and 100 targets per record, 142 missing last pages, and no physical images. The 50-row SQL page took 1,053.1ms versus 5,696.0ms for legacy full-workspace summaries; five searches took 919.2–1,050.4ms. Legacy reads ran first, then SQL, using warm caches and single samples. This does not measure cold starts, GUI latency, images, OCR or collection throughput. See the [0.3.1 notes](docs/releases/0.3.1-dev.md) and [read-workload evidence](output/validation/0.3.1-query-1000.json).

0.3.0-dev 的历史记录：2026-10-22 完成 **137 项自动化回归，零失败、错误或跳过**，并成功构建自包含程序及本机 MSI。匿名题组独立保留集中，方框产生 115/120 个正确候选、圈数字 118/120 个，均为零错误候选；各有 32 个歧义题组，全部转人工。这些是生成像素的候选覆盖结果，不能推论真实扫描准确率，候选仍须人工核对。

On 2026-10-22, **all 137 automated regression tests passed**, with no failures, errors or skips, and the self-contained application and local MSI built successfully. Anonymous question-group holdouts produced 115/120 correct checkbox candidates and 118/120 correct circled-digit candidates, with zero wrong candidates; all 32 additional ambiguous groups per style required human handling. These generated-pixel coverage results do not establish real-scan accuracy; candidates still require human review.

实际引擎整合测试要求两种标记均产生正确、非空候选，并核对两笔来源引用、换版及重跑后的人工答案保护、三语快照和重启恢复。另对三语单笔匿名工作簿进行独立答案及四表 OOXML 核对，144 项全部通过。GUI 仅在繁中、浅色下抽查项目、模板、校准与触发预览，未验证预览结果显示。

Actual-engine integration requires correct, non-null candidates for both mark styles and verifies two source references, human-answer protection through recalibration/rerun, three-language snapshots and reopening. Independent answer/four-sheet OOXML auditing passed all 144 checks across three anonymous single-record workbooks. GUI checks were limited to opening a project, template and calibration, and triggering preview in Traditional Chinese/light theme; preview-result display remains unverified.

0.3.0 另通过 1,000 份后端人工覆核资料负载测试：每份 2 页、9 个答案目标，共用 2 张匿名影像；总耗时 510.658 秒，三语导出合计 3.948 秒，JVM heap pool 峰值约 264.9 MiB。三语工作簿合计 3,000 行、27,000 个答案值，独立 42,168 项检查全过；重开后核对 1,000 笔清单并抽核 3 份共 27 个答案。此测试不代表独立扫描、OCR 吞吐或 GUI 延迟；0.3.0 当时尚无分页/数据库搜索 API，搜索计时只量完整读取后的程式端筛选。规模变体不累加到 137 项历史回归总数。

In 0.3.0, a separate 1,000-record backend human-review workload passed: 2 pages and 9 answer targets per record, sharing 2 anonymous images. The run took 510.658 seconds, three-language export took 3.948 seconds combined, and peak JVM heap pools were approximately 264.9 MiB. Independent workbook auditing passed 42,168 checks across 3,000 response rows and 27,000 answer values; reopening verified the list and sampled 27 answers. This did not measure independent scans, OCR throughput or GUI latency. Version 0.3.0 had no paging/database-search APIs; its search timings measured client-side filtering after full reads. That scale variant is excluded from the historical 137-test total.

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
- [0.3.2 current delivery / 最新交付](docs/releases/0.3.2-dev.md) / [0.3.1 archive / 前版交付](docs/releases/0.3.1-dev.md) / [0.3.0 earlier delivery](docs/releases/0.3.0-dev.md) / [0.2 release notes](docs/releases/0.2.0-dev.md) / [0.1 archive notes](docs/releases/0.1.0-dev.md)
- [0.3.2 regression](output/validation/0.3.2-regression.json) / [0.3.2 package checks](output/validation/0.3.2-package.json) / [Build summary](output/validation/build-summary.json)
- [0.3.2 source delivery](output/validation/0.3.2-github.json): `f409289`, author/committer Nickwong-kyoaka; original Releases/assets/tags preserved, private questionnaire derivatives excluded.
- [0.3.2 original-input answer measurement](output/validation/0.3.2-input-accuracy.json) / [Recognition and native-process diagnostics](output/validation/0.3.2-recognition-diagnostics.json)
- [0.3.1 regression](output/validation/0.3.1-regression.json) / [SQL read workload](output/validation/0.3.1-query-1000.json) / [Package checks](output/validation/0.3.1-package.json)
- [0.3.1 GitHub source/identity verification and preserved Releases](output/validation/0.3.1-github.json)
- [0.3 regression](output/validation/0.3.0-regression.json) / [Anonymous recognition](output/validation/0.3.0-recognition.json) / [Independent answer and OOXML checks](output/validation/0.3.0-answer-workbooks.json) / [Package checks](output/validation/0.3.0-package.json)
- [Limited desktop smoke](output/validation/0.3.0-desktop-smoke.json)
- [1,000-record workload](output/validation/0.3.0-scale.json) / [Scale answer and OOXML checks](output/validation/0.3.0-scale-workbooks.json)
- [Latest completed build summary](output/validation/build-summary.json) / [0.3.1 historical summary](output/validation/build-summary-0.3.1.json) / [0.3.0 historical summary](output/validation/build-summary-0.3.0.json) / [0.2 historical build summary](output/validation/build-summary-0.2.0.json)
- [Verified GitHub publication](output/validation/github-publication.json)
- [Third-party inventory](runtime/THIRD_PARTY_NOTICES.txt); component licenses/manifests are retained under `runtime/`. Source provenance is recorded in each applicable module.
