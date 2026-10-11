package com.liyang.pnginfo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater

/** 解析后的生成信息 */
data class ImageMeta(
    val source: String?,                       // ComfyUI / A1111 / NovelAI ...
    val positive: String?,
    val negative: String?,
    val params: List<Pair<String, String>>,    // 其他参数（种子、步数、模型……）
    val extraPrompts: List<String> = emptyList(), // 无法区分正负时的所有提示词
    val raw: List<Pair<String, String>>,       // 原始元数据块
) {
    val isEmpty get() = positive == null && negative == null && params.isEmpty() && extraPrompts.isEmpty() && raw.isEmpty()

    /** 组装成 A1111 风格的整段文本，方便一键复制全部 */
    fun toA1111Text(): String = buildString {
        positive?.let { append(it) }
        negative?.let { if (isNotEmpty()) append('\n'); append("Negative prompt: ").append(it) }
        val p = params.filterNot { it.first == "图片尺寸" || it.first == "来源" }
        if (p.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append(p.joinToString(", ") { "${it.first}: ${it.second}" })
        }
        if (isEmpty() && extraPrompts.isNotEmpty()) append(extraPrompts.joinToString("\n\n"))
    }
}

object MetadataReader {

    fun read(context: Context, uri: Uri): ImageMeta {
        val texts = LinkedHashMap<String, String>()
        val cr = context.contentResolver
        val header = cr.openInputStream(uri)?.use { s -> ByteArray(12).also { readFully(s, it) } } ?: ByteArray(0)

        when {
            isPng(header) -> cr.openInputStream(uri)?.use { texts.putAll(readPngText(it)) }
            else -> runCatching { cr.openInputStream(uri)?.use { texts.putAll(readExifText(it)) } }
        }

        val size = runCatching {
            cr.openInputStream(uri)?.use { s ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(s, null, o)
                if (o.outWidth > 0) "${o.outWidth}×${o.outHeight}" else null
            }
        }.getOrNull()

        return interpret(texts, size)
    }

    // ---------------------------------------------------------------- PNG

    private fun isPng(h: ByteArray) = h.size >= 8 &&
        h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() && h[3] == 'G'.code.toByte()

