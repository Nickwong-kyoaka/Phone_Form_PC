package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Frozen image-only parameters. No filled/blank reference or answer values are accepted. */
object MarkParameters {
    const val VERSION = RecognitionContracts.PARAMETERS_VERSION
    const val BORDER_COVERAGE = .72
    const val EMPTY_INK = .012
    const val MAX_INK = .38
    const val ELLIPSE_COVERAGE = .90
    const val MAX_RING_RESIDUAL = .19
    const val MAX_OFF_RING = .18
    val fingerprint: String get() = RecognitionBindings.hash(VERSION, BORDER_COVERAGE.toString(),
        EMPTY_INK.toString(), MAX_INK.toString(), ELLIPSE_COVERAGE.toString(), MAX_RING_RESIDUAL.toString(), MAX_OFF_RING.toString())
}

object MarkDetectors {
    fun inspect(image: BufferedImage, style: MarkStyle, option: OptionMarkRegion): OptionObservation {
        if (!option.region.isValid()) return OptionObservation(option.optionId, OptionDecision.UNSUPPORTED,
            option.region, option.printedCore, "INVALID_OPTION_REGION")
        if (style == MarkStyle.MANUAL_ONLY) return OptionObservation(option.optionId, OptionDecision.UNSUPPORTED,
            option.region, option.printedCore, "MANUAL_MARK_STYLE")
        val pixels = BinaryPixels.read(image, option.region)
        fun result(state: OptionDecision, reason: String, metrics: Map<String, Double> = emptyMap()) =
            OptionObservation(option.optionId, state, option.region, option.printedCore, reason,
                pixels.metrics + metrics, cropSha256 = pixels.sha256)
        if (min(pixels.width, pixels.height) < 20) return result(OptionDecision.UNSUPPORTED, "OPTION_RESOLUTION_TOO_LOW")
        if (pixels.softInk > .55) return result(OptionDecision.UNSUPPORTED, "OPTION_TOO_BLURRED")
        val decision = when (style) {
            MarkStyle.CHECKBOX_TICK_CROSS -> checkbox(pixels)
            MarkStyle.CIRCLED_PRINTED_NUMBER -> {
                if (!option.coreConfirmed || option.printedCore?.let { it.isValid() && RecognitionBindings.contains(option.region, it) } != true)
                    return result(OptionDecision.UNSUPPORTED, "PRINTED_CORE_NOT_CONFIRMED")
                circle(pixels, option.region, option.printedCore!!)
            }
            MarkStyle.MANUAL_ONLY -> error("Handled above")
        }
        return result(decision.state, decision.reason, decision.metrics)
    }

    private data class Decision(val state: OptionDecision, val reason: String, val metrics: Map<String, Double> = emptyMap())
    private fun uncertain(reason: String, metrics: Map<String, Double> = emptyMap()) = Decision(OptionDecision.AMBIGUOUS, reason, metrics)

