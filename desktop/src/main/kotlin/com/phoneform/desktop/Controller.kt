package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import com.phoneform.ai.core.data.*
import com.phoneform.desktop.data.DesktopRepositories
import com.phoneform.desktop.data.DesktopUiPreferencesStore
import com.phoneform.desktop.security.*
import com.phoneform.desktop.vision.DesktopVision
import com.phoneform.desktop.vision.RecognitionResult
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.image.BufferedImage
import java.io.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO

fun newId(): String = UUID.randomUUID().toString()
data class AppSnapshot(
    val settings: AppSettings = AppSettings(), val projects: List<ProjectListSummary> = emptyList(),
    val project: ProjectDetails? = null, val templateDraft: TemplateDraft? = null,
    val template: FormSchemaVersion? = null, val drafts: List<SubmissionDraft> = emptyList(),
    /** Only submissions referenced by open collection drafts, never the project's entire history. */
    val submissions: List<Submission> = emptyList(), val collectionLocked: Boolean = false,
    val exports: List<ExportListSummary> = emptyList(), val policies: List<OutputFieldPolicy> = emptyList(),
    val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val worker: String = "", val paused: Boolean = false,
    val editorSaveState: EditorSaveState = EditorSaveState.SAVED,
    val dataRevision: Long = 0, val taskTotal: Int = 0, val taskStatusCounts: Map<String,Int> = emptyMap(),
)

