package com.liyang.pnginfo

import org.junit.Assert.assertEquals
import org.junit.Test

class ParserTest {
    @Test
    fun comfyPng() {
        val texts = javaClass.classLoader!!.getResourceAsStream("comfy.png").use { MetadataReader.readPngText(it) }
        assertEquals(setOf("prompt", "workflow"), texts.keys)
        val r = ComfyParser.parsePrompt(texts["prompt"]!!)!!
        println(r)
        assertEquals("masterpiece, 1girl, 樱花, (best quality:1.2)", r.positive)
        assertEquals("lowres, bad hands, 低质量", r.negative)
        val p = r.params.toMap()
        assertEquals("123456789", p["Seed"])
        assertEquals("28", p["Steps"])
        assertEquals("6.5", p["CFG scale"])
        assertEquals("dpmpp_2m", p["Sampler"])
        assertEquals("sdxl_base.safetensors", p["Model"])
        assertEquals("detail.safetensors:0.8", p["LoRA"])
        assertEquals("832x1216", p["Size"])
    }

    @Test
    fun a1111() {
        val t = "a cat, \"quoted\"\nsecond line\nNegative prompt: ugly, blurry\nSteps: 20, Sampler: DPM++ 2M, CFG scale: 7, Seed: 42, Size: 512x768, Model: foo, Lora hashes: \"a: 1, b: 2\", Version: f2.0"
        val (pos, neg, params) = MetadataReader.parseA1111(t)
        assertEquals("a cat, \"quoted\"\nsecond line", pos)
        assertEquals("ugly, blurry", neg)
        val m = params.toMap()
        assertEquals("DPM++ 2M", m["Sampler"])
        assertEquals("a: 1, b: 2", m["Lora hashes"])
        assertEquals("f2.0", m["Version"])
    }
}