    private fun checkbox(p: BinaryPixels): Decision {
        val w=p.width; val h=p.height; val band=max(1,(min(w,h)*.025).roundToInt())
        fun rowCoverage(y: Int, x0: Int=0, x1: Int=w) = (x0 until x1).count { x ->
            (max(0,y-band)..min(h-1,y+band)).any { yy -> p[x,yy] } }.toDouble()/(x1-x0)
        fun colCoverage(x: Int, y0: Int=0, y1: Int=h) = (y0 until y1).count { y ->
            (max(0,x-band)..min(w-1,x+band)).any { xx -> p[xx,y] } }.toDouble()/(y1-y0)
        val top=(0..(h*.36).toInt()).maxWith(compareBy<Int> { rowCoverage(it) }.thenBy { it })
        val bottom=((h*.64).toInt() until h).maxWith(compareBy<Int> { rowCoverage(it) }.thenBy { -it })
        val left=(0..(w*.36).toInt()).maxWith(compareBy<Int> { colCoverage(it,top,bottom+1) }.thenBy { it })
        val right=((w*.64).toInt() until w).maxWith(compareBy<Int> { colCoverage(it,top,bottom+1) }.thenBy { -it })
        val border=listOf(rowCoverage(top,left,right+1),rowCoverage(bottom,left,right+1),
            colCoverage(left,top,bottom+1),colCoverage(right,top,bottom+1)).min()
        val bw=right-left; val bh=bottom-top
        val bm=mapOf("borderCoverage" to border,"boxWidth" to bw.toDouble(),"boxHeight" to bh.toDouble())
        if (border<MarkParameters.BORDER_COVERAGE || bw.toDouble()/bh !in .60..1.65 || min(bw,bh)<14)
            return Decision(OptionDecision.UNSUPPORTED,"CHECKBOX_BORDER_NOT_LOCALIZED",bm)
        if (left<=band || top<=band || right>=w-1-band || bottom>=h-1-band)
            return uncertain("CHECKBOX_CROP_TOUCHES_BORDER",bm)
        // The fitted printed border, rather than a fixed percentage of the user's crop, defines the interior.
        val inset=max(2,ceil(min(bw,bh)*.055).toInt())
        val x0=left+inset; val y0=top+inset; val iw=bw-2*inset; val ih=bh-2*inset
        if (iw<8 || ih<8) return Decision(OptionDecision.UNSUPPORTED,"CHECKBOX_INTERIOR_TOO_SMALL",bm)
        val points=(y0 until y0+ih).flatMap { y -> (x0 until x0+iw).filter { x -> p[x,y] }.map { x -> x to y } }
        val ink=points.size.toDouble()/(iw*ih); val m=bm+mapOf("interiorInkFraction" to ink)
        if (ink<MarkParameters.EMPTY_INK) return if(points.size<=2) Decision(OptionDecision.EMPTY,"VISIBLE_EMPTY_CHECKBOX",m)
            else uncertain("ISOLATED_OR_SMALL_CHECKBOX_INK",m)
        if (ink>MarkParameters.MAX_INK) return Decision(OptionDecision.UNSUPPORTED,"FILLED_OR_CANCELLED_CHECKBOX",m)
        val sx=(points.maxOf { it.first }-points.minOf { it.first }+1).toDouble()/iw
        val sy=(points.maxOf { it.second }-points.minOf { it.second }+1).toDouble()/ih
        if (sx<.33 || sy<.26) return uncertain("ISOLATED_OR_SMALL_CHECKBOX_INK",m+mapOf("spanX" to sx,"spanY" to sy))
        val mat=Mat(ih,iw,CvType.CV_8UC1);val lines=Mat();val hierarchy=Mat();val contours=mutableListOf<MatOfPoint>()
        return try {
            val bytes=ByteArray(iw*ih);points.forEach { (x,y)-> bytes[(y-y0)*iw+x-x0]=255.toByte() };mat.put(0,0,bytes)
            val contourInput=mat.clone()
            try { Imgproc.findContours(contourInput,contours,hierarchy,Imgproc.RETR_CCOMP,Imgproc.CHAIN_APPROX_SIMPLE) } finally { contourInput.release() }
            if(contours.indices.any { i -> (hierarchy.get(0,i)?.getOrNull(3) ?: -1.0)>=0 && abs(Imgproc.contourArea(contours[i]))>iw*ih*.025 })
                return uncertain("CLOSED_LOOP_OR_CANCELLED_CHECKBOX_INK",m)
            Imgproc.HoughLinesP(mat,lines,1.0,Math.PI/180,max(5,(min(iw,ih)*.13).toInt()),min(iw,ih)*.15,min(iw,ih)*.10)
            val slopes=(0 until lines.rows()).mapNotNull { i ->
                val l=lines.get(i,0);val dx=l[2]-l[0];val dy=l[3]-l[1]
                val length=hypot(dx,dy)
                if (abs(dx)<1 || length<min(iw,ih)*.16) null else (dy/dx).takeIf { abs(it) in .25..4.5 }
            }
            val rising=slopes.any { it < -.30 };val falling=slopes.any { it > .30 }
            val metrics=m+mapOf("spanX" to sx,"spanY" to sy,"diagonalSegments" to slopes.size.toDouble())
            if (rising && falling && ink in .018.. .29) Decision(OptionDecision.SELECTED,"CHECKBOX_TICK_OR_CROSS",metrics)
            else uncertain("CHECKBOX_MARK_NOT_CONFIDENT",metrics)
        } finally { mat.release();lines.release();hierarchy.release();contours.forEach { it.release() } }
    }

