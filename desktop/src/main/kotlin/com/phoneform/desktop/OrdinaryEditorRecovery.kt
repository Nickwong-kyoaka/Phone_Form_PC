package com.phoneform.desktop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.phoneform.ai.core.model.*

/** Read busy immediately, even if the last rendered control was still enabled. */
internal fun <T:Any> Controller.editIfAvailable(holder:RecoverableEditorState<T>,change:(T)->T) {
    if(state.value.busy)return
    holder.requireReady()
    holder.value=change(holder.value)
}

internal fun currentReviewWorkspace(expected:ReviewWorkspace,loaded:ReviewWorkspace?,requestedReload:Int,loadedReload:Int,
    loading:Boolean,projectId:String?,submissionId:String?,workspaceOpen:Boolean):Boolean = !loading && workspaceOpen &&
    requestedReload==loadedReload && projectId==expected.project.project.id && submissionId==expected.submission.id &&
    loaded!=null && loaded.submission.id==expected.submission.id && loaded.revision.id==expected.revision.id &&
    (loaded.answers?.version ?: 0)==(expected.answers?.version ?: 0)

/** The callback reads live mutable state, so saving Q1 invalidates old Q2 handlers immediately. */
internal class ReviewEditGate(private val current:()->Boolean) {
    val ready get()=current()
    fun requireReady(){check(ready){"REVIEW_RELOADING"}}
    fun run(action:()->Unit){if(ready)action()}
    fun <T:Any> edit(controller:Controller,holder:RecoverableEditorState<T>,change:(T)->T){if(ready)controller.editIfAvailable(holder,change)}
}

internal class CorruptAnswerRecoveryMarker(val retainedRaw:String,cause:Exception):IllegalArgumentException("EDITOR_RECOVERY_CORRUPT",cause)
internal fun decodeAnswerRecoveryMarker(raw:String):List<AnswerRecoveryBlock> = try {
    JsonParser.parseString(raw).asJsonArray.map {element->val value=element.asJsonObject
        val target=value.string("targetKey")
        require(target.isNotBlank()){"EDITOR_RECOVERY_CORRUPT"}
        val source=value.required("sourceVersion")
        require(source.isJsonPrimitive && source.asJsonPrimitive.isNumber){"EDITOR_RECOVERY_CORRUPT"}
        val version=requireNotNull(source.asString.toIntOrNull()){"EDITOR_RECOVERY_CORRUPT"}
        require(version>=0){"EDITOR_RECOVERY_CORRUPT"}
        AnswerRecoveryBlock(target,version)
    }.distinct()
}catch(error:Exception){throw CorruptAnswerRecoveryMarker(raw,error)}

internal suspend fun Controller.loadAnswerRecoveryBlocks(revisionId:String):List<AnswerRecoveryBlock> =
    loadEditorField(answerRecoveryMarker(revisionId),"blocked")?.let(::decodeAnswerRecoveryMarker).orEmpty()

/** A corrupt index cannot identify other sessions reliably: explicitly discard only the index. */
internal suspend fun Controller.discardCorruptAnswerRecoveryMarker(revisionId:String,expectedRaw:String) {
    val session=answerRecoveryMarker(revisionId)
    require(loadEditorField(session,"blocked")==expectedRaw){"STALE_EDIT_RELOAD"}
    try {decodeAnswerRecoveryMarker(expectedRaw);error("RECOVERY_MARKER_IS_VALID")}
    catch(_:CorruptAnswerRecoveryMarker){/* Only an explicitly reviewed, still-identical corrupt index is discarded. */}
    clearEditor(session)
}

private fun JsonObject.required(name:String)=get(name)?.takeUnless {it.isJsonNull}
    ?: throw IllegalArgumentException("EDITOR_RECOVERY_CORRUPT")
private fun JsonObject.string(name:String)=required(name).also {require(it.isJsonPrimitive && it.asJsonPrimitive.isString){"EDITOR_RECOVERY_CORRUPT"}}.asString
private fun JsonObject.boolean(name:String)=required(name).also {require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean){"EDITOR_RECOVERY_CORRUPT"}}.asBoolean

