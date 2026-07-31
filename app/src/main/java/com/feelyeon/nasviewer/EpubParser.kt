package com.feelyeon.nasviewer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

// relativePath is the chapter's path inside the EPUB (e.g. "Text/chapter1.xhtml") — used as
// a highlight-storage key since absoluteFile.name alone (just the basename) can collide when
// an EPUB has same-named chapter files under different folders.
data class EpubChapter(val title: String, val absoluteFile: File, val relativePath: String)
data class EpubBook(val chapters: List<EpubChapter>)

class EpubParseException(message: String) : Exception(message)

/**
 * Minimal EPUB reader: enough to find the OPF manifest/spine (reading order) and,
 * best-effort, chapter titles from an EPUB2 toc.ncx if the book includes one. Not a
 * full EPUB3 nav-document implementation, but covers the common case.
 */
object EpubParser {

    fun parse(epubFile: File, extractDir: File): EpubBook {
        ZipFile(epubFile).use { zip ->
            extractAll(zip, extractDir)

            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: throw EpubParseException("올바른 EPUB 파일이 아닙니다 (container.xml 없음).")
            val containerXml = zip.getInputStream(containerEntry).bufferedReader().readText()
            val opfPath = parseContainerForOpfPath(containerXml)
                ?: throw EpubParseException("EPUB 안에서 콘텐츠 목록(OPF)을 찾을 수 없습니다.")

            val opfEntry = zip.getEntry(opfPath)
                ?: throw EpubParseException("EPUB 콘텐츠 파일을 찾을 수 없습니다: $opfPath")
            val opfXml = zip.getInputStream(opfEntry).bufferedReader().readText()
            val opfDir = opfPath.substringBeforeLast('/', "")

            val (manifest, spineIds, ncxHref) = parseOpf(opfXml)
            val titlesByHref = ncxHref?.let { href ->
                val resolvedNcx = resolvePath(opfDir, href)
                zip.getEntry(resolvedNcx)?.let { entry ->
                    val ncxXml = zip.getInputStream(entry).bufferedReader().readText()
                    parseNcxTitles(ncxXml)
                }
            } ?: emptyMap()

            val chapters = spineIds.mapIndexedNotNull { index, idref ->
                val href = manifest[idref] ?: return@mapIndexedNotNull null
                val resolvedHref = resolvePath(opfDir, href)
                val title = titlesByHref[resolvedHref] ?: titlesByHref[href] ?: "챕터 ${index + 1}"
                EpubChapter(title = title, absoluteFile = File(extractDir, resolvedHref), relativePath = resolvedHref)
            }

            if (chapters.isEmpty()) throw EpubParseException("EPUB 안에서 읽을 챕터를 찾지 못했습니다.")
            return EpubBook(chapters)
        }
    }

    // Extracts into a sibling temp dir and only renames it onto extractDir once every entry
    // has copied successfully — otherwise an app kill or IO error partway through a previous
    // extraction would leave extractDir existing-but-incomplete, and the early-return above
    // would keep reusing that corrupt partial extraction forever.
    private fun extractAll(zip: ZipFile, extractDir: File) {
        if (extractDir.exists()) return // already extracted (and verified complete) for this book
        val tmpDir = File(extractDir.parentFile, "${extractDir.name}.tmp")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        try {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val outFile = File(tmpDir, entry.name)
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (!tmpDir.renameTo(extractDir)) {
                throw EpubParseException("EPUB 압축 해제 결과를 저장하지 못했습니다.")
            }
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            throw e
        }
    }

    private fun resolvePath(baseDir: String, relative: String): String {
        if (baseDir.isBlank()) return relative
        return File(baseDir, relative).path.replace('\\', '/')
    }

    private fun parseContainerForOpfPath(xml: String): String? {
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (fullPath != null) return fullPath
            }
            event = parser.next()
        }
        return null
    }

    // Returns (manifestIdToHref, spineIdrefsInOrder, ncxHrefIfAny)
    private fun parseOpf(xml: String): Triple<Map<String, String>, List<String>, String?> {
        val parser = newParser(xml)
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        var ncxId: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type")
                        if (id != null && href != null) manifest[id] = href
                        if (mediaType == "application/x-dtbncx+xml") ncxId = id
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (idref != null) spine.add(idref)
                    }
                    "spine" -> {
                        val tocAttr = parser.getAttributeValue(null, "toc")
                        if (tocAttr != null) ncxId = tocAttr
                    }
                }
            }
            event = parser.next()
        }
        val ncxHref = ncxId?.let { manifest[it] }
        return Triple(manifest, spine, ncxHref)
    }

    // Maps the NCX entry's content src (chapter file href, possibly with #fragment) to its navLabel text.
    private fun parseNcxTitles(xml: String): Map<String, String> {
        val parser = newParser(xml)
        val result = mutableMapOf<String, String>()
        var currentLabel: String? = null
        var capturingText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "text" -> capturingText = true
                    "content" -> {
                        val src = parser.getAttributeValue(null, "src")
                        val label = currentLabel
                        if (src != null && label != null) {
                            result[src.substringBefore('#')] = label
                        }
                    }
                }
                XmlPullParser.TEXT -> if (capturingText) currentLabel = parser.text?.trim()
                XmlPullParser.END_TAG -> if (parser.name == "text") capturingText = false
            }
            event = parser.next()
        }
        return result
    }

    private fun newParser(xml: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        return parser
    }
}
