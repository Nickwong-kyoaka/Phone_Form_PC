package com.phoneform.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import java.io.File
import javax.imageio.ImageIO

val BrandBlue = Color(0xff2563eb)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DesktopTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(
        primary=Color(0xff8ab4ff), onPrimary=Color(0xff082e71), background=Color(0xff111827),
        surface=Color(0xff182231), surfaceVariant=Color(0xff202d40), outlineVariant=Color(0xff334155),
        onSurface=Color(0xffe5edf8), onSurfaceVariant=Color(0xffb0bfd3), secondary=Color(0xffb0bfd3),
        primaryContainer=Color(0xff1e3a5f),onPrimaryContainer=Color(0xffdbeafe),secondaryContainer=Color(0xff223a60),
        surfaceTint=Color(0xff8ab4ff),surfaceDim=Color(0xff111827),surfaceBright=Color(0xff273447),
        surfaceContainerLowest=Color(0xff101722),surfaceContainerLow=Color(0xff151f2d),surfaceContainer=Color(0xff1c2737),
        surfaceContainerHigh=Color(0xff253247),surfaceContainerHighest=Color(0xff2c3c52))
    else lightColorScheme(primary=BrandBlue, onPrimary=Color.White, background=Color(0xfff6f8fb),
        surface=Color.White, surfaceVariant=Color(0xfff1f5f9), outlineVariant=Color(0xffe2e8f0),
        onSurface=Color(0xff1f2937), onSurfaceVariant=Color(0xff64748b),secondary=Color(0xff475569),
        primaryContainer=Color(0xffdbeafe),onPrimaryContainer=Color(0xff1e3a8a),secondaryContainer=Color(0xffeff6ff),
        surfaceTint=BrandBlue,surfaceDim=Color(0xffe8edf4),surfaceBright=Color.White,
        surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xfff8fafc),surfaceContainer=Color(0xfff1f5f9),
        surfaceContainerHigh=Color(0xffeaf0f7),surfaceContainerHighest=Color(0xffe2e8f0))
    val base = Typography()
    MaterialTheme(colorScheme=colors, shapes=Shapes(
        extraSmall=RoundedCornerShape(4.dp),small=RoundedCornerShape(6.dp),
        medium=RoundedCornerShape(8.dp),large=RoundedCornerShape(10.dp),extraLarge=RoundedCornerShape(12.dp)),
        typography=base.copy(
            bodyLarge=base.bodyLarge.copy(fontSize=14.sp,lineHeight=21.sp),
            bodyMedium=base.bodyMedium.copy(fontSize=14.sp,lineHeight=20.sp),
            bodySmall=base.bodySmall.copy(fontSize=12.sp,lineHeight=18.sp),
            labelLarge=base.labelLarge.copy(fontSize=13.sp,lineHeight=18.sp),
            titleLarge=base.titleLarge.copy(fontSize=20.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold),
            titleMedium=base.titleMedium.copy(fontSize=15.sp,lineHeight=22.sp),
            headlineSmall=base.headlineSmall.copy(fontSize=22.sp,lineHeight=30.sp))) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp, content=content)
    }
}

/** Callbacks are scoped to mounted workspaces; disposed editors cannot receive shortcuts or drops. */
internal class ScopedSaveActions {
    private val entries=linkedMapOf<Any,()->Unit>()
    val owner:Any? get()=entries.keys.lastOrNull()
    val action:(()->Unit)? get()=entries.values.lastOrNull()
    fun register(owner:Any,action:()->Unit) { entries[owner]=action }
    fun unregister(owner:Any) { entries.remove(owner) }
}