    private data class Ellipse(val x:Double,val y:Double,val a:Double,val b:Double,val angle:Double) {
        fun polar(x:Double,y:Double):Pair<Double,Double> {
            val dx=x-this.x;val dy=y-this.y;val c=cos(angle);val s=sin(angle)
            val nx=(dx*c+dy*s)/a;val ny=(-dx*s+dy*c)/b
            return hypot(nx,ny) to atan2(ny,nx)
        }
    }
    private fun circle(p:BinaryPixels, outer:NormalizedRect, core:NormalizedRect):Decision {
        val w=p.width;val h=p.height
        val x0=((core.left-outer.left)/(outer.right-outer.left)*w).roundToInt().coerceIn(0,w-1)
        val y0=((core.top-outer.top)/(outer.bottom-outer.top)*h).roundToInt().coerceIn(0,h-1)
        val x1=((core.right-outer.left)/(outer.right-outer.left)*w).roundToInt().coerceIn(x0+1,w)
        val y1=((core.bottom-outer.top)/(outer.bottom-outer.top)*h).roundToInt().coerceIn(y0+1,h)
        val coreInk=(y0 until y1).sumOf { y -> (x0 until x1).count { x->p[x,y] } }
        val edgeInk=(x0 until x1).count { p[it,y0] || p[it,y1-1] }+(y0 until y1).count { p[x0,it] || p[x1-1,it] }
        val cm=mapOf("coreInkFraction" to coreInk.toDouble()/((x1-x0)*(y1-y0)),"coreEdgeInk" to edgeInk.toDouble())
        if (coreInk<4 || edgeInk>max(2.0,(x1-x0+y1-y0)*.08)) return Decision(OptionDecision.UNSUPPORTED,"PRINTED_CORE_UNRELIABLE",cm)
        if (x1-x0>w*.70 || y1-y0>h*.70) return Decision(OptionDecision.UNSUPPORTED,"PRINTED_CORE_TOO_LARGE",cm)
        val bytes=p.bytes.copyOf()
        // Exclude only the human-confirmed printed glyph core, never an inferred answer circle.
        for (y in max(0,y0-1)..min(h-1,y1)) for(x in max(0,x0-1)..min(w-1,x1)) bytes[y*w+x]=0
        val binary=Mat(h,w,CvType.CV_8UC1);val horizontal=Mat();val vertical=Mat();val grid=Mat();val clean=Mat();val hierarchy=Mat()
        val hk=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(max(15.0,w*.76),1.0))
        val vk=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(1.0,max(15.0,h*.76)))
        val contours=mutableListOf<MatOfPoint>()
        return try {
            binary.put(0,0,bytes)
            Imgproc.morphologyEx(binary,horizontal,Imgproc.MORPH_OPEN,hk);Imgproc.morphologyEx(binary,vertical,Imgproc.MORPH_OPEN,vk)
            Core.bitwise_or(horizontal,vertical,grid);Core.subtract(binary,grid,clean)
            val remaining=ByteArray(w*h);clean.get(0,0,remaining)
            val points=remaining.indices.filter { remaining[it].toInt()!=0 }.map { (it%w).toDouble() to (it/w).toDouble() }
            val ink=points.size.toDouble()/(w*h);val m=cm+mapOf("outerInkFraction" to ink)
            if (points.size<=2) return Decision(OptionDecision.EMPTY,"NO_SURROUNDING_CIRCLE",m)
            if (points.size<max(3.0,w*h*.002)) return uncertain("ISOLATED_OUTER_INK",m)
            if (points.any { (x,y)->x<1 || y<1 || x>w-2 || y>h-2 }) return uncertain("CIRCLE_OR_INK_CROPPED",m)
            Imgproc.findContours(clean,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_NONE)
            val ellipses=mutableListOf<Ellipse>()
            for(contour in contours) {
                if(contour.total()<15) continue
                val mp=MatOfPoint2f(*contour.toArray())
                try {
                    val box=Imgproc.fitEllipse(mp)
                    val e=Ellipse(box.center.x,box.center.y,box.size.width/2,box.size.height/2,Math.toRadians(box.angle))
                    if(e.a<8 || e.b<8 || max(e.a,e.b)/min(e.a,e.b)>2.5 || max(e.a,e.b)>max(w,h)*.58)continue
                    val corners=listOf(x0.toDouble() to y0.toDouble(),x1.toDouble() to y0.toDouble(),x1.toDouble() to y1.toDouble(),x0.toDouble() to y1.toDouble())
                    if(corners.any { (x,y)->e.polar(x,y).first>.91 })continue
                    val radial=contour.toArray().map { abs(e.polar(it.x,it.y).first-1) }.sorted()
                    if(radial[(radial.size*.85).toInt()]>MarkParameters.MAX_RING_RESIDUAL)continue
                    if(ellipses.none { old->hypot(old.x-e.x,old.y-e.y)<4 && abs(old.a-e.a)<max(3.5,e.a*.13) && abs(old.b-e.b)<max(3.5,e.b*.13) })ellipses+=e
                } finally { mp.release() }
            }
            if(ellipses.size!=1) return uncertain(if(ellipses.isEmpty())"NO_RELIABLE_SURROUNDING_CURVE" else "MULTIPLE_SURROUNDING_CURVES",m+mapOf("ellipseCount" to ellipses.size.toDouble()))
            val e=ellipses.single();val bins=BooleanArray(72)
            val tolerance=max(.10,2.7/min(e.a,e.b));var inRing=0
            for((x,y) in points) {
                val (radius,angle)=e.polar(x,y)
                if(abs(radius-1)<=tolerance){inRing++;bins[((angle+PI)/(2*PI)*72).toInt().coerceIn(0,71)]=true}
            }
            val coverage=bins.count { it }/72.0;val off=1-inRing.toDouble()/points.size
            val metrics=m+mapOf("ellipseCount" to 1.0,"angleCoverage" to coverage,"offCurveInkFraction" to off,
                "ellipseCenterX" to e.x/w,"ellipseCenterY" to e.y/h,"ellipseRadiusA" to e.a/w,"ellipseRadiusB" to e.b/h)
            if(coverage>=MarkParameters.ELLIPSE_COVERAGE && off<=MarkParameters.MAX_OFF_RING) Decision(OptionDecision.SELECTED,"CIRCLE_AROUND_PRINTED_CORE",metrics)
            else uncertain("PARTIAL_CANCELLED_OR_STRAY_CIRCLE_INK",metrics)
        } finally {
            listOf(binary,horizontal,vertical,grid,clean,hierarchy,hk,vk).forEach { it.release() };contours.forEach { it.release() }
        }
    }

    private data class BinaryPixels(val width:Int,val height:Int,val bytes:ByteArray,val sha256:String,val softInk:Double) {
        operator fun get(x:Int,y:Int)=bytes[y*width+x].toInt()!=0
        val metrics get()=mapOf("cropWidth" to width.toDouble(),"cropHeight" to height.toDouble(),"softInkFraction" to softInk)
        companion object {
            fun read(image:BufferedImage,region:NormalizedRect):BinaryPixels {
                OpenCvGeometry.requireAvailable()
                val x=floor(region.left*image.width).toInt();val y=floor(region.top*image.height).toInt()
                val w=(ceil(region.right*image.width).toInt()-x).coerceAtLeast(1);val h=(ceil(region.bottom*image.height).toInt()-y).coerceAtLeast(1)
                val crop=image.getSubimage(x,y,w.coerceAtMost(image.width-x),h.coerceAtMost(image.height-y))
                val rgba=Mat();val gray=Mat();val binary=Mat()
                return try {
                    OpenCvImages.toMat(crop,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
                    Imgproc.threshold(gray,binary,0.0,255.0,Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                    val bytes=ByteArray(crop.width*crop.height);binary.get(0,0,bytes)
                    val gs=ByteArray(bytes.size);gray.get(0,0,gs)
                    val low=gs.minOf { it.toInt() and 255 };val high=gs.maxOf { it.toInt() and 255 };val span=high-low
                    val ink=gs.count { (it.toInt() and 255)<high-span*.20 }
                    val soft=gs.count { (it.toInt() and 255)>low+span*.30 && (it.toInt() and 255)<high-span*.20 }
                    val digest=MessageDigest.getInstance("SHA-256");val ints=crop.getRGB(0,0,crop.width,crop.height,null,0,crop.width)
                    val buffer=ByteBuffer.allocate(ints.size*4);ints.forEach { buffer.putInt(it) };digest.update(buffer.array())
                    BinaryPixels(crop.width,crop.height,bytes,digest.digest().joinToString(""){ "%02x".format(it.toInt() and 255) },if(ink==0)0.0 else soft.toDouble()/ink)
                } finally { rgba.release();gray.release();binary.release() }
            }
        }
    }
}
