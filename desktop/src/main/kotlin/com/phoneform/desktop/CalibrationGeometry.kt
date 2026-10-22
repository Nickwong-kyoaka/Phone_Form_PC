package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import kotlin.math.abs

internal enum class CalibrationTool { PAN, GROUP, DIVIDERS, OPTION, CORE }
internal data class CalibrationCell(val questionId: String, val optionId: String, val row: Int, val column: Int)
internal data class CalibrationGrid(val region: NormalizedRect, val rows: List<Float>, val columns: List<Float>, val cells: List<CalibrationCell>)
internal data class CalibrationBinding(val ownerId: String, val templateVersionId: String, val pageFingerprints: Map<String,String>)
internal data class CalibrationSnapshot(val settings:List<QuestionRecognitionSettings>, val grids:Map<String,CalibrationGrid>)
internal data class CalibrationEnvelope(val binding: CalibrationBinding, val draft: RecognitionProfileDraft,
    val grids:Map<String,CalibrationGrid> = emptyMap(),
    val undo: List<CalibrationSnapshot> = emptyList(), val redo: List<CalibrationSnapshot> = emptyList(),
    val publishedProfileId:String? = null)
internal data class QuestionEditEnvelope(val draftId: String, val pageFingerprint: String,
    val original: QuestionDefinition, val value: QuestionDefinition)
internal data class MatrixEditEnvelope(val draftId:String,val pageFingerprint:String,val region:NormalizedRect,
    val rows:String="",val columns:String="",val codes:String="",val labels:String="",
    val rowWeights:String="",val columnWeights:String="",val style:MarkStyle=MarkStyle.MANUAL_ONLY)

internal fun questionEnvelopeMatches(envelope:QuestionEditEnvelope,draftId:String,pageFingerprint:String,question:QuestionDefinition)=
    envelope.draftId==draftId && envelope.pageFingerprint==pageFingerprint && envelope.original==question

internal fun calibrationEnvelopeMatches(envelope:CalibrationEnvelope,binding:CalibrationBinding)=
    envelope.binding==binding && envelope.draft.id==binding.ownerId && envelope.draft.templateVersionId==binding.templateVersionId &&
        envelope.draft.pageFingerprints==binding.pageFingerprints

internal fun usableCandidate(observation:FieldObservation,job:RecognitionJob?):Boolean {
    val ref=observation.candidate?.candidateRef ?: return false
    return job!=null && job.status==RecognitionJobStatus.READY && job.provenance==RecognitionProvenance.VERSIONED &&
        observation.provenance==RecognitionProvenance.VERSIONED && observation.profileId==job.profileId &&
        observation.evidenceHash.isNotBlank() && ref==CandidateRef(job.id,job.profileId ?: return false,observation.targetKey,observation.evidenceHash)
}

internal fun arrangeCalibrationOptions(settings:QuestionRecognitionSettings,region:NormalizedRect,vertical:Boolean):QuestionRecognitionSettings =
    settings.copy(options=settings.options.mapIndexed { index,o->
        val count=settings.options.size.coerceAtLeast(1)
        val r=if(vertical)NormalizedRect(region.left,region.top+(region.bottom-region.top)*index/count,region.right,region.top+(region.bottom-region.top)*(index+1)/count)
            else NormalizedRect(region.left+(region.right-region.left)*index/count,region.top,region.left+(region.right-region.left)*(index+1)/count,region.bottom)
        o.copy(region=r,printedCore=null,coreConfirmed=false)
    })

internal fun calibrationSettings(question: QuestionDefinition, current: QuestionRecognitionSettings? = question.recognition): QuestionRecognitionSettings {
    val region = question.answerRegion ?: question.region ?: NormalizedRect(.1f,.1f,.9f,.9f)
    return (current ?: QuestionRecognitionSettings(question.id)).copy(options=question.options.mapIndexed { index, option ->
        current?.options?.firstOrNull { it.optionId==option.id } ?: OptionMarkRegion(option.id,option.region ?: NormalizedRect(
            region.left+(region.right-region.left)*index/question.options.size.coerceAtLeast(1),region.top,
            region.left+(region.right-region.left)*(index+1)/question.options.size.coerceAtLeast(1),region.bottom))
    }, answerRegion=current?.answerRegion ?: question.answerRegion)
}

