package com.karen.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.karen.utils.ArtifactMeta
import com.karen.utils.TraceKind
import com.karen.utils.TraceRow
import com.karen.utils.TraceState
import com.karen.utils.appendTrace
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * On-device presentation builder (OBSERVATION_STEPS pattern, PPT variant).
 *
 * Generates a real .pptx with the Android framework only (java.util.zip +
 * handwritten OOXML — no dependencies): minimal professional deck,
 * title slide + agenda + content slides + closing, white background,
 * dark slate text, single blue accent. Slides re-open after writing
 * (render→read→flag): zip is listed, slide count checked, each slide's
 * title text extracted; problems surface as Flagging trace rows.
 */
object PptxBuilder {
    private const val TAG = "KarenPptx"

    data class Outline(
        val title: String,
        val subtitle: String,
        val slides: Int = 8
    )

    data class BuildResult(val file: File, val meta: ArtifactMeta, val trace: List<TraceRow>)

    /** Best-effort outline: "8 slide ppt for X" → title X. */
    fun outlineFor(prompt: String, fallbackTitle: String = "Presentation"): Outline {
        val p = prompt.trim()
        val slides = Regex("(?i)(\\d+)\\s*(?:slide|slides|page|pages)").find(p)
            ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(4, 40) ?: 8
        val subject = Regex("(?i)(?:ppt|deck|presentation|slides?)\\s+(?:for|on|about)\\s+([A-Za-z0-9][A-Za-z0-9 .'-]{1,40})").find(p)
            ?.groupValues?.get(1)?.trim()?.substringBefore(" and ")?.substringBefore(" step")?.trim()
            ?: Regex("(?i)for\\s+([A-Za-z0-9][A-Za-z0-9 .'-]{1,40})").find(p)
                ?.groupValues?.get(1)?.trim()?.substringBefore(" and ")?.substringBefore(" step")?.trim()
        val title = subject?.ifBlank { null } ?: fallbackTitle
        return Outline(title = title, subtitle = "A minimal, professional deck.", slides = slides)
    }