class DesktopActions {
    internal val saves=ScopedSaveActions()
    val saveOwner: Any? get()=saves.owner
    val save: (() -> Unit)? get()=saves.action
    var searchOwner: Any? = null
    var search: (() -> Unit)? = null
    var dropOwner: Any? = null
    var drop: ((File) -> Unit)? = null
    var dropRejected: (() -> Unit)? = null
    var dropError by mutableStateOf<String?>(null)
}
val LocalDesktopActions = staticCompositionLocalOf { DesktopActions() }
val LocalEditorEnabled = staticCompositionLocalOf { true }

@Composable fun RegisterSaveAction(enabled:Boolean=true, action:()->Unit) {
    val registry=LocalDesktopActions.current
    val current by rememberUpdatedState(action)
    val owner=remember { Any() }
    DisposableEffect(registry,enabled) {
        if(enabled) registry.saves.register(owner){current()}
        onDispose {registry.saves.unregister(owner)}
    }
}
@Composable fun RegisterSearchAction(request:()->Unit) {
    val registry=LocalDesktopActions.current
    val current by rememberUpdatedState(request)
    val owner=remember {Any()}
    DisposableEffect(registry) {
        registry.searchOwner=owner;registry.search={current()}
        onDispose {if(registry.searchOwner===owner){registry.searchOwner=null;registry.search=null}}
    }
}
@Composable fun FileDropTarget(language:UiLanguage,enabled:Boolean=true,onFile:(File)->Unit,content:@Composable ()->Unit) {
    val registry=LocalDesktopActions.current
    val current by rememberUpdatedState(onFile)
    val owner=remember {Any()}
    DisposableEffect(registry,enabled,language) {
        if(enabled) {
            registry.dropOwner=owner;registry.drop={current(it)}
            registry.dropRejected={registry.dropError=tr(language,"每次請拖入一個 PDF、JPG 或 PNG 檔案。","每次请拖入一个 PDF、JPG 或 PNG 文件。","Drop one PDF, JPG or PNG file at a time.")}
        }
        onDispose {if(registry.dropOwner===owner){registry.dropOwner=null;registry.drop=null;registry.dropRejected=null}}
    }
    content()
}