    fun readPngText(input: InputStream): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val s = DataInputStream(input.buffered())
        s.skipFully(8)
        while (true) {
            val len = try { s.readInt() } catch (e: Exception) { break }
            val typeBytes = ByteArray(4)
            if (!tryReadFully(s, typeBytes)) break
            val type = String(typeBytes, Charsets.ISO_8859_1)
            if (len < 0) break
            if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                val data = ByteArray(len)
                if (!tryReadFully(s, data)) break
                runCatching { parseTextChunk(type, data) }.getOrNull()?.let { (k, v) ->
                    out[k] = if (out.containsKey(k)) out[k] + "\n" + v else v
                }
            } else {
                s.skipFully(len.toLong())
            }
            s.skipFully(4) // CRC
            if (type == "IEND") break
        }
        return out
    }

    private fun parseTextChunk(type: String, d: ByteArray): Pair<String, String> {
        val z = d.indexOf(0)
        val key = String(d, 0, z, Charsets.ISO_8859_1)
        return when (type) {
            "tEXt" -> key to decodeSmart(d.copyOfRange(z + 1, d.size))
            "zTXt" -> key to decodeSmart(inflate(d.copyOfRange(z + 2, d.size)))
            else -> { // iTXt
                val compressed = d[z + 1].toInt() == 1
                var p = z + 3
                val langEnd = indexOfZero(d, p); p = langEnd + 1
                val transEnd = indexOfZero(d, p); p = transEnd + 1
                val body = d.copyOfRange(p, d.size)
                key to String(if (compressed) inflate(body) else body, Charsets.UTF_8)
            }
        }
    }

    // ---------------------------------------------------------------- JPEG / WebP (EXIF)

    private fun readExifText(input: InputStream): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val exif = ExifInterface(input)
        exif.getAttributeBytes(ExifInterface.TAG_USER_COMMENT)?.let { decodeUserComment(it) }
            ?.takeIf { it.isNotBlank() }?.let { out["UserComment"] = it }
        listOf(
            ExifInterface.TAG_IMAGE_DESCRIPTION to "ImageDescription",
            ExifInterface.TAG_MAKE to "Make",
            ExifInterface.TAG_MODEL to "Model",
            ExifInterface.TAG_SOFTWARE to "Software",
            ExifInterface.TAG_ARTIST to "Artist",
        ).forEach { (tag, name) ->
            exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { out[name] = it }
        }
        // ComfyUI 保存 webp 时会把 "prompt:{...}" / "workflow:{...}" 写进 EXIF 文本字段
        for ((k, v) in out.toList()) {
            val lower = v.take(12).lowercase()
            when {
                lower.startsWith("prompt:") -> { out["prompt"] = v.substringAfter(':'); out.remove(k) }
                lower.startsWith("workflow:") -> { out["workflow"] = v.substringAfter(':'); out.remove(k) }
            }
        }
        // UserComment：A1111/Forge 的 parameters，或 ComfyUI 的 prompt JSON。
        // 其他软件（例如 Grok 生成图的 "Signature: ..." 签名）只是普通注释，留在原始元数据里，不当成提示词。
        out.remove("UserComment")?.let { uc ->
            val t = uc.trimStart()
            when {
                t.startsWith("{") && !out.containsKey("prompt") && looksLikeComfyPrompt(uc) -> out["prompt"] = uc
                t.startsWith("{") || looksLikeA1111(uc) -> out["parameters"] = uc
                else -> out["UserComment"] = uc
            }
        }
        return out
    }

    /** A1111 / Forge 的 parameters 一定带有这些参数名之一。 */
    private fun looksLikeA1111(text: String): Boolean =
        listOf("Steps:", "Negative prompt:", "Sampler:", "CFG scale:", "Seed:").any { text.contains(it) }

    private fun decodeUserComment(b: ByteArray): String {
        if (b.size < 8) return String(b, Charsets.UTF_8)
        val head = String(b, 0, 8, Charsets.ISO_8859_1)
        val body = b.copyOfRange(8, b.size)
        val text = when {
            head.startsWith("UNICODE") -> {
                // 判断字节序：ASCII 字符在 BE 中高字节为 0
                val be = body.size >= 2 && body[0].toInt() == 0 && body[1].toInt() != 0
                val le = body.size >= 2 && body[1].toInt() == 0 && body[0].toInt() != 0
                String(body, if (le && !be) Charsets.UTF_16LE else Charsets.UTF_16BE)
            }
            head.startsWith("ASCII") || head.all { it == '\u0000' } -> String(body, Charsets.UTF_8)
            else -> String(b, Charsets.UTF_8)
        }
        return text.trimEnd('\u0000').trim()
    }

    // ---------------------------------------------------------------- 解释

    private fun interpret(texts: Map<String, String>, size: String?): ImageMeta {
        val raw = texts.map { (k, v) -> k to prettyJsonIfPossible(v) }
        val sizeParam = listOfNotNull(size?.let { "图片尺寸" to it })

        // 1) ComfyUI（API 格式 prompt）
        texts["prompt"]?.takeIf { looksLikeComfyPrompt(it) }?.let { p ->
            ComfyParser.parsePrompt(p)?.let { r ->
                return ImageMeta("ComfyUI", r.positive, r.negative, r.params + sizeParam, r.others, raw)
            }
        }
        // 2) A1111 / Forge / Fooocus / SwarmUI 的 parameters
        texts["parameters"]?.let { p ->
            val t = p.trim()
            if (t.startsWith("{")) parseJsonParameters(t)?.let {
                return it.copy(params = it.params + sizeParam, raw = raw)
            }
            val r = parseA1111(t)
            return ImageMeta("A1111 / Forge", r.first, r.second, r.third + sizeParam, raw = raw)
        }
        // 3) NovelAI
        texts["Comment"]?.let { c ->
            runCatching {
                val j = JSONObject(c)
                val params = mutableListOf<Pair<String, String>>()
                listOf("steps" to "Steps", "scale" to "CFG scale", "seed" to "Seed", "sampler" to "Sampler",
                    "noise_schedule" to "Schedule", "strength" to "Strength").forEach { (k, n) ->
                    if (j.has(k)) params += n to j.get(k).toString()
                }
                texts["Source"]?.let { params += "Model" to it }
                return ImageMeta("NovelAI", texts["Description"] ?: j.optString("prompt").ifBlank { null },
                    j.optString("uc").ifBlank { null }, params + sizeParam, raw = raw)
            }
        }
        // 4) InvokeAI
        texts["invokeai_metadata"]?.let { m ->
            runCatching {
                val j = JSONObject(m)
                val params = mutableListOf<Pair<String, String>>()
                listOf("seed" to "Seed", "steps" to "Steps", "cfg_scale" to "CFG scale", "scheduler" to "Scheduler",
                    "width" to "Width", "height" to "Height").forEach { (k, n) ->
                    if (j.has(k)) params += n to j.get(k).toString()
                }
                j.optJSONObject("model")?.let { params += "Model" to (it.optString("name").ifBlank { it.optString("model_name") }) }
                return ImageMeta("InvokeAI", j.optString("positive_prompt").ifBlank { null },
                    j.optString("negative_prompt").ifBlank { null }, params + sizeParam, raw = raw)
            }
        }
        // 5) 只有 ComfyUI workflow（UI 格式）
        texts["workflow"]?.let { w ->
            ComfyParser.parseWorkflow(w)?.let { r ->
                return ImageMeta("ComfyUI (workflow)", r.positive, r.negative, r.params + sizeParam, r.others, raw)
            }
        }
        return ImageMeta(null, null, null, sizeParam, raw = raw)
    }

    /** A1111 格式：正向\nNegative prompt: 负向\nSteps: 20, Sampler: ... */
    fun parseA1111(text: String): Triple<String?, String?, List<Pair<String, String>>> {
        val lines = text.lines()
        val idx = lines.indexOfLast { it.trimStart().startsWith("Steps:") }
        val paramText = if (idx >= 0) lines.subList(idx, lines.size).joinToString("\n") else ""
        val body = (if (idx >= 0) lines.subList(0, idx) else lines).joinToString("\n")

        val negMarker = Regex("(^|\\n)Negative prompt:\\s?")
        val m = negMarker.find(body)
        val pos = (if (m != null) body.substring(0, m.range.first) else body).trim()
        val neg = m?.let { body.substring(it.range.last + 1).trim() }

        val params = mutableListOf<Pair<String, String>>()
        val re = Regex("""\s*(\w[\w \-/]+):\s*("(?:\\.|[^\\"])+"|[^,]*)(?:,|$)""")
        for (pm in re.findAll(paramText)) {
            var v = pm.groupValues[2].trim()
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
            params += pm.groupValues[1].trim() to v
        }
        return Triple(pos.ifEmpty { null }, neg?.ifEmpty { null }, params)
    }

    /** Fooocus / SwarmUI 等以 JSON 存放 parameters */
    private fun parseJsonParameters(t: String): ImageMeta? = runCatching {
        var j = JSONObject(t)
        var source = "JSON parameters"
        if (j.has("sui_image_params")) { j = j.getJSONObject("sui_image_params"); source = "SwarmUI" }
        if (j.optString("metadata_scheme") == "fooocus" || j.has("fooocus_scheme")) source = "Fooocus"
        val posKeys = listOf("prompt", "positive_prompt", "full_prompt")
        val negKeys = listOf("negative_prompt", "negativeprompt", "full_negative_prompt")
        val pos = posKeys.firstNotNullOfOrNull { k -> j.opt(k)?.let { flattenText(it) }?.ifBlank { null } }
        val neg = negKeys.firstNotNullOfOrNull { k -> j.opt(k)?.let { flattenText(it) }?.ifBlank { null } }
        val params = j.keys().asSequence()
            .filter { it !in posKeys && it !in negKeys }
            .mapNotNull { k -> val v = j.get(k); if (v is JSONObject || v is JSONArray) null else k to v.toString() }
            .toList()
        ImageMeta(source, pos, neg, params, raw = emptyList())
    }.getOrNull()

    private fun flattenText(v: Any): String = when (v) {
        is JSONArray -> (0 until v.length()).joinToString("\n") { v.get(it).toString() }
        else -> v.toString()
    }

    fun looksLikeComfyPrompt(s: String) = s.contains("class_type")

    // ---------------------------------------------------------------- 工具

    private fun prettyJsonIfPossible(s: String): String {
        val t = s.trim()
        return runCatching {
            when {
                t.startsWith("{") -> JSONObject(t).toString(2)
                t.startsWith("[") -> JSONArray(t).toString(2)
                else -> s
            }
        }.getOrDefault(s).replace("\\/", "/")
    }

    private fun decodeSmart(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        String(b, Charsets.ISO_8859_1)
    }

    private fun inflate(b: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(b)
        val out = ByteArrayOutputStream(b.size * 4)
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    private fun indexOfZero(d: ByteArray, from: Int): Int {
        for (i in from until d.size) if (d[i].toInt() == 0) return i
        return d.size
    }

    private fun readFully(s: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) { val n = s.read(b, off, b.size - off); if (n < 0) break; off += n }
    }

    private fun tryReadFully(s: DataInputStream, b: ByteArray) = try { s.readFully(b); true } catch (e: Exception) { false }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val k = skip(left)
            if (k <= 0) { if (read() < 0) return; left-- } else left -= k
        }
    }
}
