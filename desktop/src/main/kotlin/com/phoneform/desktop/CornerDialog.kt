package com.phoneform.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import com.phoneform.ai.core.model.*

@Composable fun CornerDialog(controller:Controller,page:SamplePage,language:UiLanguage,close:()->Unit,save:(List<NormalizedPoint>)->Unit) {
    val display=rememberDisplayImage(controller,controller.state.value.project?.project?.id,page)
    val image=display.bitmap
    var corners by remember(page.id){mutableStateOf(listOf(NormalizedPoint(.03f,.03f),NormalizedPoint(.97f,.03f),NormalizedPoint(.97f,.97f),NormalizedPoint(.03f,.97f)))}
    var active by remember{mutableStateOf(-1)}
    AppDialog(tr(language,"四角校正","四角校正","Perspective correction"),close,width=940.dp,height=800.dp) {Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(tr(language,"四角校正","四角校正","Perspective correction"),style=MaterialTheme.typography.titleLarge)
        Text(tr(language,"拖動控制點到紙張四角，依左上 → 右上 → 右下 → 左下排列。","拖动控制点到纸张四角，按左上 → 右上 → 右下 → 左下排列。","Drag the handles to the paper corners: top-left → top-right → bottom-right → bottom-left."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color=MaterialTheme.colorScheme.errorContainer.copy(alpha=.5f),shape=MaterialTheme.shapes.small) {Text(tr(language,"套用會清除本頁題目、矩陣及綁定，需要重新標註；原件會保留。","应用会清除本页题目、矩阵和绑定，需要重新标注；原件会保留。","Applying clears this page's questions, matrices and bindings. Re-annotate them afterward; the original is retained."),modifier=Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}
        image?.let {bitmap->Canvas(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(page.id,bitmap){
            fun originSize():Pair<Offset,Size>{val scale=minOf(size.width.toFloat()/bitmap.width,size.height.toFloat()/bitmap.height);val sz=Size(bitmap.width*scale,bitmap.height*scale);return Offset((size.width-sz.width)/2,(size.height-sz.height)/2) to sz}
            detectDragGestures(onDragStart={point->val(origin,sz)=originSize();active=(corners.indices.minByOrNull {i->(point-Offset(origin.x+corners[i].x*sz.width,origin.y+corners[i].y*sz.height)).getDistance()}?:-1).takeIf {i->i>=0&&(point-Offset(origin.x+corners[i].x*sz.width,origin.y+corners[i].y*sz.height)).getDistance()<=32.dp.toPx()}?:-1},onDrag={change,_->change.consume();val(origin,sz)=originSize();if(active>=0)corners=corners.mapIndexed {i,p->if(i==active)NormalizedPoint(((change.position.x-origin.x)/sz.width).coerceIn(0f,1f),((change.position.y-origin.y)/sz.height).coerceIn(0f,1f))else p}},onDragEnd={active=-1},onDragCancel={active=-1})
        }){val scale=minOf(size.width/bitmap.width,size.height/bitmap.height);val w=bitmap.width*scale;val h=bitmap.height*scale;val origin=Offset((size.width-w)/2,(size.height-h)/2)
            drawImage(bitmap,dstOffset=IntOffset(origin.x.toInt(),origin.y.toInt()),dstSize=IntSize(w.toInt(),h.toInt()))
            val points=corners.map {Offset(origin.x+it.x*w,origin.y+it.y*h)};points.indices.forEach {i->drawLine(Color(0xff2563eb),points[i],points[(i+1)%4],3f);drawCircle(Color.White,10.dp.toPx(),points[i]);drawCircle(if(i==active)Color(0xfff59e0b)else Color(0xff2563eb),7.dp.toPx(),points[i])}
        }}?:Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){if(display.error!=null)Column(horizontalAlignment=Alignment.CenterHorizontally){Text(tr(language,"無法開啟影像","无法打开图像","Image unavailable"));TextButton(onClick={display.retry(false)}){Text(tr(language,"重試","重试","Retry"))}}else CircularProgressIndicator(Modifier.size(28.dp))}
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){TextButton(onClick=close){Text(tr(language,"取消","取消","Cancel"))};Spacer(Modifier.weight(1f));Button(onClick={save(corners)},enabled=image!=null){Text(tr(language,"清除標註並套用","清除标注并应用","Clear annotations and apply"))}}
    }}
}