internal fun decodeAnswerRecovery(raw:String,targetKey:String):AnswerValue {
    val value=JsonParser.parseString(raw).asJsonObject
    require(value.string("targetKey")==targetKey){"RECOVERY_TARGET_CHANGED"}
    AnswerState.valueOf(value.string("state"));AnswerSource.valueOf(value.string("source"))
    value.string("text");value.boolean("humanEdited")
    val options=value.required("optionIds");require(options.isJsonArray && options.asJsonArray.all {it.isJsonPrimitive && it.asJsonPrimitive.isString}){"EDITOR_RECOVERY_CORRUPT"}
    value.get("candidateRef")?.takeUnless {it.isJsonNull}?.asJsonObject?.let {ref->
        listOf("jobId","profileId","targetKey","evidenceHash").forEach {require(ref.string(it).isNotBlank()){"EDITOR_RECOVERY_CORRUPT"}}
        require(ref.string("targetKey")==targetKey){"RECOVERY_TARGET_CHANGED"}
    }
    return requireNotNull(editorJson.fromJson(value,AnswerValue::class.java))
}

internal fun answerEditorValue(holder:RecoverableEditorState<AnswerValue>,targetKey:String):AnswerValue {
    holder.requireReady()
    return holder.value.also {require(it.targetKey==targetKey){"RECOVERY_TARGET_CHANGED"}}.copy(humanEdited=true)
}

internal data class CollectionEditorValues(val paperId:String,val repeatReason:String)
internal fun collectionEditorValues(paperId:RecoverableEditorState<String>,repeatReason:RecoverableEditorState<String>,required:Boolean):CollectionEditorValues {
    EditorRecoveryFields(paperId,repeatReason).requireReady()
    val result=CollectionEditorValues(paperId.value,repeatReason.value)
    require(!required || result.paperId.isNotBlank()){"PAPER_ID_REQUIRED"}
    return result
}

internal fun decodeOutputPolicies(raw:String,projectId:String):List<OutputFieldPolicy> {
    val values=JsonParser.parseString(raw).asJsonArray
    values.forEach {element->val value=element.asJsonObject
        require(value.string("projectId")==projectId){"RECOVERY_TARGET_CHANGED"}
        require(value.string("targetKey").isNotBlank()){"EDITOR_RECOVERY_CORRUPT"}
        value.boolean("personalIdentifier");value.boolean("exportIncluded")
    }
    val result=editorJson.fromJson(values,Array<OutputFieldPolicy>::class.java).toList()
    require(result.map {it.targetKey}.distinct().size==result.size){"EDITOR_RECOVERY_CORRUPT"}
    return result
}

internal fun decodeOutputLayout(raw:String):List<OutputColumnLayout> {
    val values=JsonParser.parseString(raw).asJsonArray
    values.forEach {element->val value=element.asJsonObject
        require(value.string("key").isNotBlank()){"EDITOR_RECOVERY_CORRUPT"}
        val position=value.required("position")
        require(position.isJsonPrimitive && position.asJsonPrimitive.isNumber && position.asString.toIntOrNull()!=null){"EDITOR_RECOVERY_CORRUPT"}
        value.get("titleOverride")?.takeUnless {it.isJsonNull}?.let {require(it.isJsonPrimitive && it.asJsonPrimitive.isString){"EDITOR_RECOVERY_CORRUPT"}}
    }
    val result=editorJson.fromJson(values,Array<OutputColumnLayout>::class.java).toList()
    require(result.map {it.key}.distinct().size==result.size){"EDITOR_RECOVERY_CORRUPT"}
    return result
}

internal data class ProjectEditorValues(val name:String,val expected:Int?)
internal fun projectEditorValues(name:RecoverableEditorState<String>,expected:RecoverableEditorState<String>):ProjectEditorValues {
    EditorRecoveryFields(name,expected).requireReady()
    val latestName=name.value.trim();val count=expected.value.trim()
    require(latestName.isNotBlank()){"PROJECT_NAME_REQUIRED"}
    val parsed=count.takeIf {it.isNotEmpty()}?.let {requireNotNull(it.toIntOrNull()){"EXPECTED_COUNT_INVALID"}}
    require(parsed==null || parsed>0){"EXPECTED_COUNT_INVALID"}
    return ProjectEditorValues(latestName,parsed)
}

internal data class OutputEditorValues(val language:OutputHeaderLanguage,val personal:Boolean,val policies:List<OutputFieldPolicy>,val layout:List<OutputColumnLayout>)
internal fun outputEditorValues(language:RecoverableEditorState<OutputHeaderLanguage>,personal:RecoverableEditorState<Boolean>,
    policies:RecoverableEditorState<List<OutputFieldPolicy>>,layout:RecoverableEditorState<List<OutputColumnLayout>>):OutputEditorValues {
    EditorRecoveryFields(language,personal,policies,layout).requireReady()
    return OutputEditorValues(language.value,personal.value,policies.value,layout.value)
}
