package com.phoneform.desktop

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class WorkspaceSection { TEMPLATE, COLLECTION, REVIEW, EXPORT }
enum class EditorSaveState { SAVED, SAVING, FAILED }

/** Pan offsets are fractions of the viewport, so they survive resizing and DPI changes. */
data class CanvasViewState(val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f,
    val pan: Boolean = false, val source: String = "BASE")

internal fun CanvasViewState.normalized() = copy(
    zoom = if (zoom.isFinite()) zoom.coerceIn(.5f, 5f) else 1f,
    offsetX = if (offsetX.isFinite()) offsetX.coerceIn(-5f,5f) else 0f,
    offsetY = if (offsetY.isFinite()) offsetY.coerceIn(-5f,5f) else 0f,
    source = source.takeIf { it in setOf("BASE","ORIGINAL","ENHANCED") } ?: "BASE",
)

data class ProjectUiState(
    val templatePageId: String? = null, val templateQuestionId: String? = null,
    val templateInspectorTab: Int = 0, val collectionDraftId: String? = null,
    val collectionPageId: String? = null, val reviewSubmissionId: String? = null,
    val reviewTargetKey: String? = null, val reviewFilter: String = "PENDING",
    val reviewSearch: String = "", val reviewWorkspace: Boolean = false,
    val exportTab: Int = 0, val exportSearch: String = "",
    val paneLeft: Float = 220f, val paneRight: Float = 340f,
    val canvasViews: Map<String,CanvasViewState> = emptyMap(),
    val recognitionPageId: String? = null, val recognitionQuestionId: String? = null,
    val recognitionOptionId: String? = null,
    val recognitionTool:String = "GROUP",
    val reviewPageIndex: Int = 0, val reviewPageSize: Int = 50,
)

data class DesktopUiState(
    val theme: ThemeMode = ThemeMode.LIGHT,
    val section: WorkspaceSection = WorkspaceSection.TEMPLATE,
    val projectId: String? = null, val sidebarExpanded: Boolean = true,
    val projects: Map<String, ProjectUiState> = emptyMap(),
    val projectSearch: String = "", val projectSort: String = "UPDATED",
    val projectSortAscending: Boolean = false,
    val taskSearch: String = "", val taskFilter: String = "PENDING",
    val taskPageIndex: Int = 0, val taskPageSize: Int = 50,
)

data class UiTaskSummary(
    val id: String, val projectId: String, val projectName: String,
    val phase: String, val status: String, val submissionNumber: Int? = null,
    val pageNumber: Int? = null, val error: String? = null,
    val retrySupported: Boolean = false,
)

internal val pendingTaskStatuses = setOf("QUEUED", "PENDING", "RUNNING", "PAUSED", "BLOCKED")
internal fun pendingTaskCount(statusCounts: Map<String,Int>): Int = statusCounts.filterKeys { it in pendingTaskStatuses }.values.sum()

internal fun ProjectUiState.normalized() = copy(
    templateInspectorTab = templateInspectorTab.coerceIn(0, 2),
    exportTab = exportTab.coerceIn(0, 2),
    reviewFilter = reviewFilter.takeIf { it in setOf("PENDING", "ALL", "CONFIRMED", "EXCEPTIONS") } ?: "PENDING",
    reviewPageIndex = reviewPageIndex.coerceAtLeast(0), reviewPageSize = reviewPageSize.takeIf { it in 1..100 } ?: 50,
    paneLeft = if (paneLeft.isFinite()) paneLeft.coerceIn(160f, 360f) else 220f,
    paneRight = if (paneRight.isFinite()) paneRight.coerceIn(280f, 480f) else 340f,
    canvasViews = canvasViews.mapValues { it.value.normalized() },
    recognitionTool=recognitionTool.takeIf {name->CalibrationTool.entries.any {it.name==name}} ?: "GROUP",
)

/** Explicit decoding preserves defaults when an older/future UI preference has missing/unknown values. */
internal object DesktopUiCodec {
    private val gson = Gson()
    fun encode(value: DesktopUiState): String = gson.toJson(value)
    fun decode(payload: String?): DesktopUiState {
        if (payload == null) return DesktopUiState()
        return runCatching {
            val json = JsonParser.parseString(payload).asJsonObject
            val defaults = DesktopUiState()
            val projects = json.getAsJsonObject("projects")?.entrySet()?.associate { (id, entry) ->
                val p = entry.asJsonObject
                val views = p.getAsJsonObject("canvasViews")?.entrySet()?.associate { (pageId,view) ->
                    val v = view.asJsonObject
                    pageId to CanvasViewState(v.float("zoom",1f),v.float("offsetX",0f),v.float("offsetY",0f),
                        v.bool("pan",false),v.text("source") ?: "BASE").normalized()
                }.orEmpty()
                id to ProjectUiState(p.text("templatePageId"), p.text("templateQuestionId"), p.int("templateInspectorTab", 0),
                    p.text("collectionDraftId"), p.text("collectionPageId"), p.text("reviewSubmissionId"),
                    p.text("reviewTargetKey"), p.text("reviewFilter") ?: "PENDING", p.text("reviewSearch") ?: "",
                    p.bool("reviewWorkspace", false), p.int("exportTab", 0), p.text("exportSearch") ?: "",
                    p.float("paneLeft", 220f), p.float("paneRight", 340f), views,
                    p.text("recognitionPageId"),p.text("recognitionQuestionId"),p.text("recognitionOptionId"),p.text("recognitionTool") ?: "GROUP",
                    p.int("reviewPageIndex",0),p.int("reviewPageSize",50)).normalized()
            }.orEmpty()
            defaults.copy(theme = enumValue(json.text("theme"), ThemeMode.LIGHT),
                section = enumValue(json.text("section"), WorkspaceSection.TEMPLATE),
                projectId = json.text("projectId"), sidebarExpanded = json.bool("sidebarExpanded", true),
                projects = projects, projectSearch = json.text("projectSearch") ?: "",
                projectSort = json.text("projectSort") ?: "UPDATED",
                projectSortAscending = json.bool("projectSortAscending", false),
                taskSearch = json.text("taskSearch") ?: "",
                taskFilter = json.text("taskFilter")?.takeIf { it in setOf("PENDING","FAILED","COMPLETED","ALL") } ?: "PENDING",
                taskPageIndex = json.int("taskPageIndex",0).coerceAtLeast(0),
                taskPageSize = json.int("taskPageSize",50).takeIf { it in 1..100 } ?: 50)
        }.getOrDefault(DesktopUiState())
    }
    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T = enumValues<T>().firstOrNull { it.name == value } ?: default
    private fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.int(key: String, default: Int) = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: default
    private fun JsonObject.float(key: String, default: Float) = get(key)?.takeUnless { it.isJsonNull }?.asFloat ?: default
    private fun JsonObject.bool(key: String, default: Boolean) = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: default
}