@Composable fun WorkspaceToolbar(title:String,subtitle:String?=null,actions:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal=20.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(!subtitle.isNullOrBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        actions()
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
}
@Composable fun PanelHeading(title:String,detail:String?=null) {
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=12.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        if(!detail.isNullOrBlank()) Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun InspectorTabs(labels:List<String>,selected:Int,onSelect:(Int)->Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).background(MaterialTheme.colorScheme.surface)) {
        labels.forEachIndexed {index,label ->
            Column(Modifier.weight(1f).fillMaxHeight().clickable {onSelect(index)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Text(label,color=if(selected==index)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.height(2.dp).fillMaxWidth(.65f).background(if(selected==index)MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
}
@Composable fun EmptyState(title:String,detail:String,action:(@Composable ()->Unit)?=null) {
    Box(Modifier.fillMaxSize().padding(28.dp),contentAlignment=Alignment.Center) {
        Column(Modifier.widthIn(max=480.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
            AppIcon("document",Modifier.size(42.dp),MaterialTheme.colorScheme.primary)
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@Composable fun DesktopWorkspace(controller:Controller,state:AppSnapshot,language:UiLanguage,key:String,
    navigation:@Composable ()->Unit,canvas:@Composable ()->Unit,inspector:@Composable ()->Unit) {
    val ui by controller.ui.collectAsState()
    val projectId=state.project?.project?.id
    val saved=ui.projects[projectId]?:ProjectUiState()
    var left by remember(projectId,key){mutableStateOf(saved.paneLeft)}
    var right by remember(projectId,key){mutableStateOf(saved.paneRight)}
    var drawer by remember(projectId,key){mutableStateOf(false)}
    var smallTab by remember(projectId,key){mutableStateOf(0)}
    val density=LocalDensity.current
    fun persist() {if(projectId!=null)controller.updateProjectUi(projectId){it.copy(paneLeft=left,paneRight=right)}}
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val full=maxWidth>=1160.dp
        val split=maxWidth>=960.dp
        Column(Modifier.fillMaxSize()) {
            if(!full) Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={drawer=!drawer},contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp)) {
                    AppIcon("list",Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(tr(language,"頁面／題目","页面／题目","Pages / targets"))
                }
                if(!split) {
                    FilterChip(smallTab==0,{smallTab=0},label={Text(tr(language,"圖像","图像","Image"))})
                    FilterChip(smallTab==1,{smallTab=1},label={Text(tr(language,"編輯","编辑","Edit"))})
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if(full) {
                    WorkspaceSurface(Modifier.width(left.coerceIn(176f,280f).dp).fillMaxHeight(),navigation)
                    SplitHandle(onEnd={persist()}) {delta ->left=(left+delta/density.density).coerceIn(176f,280f)}
                }
                if(split||smallTab==0) WorkspaceSurface(Modifier.weight(1f).fillMaxHeight(),canvas)
                if(split) {
                    SplitHandle(onEnd={persist()}) {delta->right=(right-delta/density.density).coerceIn(300f,400f)}
                    WorkspaceSurface(Modifier.width(right.coerceIn(300f,400f).dp).fillMaxHeight(),inspector)
                } else if(smallTab==1) WorkspaceSurface(Modifier.weight(1f).fillMaxHeight(),inspector)
            }
        }
        if(drawer&&!full) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.25f)).clickable {drawer=false})
            Surface(Modifier.width(264.dp).fillMaxHeight(),shadowElevation=12.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(tr(language,"導航","导航","Navigation"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick={drawer=false}){Text(tr(language,"收起","收起","Hide"))}
                    }
                    Box(Modifier.weight(1f)){navigation()}
                }
            }
        }
    }
    DisposableEffect(projectId,key) {onDispose {persist()}}
}
@Composable private fun WorkspaceSurface(modifier:Modifier,content:@Composable ()->Unit) {
    Surface(modifier.border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(8.dp)),shape=RoundedCornerShape(8.dp)) {content()}
}
@Composable private fun SplitHandle(onEnd:()->Unit={},onDrag:(Float)->Unit) {
    val current by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Box(Modifier.width(8.dp).fillMaxHeight().pointerInput(Unit) {
        detectDragGestures(onDragEnd={end()},onDragCancel={end()}){change,amount->change.consume();current(amount.x)}
    },contentAlignment=Alignment.Center) {
        Box(Modifier.width(2.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(2.dp)))
    }
}

@Composable fun PageThumbnail(controller:Controller,page:SamplePage?,label:String,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    var bitmap by remember(page?.id,page?.revision,page?.thumbnailPath){mutableStateOf<ImageBitmap?>(null)}
    LaunchedEffect(page?.id,page?.revision,page?.thumbnailPath) {
        bitmap=page?.let {withContext(Dispatchers.IO){runCatching {
            controller.workspace.assets.readBytes(it.thumbnailPath).inputStream().use(ImageIO::read)?.toComposeImageBitmap()
        }.getOrNull()}}
    }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
        .border(if(selected)2.dp else 1.dp,if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(6.dp))
        .clickable(onClick=onClick).padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(4.dp)),contentAlignment=Alignment.Center) {
            val loaded=bitmap
            if(loaded!=null)Image(loaded,label,Modifier.fillMaxSize().padding(4.dp))
            else AppIcon("document",Modifier.size(28.dp),MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable fun AppIcon(kind:String,modifier:Modifier=Modifier.size(20.dp),tint:Color=MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier) {
        val s=size.minDimension;val stroke=1.65.dp.toPx();fun p(x:Float,y:Float)=Offset(s*x,s*y)
        fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(tint,p(x,y),p(a,b),stroke,StrokeCap.Round)
        when(kind) {
            "projects" -> {drawRoundRect(tint,p(.12f,.2f),androidx.compose.ui.geometry.Size(s*.76f,s*.65f),androidx.compose.ui.geometry.CornerRadius(s*.06f),style=Stroke(stroke));line(.12f,.32f,.88f,.32f);line(.16f,.2f,.4f,.2f)}
            "template","document" -> {drawRoundRect(tint,p(.23f,.1f),androidx.compose.ui.geometry.Size(s*.57f,s*.8f),androidx.compose.ui.geometry.CornerRadius(s*.04f),style=Stroke(stroke));line(.35f,.36f,.67f,.36f);line(.35f,.53f,.67f,.53f);line(.35f,.7f,.57f,.7f)}
            "collection" -> {line(.5f,.12f,.5f,.63f);line(.33f,.45f,.5f,.63f);line(.67f,.45f,.5f,.63f);line(.15f,.62f,.15f,.86f);line(.15f,.86f,.85f,.86f);line(.85f,.86f,.85f,.62f)}
            "review" -> {drawCircle(tint,s*.32f,p(.5f,.5f),style=Stroke(stroke));line(.32f,.5f,.45f,.64f);line(.45f,.64f,.69f,.35f)}
            "export" -> {line(.5f,.85f,.5f,.28f);line(.33f,.45f,.5f,.28f);line(.67f,.45f,.5f,.28f);line(.15f,.4f,.15f,.15f);line(.15f,.15f,.85f,.15f);line(.85f,.15f,.85f,.4f)}
            "tasks" -> {for(i in 0..2){val y=.25f+i*.25f;drawCircle(tint,s*.035f,p(.18f,y));line(.35f,y,.83f,y)}}
            "settings" -> {drawCircle(tint,s*.3f,p(.5f,.5f),style=Stroke(stroke));drawCircle(tint,s*.1f,p(.5f,.5f),style=Stroke(stroke));for(i in 0..7){val a=i*Math.PI/4;drawLine(tint,Offset((.5+Math.cos(a)*.32).toFloat()*s,(.5+Math.sin(a)*.32).toFloat()*s),Offset((.5+Math.cos(a)*.43).toFloat()*s,(.5+Math.sin(a)*.43).toFloat()*s),stroke)}}
            "help" -> {drawCircle(tint,s*.36f,p(.5f,.5f),style=Stroke(stroke));line(.43f,.36f,.56f,.32f);line(.56f,.32f,.62f,.42f);line(.62f,.42f,.5f,.54f);drawCircle(tint,s*.025f,p(.5f,.68f))}
            "search" -> {drawCircle(tint,s*.26f,p(.43f,.43f),style=Stroke(stroke));line(.63f,.63f,.86f,.86f)}
            "chevron" -> {line(.36f,.25f,.63f,.5f);line(.63f,.5f,.36f,.75f)}
            "back" -> {line(.62f,.25f,.35f,.5f);line(.35f,.5f,.62f,.75f)}
            else -> {line(.18f,.25f,.82f,.25f);line(.18f,.5f,.82f,.5f);line(.18f,.75f,.82f,.75f)}
        }
    }
}

@Composable fun StatusBadge(text:String,good:Boolean=false,warning:Boolean=false) {
    val color=if(warning)MaterialTheme.colorScheme.error else if(good)Color(0xff168365) else MaterialTheme.colorScheme.primary
    Text(text,Modifier.background(color.copy(alpha=.1f),RoundedCornerShape(4.dp)).padding(horizontal=7.dp,vertical=3.dp),color=color,style=MaterialTheme.typography.bodySmall,maxLines=1)
}

@Composable fun AppDialog(title:String,close:()->Unit,width:Dp=700.dp,height:Dp=700.dp,content:@Composable ColumnScope.()->Unit) {
    val available=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    Dialog(onCloseRequest=close,title=title,state=rememberDialogState(width=minOf(width,(available.width*.9f).dp),height=minOf(height,(available.height*.9f).dp))) {
        Surface {Column(Modifier.fillMaxSize(),content=content)}
    }
}
