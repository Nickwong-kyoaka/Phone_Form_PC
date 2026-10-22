package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.*
import java.awt.*
import java.awt.geom.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import kotlin.math.max
import org.junit.Assert.*
import org.junit.Test

/** New anonymous pixels only. No input PDF, answer repository or former private fixture is read. */
class AnonymousMarkAcceptanceTest {
    data class Fixture(val image:BufferedImage,val option:OptionMarkRegion,val expected:OptionDecision?,val ambiguity:Int?=null)
    data class Aggregate(val clear:Int,val correct:Int,val wrong:Int,val abstained:Int,val ambiguous:Int,val intercepted:Int,
        val reasons:Map<String,Int>) {
        val passed get()=clear>=100 && wrong==0 && correct.toDouble()/clear>=.80 && intercepted==ambiguous
        fun json()="""{"clear":$clear,"correct":$correct,"wrong":$wrong,"abstained":$abstained,"coverage":${correct.toDouble()/clear},"ambiguous":$ambiguous,"intercepted":$intercepted,"passed":$passed,"reasons":{${reasons.entries.joinToString { "\"${it.key}\":${it.value}" }}}}"""
    }

    @Test fun tuneBeforeFreeze() {
        assertTrue(OpenCvRuntime.load())
        MarkStyle.entries.filter { it!=MarkStyle.MANUAL_ONLY }.forEach { style ->
            val result=evaluate(style,false)
            println("ANONYMOUS_TUNE ${style.name} ${result.json()}")
        }
    }

