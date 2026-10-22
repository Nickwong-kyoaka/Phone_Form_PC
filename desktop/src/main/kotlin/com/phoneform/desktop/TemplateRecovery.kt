package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

internal data class TemplateRecoveryIssue(val sessionId:String,val pageId:String,val questionId:String?=null,val matrix:Boolean=false)

/** Read at invocation time: a Ctrl+S may arrive before Compose renders the last keystroke. */
internal fun RecoverableEditorState<QuestionEditEnvelope>.questionForCommit(draftId:String,pageFingerprint:String,question:QuestionDefinition):QuestionDefinition {
    requireReady()
    val latest=value
    require(questionEnvelopeMatches(latest,draftId,pageFingerprint,question)){"STALE_EDIT_RELOAD"}
    return latest.value
}

internal fun decodeQuestionRecovery(raw:String):QuestionEditEnvelope =
    requireNotNull(editorJson.fromJson(raw,QuestionEditEnvelope::class.java)).also {
        require(!it.draftId.isNullOrBlank() && !it.pageFingerprint.isNullOrBlank() && it.original!=null && it.value!=null &&
            listOf(it.original,it.value).all {q->q.id!=null && q.pageId!=null && q.options!=null && q.type!=null && q.title!=null && q.label!=null &&
                q.options.all {o->o.id!=null && o.title!=null && o.code!=null} &&
                q.recognition?.let {s->s.style!=null && s.options!=null}!=false}){"EDITOR_RECOVERY_CORRUPT"}
    }

internal fun decodeCalibrationRecovery(raw:String):CalibrationEnvelope =
    requireNotNull(editorJson.fromJson(raw,CalibrationEnvelope::class.java)).also {
        require(it.binding!=null && it.draft!=null && it.grids!=null && it.undo!=null && it.redo!=null &&
            it.draft.settings!=null && it.draft.pageFingerprints!=null && it.draft.settings.all {s->!s.questionId.isNullOrBlank() && s.style!=null && s.options!=null &&
                s.options.all {o->o.optionId!=null && o.region!=null}} &&
            it.grids.values.all {g->g.region!=null && g.rows!=null && g.columns!=null && g.cells!=null && g.rows.size>=2 && g.columns.size>=2} &&
            (it.undo+it.redo).all {s->s.settings!=null && s.grids!=null}){"EDITOR_RECOVERY_CORRUPT"}
    }

internal fun decodeMatrixRecovery(raw:String):MatrixEditEnvelope =
    requireNotNull(editorJson.fromJson(raw,MatrixEditEnvelope::class.java)).also {
        require(!it.draftId.isNullOrBlank() && !it.pageFingerprint.isNullOrBlank() && it.region!=null && it.rows!=null &&
            it.columns!=null && it.codes!=null && it.labels!=null && it.rowWeights!=null && it.columnWeights!=null && it.style!=null){"EDITOR_RECOVERY_CORRUPT"}
    }

internal fun matrixRecoveryHasInput(value:MatrixEditEnvelope)=
    listOf(value.rows,value.columns,value.codes,value.labels,value.rowWeights,value.columnWeights).any {it.isNotBlank()} || value.style!=MarkStyle.MANUAL_ONLY

private fun matrixBoundaries(text:String,count:Int):List<Float>? {
    if(count<=0)return null
    val weights=if(text.isBlank())List(count){1f}else text.split(',').map {it.trim().toFloatOrNull() ?: return null}
    if(weights.size!=count || weights.any {!it.isFinite() || it<=0})return null
    val total=weights.sum();if(!total.isFinite())return null
    var sum=0f
    return listOf(0f)+weights.map {sum+=it;sum/total}
}

// Reconstructing a generated cell can differ by a few Float ULPs from its saved decimal
// coordinates. This tolerance is only for detecting an already-applied recovery envelope;
// it never changes stored geometry, codes or recognition parameters (0.01 px at 10,000 px).
private const val RECOVERY_GEOMETRY_EPSILON=1e-6f
private fun recoveryCoordinateEquals(a:Float,b:Float)=a.isFinite() && b.isFinite() && abs(a-b)<=RECOVERY_GEOMETRY_EPSILON
private fun recoveryBoundariesEqual(a:List<Float>,b:List<Float>)=a.size==b.size && a.zip(b).all {(x,y)->recoveryCoordinateEquals(x,y)}
private fun recoveryRectEquals(a:NormalizedRect?,b:NormalizedRect)=a!=null && a.isValid() && b.isValid() &&
    recoveryCoordinateEquals(a.left,b.left) && recoveryCoordinateEquals(a.top,b.top) &&
    recoveryCoordinateEquals(a.right,b.right) && recoveryCoordinateEquals(a.bottom,b.bottom)

