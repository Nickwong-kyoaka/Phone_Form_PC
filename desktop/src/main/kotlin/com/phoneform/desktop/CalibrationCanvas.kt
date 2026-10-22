package com.phoneform.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import kotlin.math.abs

private data class CalibrationDrag(val kind:String,val index:Int,val start:Offset,val grid:CalibrationGrid?,
    val settings:List<QuestionRecognitionSettings>,val anchor:Offset)

@Composable internal fun CalibrationCanvas(controller:Controller,page:SamplePage,grid:CalibrationGrid?,
    settings:List<QuestionRecognitionSettings>,selectedOptionId:String?,language:UiLanguage,
    onSelectOption:(String)->Unit,onCommit:(List<QuestionRecognitionSettings>,CalibrationGrid?)->Unit,
    modifier:Modifier=Modifier,enabled:Boolean=true,extraOverlays:List<NormalizedRect> = emptyList(),scalarQuestionId:String?=null) {
    var image by remember(page.id,page.revision) { mutableStateOf<ImageBitmap?>(null) }
    var imageFailed by remember(page.id,page.revision) { mutableStateOf(false) }
    var reload by remember(page.id) { mutableIntStateOf(0) }
    LaunchedEffect(page.id,page.revision,reload) { image=null;imageFailed=false
        try { image=withContext(Dispatchers.IO) { controller.loadImage(page).let { source->try { source.toComposeImageBitmap() } finally { source.flush() } } } }
        catch(e:CancellationException){throw e}catch(_:Exception){imageFailed=true}
    }
    val projectId=controller.state.value.project?.project?.id
    val savedView=remember(projectId,page.id) { controller.ui.value.projects[projectId]?.canvasViews?.get(page.id)?.normalized() ?: CanvasViewState() }
    var zoom by remember(page.id){mutableFloatStateOf(savedView.zoom)}
    var offset by remember(page.id){mutableStateOf(Offset(savedView.offsetX,savedView.offsetY))}
    var tool by remember(page.id){mutableStateOf(CalibrationTool.entries.firstOrNull {it.name==controller.ui.value.projects[projectId]?.recognitionTool} ?: CalibrationTool.GROUP)}
    var drag by remember(page.id){mutableStateOf<CalibrationDrag?>(null)}
    var pendingSettings by remember(page.id){mutableStateOf<List<QuestionRecognitionSettings>?>(null)}
    var pendingGrid by remember(page.id){mutableStateOf<CalibrationGrid?>(null)}
    val latestSettings by rememberUpdatedState(settings);val latestGrid by rememberUpdatedState(grid)
    val latestCommit by rememberUpdatedState(onCommit)
    val hitPx=with(LocalDensity.current) { 12.dp.toPx() }
    fun saveView() { if(projectId!=null)controller.updateProjectUi(projectId){it.copy(canvasViews=it.canvasViews+(page.id to CanvasViewState(zoom,offset.x,offset.y,true,"BASE")))} }
    DisposableEffect(page.id) { onDispose { saveView() } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={zoom=(zoom/1.25f).coerceAtLeast(.5f);saveView()}){Text("−")}
            Text("${(zoom*100).toInt()}%",style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={zoom=(zoom*1.25f).coerceAtMost(8f);saveView()}){Text("+")}
            TextButton(onClick={zoom=1f;offset=Offset.Zero;saveView()}){Text(tr(language,"適合","适合","Fit"))}
            CalibrationTool.entries.forEach { value->TextButton(onClick={tool=value;if(projectId!=null)controller.updateProjectUi(projectId){it.copy(recognitionTool=value.name)}},enabled=enabled || value==CalibrationTool.PAN,
                colors=ButtonDefaults.textButtonColors(contentColor=if(tool==value)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                Text(when(value){CalibrationTool.PAN->tr(language,"平移","平移","Pan");CalibrationTool.GROUP->tr(language,"整組／縮放","整组／缩放","Move / resize");CalibrationTool.DIVIDERS->tr(language,"分隔線","分隔线","Dividers");CalibrationTool.OPTION->tr(language,"選項框","选项框","Option region");CalibrationTool.CORE->if(scalarQuestionId!=null)tr(language,"答案框","答案框","Answer region")else tr(language,"數字核心","数字核心","Printed core")})
            } }
        }
        Text(tr(language,"拖曳整組內部移動、四角縮放；分隔線模式調整行列。核心框需逐項人工確認。","拖动整组内部移动、四角缩放；分隔线模式调整行列。核心框须逐项人工确认。","Drag inside the group to move it, or drag a corner to resize. Dividers adjust rows/columns. Confirm each printed core."),Modifier.padding(horizontal=10.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        val bitmap=image
        if(bitmap==null) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            if(imageFailed) TextButton(onClick={reload++}){Text(tr(language,"影像無法開啟，重試","图像无法打开，重试","Image unavailable · Retry"))} else CircularProgressIndicator(Modifier.size(28.dp))
        } else Canvas(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(page.id,bitmap,zoom,tool,selectedOptionId,enabled,hitPx) {
            fun fit():Pair<Offset,Size> { val scale=minOf(size.width.toFloat()/bitmap.width,size.height.toFloat()/bitmap.height)*zoom
                val s=Size(bitmap.width*scale,bitmap.height*scale)
                return Offset((size.width-s.width)/2+offset.x*size.width,(size.height-s.height)/2+offset.y*size.height) to s }
            fun norm(p:Offset):Offset { val(o,s)=fit();return Offset(((p.x-o.x)/s.width).coerceIn(0f,1f),((p.y-o.y)/s.height).coerceIn(0f,1f)) }
            fun pixel(p:Offset):Offset { val(o,s)=fit();return Offset(o.x+p.x*s.width,o.y+p.y*s.height) }
            fun corners(r:NormalizedRect)=listOf(Offset(r.left,r.top),Offset(r.right,r.top),Offset(r.right,r.bottom),Offset(r.left,r.bottom))
            detectDragGestures(onDragStart={ position->
                val start=norm(position);val g=latestGrid;val current=latestSettings
                pendingSettings=null;pendingGrid=null
                if(tool==CalibrationTool.PAN || !enabled) drag=CalibrationDrag("PAN",0,start,g,current,start)
                else if(tool==CalibrationTool.OPTION) {
                    val option=current.flatMap {it.options}.firstOrNull {it.optionId==selectedOptionId}
                    if(option!=null) {
                        val index=corners(option.region).indexOfFirst {(pixel(it)-position).getDistance()<=hitPx}
                        drag=CalibrationDrag("OPTION",index,start,g,current,if(index>=0)corners(option.region)[(index+2)%4]else start)
                    }
                }
                else if(tool==CalibrationTool.CORE && scalarQuestionId!=null) drag=CalibrationDrag("SCALAR",0,start,g,current,start)
                else if(tool==CalibrationTool.CORE) {
                    val option=current.flatMap { it.options }.firstOrNull { it.optionId==selectedOptionId }
                    if(option!=null) {
                        val index=option.printedCore?.let { r->corners(r).indexOfFirst { (pixel(it)-position).getDistance()<=hitPx } } ?: -1
                        val anchor=if(index>=0)corners(option.printedCore!!)[(index+2)%4] else start
                        drag=CalibrationDrag("CORE",index,start,g,current,anchor)
                    }
                } else if(g!=null) {
                    val corner=corners(g.region).indexOfFirst { (pixel(it)-position).getDistance()<=hitPx }
                    if(tool==CalibrationTool.GROUP) {
                        if(corner>=0)drag=CalibrationDrag("RESIZE",corner,start,g,current,corners(g.region)[(corner+2)%4])
                        else if(start.x in g.region.left..g.region.right && start.y in g.region.top..g.region.bottom)drag=CalibrationDrag("MOVE",0,start,g,current,start)
                    } else {
                        val row=(1 until g.rows.lastIndex).firstOrNull { index->abs(pixel(Offset(g.region.left,g.region.top+(g.region.bottom-g.region.top)*g.rows[index])).y-position.y)<=hitPx && start.x in g.region.left..g.region.right }
                        val col=(1 until g.columns.lastIndex).firstOrNull { index->abs(pixel(Offset(g.region.left+(g.region.right-g.region.left)*g.columns[index],g.region.top)).x-position.x)<=hitPx && start.y in g.region.top..g.region.bottom }
                        drag=when { row!=null->CalibrationDrag("ROW",row,start,g,current,start);col!=null->CalibrationDrag("COLUMN",col,start,g,current,start);else->null }
                    }
                }
            },onDrag={change,delta->
                val active=drag ?: return@detectDragGestures
                change.consume()
                if(active.kind=="PAN")offset=Offset((offset.x+delta.x/size.width.coerceAtLeast(1)).coerceIn(-5f,5f),(offset.y+delta.y/size.height.coerceAtLeast(1)).coerceIn(-5f,5f))
                else {
                    val point=norm(change.position)
                    if(active.kind=="OPTION") {
                        val r=NormalizedRect(minOf(active.anchor.x,point.x),minOf(active.anchor.y,point.y),maxOf(active.anchor.x,point.x),maxOf(active.anchor.y,point.y))
                        if(r.isValid())pendingSettings=active.settings.map {s->s.copy(options=s.options.map {o->if(o.optionId!=selectedOptionId)o else o.copy(region=r,coreConfirmed=o.coreConfirmed && o.printedCore?.let {RecognitionBindings.contains(r,it)}==true)})}
                    } else if(active.kind=="SCALAR") {
                        val r=NormalizedRect(minOf(active.start.x,point.x),minOf(active.start.y,point.y),maxOf(active.start.x,point.x),maxOf(active.start.y,point.y))
                        if(r.isValid())pendingSettings=active.settings.map { if(it.questionId==scalarQuestionId)it.copy(answerRegion=r)else it }
                    } else if(active.kind=="CORE") {
                        pendingSettings=active.settings.map { s->s.copy(options=s.options.map { option->if(option.optionId!=selectedOptionId)option else {
                            val r=option.region;val end=Offset(point.x.coerceIn(r.left,r.right),point.y.coerceIn(r.top,r.bottom));val anchor=Offset(active.anchor.x.coerceIn(r.left,r.right),active.anchor.y.coerceIn(r.top,r.bottom))
                            val core=NormalizedRect(minOf(anchor.x,end.x),minOf(anchor.y,end.y),maxOf(anchor.x,end.x),maxOf(anchor.y,end.y))
                            if(core.isValid())option.copy(printedCore=core,coreConfirmed=false) else option
                        } }) }
                    } else active.grid?.let { before->
                        val next=when(active.kind) {
                            "MOVE"->moveGrid(before,point.x-active.start.x,point.y-active.start.y)
                            "RESIZE"->{ val min=.006f;val x=if(active.index in listOf(0,3))point.x.coerceAtMost((active.anchor.x-min).coerceAtLeast(0f)) else point.x.coerceAtLeast((active.anchor.x+min).coerceAtMost(1f))
                                val y=if(active.index in listOf(0,1))point.y.coerceAtMost((active.anchor.y-min).coerceAtLeast(0f)) else point.y.coerceAtLeast((active.anchor.y+min).coerceAtMost(1f))
                                before.copy(region=NormalizedRect(minOf(x,active.anchor.x),minOf(y,active.anchor.y),maxOf(x,active.anchor.x),maxOf(y,active.anchor.y))) }
                            "ROW"->changeDivider(before,true,active.index,(point.y-before.region.top)/(before.region.bottom-before.region.top))
                            "COLUMN"->changeDivider(before,false,active.index,(point.x-before.region.left)/(before.region.right-before.region.left))
                            else->before
                        }
                        pendingGrid=next;pendingSettings=changeCalibrationGrid(active.settings,before,next,active.kind in listOf("MOVE","RESIZE"))
                    }
                }
            },onDragEnd={ pendingSettings?.let { latestCommit(it,pendingGrid ?: latestGrid) };drag=null;pendingSettings=null;pendingGrid=null;saveView() },
                onDragCancel={drag=null;pendingSettings=null;pendingGrid=null;saveView()})
        }) {
            val scale=minOf(size.width/bitmap.width,size.height/bitmap.height)*zoom;val w=bitmap.width*scale;val h=bitmap.height*scale
            val left=(size.width-w)/2+offset.x*size.width;val top=(size.height-h)/2+offset.y*size.height
            fun rectangle(r:NormalizedRect,c:Color,stroke:Float=1.5f){drawRect(c,Offset(left+r.left*w,top+r.top*h),Size((r.right-r.left)*w,(r.bottom-r.top)*h),style=Stroke(stroke))}
            clipRect {
                drawImage(bitmap,dstOffset=IntOffset(left.toInt(),top.toInt()),dstSize=IntSize(w.toInt().coerceAtLeast(1),h.toInt().coerceAtLeast(1)))
                val shown=pendingSettings ?: settings
                shown.filter { it.options.isEmpty() }.mapNotNull { it.answerRegion }.forEach { rectangle(it,Color(0xff2563eb),2f) }
                shown.flatMap { it.options }.forEach { option->rectangle(option.region,if(option.optionId==selectedOptionId)Color(0xfff59e0b) else Color(0xff2563eb),if(option.optionId==selectedOptionId)3f else 1.5f)
                    option.printedCore?.let { rectangle(it,if(option.coreConfirmed)Color(0xff16a34a)else Color(0xffdc2626),2f) } }
                (pendingGrid ?: grid)?.let { g->
                    rectangle(g.region,Color(0xff7c3aed),2f)
                    (1 until g.rows.lastIndex).forEach { index->val y=top+(g.region.top+(g.region.bottom-g.region.top)*g.rows[index])*h
                        drawLine(Color(0xff7c3aed),Offset(left+g.region.left*w,y),Offset(left+g.region.right*w,y),2f) }
                    (1 until g.columns.lastIndex).forEach { index->val x=left+(g.region.left+(g.region.right-g.region.left)*g.columns[index])*w
                        drawLine(Color(0xff7c3aed),Offset(x,top+g.region.top*h),Offset(x,top+g.region.bottom*h),2f) }
                    listOf(Offset(g.region.left,g.region.top),Offset(g.region.right,g.region.top),Offset(g.region.right,g.region.bottom),Offset(g.region.left,g.region.bottom)).forEach { drawCircle(Color(0xff7c3aed),4.dp.toPx(),Offset(left+it.x*w,top+it.y*h)) }
                }
                extraOverlays.forEach { rectangle(it,Color(0xfff59e0b),4f) }
            }
        }
    }
}