internal fun unionRegions(regions: List<NormalizedRect>): NormalizedRect? = regions.filter { it.isValid() }.takeIf { it.isNotEmpty() }?.let {
    NormalizedRect(it.minOf { r->r.left },it.minOf { r->r.top },it.maxOf { r->r.right },it.maxOf { r->r.bottom })
}

internal fun calibrationGrid(questions: List<QuestionDefinition>, settings: List<QuestionRecognitionSettings>, matrix: MatrixDefinition? = null): CalibrationGrid? {
    if(questions.isEmpty() || questions.any { it.options.isEmpty() }) return null
    val ordered=if(matrix!=null) matrix.questionIds.mapNotNull { id->questions.firstOrNull { it.id==id } } else questions
    val regions=ordered.flatMap { q->calibrationSettings(q,settings.firstOrNull { it.questionId==q.id }).options.map { it.region } }
    val bounds=unionRegions(regions) ?: return null
    val first=calibrationSettings(ordered.first(),settings.firstOrNull { it.questionId==ordered.first().id }).options
    val vertical=matrix==null && first.size>1 && (first.maxOf { (it.region.top+it.region.bottom)/2 }-first.minOf { (it.region.top+it.region.bottom)/2 }) >
        (first.maxOf { (it.region.left+it.region.right)/2 }-first.minOf { (it.region.left+it.region.right)/2 })
    val cells=if(vertical) ordered.first().options.mapIndexed { r,o->CalibrationCell(ordered.first().id,o.id,r,0) }
        else ordered.flatMapIndexed { r,q->q.options.mapIndexed { c,o->CalibrationCell(q.id,o.id,r,c) } }
    val rowCount=cells.maxOf { it.row }+1; val colCount=cells.maxOf { it.column }+1
    fun boundaries(count:Int,row:Boolean):List<Float> {
        if(count==1) return listOf(0f,1f)
        val centers=(0 until count).map { index->cells.filter { if(row) it.row==index else it.column==index }.mapNotNull { cell->
            settings.firstOrNull { it.questionId==cell.questionId }?.options?.firstOrNull { it.optionId==cell.optionId }?.region
                ?: ordered.firstOrNull { it.id==cell.questionId }?.options?.firstOrNull { it.id==cell.optionId }?.region
        }.let { rs-> if(rs.isEmpty()) (index+.5f)/count else rs.map { if(row) ((it.top+it.bottom)/2-bounds.top)/(bounds.bottom-bounds.top) else ((it.left+it.right)/2-bounds.left)/(bounds.right-bounds.left) }.average().toFloat() } }
        return listOf(0f)+(1 until count).map { (centers[it-1]+centers[it])/2 }+1f
    }
    return CalibrationGrid(bounds,boundaries(rowCount,true),boundaries(colCount,false),cells)
}

internal fun gridCell(grid:CalibrationGrid,cell:CalibrationCell)=NormalizedRect(
    grid.region.left+(grid.region.right-grid.region.left)*grid.columns[cell.column],
    grid.region.top+(grid.region.bottom-grid.region.top)*grid.rows[cell.row],
    grid.region.left+(grid.region.right-grid.region.left)*grid.columns[cell.column+1],
    grid.region.top+(grid.region.bottom-grid.region.top)*grid.rows[cell.row+1])

internal fun remapRegion(rect:NormalizedRect,from:NormalizedRect,to:NormalizedRect)=NormalizedRect(
    to.left+(rect.left-from.left)/(from.right-from.left)*(to.right-to.left),
    to.top+(rect.top-from.top)/(from.bottom-from.top)*(to.bottom-to.top),
    to.left+(rect.right-from.left)/(from.right-from.left)*(to.right-to.left),
    to.top+(rect.bottom-from.top)/(from.bottom-from.top)*(to.bottom-to.top))

internal fun changeCalibrationGrid(settings:List<QuestionRecognitionSettings>,old:CalibrationGrid,next:CalibrationGrid,moveCores:Boolean):List<QuestionRecognitionSettings> = settings.map { s->
    s.copy(options=s.options.map optionMap@ { option->
        val cell=old.cells.firstOrNull { it.questionId==s.questionId && it.optionId==option.optionId } ?: return@optionMap option
        val region=remapRegion(option.region,gridCell(old,cell),gridCell(next,cell))
        val core=option.printedCore?.let { if(moveCores) remapRegion(it,old.region,next.region) else it }
        option.copy(region=region,printedCore=core,coreConfirmed=!moveCores && option.coreConfirmed && core?.let { RecognitionBindings.contains(region,it) }==true)
    })
}