    @Test fun frozenHoldoutAndProductionGates() {
        assertTrue(OpenCvRuntime.load())
        val frozen=MarkParameters.fingerprint
        val detectorPath=Path.of("src/main/kotlin/com/phoneform/desktop/vision/MarkDetectors.kt")
        fun sourceHash()=MessageDigest.getInstance("SHA-256").digest(Files.readString(detectorPath)
            .replace("\r\n","\n").replace("\r","\n").toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it.toInt() and 255) }
        val frozenSource=sourceHash()
        val results=MarkStyle.entries.filter { it!=MarkStyle.MANUAL_ONLY }.associateWith { evaluate(it,true) }
        val groups=results.keys.associateWith { evaluateQuestionGroups(it) }
        assertEquals("Parameters must remain fixed before/through held-out evaluation",frozen,MarkParameters.fingerprint)
        assertEquals("Detector code/implicit thresholds remain frozen through evaluation",frozenSource,sourceHash())
        results.forEach { (style,result) ->
            assertTrue("Failed style must keep automatic question candidates disabled: $style",(result.passed && groups.getValue(style).passed) || !MarkAcceptanceGate.enabled(style))
            println("ANONYMOUS_HOLDOUT ${style.name} ${result.json()}")
            println("ANONYMOUS_QUESTION_GROUP_HOLDOUT ${style.name} ${groups.getValue(style).json()}")
        }
        val report=Path.of("build","reports","recognition","anonymous-${MarkParameters.VERSION}.json")
        Files.createDirectories(report.parent)
        val optionJson=results.entries.joinToString(prefix="{",postfix="}"){ "\"${it.key.name}\":${it.value.json()}" }
        val groupJson=groups.entries.joinToString(prefix="{",postfix="}"){ "\"${it.key.name}\":${it.value.json()}" }
        val gateJson=results.keys.joinToString(prefix="{",postfix="}"){ "\"${it.name}\":${MarkAcceptanceGate.enabled(it)}" }
        Files.writeString(report,"""{"schema":"anonymous-mark-acceptance-v1","engine":"${DesktopVision.VERSION}","parameters":"${MarkParameters.VERSION}","parameterHash":"$frozen","detectorSourceSha256":"$frozenSource","detectorSourceHashEncoding":"UTF-8 with canonical LF line endings","parametersFrozenBeforeHoldout":true,"truth":"Fixed generator labels established before any detector call","tuningClearOptionCropsPerMode":128,"tuningAmbiguousOptionCropsPerMode":32,"questionGroupComposition":{"singleChoice":40,"multipleChoice":40,"allBlank":40,"ambiguous":32,"optionsEach":4},"split":"Tuning, option holdout and question-group holdout have disjoint seeds; holdout adds font/style/size variants","scope":"Anonymous generated pixels only; not real-scan or general handwriting accuracy","imagesPersisted":0,"optionCropHoldout":$optionJson,"questionGroupHoldout":$groupJson,"automaticCandidateGates":$gateJson}""")
    }

    private fun evaluate(style:MarkStyle,holdout:Boolean):Aggregate {
        val seed=(if(holdout)2026119301L else 2026107201L)+style.ordinal*100003
        val cases=(0 until 128).map { fixture(style,seed+it, it%2==0,null,holdout) }+
            (0 until 32).map { fixture(style,seed+50000+it,false,it%8,holdout) }
        var correct=0;var wrong=0;var abstained=0;var intercepted=0
        val reasons=mutableMapOf<String,Int>()
        val ambiguityMisses=mutableMapOf<Int,Int>()
        try {
            for(case in cases) {
                val decision=MarkDetectors.inspect(case.image,style,case.option)
                reasons[decision.reason]=(reasons[decision.reason] ?: 0)+1
                if(case.expected==null){if(decision.decision in listOf(OptionDecision.AMBIGUOUS,OptionDecision.UNSUPPORTED))intercepted++
                    else ambiguityMisses[case.ambiguity!!]=(ambiguityMisses[case.ambiguity] ?: 0)+1}
                else if(decision.decision==case.expected)correct++
                else if(decision.decision in listOf(OptionDecision.AMBIGUOUS,OptionDecision.UNSUPPORTED))abstained++ else wrong++
                assertTrue(decision.cropSha256.matches(Regex("[a-f0-9]{64}")))
                assertTrue(decision.metrics.values.all { it.isFinite() })
            }
        } finally { cases.forEach { it.image.flush() } }
        if(!holdout)println("ANONYMOUS_TUNE_AMBIGUITY_MISS_COUNTS $style $ambiguityMisses")
        return Aggregate(128,correct,wrong,abstained,32,intercepted,reasons.toSortedMap())
    }

    private fun evaluateQuestionGroups(style:MarkStyle):Aggregate {
        var correct=0;var wrong=0;var abstained=0;var intercepted=0
        val reasons=mutableMapOf<String,Int>()
        val seed=2026127301L+style.ordinal*200003
        for(index in 0 until 152) {
            val ambiguous=if(index>=120)(index-120)%8 else null
            val multiple=index%3==1
            val expected=when { ambiguous!=null -> emptySet();index%3==2 -> emptySet();multiple -> setOf(0,2);else -> setOf(index/3%4) }
            val cells=(0..3).map { column -> fixture(style,seed+index*37+column,column in expected,if(column==1)ambiguous else null,true) }
            val width=cells.sumOf { it.image.width };val height=cells.maxOf { it.image.height }
            val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
            val options=mutableListOf<OptionMarkRegion>();var offset=0
            image.createGraphics().let { g ->
                try {
                    g.color=Color.WHITE;g.fillRect(0,0,width,height)
                    cells.forEachIndexed { column,cell ->
                        g.drawImage(cell.image,offset,0,null)
                        val r=NormalizedRect(offset.toFloat()/width,0f,(offset+cell.image.width).toFloat()/width,cell.image.height.toFloat()/height)
                        val core=cell.option.printedCore?.let { c -> NormalizedRect((offset+c.left*cell.image.width)/width,c.top*cell.image.height/height,
                            (offset+c.right*cell.image.width)/width,c.bottom*cell.image.height/height) }
                        options+=OptionMarkRegion("option-$column",r,core,cell.option.coreConfirmed);offset+=cell.image.width
                    }
                } finally { g.dispose() }
            }
            try {
                val q=QuestionDefinition("anonymous-group","anonymous-page",0,"","Anonymous group",
                    if(multiple)QuestionType.MULTIPLE_CHOICE else QuestionType.SINGLE_CHOICE,options=options.mapIndexed { i,o -> OptionDefinition(o.optionId,"Option$i",i.toString(),o.region) })
                val observed=options.map { MarkDetectors.inspect(image,style,it) }
                val combined=DesktopVision.aggregateChoices(q,observed,FieldObservation("question:anonymous-group","anonymous-page","anonymous-input",1))
                reasons[combined.reason]=(reasons[combined.reason] ?: 0)+1
                if(ambiguous!=null) {if(combined.candidate==null)intercepted++}
                else {
                    val answer=combined.candidate
                    val expectedIds=expected.map { "option-$it" }.toSet()
                    if(answer==null)abstained++
                    else if(answer.state==(if(expected.isEmpty())AnswerState.BLANK else AnswerState.VALUE) && answer.optionIds.toSet()==expectedIds)correct++
                    else wrong++
                }
                assertTrue(combined.requiresReview)
            } finally { image.flush();cells.forEach { it.image.flush() } }
        }
        return Aggregate(120,correct,wrong,abstained,32,intercepted,reasons.toSortedMap())
    }

    companion object {
        fun fixture(style:MarkStyle,seed:Long,selected:Boolean,ambiguous:Int?=null,holdout:Boolean=false):Fixture {
            val r=Random(seed);val size=(if(holdout) listOf(144,176,208,240) else listOf(160,192,224))[r.nextInt(if(holdout)4 else 3)]
            val image=BufferedImage(size,size,BufferedImage.TYPE_INT_RGB)
            val g=image.createGraphics();val cx=size/2.0;val cy=size/2.0
            var core:NormalizedRect?=null
            try {
                val paper=238+r.nextInt(18);g.color=Color(paper,paper,paper);g.fillRect(0,0,size,size)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val ink=r.nextInt(55);g.color=Color(ink,ink,ink)
                if(style==MarkStyle.CHECKBOX_TICK_CROSS) {
                    val side=size*(.53+r.nextDouble()*.06);val x=cx-side/2;val y=cy-side/2
                    g.rotate(Math.toRadians((r.nextDouble()-.5)*(if(holdout)5.0 else 3.0)),cx,cy)
                    g.stroke=BasicStroke((size*(.010+r.nextDouble()*.006)).toFloat(),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)
                    g.draw(Rectangle2D.Double(x,y,side,side))
                    g.stroke=BasicStroke((size*(.011+r.nextDouble()*.011)).toFloat(),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)
                    val lo=.22;val hi=.78
                    if(selected) {
                        if(seed%4L<2) {g.draw(Line2D.Double(x+side*lo,y+side*lo,x+side*hi,y+side*hi));g.draw(Line2D.Double(x+side*lo,y+side*hi,x+side*hi,y+side*lo))}
                        else {val path=Path2D.Double();path.moveTo(x+side*.16,y+side*.51);path.lineTo(x+side*.39,y+side*.76);path.lineTo(x+side*.86,y+side*.21);g.draw(path)}
                    }
                    when(ambiguous) {
                        0 -> g.fill(Rectangle2D.Double(x+side*.07,y+side*.07,side*.86,side*.86))
                        1 -> g.fill(Ellipse2D.Double(cx-2,cy-2,4.0,4.0))
                        2 -> g.draw(Line2D.Double(x+side*.22,y+side*.75,x+side*.78,y+side*.25))
                        3 -> {g.color=Color(paper,paper,paper);g.stroke=BasicStroke((size*.06).toFloat());g.draw(Line2D.Double(x+side*.15,y,x+side*.85,y))}
                        4 -> g.draw(Ellipse2D.Double(x+side*.18,y+side*.18,side*.64,side*.64))
                        5 -> {g.stroke=BasicStroke((side*.13).toFloat());for(i in 0..5)g.draw(Line2D.Double(x+side*.15,y+side*(.15+i*.12),x+side*.85,y+side*(.24+i*.12)))}
                        6 -> {g.color=Color(paper,paper,paper);g.fill(Rectangle2D.Double(x-4,y-4,side+8,side+8))}
                        7 -> {g.stroke=BasicStroke((side*.20).toFloat());g.draw(Ellipse2D.Double(x+side*.14,y+side*.14,side*.72,side*.72))}
                    }
                } else {
                    val fontName=(if(holdout)listOf("Serif","Monospaced","Dialog") else listOf("SansSerif","Dialog"))[r.nextInt(if(holdout)3 else 2)]
                    val font=Font(fontName,if(seed%3L==0L)Font.BOLD else Font.PLAIN,(size*.19).toInt());g.font=font
                    val text=(seed%10).toString();val bounds=font.createGlyphVector(g.fontRenderContext,text).visualBounds
                    val bx=cx-bounds.centerX;val by=cy-bounds.centerY
                    g.drawString(text,bx.toFloat(),by.toFloat())
                    val margin=max(4.0,size*.026)
                    core=NormalizedRect(((bx+bounds.x-margin)/size).toFloat(),((by+bounds.y-margin)/size).toFloat(),
                        ((bx+bounds.maxX+margin)/size).toFloat(),((by+bounds.maxY+margin)/size).toFloat())
                    val rx=size*(.26+r.nextDouble()*.045);val ry=size*(.28+r.nextDouble()*.04)
                    g.stroke=BasicStroke((size*(.009+r.nextDouble()*.008)).toFloat(),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)
                    g.rotate(Math.toRadians((r.nextDouble()-.5)*24),cx,cy)
                    if(selected)g.draw(Ellipse2D.Double(cx-rx,cy-ry,rx*2,ry*2))
                    when(ambiguous) {
                        0 -> g.draw(Arc2D.Double(cx-rx,cy-ry,rx*2,ry*2,45.0,240.0,Arc2D.OPEN))
                        1 -> {g.draw(Ellipse2D.Double(cx-rx,cy-ry,rx*2,ry*2));g.draw(Ellipse2D.Double(cx-rx-size*.07,cy-ry-size*.07,rx*2+size*.14,ry*2+size*.14))}
                        2 -> {g.draw(Ellipse2D.Double(cx-rx,cy-ry,rx*2,ry*2));g.draw(Line2D.Double(cx-rx,cy-ry,cx+rx,cy+ry))}
                        3 -> g.fill(Ellipse2D.Double(cx+size*.23,cy-size*.15,5.0,5.0))
                        4 -> g.draw(Ellipse2D.Double(-size*.08,size*.16,size*1.16,size*.68))
                        5 -> g.fill(Ellipse2D.Double(cx-rx,cy-ry,rx*2,ry*2))
                        6 -> core=null
                        7 -> {g.draw(Line2D.Double(cx-rx,cy-ry,cx+rx,cy+ry));g.draw(Line2D.Double(cx-rx,cy+ry,cx+rx,cy-ry))}
                    }
                    if(ambiguous==null && seed%3==0L){g.transform=AffineTransform();g.stroke=BasicStroke(1.2f);g.drawRect(8,8,size-16,size-16)}
                }
            } finally {g.dispose()}
            return Fixture(image,OptionMarkRegion("anonymous-option",NormalizedRect(0f,0f,1f,1f),core,core!=null),
                if(ambiguous!=null)null else if(selected)OptionDecision.SELECTED else OptionDecision.EMPTY,ambiguous)
        }
    }
}
