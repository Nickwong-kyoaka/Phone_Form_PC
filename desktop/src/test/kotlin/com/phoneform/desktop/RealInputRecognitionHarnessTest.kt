package com.phoneform.desktop

import com.google.gson.*
import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import com.phoneform.desktop.vision.DesktopVision
import com.phoneform.desktop.vision.MarkParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Explicit local diagnostic tool, not an accuracy assertion or a default regression fixture.
 * This reader accepts geometry only. Expected answers belong to a separate comparison process.
 * No answer save, review confirmation, workbook export or algorithm tuning occurs here. */
class RealInputRecognitionHarnessTest {
    private val json=GsonBuilder().serializeNulls().setPrettyPrinting().create()

    @Test(timeout=1_800_000) fun runOnlyWhenExplicitGeometryManifestAndNewLocalOutputAreProvided():Unit=runBlocking {
        val manifestSetting=System.getenv("PHONEFORM_REAL_INPUT_MANIFEST")?.takeIf {it.isNotBlank()}
        val outputSetting=System.getenv("PHONEFORM_REAL_INPUT_RUN_DIR")?.takeIf {it.isNotBlank()}
        if(manifestSetting==null && outputSetting==null) {
            println("REAL_INPUT_HARNESS_NOT_REQUESTED: set both explicit geometry manifest and new local output directory")
            return@runBlocking
        }
        require(manifestSetting!=null && outputSetting!=null) {"Both real-input harness environment variables are required"}
        val manifestPath=Path.of(manifestSetting).toAbsolutePath().normalize()
        val output=Path.of(outputSetting).toAbsolutePath().normalize()
        val localRoot=Path.of("../.local").toAbsolutePath().normalize()
        require(output.startsWith(localRoot) && output!=localRoot && !Files.exists(output)) {"Output must be a new directory inside PC_app/.local"}
        require(Files.isRegularFile(manifestPath)) {"Geometry manifest is unavailable"}
        val manifestBytes=Files.readAllBytes(manifestPath)
        val manifest=JsonParser.parseString(String(manifestBytes,Charsets.UTF_8).removePrefix("\uFEFF")).asJsonObject
        require(!manifest.has("truth") && !manifest.has("expectedAnswers")) {"Expected answers must be kept outside the engine geometry manifest"}
        val documents=manifest.getAsJsonArray("documents") ?: error("Geometry manifest documents are required")
        require(documents.size()>0)
        Files.createDirectories(output)
        Files.write(output.resolve("geometry-used.json"),manifestBytes)
        val results=JsonArray()
        val report=JsonObject().apply {
            addProperty("harnessVersion",1);addProperty("appVersion",System.getProperty("phoneform.app.version","0.3.2-dev"))
            addProperty("engineVersion",DesktopVision.VERSION);addProperty("parametersVersion",MarkParameters.VERSION)
            addProperty("parametersFingerprint",MarkParameters.fingerprint)
            addProperty("geometryManifestSha256",hash(manifestBytes))
            addProperty("automaticUnconfiguredCandidatePolicy","MANUAL_ONLY: detection does not invent a marking style or answer candidate")
            addProperty("conditions","Actual encrypted production PDF import at 144 DPI; raw PDF orientation; same filled PDF used as template and submission. Conditional manual calibration, not alignment generalization. No expected answers, human answers, blank reference, or Excel.")
            add("documents",results)
        }
        write(output.resolve("engine-freeze.json"),report.deepCopy().apply {remove("documents")})
        val automatic=JsonArray()
        try {
            for((documentIndex,element) in documents.withIndex()) {
                val spec=element.asJsonObject
                rejectTruthKeys(spec)
                val id=spec.text("id")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {"Document IDs must be stable, non-identifying tokens"}
                val source=Path.of(spec.text("source")).toAbsolutePath().normalize()
                require(Files.isRegularFile(source) && source.fileName.toString().endsWith(".pdf",true)) {"A PDF source is required"}
                val sourceHash=hashFile(source)
                require(sourceHash.equals(spec.text("sha256"),true)) {"PDF SHA changed before the run"}
                val language=QuestionnaireLanguage.valueOf(spec.text("language"))
                val item=JsonObject().apply {addProperty("id",id);addProperty("pdfSha256",sourceHash)}
                results.add(item)
                val workspacePath=output.resolve("workspace-$id")
                Controller(SecureWorkspace.open(workspacePath),Path.of(System.getProperty("phoneform.model.dir","../runtime/models")),startWorkers=false).use {controller->
                    val runtime=controller.vision.initialize()
                    item.add("runtime",json.toJsonTree(runtime))
                    require(runtime.openCvAvailable && runtime.ocrAvailable) {"Production OCR/CV runtime is unavailable"}
                    controller.createProject("Local recognition diagnostic $id",null,language,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)
                    controller.refresh()
                    val count=controller.pdfCount(source.toFile())
                    item.addProperty("pageCount",count)
                    controller.importTemplate(source.toFile(),(0 until count).toList())
                    val original=controller.templateDraft()
                    val autoPages=JsonArray()
                    for((pageIndex,page) in original.pages.withIndex()) {
                        val auto=JsonObject().apply {addProperty("page",pageIndex);addProperty("width",page.width);addProperty("height",page.height)}
                        val image=controller.loadImage(page)
                        try {auto.add("questions",json.toJsonTree(controller.vision.analyzeTemplate(page,image,language)))}
                        catch(failure:Exception) {auto.addProperty("error",Controller.errorCode(failure));auto.add("questions",JsonArray())}
                        finally {image.flush()}
                        autoPages.add(auto)
                        println("REAL_INPUT_AUTO_PAGE_COMPLETE:${documentIndex+1}:${pageIndex+1}")
                    }
                    item.add("automatic",autoPages)
                    automatic.add(JsonObject().apply {addProperty("id",id);addProperty("pdfSha256",sourceHash);add("pages",autoPages)})
                    // Freeze detection output before reading/calibrating any manually defined question.
                    write(output.resolve("automatic.json"),automatic)
                    if(manifest.get("automaticOnly")?.asBoolean==true) {
                        item.add("calibrated",JsonObject().apply {addProperty("notRequested",true)})
                        return@use
                    }
                    val configured=parseGeometry(spec,original)
                    val calibrated=JsonObject()
                    item.add("calibrated",calibrated)
                    calibrated.add("questions",json.toJsonTree(configured.questions))
                    calibrated.add("questionCategories",json.toJsonTree(configured.categories))
                    calibrated.addProperty("geometryOnly",true)
                    try {
                        val saved=controller.saveTemplate(original.copy(
                            pages=original.pages.map {page->page.copy(noExtraction=configured.questions.none {it.pageId==page.id})},
                            questions=configured.questions,matrices=configured.matrices))
                        var passes=drain(controller,count*4+20)
                        controller.repositories.imageProcessing.keepAllWarnings(
                            PageProcessingOwner(saved.projectId,PageProcessingOwnerKind.TEMPLATE_DRAFT,saved.id,saved.revision),
                            saved.pages.associate {it.id to PageProcessingFingerprint.of(it)},"Explicit local diagnostic image quality acknowledgement")
                        controller.confirmTemplate();controller.refresh()
                        val template=requireNotNull(controller.repositories.templates.observeConfirmed(saved.projectId).first())
                        val profile=requireNotNull(controller.repositories.intelligence.getRecognitionProfile(template.id))
                        calibrated.add("profile",json.toJsonTree(profile));calibrated.addProperty("profileFingerprint",hash(json.toJson(profile).toByteArray(Charsets.UTF_8)))
                        controller.importSubmission(source.toFile(),(0 until count).toList(),template.document.pages.map {it.id})
                        passes+=drain(controller,count*4+20)
                        val draft=controller.repositories.submissions.observeDrafts(saved.projectId).first().single()
                        controller.repositories.imageProcessing.keepAllWarnings(
                            PageProcessingOwner(saved.projectId,PageProcessingOwnerKind.SUBMISSION_DRAFT,draft.id,draft.editRevision),
                            draft.pages.associate {it.image.id to PageProcessingFingerprint.of(it.image)},"Explicit local diagnostic image quality acknowledgement")
                        val revision=controller.repositories.submissions.submitSubmission(draft.id,draft.editRevision,SubmissionConfirmation(allowMissingPages=true,duplicatePageSourcesConfirmed=true))
                        passes+=drain(controller,count*6+30)
                        val review=requireNotNull(controller.repositories.intelligence.getReview(draft.submissionId))
                        calibrated.addProperty("submissionRevisionId",revision.id);calibrated.add("job",json.toJsonTree(review.job))
                        calibrated.add("answers",json.toJsonTree(review.answers));calibrated.add("confirmation",json.toJsonTree(review.confirmation))
                        calibrated.addProperty("workerPasses",passes)
                        assertNull("The recognition harness must not create human answers",review.answers)
                        assertNull("The recognition harness must not confirm a questionnaire",review.confirmation)
                    }catch(failure:Exception) {calibrated.addProperty("error",Controller.errorCode(failure))}
                }
                require(hashFile(source)==sourceHash) {"PDF SHA changed during the run"}
                write(output.resolve("raw-report.json"),report)
                println("REAL_INPUT_DOCUMENT_COMPLETE:${documentIndex+1}")
            }
        }finally {write(output.resolve("raw-report.json"),report)}
        Files.writeString(output.resolve("raw-report.sha256"),hashFile(output.resolve("raw-report.json"))+"\n")
        Unit
    }