internal fun moveGrid(grid:CalibrationGrid,dx:Float,dy:Float):CalibrationGrid {
    val x=dx.coerceIn(-grid.region.left,1-grid.region.right);val y=dy.coerceIn(-grid.region.top,1-grid.region.bottom)
    return grid.copy(region=grid.region.let { NormalizedRect(it.left+x,it.top+y,it.right+x,it.bottom+y) })
}

internal fun changeDivider(grid:CalibrationGrid,row:Boolean,index:Int,value:Float):CalibrationGrid {
    val values=if(row)grid.rows else grid.columns
    if(index<=0 || index>=values.lastIndex) return grid
    val previous=values[index-1];val following=values[index+1]
    if(!value.isFinite() || !previous.isFinite() || !following.isFinite() || previous>=following)return grid
    val gap=minOf(.003f,(following-previous)/3)
    val minimum=maxOf(previous+gap,Math.nextUp(previous))
    val maximum=minOf(following-gap,Math.nextDown(following))
    if(minimum>maximum)return grid
    val next=values.mapIndexed { i,v->if(i==index)value.coerceIn(minimum,maximum) else v }
    return if(row)grid.copy(rows=next) else grid.copy(columns=next)
}

internal fun CalibrationEnvelope.edit(settings:List<QuestionRecognitionSettings>,nextGrids:Map<String,CalibrationGrid> = grids):CalibrationEnvelope =
    if(draft.settings==settings && grids==nextGrids)this else copy(draft=draft.copy(settings=settings),grids=nextGrids,undo=(undo+CalibrationSnapshot(draft.settings,grids)).takeLast(30),redo=emptyList())

internal fun CalibrationEnvelope.undoEdit():CalibrationEnvelope = if(undo.isEmpty())this else copy(
    draft=draft.copy(settings=undo.last().settings),grids=undo.last().grids,undo=undo.dropLast(1),redo=(redo+CalibrationSnapshot(draft.settings,grids)).takeLast(30))
internal fun CalibrationEnvelope.redoEdit():CalibrationEnvelope = if(redo.isEmpty())this else copy(
    draft=draft.copy(settings=redo.last().settings),grids=redo.last().grids,undo=(undo+CalibrationSnapshot(draft.settings,grids)).takeLast(30),redo=redo.dropLast(1))

internal fun calibrationStyleLabel(style:MarkStyle,language:UiLanguage)=when(style) {
    MarkStyle.CHECKBOX_TICK_CROSS->tr(language,"方框勾選／交叉","方框勾选／交叉","Checkbox tick / cross")
    MarkStyle.CIRCLED_PRINTED_NUMBER->tr(language,"圈選印刷數字","圈选印刷数字","Circled printed number")
    MarkStyle.MANUAL_ONLY->tr(language,"只作人工輸入","仅人工输入","Manual only")
}

internal fun optionDecisionLabel(value:OptionDecision,language:UiLanguage)=when(value) {
    OptionDecision.SELECTED->tr(language,"有標記","有标记","Marked")
    OptionDecision.EMPTY->tr(language,"無標記","无标记","Unmarked")
    OptionDecision.AMBIGUOUS->tr(language,"不確定","不确定","Ambiguous")
    OptionDecision.UNSUPPORTED->tr(language,"未處理","未处理","Not processed")
}

/** A core is stored in template-page coordinates; display only through the bound alignment. */
internal fun projectEvidenceRect(rect:NormalizedRect,matrix:List<Double>):NormalizedRect? {
    if(matrix.size!=9 || matrix.any {!it.isFinite()} || !rect.isValid())return null
    val points=listOf(rect.left to rect.top,rect.right to rect.top,rect.right to rect.bottom,rect.left to rect.bottom).map { (x,y)->
        val z=matrix[6]*x+matrix[7]*y+matrix[8]
        if(!z.isFinite() || abs(z)<1E-12)return null
        val px=((matrix[0]*x+matrix[1]*y+matrix[2])/z).toFloat();val py=((matrix[3]*x+matrix[4]*y+matrix[5])/z).toFloat()
        if(!px.isFinite() || !py.isFinite())return null
        px to py
    }
    return NormalizedRect(points.minOf {it.first}.coerceIn(0f,1f),points.minOf {it.second}.coerceIn(0f,1f),
        points.maxOf {it.first}.coerceIn(0f,1f),points.maxOf {it.second}.coerceIn(0f,1f)).takeIf {it.isValid()}
}
