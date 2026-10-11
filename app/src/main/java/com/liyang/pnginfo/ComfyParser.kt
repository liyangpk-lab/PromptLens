package com.liyang.pnginfo

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从 ComfyUI 的节点图中追踪出正/负提示词与采样参数。
 * - parsePrompt：PNG 里的 "prompt"（API 格式，{id: {class_type, inputs}}）
 * - parseWorkflow：PNG 里的 "workflow"（UI 格式，只有在没有 prompt 时才使用）
 */
object ComfyParser {

    data class Result(
        val positive: String?,
        val negative: String?,
        val params: List<Pair<String, String>>,
        val others: List<String>,
    )

    private val TEXT_KEYS = listOf(
        "populated_text", "text", "text_g", "text_l", "t5xxl", "clip_l", "prompt", "positive_prompt",
        "string", "String", "Text", "value", "wildcard_text", "text_positive",
    )
    private val NON_TEXT_LINKS = setOf(
        "clip", "model", "vae", "control_net", "image", "images", "mask", "latent_image", "samples", "pixels",
        "clip_vision", "clip_vision_output", "style_model", "upscale_model", "noise", "sigmas", "sampler", "seed",
    )

    // ------------------------------------------------------------ API 格式

    fun parsePrompt(json: String): Result? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val nodes = LinkedHashMap<String, JSONObject>()
        root.keys().asSequence()
            .sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
            .forEach { k -> root.optJSONObject(k)?.takeIf { it.has("class_type") }?.let { nodes[k] = it } }
        if (nodes.isEmpty()) return null

        fun cls(n: JSONObject) = n.optString("class_type")
        fun inputs(n: JSONObject) = n.optJSONObject("inputs") ?: JSONObject()
        fun isLink(v: Any?) = v is JSONArray && v.length() == 2 && (v.opt(0) is String || v.opt(0) is Number)

        fun resolveText(id: String, slot: Int = 0, visited: MutableSet<String> = HashSet()): List<String> {
            if (!visited.add("$id:$slot") || visited.size > 200) return emptyList()
            val n = nodes[id] ?: return emptyList()
            val ins = inputs(n)
            val found = mutableListOf<String>()
            for (k in TEXT_KEYS) {
                if (!ins.has(k)) continue
                when (val v = ins.get(k)) {
                    is String -> if (v.isNotBlank()) found += v
                    is JSONArray -> if (isLink(v)) found += resolveText(v.get(0).toString(), v.optInt(1), visited)
                }
                // populated_text（通配符展开结果）优先
                if (k == "populated_text" && found.isNotEmpty()) break
            }
            if (found.isNotEmpty()) return found.distinct()
            // 条件合并 / ControlNet / FluxGuidance 等：沿着其他连线继续找
            // 同时有 positive/negative 输入的节点（如 ControlNetApplyAdvanced），按输出槽位选择
            val skip = if (ins.has("positive") && ins.has("negative")) {
                if (slot == 1) setOf("positive") else setOf("negative")
            } else emptySet()
            for (k in ins.keys()) {
                if (k in NON_TEXT_LINKS || k in skip) continue
                val v = ins.get(k)
                if (isLink(v)) found += resolveText((v as JSONArray).get(0).toString(), v.optInt(1), visited)
            }
            return found.distinct()
        }

        fun resolveScalar(v: Any?, depth: Int = 0): String? = when {
            v == null || depth > 10 -> null
            v is JSONArray && isLink(v) -> nodes[v.get(0).toString()]?.let { n ->
                val ins = inputs(n)
                val pref = listOf("seed", "noise_seed", "value", "int", "float", "number", "Value")
                pref.firstNotNullOfOrNull { k -> if (ins.has(k)) resolveScalar(ins.get(k), depth + 1) else null }
                    ?: ins.keys().asSequence().map { ins.get(it) }.firstOrNull { it is Number || it is String }?.toString()
            }
            v is JSONArray || v is JSONObject -> null
            else -> v.toString()
        }

        fun joinTexts(l: List<String>) = l.joinToString("\n\n").ifBlank { null }

        // 找采样器 / guider
        val withPositive = nodes.entries.filter { inputs(it.value).has("positive") }
        val sampler = withPositive.firstOrNull { cls(it.value).contains("KSampler", true) }
            ?: withPositive.firstOrNull()
        val basicGuider = nodes.entries.firstOrNull { cls(it.value).contains("Guider") && inputs(it.value).has("conditioning") }

        var positive: String? = null
        var negative: String? = null
        if (sampler != null) {
            val ins = inputs(sampler.value)
            (ins.opt("positive") as? JSONArray)?.let { if (isLink(it)) positive = joinTexts(resolveText(it.get(0).toString(), it.optInt(1))) }
            (ins.opt("negative") as? JSONArray)?.let { if (isLink(it)) negative = joinTexts(resolveText(it.get(0).toString(), it.optInt(1))) }
            (ins.opt("positive") as? String)?.let { positive = it }
            (ins.opt("negative") as? String)?.let { negative = it }
        } else if (basicGuider != null) {
            (inputs(basicGuider.value).opt("conditioning") as? JSONArray)?.let {
                if (isLink(it)) positive = joinTexts(resolveText(it.get(0).toString(), it.optInt(1)))
            }
        }