class Controller(val workspace: SecureWorkspace, val modelDir: Path, startWorkers: Boolean = true) : AutoCloseable {
    val repositories = DesktopRepositories(workspace)
    val vision = DesktopVision(modelDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionId = newId()
    @Volatile private var selectedProjectId: String? = null
    private val mutable = MutableStateFlow(AppSnapshot())
    val state: StateFlow<AppSnapshot> = mutable.asStateFlow()
    private val mutableUi = MutableStateFlow(DesktopUiState())
    val ui: StateFlow<DesktopUiState> = mutableUi.asStateFlow()
    private val mutableTasks = MutableStateFlow<List<UiTaskSummary>>(emptyList())
    val tasks: StateFlow<List<UiTaskSummary>> = mutableTasks.asStateFlow()
    private val reviewLoader = ReviewPageLoader(scope, { id, query -> repositories.intelligence.queryReviewPage(id, query) }, ::errorCode)
    val reviewPage: StateFlow<ReviewListState> = reviewLoader.state
    private val taskRequests = Channel<List<ProjectListSummary>>(Channel.CONFLATED)
    private val uiPreferences = DesktopUiPreferencesStore(workspace.database)
    private val uiSaveLock = Mutex()
    private var uiSaveJob: Job? = null
    private var editorSaveJob: Job? = null
    private var activeWorker: Job? = null
    private val initialized = CompletableDeferred<Unit>()
    private val workerLock = Mutex()
    private val renderLock = Any()
    private val pageOwners = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val displayImages = DisplayImageLoader(::validateDisplayImageRequest)
    private val actions = Mutex()
    private val actionAdmission = Any()
    @Volatile private var closing = false
    @Volatile private var templateCommit: Pair<String, suspend () -> Unit>? = null
    fun registerTemplateCommit(owner: String, commit: suspend () -> Unit) { templateCommit = owner to commit }
    fun unregisterTemplateCommit(owner: String) { if (templateCommit?.first == owner) templateCommit = null }
    private data class PendingEditor(val projectId: String?, val fields: java.util.concurrent.ConcurrentHashMap<String,String> = java.util.concurrent.ConcurrentHashMap(),
        val version: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(),
        val savedVersion: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong())
    private val editorFields = java.util.concurrent.ConcurrentHashMap<String,PendingEditor>()
    private val editorLock = Mutex()
    suspend fun loadEditorField(session: String, field: String): String? {
        editorFields[session]?.fields?.get(field)?.let { return it }
        val payload = repositories.loadEditorSession(session)?.payload ?: return null
        return java.util.Properties().apply { load(StringReader(payload)) }.getProperty(field)
    }
    fun queueEditorField(session: String, projectId: String?, field: String, value: String) {
        if (closing) return
        val draft = editorFields.computeIfAbsent(session) { PendingEditor(projectId) }
        draft.fields[field] = value; draft.version.incrementAndGet()
        mutable.update { it.copy(editorSaveState = EditorSaveState.SAVING) }
        editorSaveJob?.cancel()
        editorSaveJob = scope.launch { delay(400); try { flushEdits() } catch(e: CancellationException) { throw e } catch(_: Exception) { /* flushEdits reports the failure and retains the buffer. */ } }
    }
    suspend fun clearEditor(session: String) = editorLock.withLock {
        repositories.discardEditorSession(session); editorFields.remove(session)
        if (editorFields.values.none { it.version.get() != it.savedVersion.get() }) mutable.update { it.copy(editorSaveState = EditorSaveState.SAVED) }
    }
    suspend fun flushEdits() {
        try {
            editorLock.withLock {
                for ((id, draft) in editorFields) {
                    val version = draft.version.get()
                    if (version == draft.savedVersion.get()) continue
                    val previous = repositories.loadEditorSession(id)
                    val values = java.util.Properties().apply { previous?.payload?.let { load(StringReader(it)) }; putAll(draft.fields.toMap()) }
                    val out = StringWriter(); values.store(out, null)
                    repositories.saveEditorSession(id, draft.projectId, out.toString(), previous?.revision)
                    draft.savedVersion.set(version)
                }
                mutable.update { it.copy(editorSaveState = if (editorFields.values.any { draft -> draft.version.get() != draft.savedVersion.get() }) EditorSaveState.SAVING else EditorSaveState.SAVED) }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            mutable.update { it.copy(editorSaveState = EditorSaveState.FAILED, error = errorCode(failure)) }
            throw failure
        }
    }

    fun updateUi(change: (DesktopUiState) -> DesktopUiState) {
        if (closing) return
        mutableUi.update { old -> change(old).let { it.copy(projectId = selectedProjectId, projects = it.projects.mapValues { entry -> entry.value.normalized() }) } }
        scheduleUiSave()
        requestReviewPage()
    }
    fun updateProjectUi(projectId: String, change: (ProjectUiState) -> ProjectUiState) {
        if (closing || state.value.projects.none { it.project.id == projectId }) return
        updateUi {
            val before = it.projects[projectId] ?: ProjectUiState()
            var after = change(before).normalized()
            if (before.reviewSearch != after.reviewSearch || before.reviewFilter != after.reviewFilter || before.reviewPageSize != after.reviewPageSize)
                after = after.copy(reviewPageIndex = 0)
            it.copy(projects = it.projects + (projectId to after))
        }
    }
    fun navigate(section: WorkspaceSection): Job = scope.launch {
        try {
            initialized.await()
            actions.withLock {
                if (closing) return@withLock
                flushEdits(); mutableUi.update { it.copy(section = section) }; scheduleUiSave(); requestReviewPage()
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { mutable.update { it.copy(error = errorCode(failure)) } }
    }
    private fun scheduleUiSave() {
        if (closing) return
        uiSaveJob?.cancel()
        uiSaveJob = scope.launch {
            delay(500)
            try { flushUiPreferences() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { mutable.update { it.copy(error = errorCode(failure)) } }
        }
    }
    private suspend fun flushUiPreferences() = uiSaveLock.withLock { uiPreferences.save(DesktopUiCodec.encode(mutableUi.value)) }

    private suspend fun refreshTasks(projects: List<ProjectListSummary>) {
        val names = projects.associate { it.project.id to it.project.name }
        val page = repositories.taskSummaries(limit = 200)
        mutableTasks.value = page.items.map { task -> UiTaskSummary(task.id, task.projectId, names[task.projectId] ?: task.projectId,
            task.phase.name, task.status, task.submissionNumber, task.pageNumber, task.error, task.retrySupported) }
        mutable.update { it.copy(taskTotal = page.total, taskStatusCounts = page.statusCounts) }
    }
    private fun requestReviewPage(force: Boolean = false) {
        val id = selectedProjectId.takeIf { mutableUi.value.section == WorkspaceSection.REVIEW }
        val query = (id?.let { mutableUi.value.projects[it] } ?: ProjectUiState()).reviewQuery()
        val previous = reviewPage.value
        val searchChanged = previous.projectId == id && previous.query.search != query.search
        reviewLoader.request(id, query, state.value.dataRevision, if (searchChanged) 300 else 0, force)
    }
    fun retryReviewPage() = requestReviewPage(force = true)
    fun changeReviewPage(pageIndex: Int) {
        val id = selectedProjectId ?: return
        updateProjectUi(id) { it.copy(reviewPageIndex = pageIndex.coerceAtLeast(0),
            reviewSubmissionId = if (it.reviewWorkspace) it.reviewSubmissionId else null) }
    }
    fun retryTask(id: String) {
        val phase = tasks.value.firstOrNull { it.id == id }?.phase?.let(BackgroundTaskPhase::valueOf) ?: return
        retryTask(id, phase)
    }
    fun retryTask(id: String, phase: BackgroundTaskPhase) = perform {
        val task = repositories.getTaskSummary(id, phase) ?: error("TASK_RETRY_UNAVAILABLE")
        require(task.retrySupported) { "TASK_RETRY_UNAVAILABLE" }
        when (phase) {
            BackgroundTaskPhase.IMAGE -> repositories.imageProcessing.retry(id)
            BackgroundTaskPhase.PREPARATION -> repositories.submissions.retryJob(id)
            BackgroundTaskPhase.RECOGNITION -> repositories.intelligence.retryRecognition(id)
        }
    }
    /** The task page is a read snapshot. Revalidate its owner before changing navigation. */
    suspend fun openTaskQuestionnaire(id: String, phase: BackgroundTaskPhase) {
        val task = repositories.getTaskSummary(id, phase) ?: error("TASK_RETRY_UNAVAILABLE")
        val draft = task.collectionDraftId?.let { repositories.submissions.getDraft(it) }
        val slot = if (task.collectionDraftId != null) {
            requireNotNull(draft) { "TASK_OWNER_UNAVAILABLE" }
            require(draft.projectId == task.projectId) { "TASK_OWNER_UNAVAILABLE" }
            requireNotNull(draft.pages.firstOrNull { it.image.id == task.pageId }) { "TASK_OWNER_UNAVAILABLE" }.templatePageId
        } else {
            val submissionId = requireNotNull(task.submissionId) { "TASK_OWNER_UNAVAILABLE" }
            requireNotNull(repositories.intelligence.getReviewSummary(submissionId)) { "TASK_OWNER_UNAVAILABLE" }
            null
        }
        flushEdits()
        selectedProjectId = task.projectId
        mutableUi.update { old ->
            val before=old.projects[task.projectId] ?: ProjectUiState()
            val location=if(draft!=null)before.copy(collectionDraftId=draft.id,collectionPageId=slot)
                else before.copy(reviewSubmissionId=task.submissionId,reviewTargetKey=null,reviewWorkspace=true)
            old.copy(projectId=task.projectId,section=if(draft!=null)WorkspaceSection.COLLECTION else WorkspaceSection.REVIEW,
                projects=old.projects+(task.projectId to location))
        }
        scheduleUiSave()
    }
    init {
        // ImageIO's default disk cache can contain decrypted questionnaire pixels.
        ImageIO.setUseCache(false)
        scope.launch {
            try {
                repositories.imageProcessing.initialize(sessionId)
                repositories.jobs.initializeProcess(sessionId)
                repositories.intelligence.initializeRecognition(sessionId)
                val restored = DesktopUiCodec.decode(uiPreferences.load())
                val ids = repositories.projects.observeProjects().first().map { it.id }.toSet()
                val valid = restored.copy(projectId = restored.projectId?.takeIf { it in ids }, projects = restored.projects.filterKeys { it in ids })
                mutableUi.value = valid; selectedProjectId = valid.projectId
                initialized.complete(Unit)
                try { refresh() }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { mutable.update { it.copy(error = errorCode(failure)) } }
                workspace.database.changes.debounce(125).collect {
                    try { refresh() }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { mutable.update { it.copy(error = errorCode(failure)) } }
                }
            } catch (failure: Throwable) {
                initialized.completeExceptionally(failure)
                if (failure is CancellationException) throw failure
                mutable.update { it.copy(error = errorCode(failure)) }
            }
        }
        if (startWorkers) activeWorker = scope.launch { initialized.await(); workerLoop() }
        scope.launch {
            reviewPage.collect { result ->
                val page = result.page ?: return@collect
                val id = result.projectId ?: return@collect
                if (!result.loading && !result.stale && result.error == null && selectedProjectId == id &&
                    mutableUi.value.projects[id]?.reviewQuery() == result.query && page.query.pageIndex != result.query.pageIndex) {
                    mutableUi.update { current -> current.copy(projects = current.projects + (id to
                        (current.projects[id] ?: ProjectUiState()).copy(reviewPageIndex = page.query.pageIndex))) }
                    scheduleUiSave()
                    requestReviewPage()
                }
            }
        }
        scope.launch {
            try { initialized.await() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { mutable.update { it.copy(error = errorCode(failure)) }; return@launch }
            for (requested in taskRequests) {
                // Editor/layout writes can arrive in bursts. Read the bounded task projection once
                // per burst, independently of navigation, and retain only the newest project list.
                delay(750)
                var latest = requested
                while (true) latest = taskRequests.tryReceive().getOrNull() ?: break
                try { refreshTasks(latest) }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { mutable.update { it.copy(error = errorCode(failure)) } }
            }
        }
    }
    fun perform(action: suspend Controller.() -> Unit) = synchronized(actionAdmission) {
        if (mutable.value.busy || closing) return@synchronized
        // Claim synchronously: a second click before the IO coroutine starts is still a duplicate.
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        val job = scope.launch {
            try { actions.withLock { if (!closing) { action(); refresh() } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = errorCode(e)) } }
        }
        // Also runs if shutdown cancels a coroutine before its body ever starts.
        job.invokeOnCompletion { synchronized(actionAdmission) { mutable.update { it.copy(busy = false) } } }
        Unit
    }
    fun clearMessage() = mutable.update { it.copy(error = null, notice = null) }
    fun select(projectId: String?): Job = scope.launch {
        try {
            initialized.await()
            actions.withLock {
                if (closing) return@withLock
                if (projectId != null) requireNotNull(repositories.projects.getProject(projectId)) { "PROJECT_UNAVAILABLE" }
                flushEdits()
                selectedProjectId = projectId
                mutableUi.update { it.copy(projectId = projectId) }
                scheduleUiSave(); refresh()
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { mutable.update { it.copy(error = errorCode(failure)) } }
    }
    suspend fun refresh() {
        val id = selectedProjectId
        val uiAtStart = mutableUi.value
        val project = id?.let { repositories.projects.getProject(it) }
        val settings = repositories.settings.settings.first()
        val summaries = repositories.projects.observeProjectSummaries().first()
        val td = id?.let { repositories.templates.observeDraft(it).first() }
        val template = id?.let { repositories.templates.observeConfirmed(it).first() }
        val drafts = id?.let { repositories.submissions.observeDrafts(it).first() }.orEmpty()
        val submissions = drafts.map { it.submissionId }.distinct().mapNotNull { repositories.submissions.getSubmission(it) }
        val collectionLocked = id?.let { repositories.submissions.observeCollectionState(it).first().locked } ?: false
        val activeReviewId = id?.let { uiAtStart.projects[it]?.reviewSubmissionId }
        val activeReviewExists = activeReviewId?.let { repositories.intelligence.getReviewSummary(it) != null } ?: false
        val exports = id?.let { repositories.intelligence.observeExportSummaries(it).first() }.orEmpty()
        val policies = id?.let { repositories.intelligence.observePolicies(it).first() }.orEmpty()
        if (id != null) {
            (td?.pages.orEmpty() + template?.document?.pages.orEmpty() + drafts.flatMap { it.pages.map(PageRevision::image) })
                .forEach { pageOwners[it.id] = id }
        }
        if (id == selectedProjectId) mutable.update { it.copy(settings = settings, projects = summaries,
            project = project, templateDraft = td, template = template, drafts = drafts,
            submissions = submissions, collectionLocked = collectionLocked, exports = exports, policies = policies,
            dataRevision = workspace.database.changes.value) }
        if (id == selectedProjectId) {
            val ids = summaries.map { it.project.id }.toSet()
            if (id != null && project == null) selectedProjectId = null
            val previous = mutableUi.value
            mutableUi.update { current ->
                // A background refresh started before the latest click must not erase its selection.
                if (current != uiAtStart) return@update current
                var next = current.copy(projectId = selectedProjectId, projects = current.projects.filterKeys { it in ids })
                if (id != null && project != null && next.projects.containsKey(id)) {
                    val pages = td?.pages ?: template?.document?.pages.orEmpty()
                    val questions = td?.questions ?: template?.document?.questions.orEmpty()
                    val slots = template?.document?.pages.orEmpty().map { it.id }.toSet()
                    val targets = template?.let { ReviewTargets.build(project, it).map { target -> target.key }.toSet() }.orEmpty()
                    val old = next.projects.getValue(id)
                    val valid = old.copy(templatePageId = old.templatePageId?.takeIf { key -> pages.any { it.id == key } },
                        templateQuestionId = old.templateQuestionId?.takeIf { key -> questions.any { it.id == key } },
                        collectionDraftId = old.collectionDraftId?.takeIf { key -> drafts.any { it.id == key } },
                        collectionPageId = old.collectionPageId?.takeIf { it in slots },
                        reviewSubmissionId = old.reviewSubmissionId?.takeIf { key -> key != activeReviewId || activeReviewExists },
                        reviewTargetKey = old.reviewTargetKey?.takeIf { it in targets })
                    next = next.copy(projects = next.projects + (id to valid))
                }
                next
            }
            if (mutableUi.value != previous) scheduleUiSave()
        }
        taskRequests.trySend(summaries)
        requestReviewPage()
    }
    suspend fun createProject(name: String, expected: Int?, language: QuestionnaireLanguage,
                              numbering: NumberingStrategy, output: OutputHeaderLanguage,
                              fields: List<Pair<String, FieldSource>> = emptyList(), extractionScope: ExtractionScope = ExtractionScope.ALL) {
        initialized.await()
        require(name.isNotBlank()) { "PROJECT_NAME_REQUIRED" }
        require(expected == null || expected > 0) { "EXPECTED_COUNT_INVALID" }
        val id = newId(); val time = System.currentTimeMillis()
        val detail = ProjectDetails(ProjectConfig(id, name.trim(), expected, language, numbering, time, time),
            OutputProfile(projectId = id, language = output, extractionScope = extractionScope), fields.mapIndexed { index, item ->
                RequestedOutputField(newId(), id, item.first, index, item.second,
                    mappingState = if (item.second == FieldSource.MANUAL_METADATA) FieldMappingState.MANUAL_ENTRY else FieldMappingState.UNMAPPED)
            })
        repositories.projects.saveProject(detail); selectedProjectId = id
        mutableUi.update { it.copy(projectId = id, section = WorkspaceSection.TEMPLATE) }; scheduleUiSave()
        clearEditor("create-project")
    }
    suspend fun setLanguage(language: UiLanguage) = repositories.settings.setLanguage(language)
    suspend fun finishOnboarding() = repositories.settings.completeOnboarding()
    suspend fun setCloseBehavior(value: CloseBehavior) = repositories.settings.setCloseBehavior(value)
    suspend fun templateDraft(): TemplateDraft {
        val id = requireNotNull(selectedProjectId)
        return repositories.templates.observeDraft(id).first() ?: repositories.templates.beginDraft(id)
    }
    suspend fun saveTemplate(draft: TemplateDraft) = repositories.templates.saveDraft(draft)
    suspend fun confirmTemplate() {
        templateCommit?.second?.invoke()
        val draft = templateDraft()
        requireTemplateRecoveryClean(draft)
        repositories.templates.confirmDraft(draft.projectId, draft.id, draft.revision)
    }
    suspend fun loadRecognitionDraft(): RecognitionProfileDraft {
        val version = requireNotNull(state.value.project?.project?.templateVersionId) { "TEMPLATE_REQUIRED" }
        return repositories.intelligence.beginRecognitionProfileDraft(version)
    }
    suspend fun saveRecognitionDraft(draft: RecognitionProfileDraft): RecognitionProfileDraft {
        require(draft.projectId == selectedProjectId) { "PROJECT_CHANGED" }
        return repositories.intelligence.saveRecognitionProfileDraft(draft)
    }
    suspend fun confirmRecognitionDraft(draft: RecognitionProfileDraft): TemplateRecognitionProfile {
        val saved = saveRecognitionDraft(draft)
        return confirmSavedRecognitionDraft(saved)
    }
    suspend fun confirmSavedRecognitionDraft(saved: RecognitionProfileDraft): TemplateRecognitionProfile {
        require(saved.projectId == selectedProjectId) { "PROJECT_CHANGED" }
        return repositories.intelligence.confirmRecognitionProfileDraft(saved.id, saved.revision, saved.baseProfileId,
            "profile:${saved.id}:${saved.revision}")
    }
    suspend fun discardRecognitionDraft(draft: RecognitionProfileDraft) {
        require(draft.projectId == selectedProjectId) { "PROJECT_CHANGED" }
        repositories.intelligence.discardRecognitionProfileDraft(draft.id, draft.revision)
    }
    suspend fun previewRecognition(draft: RecognitionProfileDraft, pageId: String, questionIds: Set<String>? = null): RecognitionResult =
        ProjectOperationGate.shared.withLease(draft.projectId) {
            val template = requireNotNull(repositories.templates.getVersion(draft.templateVersionId)) { "TEMPLATE_REQUIRED" }
            require(draft.projectId == selectedProjectId && template.projectId == draft.projectId) { "PROJECT_CHANGED" }
            require(draft.pageFingerprints == RecognitionBindings.pageFingerprints(template.document)) { "RECOGNITION_PAGE_CHANGED" }
            val page = template.document.pages.single { it.id == pageId }
            val questions = template.document.questions.filter { it.pageId == pageId && (questionIds == null || it.id in questionIds) }
            require(questions.isNotEmpty() && (questionIds == null || questions.map { it.id }.toSet() == questionIds)) { "RECOGNITION_SETTINGS_INVALID" }
            val selectedIds = questions.map { it.id }.toSet()
            val settings = draft.settings.filter { it.questionId in selectedIds }
            require(settings.map { it.questionId }.toSet() == selectedIds &&
                RecognitionBindings.validate(template.document, settings).isEmpty()) { "RECOGNITION_SETTINGS_INVALID" }
            val profile = TemplateRecognitionProfile(template.id, draft.engineVersion, draft.pageFingerprints,
                id = "preview:${draft.id}", projectId = draft.projectId, settings = settings,
                parametersVersion = draft.parametersVersion)
            val image = loadImage(page)
            try { vision.recognize(page, image, page, image, questions,
                requireNotNull(repositories.projects.getProject(draft.projectId)).project.questionnaireLanguage, profile, null,
                hash(workspace.assets.readBytes(page.renderPath))) }
            finally { image.flush() }
        }
    suspend fun suggestPrintedCores(pageId: String, options: List<OptionMarkRegion>): List<OptionMarkRegion> {
        val projectId = requireNotNull(selectedProjectId)
        return ProjectOperationGate.shared.withLease(projectId) {
            val document = state.value.templateDraft ?: requireNotNull(state.value.template).document
            val page = document.pages.single { it.id == pageId }
            val image = loadImage(page)
            try { vision.suggestPrintedCores(image, options, requireNotNull(state.value.project).project.questionnaireLanguage) }
            finally { image.flush() }
        }
    }
    suspend fun rerunRecognition(submissionId: String): RecognitionJob {
        val review = requireNotNull(repositories.intelligence.getReview(submissionId)) { "REVISION_UNAVAILABLE" }
        require(review.project.project.id == selectedProjectId && review.confirmation == null) { "REVIEW_ALREADY_CONFIRMED" }
        val profile = requireNotNull(repositories.intelligence.getRecognitionProfile(review.template.id)) { "RECOGNITION_SETTINGS_REQUIRED" }
        return repositories.intelligence.rerunRecognition(submissionId, review.revision.id, profile.id,
            review.job?.id, newId())
    }
    suspend fun rerunRecognition(target: RecognitionRerunTarget): RecognitionJob {
        val id = requireNotNull(selectedProjectId)
        val project = requireNotNull(repositories.projects.getProject(id)) { "PROJECT_UNAVAILABLE" }
        require(project.project.templateVersionId == target.templateVersionId) { "RECOGNITION_INPUT_CHANGED" }
        val profile = requireNotNull(repositories.intelligence.getRecognitionProfile(target.templateVersionId)) { "RECOGNITION_SETTINGS_REQUIRED" }
        return repositories.intelligence.rerunRecognition(target.submissionId, target.submissionRevisionId, profile.id,
            target.currentJobId, newId())
    }
    suspend fun discardTemplate() {
        val draft = requireNotNull(state.value.templateDraft)
        repositories.templates.discardDraft(draft.projectId, draft.id)
    }
    suspend fun beginCollection() = repositories.submissions.beginSubmission(requireNotNull(selectedProjectId))
    suspend fun collectionDraft(): SubmissionDraft = repositories.submissions.observeDrafts(requireNotNull(selectedProjectId)).first()
        .firstOrNull { it.baseRevisionId == null } ?: beginCollection()
    suspend fun importTemplate(file: File, indices: List<Int>) {
        val draft = templateDraft()
        ProjectOperationGate.shared.withLease(draft.projectId) {
            val pages = importPages(file, indices, draft.pages.size)
            saveTemplate(draft.copy(pages = draft.pages + pages))
        }
    }
    suspend fun importSubmission(file: File, indices: List<Int>, slots: List<String>, draftId: String? = null) {
        val draft = draftId?.let { repositories.submissions.getDraft(it) } ?: collectionDraft()
        requireNotNull(draft); require(indices.size == slots.size && slots.distinct().size == slots.size) { "PAGE_SLOTS_INVALID" }
        ProjectOperationGate.shared.withLease(draft.projectId) {
            val pages = importPages(file, indices, 0)
            val replaced = draft.pages.filterNot { it.templatePageId in slots } + slots.zip(pages) { slot, page -> PageRevision(slot, page) }
            repositories.submissions.saveDraft(draft.copy(pages = replaced))
        }
    }
    suspend fun pdfCount(file: File): Int {
        val asset = storeOriginal(file)
        return try { PdfClient(workspace.assets).open(asset.relativePath).use { it.pageCount } }
        finally { workspace.assets.delete(asset.relativePath) }
    }
    private fun storeOriginal(file: File): SampleAsset {
        val kind = if (file.extension.equals("pdf", true)) SampleKind.PDF else SampleKind.IMAGE
        require(file.extension.lowercase() in listOf("pdf", "png", "jpg", "jpeg")) { "UNSUPPORTED_FORMAT" }
        val id = newId()
        val info = file.inputStream().use { workspace.assets.put("originals/$id/source.${file.extension.lowercase()}", it) }
        return SampleAsset(id, kind, info.relativePath, info.sha256, info.byteCount)
    }
    private fun importPages(file: File, indices: List<Int>, startPosition: Int): List<SamplePage> {
        require(indices.isNotEmpty() && indices.distinct().size == indices.size && indices.all { it >= 0 }) { "NO_PAGES" }
        val asset = storeOriginal(file)
        return if (asset.kind == SampleKind.PDF) PdfClient(workspace.assets).open(asset.relativePath).use { pdf ->
            require(indices.all { it < pdf.pageCount }) { "PAGE_OUT_OF_RANGE" }
            indices.mapIndexed { offset, index -> createPage(asset, index, startPosition + offset,
                decodeImage(pdf.render(index, 144))) }
        } else {
            require(indices == listOf(0)) { "PAGE_OUT_OF_RANGE" }
            val image = decodeImage(workspace.assets.readBytes(asset.relativePath))
            listOf(createPage(asset, 0, startPosition, image))
        }
    }
    private fun createPage(asset: SampleAsset, index: Int, position: Int, source: BufferedImage): SamplePage {
        require(source.width.toLong() * source.height <= 100_000_000) { "IMAGE_TOO_LARGE" }
        val id = newId(); val image = scaleBase(source)
        try {
            ProjectOperationGate.shared.currentProjectId()?.let { pageOwners[id] = it }
            val path = "pages/$id/base.png"; savePng(path, image)
            val thumbnailPath = "pages/$id/thumb.png"; saveThumbnail(thumbnailPath, image)
            return SamplePage(id, asset, index, position, path, thumbnailPath, image.width, image.height,
                processing = PageProcessingSpec(PageProcessingMode.AUTO_LIGHT))
        } finally { if(image !== source) image.flush(); source.flush() }
    }
    fun loadImage(page: SamplePage): BufferedImage {
        val owner = ProjectOperationGate.shared.currentProjectId() ?: pageOwners[page.id]
        fun read(): BufferedImage = synchronized(renderLock) {
            if (!workspace.assets.exists(page.renderPath)) rebuildDerived(page)
            val image=decodeImage(workspace.assets.readBytes(page.renderPath))
            try {
                require(image.width == page.width && image.height == page.height) { "PAGE_DIMENSIONS_CHANGED" }
                if (!workspace.assets.exists(page.thumbnailPath)) saveThumbnail(page.thumbnailPath,image)
                image
            } catch(failure:Throwable) { image.flush(); throw failure }
        }
        return if (owner == null) {
            // Reading existing data is safe through file leases; rebuilding requires a project lease.
            require(workspace.assets.exists(page.renderPath) && workspace.assets.exists(page.thumbnailPath)) { "DERIVED_IMAGE_MISSING" }
            read()
        } else runBlocking { ProjectOperationGate.shared.withLease(owner) { read() } }
    }

    /** Small display derivatives can be repaired only when absent or explicitly requested. */
    internal fun loadThumbnail(page: SamplePage, rebuild: Boolean = false): BufferedImage {
        val owner=ProjectOperationGate.shared.currentProjectId() ?: pageOwners[page.id]
        fun read():BufferedImage=synchronized(renderLock) {
            if(rebuild || !workspace.assets.exists(page.thumbnailPath)) {
                // Authenticate any existing ciphertext before explicit replacement. Authentication
                // failure must never be mistaken for a missing/oversized derived image.
                if(rebuild && workspace.assets.exists(page.thumbnailPath)) workspace.assets.open(page.thumbnailPath).use { input ->
                    val buffer=ByteArray(8192); while(input.read(buffer)>=0) { /* authenticate to EOF */ }
                }
                check(ProjectOperationGate.shared.currentProjectId()!=null) { "DERIVED_IMAGE_REBUILD_REQUIRES_PROJECT" }
                val base=loadImage(page)
                try { if(rebuild || !workspace.assets.exists(page.thumbnailPath))saveThumbnail(page.thumbnailPath,base) } finally { base.flush() }
            }
            val bytes=workspace.assets.open(page.thumbnailPath).use(::readThumbnailBytes)
            val legacy=decodeImage(bytes,ThumbnailBounds.LEGACY_WIDTH,ThumbnailBounds.LEGACY_HEIGHT)
            if(legacy.width<=ThumbnailBounds.WIDTH && legacy.height<=ThumbnailBounds.HEIGHT)legacy
            else try { thumbnailCopy(legacy) } finally { legacy.flush() }
        }
        return if (owner == null) {
            require(workspace.assets.exists(page.thumbnailPath) && !rebuild) { "DERIVED_IMAGE_MISSING" }
            read()
        } else runBlocking { ProjectOperationGate.shared.withLease(owner) { read() } }
    }

    /** UI-only decoder budget; recognition/geometry keep their existing processing contracts. */
    internal fun validateDisplayImageRequest(key:DisplayImageKey) {
        check(!closing) { "IMAGE_LOADER_CLOSED" }
        ProjectOperationGate.shared.checkProject(key.projectId)
        check(selectedProjectId==key.projectId) { "PROJECT_CHANGED" }
    }
    suspend fun loadDisplayImage(projectId:String,page:SamplePage,source:DisplayImageSource=DisplayImageSource.BASE,
        rebuildThumbnail:Boolean=false):androidx.compose.ui.graphics.ImageBitmap {
        val key=DisplayImageKey.of(projectId,page,source)
        return ProjectOperationGate.shared.withLease(projectId) {
            displayImages.load(key,decode={
                pageOwners[page.id]=projectId
                when(source) {
                    DisplayImageSource.BASE -> loadImage(page)
                    DisplayImageSource.ORIGINAL -> loadOriginalImage(page)
                    DisplayImageSource.ENHANCED -> loadImage(page,true)
                    DisplayImageSource.THUMBNAIL -> loadThumbnail(page,rebuildThumbnail)
                }
            },convert={ it.toComposeImageBitmap() })
        }
    }

    /** The original is immutable. Enhanced display is accepted only when its complete evidence chain matches. */
    suspend fun loadImage(page: SamplePage, enhanced: Boolean): BufferedImage {
        if (!enhanced) return loadImage(page)
        check(page.processing.mode == PageProcessingMode.AUTO_LIGHT) { "ENHANCEMENT_UNAVAILABLE" }
        loadImage(page).flush()
        val result = repositories.imageProcessing.getResult(page) ?: error("ENHANCEMENT_UNAVAILABLE")
        val artifact = result.enhanced ?: error("ENHANCEMENT_UNAVAILABLE")
        val baseBytes = workspace.assets.readBytes(page.renderPath)
        if (result.inputFingerprint != PageProcessingFingerprint.of(page) || result.report.inputSha256 != hash(baseBytes) ||
            artifact.inputFingerprint != result.inputFingerprint || artifact.inputSha256 != result.report.inputSha256 ||
            artifact.processingSpec != page.processing || artifact.width != page.width || artifact.height != page.height) error("ENHANCEMENT_UNAVAILABLE")
        return runCatching {
            val bytes = workspace.assets.readBytes(artifact.relativePath)
            require(bytes.size.toLong() == artifact.byteCount && hash(bytes) == artifact.sha256)
            val image=decodeImage(bytes)
            try {require(image.width==page.width && image.height==page.height);image}
            catch(failure:Throwable){image.flush();throw failure}
        }.getOrElse { error("ENHANCEMENT_UNAVAILABLE") }
    }

    fun loadOriginalImage(page: SamplePage): BufferedImage {
        if(page.asset.kind == SampleKind.PDF) {
            workspace.assets.open(page.asset.relativePath).use { verifyOriginalStream(it,page.asset) }
            return PdfClient(workspace.assets).open(page.asset.relativePath).use {
                require(page.sourcePageIndex in 0 until it.pageCount) { "PAGE_OUT_OF_RANGE" }
                val original=decodeImage(it.render(page.sourcePageIndex,144))
                val scaled=try { scaleBase(original) } catch(failure:Throwable) { original.flush(); throw failure }
                if(scaled !== original) original.flush()
                scaled
            }
        }
        val bytes=workspace.assets.readBytes(page.asset.relativePath)
        require(bytes.size.toLong()==page.asset.byteCount && hash(bytes).equals(page.asset.sha256,true)) { "ASSET_INTEGRITY" }
        val original=decodeImage(bytes)
        val scaled=try {scaleBase(original)}catch(failure:Throwable){original.flush();throw failure}
        if(scaled !== original) original.flush()
        return scaled
    }

    private fun decodeImage(bytes: ByteArray, maxWidth:Int=Int.MAX_VALUE,maxHeight:Int=Int.MAX_VALUE): BufferedImage = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        requireNotNull(input) { "IMAGE_DECODE" }
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("IMAGE_DECODE")
        try {
            reader.input = input
            require(reader.getWidth(0).toLong() * reader.getHeight(0) in 1L..100_000_000L) { "IMAGE_TOO_LARGE" }
            require(reader.getWidth(0)<=maxWidth && reader.getHeight(0)<=maxHeight) { "THUMBNAIL_REBUILD_REQUIRED" }
            reader.read(0) ?: error("IMAGE_DECODE")
        } finally { reader.dispose() }
    }

    private fun scaleBase(source: BufferedImage): BufferedImage {
        val scale = minOf(1.0, 2560.0 / maxOf(source.width, source.height))
        if (scale == 1.0 && source.type == BufferedImage.TYPE_INT_RGB) return source
        return BufferedImage((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1),
            BufferedImage.TYPE_INT_RGB).apply { createGraphics().useGraphics {
                it.color = java.awt.Color.WHITE; it.fillRect(0, 0, width, height); it.drawImage(source, 0, 0, width, height, null)
            } }
    }

    private fun saveThumbnail(path: String, image: BufferedImage) {
        val thumbnail=thumbnailCopy(image)
        try {savePng(path,thumbnail)}finally{thumbnail.flush()}
    }
    private fun thumbnailCopy(image:BufferedImage):BufferedImage {
        val (width,height)=ThumbnailBounds.size(image.width,image.height)
        val thumbnail = BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        thumbnail.createGraphics().useGraphics { it.drawImage(image,0,0,width,height,null) }
        return thumbnail
    }

    private fun rebuildDerived(page: SamplePage) {
        check(ProjectOperationGate.shared.currentProjectId() != null) { "DERIVED_IMAGE_REBUILD_REQUIRES_PROJECT" }
        val original = loadOriginalImage(page)
        val matrix = page.transform.sourceToPage
        val identity = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        try {
            val rebuilt = if (matrix == identity && original.width == page.width && original.height == page.height) original
                else vision.warpGeometry(original, matrix, page.width, page.height)
            try { savePng(page.renderPath, rebuilt); saveThumbnail(page.thumbnailPath, rebuilt) }
            finally { if (rebuilt !== original) rebuilt.flush() }
        } finally { original.flush() }
    }
    private fun savePng(path: String, image: BufferedImage) {
        val out = ByteArrayOutputStream(); check(ImageIO.write(image, "png", out))
        workspace.assets.put(path, ByteArrayInputStream(out.toByteArray()))
    }
    suspend fun rotateTemplatePage(pageId: String) {
        val draft = templateDraft(); val page = draft.pages.first { it.id == pageId }
        ProjectOperationGate.shared.withLease(draft.projectId) {
            val matrix = multiplyMatrix(listOf(0f, -1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f), page.transform.sourceToPage)
            saveTemplateGeometry(draft, page, matrix, page.height, page.width, (page.transform.rotationDegrees + 90) % 360)
        }
    }

    suspend fun rectifyTemplatePage(pageId: String, corners: List<NormalizedPoint>) {
        val draft = templateDraft(); val page = draft.pages.first { it.id == pageId }
        ProjectOperationGate.shared.withLease(draft.projectId) {
            val current = loadImage(page)
            val preview = try { vision.rectify(current, corners) } finally { current.flush() }
            try {
                val matrix = multiplyMatrix(vision.rectificationMatrix(corners), page.transform.sourceToPage)
                saveTemplateGeometry(draft, page, matrix, preview.width, preview.height, page.transform.rotationDegrees)
            } finally { preview.flush() }
        }
    }

    private suspend fun saveTemplateGeometry(draft: TemplateDraft, page: SamplePage, matrix: List<Float>, width: Int, height: Int, rotation: Int) {
        val original = loadOriginalImage(page)
        val corrected = try { vision.warpGeometry(original, matrix, width, height) } finally { original.flush() }
        val path = "pages/${page.id}/r${page.revision + 1}.png"
        val thumbnail = "pages/${page.id}/r${page.revision + 1}-thumb.png"
        try { savePng(path, corrected); saveThumbnail(thumbnail, corrected) } finally { corrected.flush() }
        val transform = PageTransform(rotation, sourceCorners(matrix), matrix)
        val updated = page.copy(renderPath = path, thumbnailPath = thumbnail, width = width, height = height,
            revision = page.revision + 1, transform = transform)
        saveTemplate(draft.copy(pages = draft.pages.map { if (it.id == page.id) updated else it },
            questions = draft.questions.filterNot { it.pageId == page.id }, matrices = draft.matrices.filterNot { it.pageId == page.id },
            mappings = draft.mappings.filterNot { mapping -> draft.questions.any { it.pageId == page.id && it.id == mapping.questionId } }))
    }
    suspend fun analyze(pageId: String, region: NormalizedRect?) {
        val draft = templateDraft(); val page = draft.pages.first { it.id == pageId }
        ProjectOperationGate.shared.withLease(draft.projectId) {
        val project = repositories.projects.getProject(draft.projectId) ?: error("PROJECT_UNAVAILABLE")
        val questions = vision.analyzeTemplate(page, loadImage(page), project.project.questionnaireLanguage, region)
        val suggestions = questions.map { TemplateSuggestion(newId(), page.id, page.revision, it,
            scope = TemplateAnalysisScope(pageId, region), processingFingerprint = PageProcessingFingerprint.of(page)) }
        repositories.intelligence.saveAnalysis(TemplateAnalysisRun(newId(), draft.projectId, draft.id, draft.revision,
            DesktopVision.VERSION, suggestions, scope = TemplateAnalysisScope(pageId, region),
            processingFingerprints = mapOf(page.id to PageProcessingFingerprint.of(page))))
        }
    }
    suspend fun saveAnswer(workspace: ReviewWorkspace, answer: AnswerValue) = repositories.intelligence.saveAnswer(
        workspace.submission.id, workspace.revision.id, workspace.answers?.version ?: 0, answer, newId())
    suspend fun export(projectId: String, includeUnresolved: Boolean): ExportSnapshot = ProjectOperationGate.shared.withLease(projectId) {
        val snapshot = repositories.intelligence.createExportSnapshot(projectId, includeUnresolved, newId())
        generateArtifact(snapshot); snapshot
    }
    suspend fun generateArtifact(snapshot: ExportSnapshot): ExportArtifact {
        val existing = repositories.intelligence.getExportArtifact(snapshot.id)
        if (existing?.status == ExportArtifactStatus.READY && existing.relativePath?.let(workspace.assets::exists) == true) return existing
        repositories.intelligence.saveExportArtifact(ExportArtifact(snapshot.id, ExportArtifactStatus.GENERATING))
        try {
            val output = ByteArrayOutputStream(); val counts = XlsxWriter.write(output, snapshot.toXlsxSheets())
            val bytes = output.toByteArray(); XlsxWriter.validate(ByteArrayInputStream(bytes), counts)
            val info = workspace.assets.put("exports/${snapshot.id}/workbook.xlsx", ByteArrayInputStream(bytes))
            workspace.assets.open(info.relativePath).use { XlsxWriter.validate(it, counts) }
            return ExportArtifact(snapshot.id, ExportArtifactStatus.READY, info.relativePath, info.sha256, info.byteCount)
                .also { repositories.intelligence.saveExportArtifact(it) }
        } catch (e: Exception) {
            repositories.intelligence.saveExportArtifact(ExportArtifact(snapshot.id, ExportArtifactStatus.FAILED, error = errorCode(e)))
            throw e
        }
    }
    suspend fun saveExternal(snapshot: ExportSnapshot, target: Path) {
        val artifact = generateArtifact(snapshot)
        val parent = target.toAbsolutePath().parent
        val part = Files.createTempFile(parent, ".phoneform-export-", ".part")
        try {
            workspace.assets.open(requireNotNull(artifact.relativePath)).use { input -> Files.newOutputStream(part).use(input::copyTo) }
            require(hash(Files.readAllBytes(part)) == artifact.sha256) { "EXPORT_HASH" }
            try { Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING) }
            mutable.update { it.copy(notice = "EXPORT_SAVED") }
        } finally { Files.deleteIfExists(part) }
    }
    fun togglePaused() = mutable.update { it.copy(paused = !it.paused) }
    private suspend fun workerLoop() {
        while (scope.isActive) {
            val worked = runWorkerPass()
            delay(if (worked) 50 else 500)
        }
    }

    /** One durable claim per pass; internal visibility makes restart/checkpoint tests deterministic. */
    internal suspend fun runWorkerPass(): Boolean = workerLock.withLock {
        initialized.await()
        if (state.value.paused) return@withLock false
        var preparation: ProcessingJob? = null; var recognition: RecognitionJob? = null; var imageJob: PageProcessingJob? = null
        try {
            imageJob = repositories.imageProcessing.claim(sessionId, true)
            if (imageJob != null) {
                val job = imageJob
                mutable.update { it.copy(worker = "PREPARING") }
                ProjectOperationGate.shared.withLease(job.owner.projectId) { processImageJob(job) }
                return@withLock true
            }
            preparation = repositories.jobs.claimNext(sessionId, true)
            if (preparation != null) {
                val job = preparation
                mutable.update { it.copy(worker = "PREPARING") }
                ProjectOperationGate.shared.withLease(job.projectId) { prepareSubmission(job) }
                return@withLock true
            }
            recognition = repositories.intelligence.claimRecognition(sessionId, true)
            if (recognition != null) {
                val job = recognition
                mutable.update { it.copy(worker = "RECOGNIZING") }
                ProjectOperationGate.shared.withLease(job.projectId) { recognizeSubmission(job) }
                return@withLock true
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                imageJob?.let { runCatching { repositories.imageProcessing.pause(it, "INTERRUPTED") } }
                preparation?.let { runCatching { repositories.jobs.pause(it, "INTERRUPTED") } }
                recognition?.let { runCatching { repositories.intelligence.pauseRecognition(it, "INTERRUPTED") } }
            }
            throw cancelled
        } catch (failure: Exception) {
            imageJob?.let { runCatching { repositories.imageProcessing.fail(it, errorCode(failure)) } }
            preparation?.let { runCatching { repositories.jobs.fail(it, errorCode(failure)) } }
            recognition?.let { runCatching { repositories.intelligence.failRecognition(it, errorCode(failure)) } }
        } finally { mutable.update { it.copy(worker = "") } }
        imageJob != null || preparation != null || recognition != null
    }

    private suspend fun processImageJob(job: PageProcessingJob) {
        pageOwners[job.page.id] = job.owner.projectId
        if (!repositories.imageProcessing.isClaimValid(job, true)) return
        if (state.value.paused) { repositories.imageProcessing.pause(job, "USER_PAUSED"); return }
        val base = loadImage(job.page)
        try {
            // Rebuilding a missing render changes a previously empty input hash; claim it afresh.
            val sourceHash = hash(workspace.assets.readBytes(job.page.renderPath))
            if (sourceHash != job.inputSha256) {
                repositories.imageProcessing.fail(job, "INPUT_CHANGED")
                repositories.imageProcessing.retry(job.id)
                return
            }
            val result = vision.process(job.page, base)
            try {
                currentCoroutineContext().ensureActive()
                if (!repositories.imageProcessing.isClaimValid(job, true)) return
                if (state.value.paused) { repositories.imageProcessing.pause(job, "USER_PAUSED"); return }
                val enhanced = if (result.image !== base && job.page.processing.mode == PageProcessingMode.AUTO_LIGHT &&
                    result.quality.issues.none { it.code == PageQualityIssueCode.ENHANCEMENT_UNAVAILABLE }) {
                    val path = "enhanced/${job.inputFingerprint}/${job.id}-${job.attempt}.png"
                    val thumbnail = "enhanced/${job.inputFingerprint}/${job.id}-${job.attempt}-thumb.png"
                    savePng(path, result.image); saveThumbnail(thumbnail, result.image)
                    val bytes = workspace.assets.readBytes(path)
                    EnhancedPageArtifact(path, thumbnail, hash(bytes), bytes.size.toLong(), result.image.width, result.image.height,
                        job.inputFingerprint, result.quality.engineVersion, sourceHash, job.page.processing)
                } else null
                currentCoroutineContext().ensureActive()
                if (repositories.imageProcessing.isClaimValid(job, true) && !state.value.paused)
                    repositories.imageProcessing.complete(job, PageProcessingResult(job.inputFingerprint,
                        result.quality.copy(inputFingerprint = job.inputFingerprint, inputSha256 = sourceHash), enhanced))
                else if (state.value.paused) repositories.imageProcessing.pause(job, "USER_PAUSED")
            } finally { if (result.image !== base) result.image.flush() }
        } finally { base.flush() }
    }

    private suspend fun prepareSubmission(job: ProcessingJob) {
        val revision = repositories.submissions.getRevision(job.submissionRevisionId) ?: error("REVISION_UNAVAILABLE")
        val prepared = job.preparedPages.mapNotNull { saved -> revision.pages.find { it.templatePageId == saved.templatePageId &&
            it.image.id == saved.image.id && PageProcessingFingerprint.of(it.image) == PageProcessingFingerprint.of(saved.image) } }.toMutableList()
        for (page in revision.pages) {
            currentCoroutineContext().ensureActive()
            if (state.value.paused) { repositories.jobs.pause(job, "USER_PAUSED"); return }
            if (!repositories.jobs.isClaimValid(job, true)) return
            if (prepared.any { it.templatePageId == page.templatePageId }) continue
            pageOwners[page.image.id] = job.projectId
            val source = workspace.assets.readBytes(page.image.asset.relativePath)
            require(hash(source) == page.image.asset.sha256 && source.size.toLong() == page.image.asset.byteCount) { "ASSET_INTEGRITY" }
            loadImage(page.image).flush()
            if (!repositories.jobs.isClaimValid(job, true)) return
            val next = prepared + page
            if (!repositories.jobs.checkpoint(job, next)) return
            prepared += page
        }
        if (!state.value.paused && repositories.jobs.isClaimValid(job, true) &&
            prepared.map { it.templatePageId }.toSet() == revision.pages.map { it.templatePageId }.toSet()) repositories.jobs.complete(job)
        else if (state.value.paused) repositories.jobs.pause(job, "USER_PAUSED")
    }

    private suspend fun recognizeSubmission(job: RecognitionJob) {
        val profile = job.profileId?.let { repositories.intelligence.getRecognitionProfileById(it) }
            ?: error("RECOGNITION_SETTINGS_REQUIRED")
        require(profile.engineVersion == DesktopVision.VERSION) { "RECOGNITION_ENGINE_UNSUPPORTED" }
        val review = repositories.intelligence.getReview(job.submissionId) ?: error("REVISION_UNAVAILABLE")
        if (review.revision.id != job.submissionRevisionId || !repositories.intelligence.isRecognitionClaimValid(job, true)) return
        val completedSlots = job.completedPageIds.toMutableSet()
        for (page in review.revision.pages) {
            currentCoroutineContext().ensureActive()
            if (state.value.paused) { repositories.intelligence.pauseRecognition(job, "USER_PAUSED"); return }
            if (!repositories.intelligence.isRecognitionClaimValid(job, true)) return
            if (page.templatePageId in completedSlots) continue
            val templatePage = review.template.document.pages.first { it.id == page.templatePageId }
            pageOwners[templatePage.id] = job.projectId; pageOwners[page.image.id] = job.projectId
            val templateImage = loadImage(templatePage)
            val result = try {
                val submittedImage=loadImage(page.image)
                try {vision.recognize(templatePage, templateImage, page.image, submittedImage,
                    review.template.document.questions.filter { it.pageId == templatePage.id }, review.project.project.questionnaireLanguage, profile, job.id,
                    hash(workspace.assets.readBytes(page.image.renderPath)))}finally{submittedImage.flush()}
            } finally {templateImage.flush()}
            currentCoroutineContext().ensureActive()
            if (!repositories.intelligence.isRecognitionClaimValid(job, true)) return
            if (state.value.paused) { repositories.intelligence.pauseRecognition(job, "USER_PAUSED"); return }
            if (!repositories.intelligence.checkpointAlignment(job, result.alignment)) return
            if (!repositories.intelligence.checkpointRecognition(job, result.alignment, result.observations)) return
            completedSlots += page.templatePageId
        }
        if (!state.value.paused && repositories.intelligence.isRecognitionClaimValid(job, true) &&
            completedSlots == review.revision.pages.map { it.templatePageId }.toSet()) repositories.intelligence.completeRecognition(job)
        else if (state.value.paused) repositories.intelligence.pauseRecognition(job, "USER_PAUSED")
    }
    override fun close() {
        closing = true
        try { runBlocking { actions.withLock {
            editorSaveJob?.cancelAndJoin(); uiSaveJob?.cancelAndJoin()
            flushEdits(); flushUiPreferences()
            displayImages.closeAndAwait()
            scope.coroutineContext[Job]?.cancelAndJoin(); workspace.close()
        } } }
        catch (e: Exception) { closing = false; throw e }
    }
    companion object {
        internal fun multiplyMatrix(a: List<Float>, b: List<Float>): List<Float> {
            require(a.size == 9 && b.size == 9)
            val result = List(9) { index -> val row = index / 3; val col = index % 3
                (0..2).sumOf { k -> a[row * 3 + k].toDouble() * b[k * 3 + col] }.toFloat() }
            require(result.all(Float::isFinite) && kotlin.math.abs(result[8]) > 1E-8f) { "INVALID_TRANSFORM" }
            return result.map { it / result[8] }
        }

        internal fun sourceCorners(matrix: List<Float>): List<NormalizedPoint> {
            require(matrix.size == 9 && matrix.all(Float::isFinite)) { "INVALID_TRANSFORM" }
            val m = matrix.map(Float::toDouble)
            val inverse = listOf(m[4]*m[8]-m[5]*m[7], m[2]*m[7]-m[1]*m[8], m[1]*m[5]-m[2]*m[4],
                m[5]*m[6]-m[3]*m[8], m[0]*m[8]-m[2]*m[6], m[2]*m[3]-m[0]*m[5],
                m[3]*m[7]-m[4]*m[6], m[1]*m[6]-m[0]*m[7], m[0]*m[4]-m[1]*m[3])
            val determinant = m[0]*inverse[0]+m[1]*inverse[3]+m[2]*inverse[6]
            require(determinant.isFinite() && kotlin.math.abs(determinant) > 1E-12) { "INVALID_TRANSFORM" }
            return listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0).map { (x,y) ->
                val divisor = inverse[6]*x+inverse[7]*y+inverse[8]
                val sourceX = ((inverse[0]*x+inverse[1]*y+inverse[2])/divisor).toFloat()
                val sourceY = ((inverse[3]*x+inverse[4]*y+inverse[5])/divisor).toFloat()
                require(sourceX.isFinite() && sourceY.isFinite() && sourceX in -.001f..1.001f && sourceY in -.001f..1.001f) { "INVALID_TRANSFORM" }
                NormalizedPoint(sourceX.coerceIn(0f,1f), sourceY.coerceIn(0f,1f))
            }
        }
        fun open(root: Path, models: Path) = Controller(SecureWorkspace.open(root), models)
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun errorCode(error: Throwable): String = when(error) {
            is TemplateValidationException -> "TEMPLATE_INVALID: " + error.issues.joinToString { it.code.name }
            is SubmissionValidationException -> "SUBMISSION_INVALID: " + error.issues.joinToString { it.code.name }
            is ReviewValidationException -> "REVIEW_INCOMPLETE"
            is StaleAnswerException, is StaleTemplateException, is StaleSubmissionException, is StaleOutputConfigurationException, is StaleRecognitionException -> "STALE_EDIT_RELOAD"
            else -> error.message?.takeIf { it.matches(Regex("[A-Z0-9_: ,.-]{1,160}")) } ?: error.javaClass.simpleName
        }
    }
}
private inline fun java.awt.Graphics2D.useGraphics(block: (java.awt.Graphics2D) -> Unit) { try { block(this) } finally { dispose() } }