    /** Builds the .pptx, re-opens it to verify, returns trace + file. */
    fun build(ctx: Context, outline: Outline): BuildResult {
        val trace = ArrayList<TraceRow>()
        trace += TraceRow("Build the deck slide by slide", TraceKind.Build, TraceState.Started)
        val dir = File(ctx.filesDir, "artifacts").apply { mkdirs() }
        val safe = outline.title.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "deck" }.take(40)
        val dest = File(dir, "${safe}_presentation.pptx".lowercase())
        // Preferred path: on-device Python (Chaquopy) runs src/main/python.
        // Fallback: pure-Kotlin writer below (same OOXML output).
        val viaPython = tryBuildWithPython(ctx, outline, dest)
        if (!viaPython) writePptx(dest, outline)
        trace += TraceRow("Created ${dest.name}", TraceKind.Create, TraceState.Completed)
        trace += TraceRow("Open the deck and check the slides", TraceKind.Read, TraceState.Info)
        val issues = verify(dest, outline.slides)
        issues.forEach { trace += TraceRow(it, TraceKind.Flag, TraceState.Info) }
        trace += TraceRow("Build the deck slide by slide", TraceKind.Build, TraceState.Completed)
        return BuildResult(dest, ArtifactMeta(dest.name, outline.slides, "pptx"), trace)
    }

    /** Runs the bundled Python script; false (no throw) when unavailable. */
    private fun tryBuildWithPython(context: Context, outline: Outline, dest: File): Boolean = try {
        val py = com.chaquo.python.Python.getInstance()
        val mod = py.getModule("make_pptx")
        val count = mod.callAttr("build_pptx", outline.title, outline.slides, dest.absolutePath, outline.subtitle)
            ?.toInt() ?: 0
        val ok = count > 0 && dest.exists() && dest.length() > 0
        Log.i(TAG, "PPT build via Python: $ok (${dest.length()} bytes, $count slides)")
        ok
    } catch (e: Exception) {
        Log.i(TAG, "Python unavailable, falling back to Kotlin builder: ${e.message}")
        false
    }

    // ---- PPTX generation (handwritten OOXML) ----

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun slideXml(title: String, bullets: List<String>, isTitleSlide: Boolean, subtitle: String): String {
        val body = buildString {
            append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>")
            append("<p:spPr><a:xfrm><a:off x=\"228600\" y=\"228600\"/><a:ext cx=\"8686800\" cy=\"1371600\"/></a:xfrm></p:spPr>")
            append("<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\" sz=\"${if (isTitleSlide) 4000 else 3200}\" b=\"1\"><a:solidFill><a:srgbClr val=\"0F172A\"/></a:solidFill></a:rPr><a:t>${esc(title)}</a:t></a:r></a:p>")
            if (isTitleSlide && subtitle.isNotBlank()) {
                append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"1600\"><a:solidFill><a:srgbClr val=\"475569\"/></a:solidFill></a:rPr><a:t>${esc(subtitle)}</a:t></a:r></a:p>")
            }
            append("</p:txBody></p:sp>")
        }
        val list = buildString {
            append("<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Body\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>")
            append("<p:spPr><a:xfrm><a:off x=\"228600\" y=\"2057400\"/><a:ext cx=\"8686800\" cy=\"3657600\"/></a:xfrm></p:spPr>")
            append("<p:txBody><a:bodyPr/><a:lstStyle/>")
            bullets.forEach { b ->
                append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"1800\"><a:solidFill><a:srgbClr val=\"334155\"/></a:solidFill></a:rPr><a:t>${esc(b)}</a:t></a:r></a:p>")
            }
            append("</p:txBody></p:sp>")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></p:bgPr></p:bg><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:grpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$body$list</p:spTree></p:cSld></p:sld>"""
    }

    private fun presentationXml(n: Int): String {
        val sldIds = (1..n).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"rId${it + 1}\"/>" }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$sldIds</p:sldIdLst><p:sldSz cx="12192000" cy="6858000" type="wide"/><p:notesMasterIdLst><p:notesMasterId r:id="rId${n + 2}"/></p:notesMasterIdLst></p:presentation>"""
    }

    private fun contentTypes(n: Int): String {
        val slides = (1..n).joinToString("") { "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/><Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"/>$slides</Types>"""
    }

    private val presRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/></Relationships>"""

    private val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>"""

    private val slideMaster = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:grpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld></p:sldMaster>"""

    private val slideLayout = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:grpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld></p:sldLayout>"""

    private val theme = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Karen"><a:themeElements><a:clrScheme name="Karen"><a:dk1><a:sysClr val="windowText" lastClr="0F172A"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1E293B"/></a:dk2><a:lt2><a:srgbClr val="F8FAFC"/></a:lt2><a:accent1><a:srgbClr val="2563EB"/></a:accent1><a:accent2><a:srgbClr val="34D399"/></a:accent2><a:accent3><a:srgbClr val="FBBF24"/></a:accent3><a:accent4><a:srgbClr val="F472B6"/></a:accent4><a:accent5><a:srgbClr val="94A3B8"/></a:accent5><a:accent6><a:srgbClr val="0F172A"/></a:accent6><a:hlink><a:srgbClr val="2563EB"/></a:hlink><a:folHlink><a:srgbClr val="1E3A8A"/></a:folHlink></a:clrScheme><a:fontScheme name="Karen"><a:majorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="Karen"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""

    private val notesMaster = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:notesMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:grpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld></p:notesMaster>"""

    private fun slideRels(it: Int) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""

    private fun presRelsFull(n: Int): String {
        val slideRels = (1..n).joinToString("") {
            "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$it.xml\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>$slideRels<Relationship Id="rId${n + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster" Target="notesMasters/notesMaster1.xml"/></Relationships>"""
    }

    private fun writePptx(dest: File, o: Outline) {
        val n = o.slides
        // Minimal professional content from the prompt subject.
        val titles = ArrayList<String>()
        titles += o.title
        titles += "Agenda"
        val sections = listOf(
            "Why it matters", "What we cover", "Key ideas", "How it works",
            "By the numbers", "Checklist", "Budget", "Examples",
            "Watch-outs", "Next steps", "Q&A", "Thanks"
        )
        for (i in 0 until (n - 3)) titles += sections[i % sections.size]
        titles += "Thanks"
        while (titles.size < n) titles += titles.last()
        val bulletsFor: (Int) -> List<String> = { i ->
            when (i) {
                0 -> listOf(o.subtitle)
                1 -> (0 until n - 2).map { "• ${titles[it + 1]}" }
                n - 1 -> listOf("Questions welcome.", "Contact details in the notes.")
                else -> listOf(
                    "Illustrative editorial example — edit before presenting.",
                    "Facts from public sources where stated.",
                    "One idea per slide; keep text short."
                )
            }
        }
        ZipOutputStream(dest.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(n))
            put("_rels/.rels", rootRels)
            put("ppt/presentation.xml", presentationXml(n))
            put("ppt/_rels/presentation.xml.rels", presRelsFull(n))
            put("ppt/slideMasters/slideMaster1.xml", slideMaster)
            put("ppt/slideLayouts/slideLayout1.xml", slideLayout)
            put("ppt/theme/theme1.xml", theme)
            put("ppt/notesMasters/notesMaster1.xml", notesMaster)
            for (i in 1..n) {
                put("ppt/slides/slide$i.xml", slideXml(titles[i - 1], bulletsFor(i - 1), i == 1, o.subtitle))
                put("ppt/slides/_rels/slide$i.xml.rels", slideRels(i))
            }
        }
    }

    /** Re-open: count slides + extract each slide title. Flags problems. */
    private fun verify(file: File, expected: Int): List<String> {
        val issues = ArrayList<String>()
        try {
            ZipFile(file).use { zip ->
                val slides = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                    .toList()
                if (slides.size != expected) issues.add("slide count mismatch: expected $expected, found ${slides.size}")
                slides.forEach { name ->
                    val xml = zip.getInputStream(zip.getEntry(name))?.bufferedReader()?.readText().orEmpty()
                    if (!xml.contains("<a:t>") || !xml.contains("0F172A")) {
                        issues.add("slide ${name.substringAfterLast('/')} needs a title")
                    }
                }
            }
        } catch (e: Exception) {
            issues.add("verification failed for ${file.name}: ${e.message}")
        }
        return issues
    }

    /** Extracts slide titles for the in-chat slide list. */
    fun slideTitles(file: File): List<String> {
        val out = ArrayList<String>()
        try {
            ZipFile(file).use { zip ->
                val slides = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                    .sortedBy { it.substringAfter("slide").substringBefore(".").toIntOrNull() ?: 999 }
                    .toList()
                slides.forEach { name ->
                    val xml = zip.getInputStream(zip.getEntry(name))?.bufferedReader()?.readText().orEmpty()
                    val title = Regex("<a:t>([^<]+)</a:t>").find(xml)?.groupValues?.get(1)?.trim().orEmpty()
                    out += title.ifBlank { name.substringAfterLast('/') }
                }
            }
        } catch (_: Exception) {
            // viewer falls back to a single-row summary
        }
        return out
    }

    /** Human rundown for the final message. */
    fun rundown(outline: Outline): String = buildString {
        append("Here's your ${outline.slides}-slide ${outline.title} presentation:")
        append("\n- Slide 1: title — ${outline.title}")
        append("\n- Slide 2: agenda")
        append("\n- Slides 3–${outline.slides - 1}: sections (why it matters, key ideas, how it works, numbers, checklist, budget, examples, watch-outs, next steps)")
        append("\n- Slide ${outline.slides}: thanks / Q&A")
        append("\n\nMinimal professional style — white background, slate text, single blue accent. Content is an illustrative editorial example; edit before presenting.")
    }

    /** Extends [text] with trace rows + artifact line from a build result. */
    fun withBuild(text: String, result: BuildResult): String {
        var out = text
        result.trace.forEach { out = appendTrace(out, it) }
        val line = "///ARTIFACT {\"name\":\"${result.file.name}\",\"pages\":${result.meta.pages},\"kind\":\"pptx\"}"
        return if (out.isBlank()) line else out.trimEnd() + "\n" + line
    }

    fun shareIntent(ctx: Context, file: File): Intent? = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (_: Exception) {
        null
    }

    fun downloadIntent(ctx: Context, file: File): Intent? = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (_: Exception) {
        null
    }
}