        // 参数
        val params = LinkedHashMap<String, String>()
        val mainSampler = nodes.entries.firstOrNull { cls(it.value).contains("KSampler", true) }
            ?: nodes.entries.firstOrNull { cls(it.value).contains("SamplerCustom", true) }
            ?: sampler
        val paramKeys = listOf(
            "seed" to "Seed", "noise_seed" to "Seed", "steps" to "Steps", "cfg" to "CFG scale",
            "guidance" to "Guidance", "sampler_name" to "Sampler", "scheduler" to "Scheduler", "denoise" to "Denoise",
        )
        fun collect(n: JSONObject) {
            val ins = inputs(n)
            for ((k, name) in paramKeys) {
                if (params.containsKey(name) || !ins.has(k)) continue
                resolveScalar(ins.get(k))?.let { params[name] = it }
            }
        }
        mainSampler?.let { collect(it.value) }
        nodes.values.forEach { collect(it) }

        fun names(key: String) = nodes.values.mapNotNull { (inputs(it).opt(key) as? String)?.takeIf { s -> s.isNotBlank() } }.distinct()
        val models = names("ckpt_name") + names("unet_name")
        if (models.isNotEmpty()) params["Model"] = models.joinToString(", ")
        names("vae_name").takeIf { it.isNotEmpty() }?.let { params["VAE"] = it.joinToString(", ") }

        // LoRA
        val loras = mutableListOf<String>()
        nodes.values.forEach { n ->
            val ins = inputs(n)
            (ins.opt("lora_name") as? String)?.takeIf { it != "None" }?.let { name ->
                val s = ins.opt("strength_model")?.takeIf { it !is JSONArray }
                loras += if (s != null) "$name:$s" else name
            }
            // rgthree Power Lora Loader
            ins.keys().forEach { k ->
                val o = ins.opt(k) as? JSONObject ?: return@forEach
                if (o.has("lora") && o.optBoolean("on", true) && o.optString("lora") != "None") {
                    loras += "${o.optString("lora")}:${o.opt("strength") ?: 1}"
                }
            }
        }
        if (loras.isNotEmpty()) params["LoRA"] = loras.distinct().joinToString(", ")

        // 潜空间尺寸
        nodes.values.firstOrNull { cls(it).contains("Latent", true) && inputs(it).opt("width") is Number }?.let {
            val ins = inputs(it)
            params["Size"] = "${ins.get("width")}x${ins.get("height")}"
        }

        // 没找到正负时，列出所有文本编码节点的内容
        val others = if (positive == null && negative == null) {
            nodes.values.filter { cls(it).contains("TextEncode", true) || cls(it).contains("Prompt", true) }
                .flatMap { n -> TEXT_KEYS.mapNotNull { k -> (inputs(n).opt(k) as? String)?.takeIf { it.isNotBlank() } } }
                .distinct()
        } else emptyList()

        return Result(positive, negative, params.toList(), others)
    }

    // ------------------------------------------------------------ UI 格式 workflow

    fun parseWorkflow(json: String): Result? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val nodesArr = root.optJSONArray("nodes") ?: return null
        val nodes = (0 until nodesArr.length()).mapNotNull { nodesArr.optJSONObject(it) }
        val byId = nodes.associateBy { it.opt("id").toString() }
        // links: [id, from_node, from_slot, to_node, to_slot, type]
        val links = root.optJSONArray("links")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.associateBy { it.opt(0).toString() }
        } ?: emptyMap()

        var positive: String? = null
        var negative: String? = null
        val others = mutableListOf<String>()
        for (n in nodes) {
            val type = n.optString("type")
            if (!type.contains("TextEncode", true)) continue
            val wv = n.optJSONArray("widgets_values") ?: continue
            val text = (0 until wv.length()).mapNotNull { wv.opt(it) as? String }.filter { it.isNotBlank() }
                .maxByOrNull { it.length } ?: continue
            // 看输出连到了目标节点的哪个输入（positive / negative）
            val outLinks = n.optJSONArray("outputs")?.optJSONObject(0)?.optJSONArray("links")
            val targets = (0 until (outLinks?.length() ?: 0)).mapNotNull { i ->
                val l = links[outLinks!!.opt(i).toString()] ?: return@mapNotNull null
                val to = byId[l.opt(3).toString()] ?: return@mapNotNull null
                to.optJSONArray("inputs")?.optJSONObject(l.optInt(4))?.optString("name")
            }
            val title = n.optString("title")
            when {
                targets.any { it.contains("negative", true) } || title.contains("negative", true) ->
                    if (negative == null) negative = text else others += text
                targets.any { it.contains("positive", true) } || title.contains("positive", true) ->
                    if (positive == null) positive = text else others += text
                else -> others += text
            }
        }
        if (positive == null && negative == null && others.isEmpty()) return null
        return Result(positive, negative, emptyList(), others.distinct())
    }
}