/** A crash after Generate can retain an envelope whose exact matrix is already saved. */
internal fun matrixRecoveryAlreadyApplied(value:MatrixEditEnvelope,draft:TemplateDraft,pageId:String):Boolean {
    val rows=value.rows.lines().filter {it.isNotBlank()};val columns=value.columns.lines().filter {it.isNotBlank()}
    val codes=if(value.codes.isBlank())columns.indices.map {(it+1).toString()}else value.codes.lines().map {it.trim()}.filter {it.isNotBlank()}
    val labels=if(value.labels.isBlank())rows.indices.map {(it+1).toString()}else value.labels.lines().map {it.trim()}.filter {it.isNotBlank()}
    val rb=matrixBoundaries(value.rowWeights,rows.size) ?: return false
    val cb=matrixBoundaries(value.columnWeights,columns.size) ?: return false
    if(rows.size !in 1..20 || columns.size !in 2..20 || codes.size!=columns.size || labels.size!=rows.size)return false
    return draft.matrices.filter {it.pageId==pageId && recoveryRectEquals(it.region,value.region) &&
        recoveryBoundariesEqual(it.rowBoundaries,rb) && recoveryBoundariesEqual(it.columnBoundaries,cb)}.any {matrix->
        val questions=matrix.questionIds.mapNotNull {id->draft.questions.firstOrNull {it.id==id}}
        questions.size==rows.size && questions.withIndex().all {(r,q)->
            val bounds=NormalizedRect(value.region.left,value.region.top+(value.region.bottom-value.region.top)*rb[r],
                value.region.right,value.region.top+(value.region.bottom-value.region.top)*rb[r+1])
            q.title==rows[r] && q.label==labels[r] && q.type==QuestionType.SINGLE_CHOICE && recoveryRectEquals(q.region,bounds) && recoveryRectEquals(q.answerRegion,bounds) &&
                q.options.size==columns.size && q.options.withIndex().all {(c,o)->o.title==columns[c] && o.code==codes[c] &&
                    recoveryRectEquals(o.region,NormalizedRect(value.region.left+(value.region.right-value.region.left)*cb[c],bounds.top,
                        value.region.left+(value.region.right-value.region.left)*cb[c+1],bounds.bottom))} &&
                q.recognition?.let {it.style==value.style && it.enabled==(value.style!=MarkStyle.MANUAL_ONLY)}==true
        }
    }
}

internal fun calibrationRecoveryHasChanges(envelope:CalibrationEnvelope,questions:List<QuestionDefinition>,matrix:MatrixDefinition?):Boolean {
    if(envelope.draft.settings.size!=questions.size || envelope.draft.settings.associateBy {it.questionId}!=questions.associate {it.id to calibrationSettings(it)})return true
    val grid=matrix?.let {envelope.grids[it.id]}
    return grid!=null && (grid.region!=matrix.region || grid.rows!=matrix.rowBoundaries || grid.columns!=matrix.columnBoundaries)
}

/** Reads current owners, including buffers whose editor was unmounted by a narrow-layout tab. */
internal suspend fun Controller.templateRecoveryIssues(draft:TemplateDraft):List<TemplateRecoveryIssue> {
    val issues=mutableListOf<TemplateRecoveryIssue>()
    for(q in draft.questions) {
        val session="question:${draft.id}:${q.id}"
        val raw=loadEditorField(session,"envelope") ?: continue
        val pending=try {decodeQuestionRecovery(raw).value!=q}catch(failure:CancellationException){throw failure}catch(_:Exception){true}
        if(pending)issues+=TemplateRecoveryIssue(session,q.pageId,q.id)
    }
    for(group in draft.questions.groupBy {it.matrixId ?: it.id}.values) {
        val q=group.first();val groupId=q.matrixId ?: q.id;val session="initial-calibration:${draft.id}:$groupId"
        val raw=loadEditorField(session,"envelope") ?: continue
        val pending=try {calibrationRecoveryHasChanges(decodeCalibrationRecovery(raw),group,draft.matrices.firstOrNull {it.id==q.matrixId})}
            catch(failure:CancellationException){throw failure}catch(_:Exception){true}
        if(pending)issues+=TemplateRecoveryIssue(session,q.pageId,q.id)
    }
    for(page in draft.pages) {
        val session="matrix:${draft.id}:${page.id}"
        val raw=loadEditorField(session,"envelope") ?: continue
        val pending=try {val envelope=decodeMatrixRecovery(raw);matrixRecoveryHasInput(envelope) && !matrixRecoveryAlreadyApplied(envelope,draft,page.id)}
            catch(failure:CancellationException){throw failure}catch(_:Exception){true}
        if(pending)issues+=TemplateRecoveryIssue(session,page.id,matrix=true)
    }
    return issues
}

/** Confirm invokes this after awaiting the active editor and reloading the latest draft. */
internal suspend fun Controller.requireTemplateRecoveryClean(draft:TemplateDraft) {
    flushEdits()
    val pending=templateRecoveryIssues(draft).firstOrNull() ?: return
    updateProjectUi(draft.projectId) {it.copy(templatePageId=pending.pageId,templateQuestionId=pending.questionId,
        templateInspectorTab=if(pending.questionId!=null)1 else 0)}
    throw IllegalStateException("EDITOR_RECOVERY_PENDING")
}