    private suspend fun drain(controller:Controller,maxPasses:Int):Int {
        var passes=0
        while(controller.runWorkerPass()) {passes++;check(passes<=maxPasses){"Worker did not reach a durable terminal state"}}
        return passes
    }
    private data class Geometry(val questions:List<QuestionDefinition>,val matrices:List<MatrixDefinition>,val categories:Map<String,String>)
    private fun parseGeometry(document:JsonObject,draft:TemplateDraft):Geometry {
        val definitions=document.getAsJsonArray("questions") ?: error("Geometry questions are required")
        val categories=linkedMapOf<String,String>()
        val groups=linkedMapOf<String,MutableList<QuestionDefinition>>()
        val questions=definitions.mapIndexed {position,value->
            val q=value.asJsonObject;rejectTruthKeys(q)
            val page=draft.pages[q.get("page").asInt]
            val id=q.text("id");val type=QuestionType.valueOf(q.text("type"))
            val category=q.optionalText("category") ?: type.name
            categories[id]=category
            val style=q.optionalText("style")?.let(MarkStyle::valueOf) ?: MarkStyle.MANUAL_ONLY
            val answer=q.get("answerRegion")?.takeUnless {it.isJsonNull}?.let(::rectangle)
            val optionSpecs=q.getAsJsonArray("options")?.map {it.asJsonObject}.orEmpty()
            optionSpecs.forEach(::rejectTruthKeys)
            val options=optionSpecs.map {o->OptionDefinition(o.text("id"),o.optionalText("title") ?: o.text("code"),o.text("code"),rectangle(o.get("region")))}
            val choice=type in listOf(QuestionType.SINGLE_CHOICE,QuestionType.MULTIPLE_CHOICE)
            val enabled=type!=QuestionType.TEXT && (!choice || style!=MarkStyle.MANUAL_ONLY)
            val regions=optionSpecs.map {o->
                val core=o.get("core")?.takeUnless {it.isJsonNull}?.let(::rectangle)
                require(!enabled || style!=MarkStyle.CIRCLED_PRINTED_NUMBER || core!=null) {"A printed-number core is required for each enabled circle option"}
                OptionMarkRegion(o.text("id"),rectangle(o.get("region")),core,core!=null)
            }
            val matrix=if(category=="MATRIX") "${page.position}:${q.optionalText("matrixGroup") ?: id}" else null
            QuestionDefinition(id,page.id,position,q.optionalText("label") ?: id,q.optionalText("title") ?: id,type,
                rectangle(q.get("region")),answer,options,matrix,
                QuestionRecognitionSettings(id,enabled,style,regions,answer)).also {if(matrix!=null)groups.getOrPut(matrix){mutableListOf()}.add(it)}
        }
        require(questions.isNotEmpty() && questions.map {it.id}.distinct().size==questions.size) {"Question IDs must be unique"}
        val matrices=groups.map { (id,rows)->
            val columns=rows.first().options.size
            require(columns>=2 && rows.all {it.options.size==columns}) {"Matrix rows must have the same option count"}
            val regions=rows.map {requireNotNull(it.region)}
            MatrixDefinition(id,rows.first().pageId,NormalizedRect(regions.minOf {it.left},regions.minOf {it.top},regions.maxOf {it.right},regions.maxOf {it.bottom}),
                (0..rows.size).map {it.toFloat()/rows.size},(0..columns).map {it.toFloat()/columns},rows.map {it.id})
        }
        return Geometry(questions,matrices,categories)
    }
    private fun rectangle(value:JsonElement?):NormalizedRect {
        val a=requireNotNull(value).asJsonArray;require(a.size()==4)
        return NormalizedRect(a[0].asFloat,a[1].asFloat,a[2].asFloat,a[3].asFloat).also {require(it.isValid()){"Invalid normalized geometry"}}
    }
    private fun JsonObject.text(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asString?.takeIf {it.isNotBlank()} ?: error("Geometry field $key is required")
    private fun JsonObject.optionalText(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asString
    private fun rejectTruthKeys(value:JsonObject) {require(value.keySet().none {it in setOf("expected","expectedAnswer","expectedAnswers","truth","selected","selectedOptionIds")}) {"Expected answers must not enter recognition geometry"}}
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun hashFile(path:Path):String {
        val digest=MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use {input->val buffer=ByteArray(65536);while(true){val size=input.read(buffer);if(size<0)break;digest.update(buffer,0,size)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun write(path:Path,value:JsonElement)=Files.writeString(path,json.toJson(value))
}
