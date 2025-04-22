package app.what.talkbacktest

import ai.onnxruntime.*
import android.content.res.Resources
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Модель для генерации описаний изображений с помощью ONNX Runtime и аппаратного ускорения NNAPI.
 */
class ImageCaptionModel(
    private val resources: Resources,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "ImageCaptionModel"
        private const val MAX_SEQ_LEN = 8                // Укороченная длина последовательности
        private const val INPUT_WIDTH = 384               // Уменьшенное разрешение для экономии памяти
        private const val INPUT_HEIGHT = 384

        // Нормализация по mean/std, используемым в BLIP
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = loadSession()
    private val id2token: List<String> = loadVocab()
    private val bosId: Int = tokenToId("<s>")
    private val eosId: Int = tokenToId("</s>")

    private fun loadSession(): OrtSession {
        val modelFile = File(cacheDir, "blip_image_caption_int8.onnx").apply {
            if (!exists()) {
                resources.openRawResource(R.raw.blip_image_caption_int8).use { inp ->
                    FileOutputStream(this).use { out -> inp.copyTo(out) }
                }
            }
        }
        // Настройки сессии: подключаем NNAPI EP и оптимизации
        val so = OrtSession.SessionOptions().apply {
            // аппаратное ускорение на Android через NNAPI
            addNnapi()  // подключаем Android Neural Networks API
            // полный граф-оптимизатор
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // ограничиваем потоки CPU для предотвращения перегрузки
        }
        Log.d(TAG, "ONNX Runtime session loaded with NNAPI")
        return env.createSession(modelFile.absolutePath, so)
    }

    private fun loadVocab(): List<String> {
        val vocabFile = File(cacheDir, "vocab.json").apply {
            if (!exists()) {
                resources.openRawResource(R.raw.vocab).use { inp ->
                    FileOutputStream(this).use { out -> inp.copyTo(out) }
                }
            }
        }
        val token2id: Map<String, Int> = InputStreamReader(vocabFile.inputStream()).use { r ->
            val type = object : TypeToken<Map<String, Int>>() {}.type
            Gson().fromJson(r, type)
        }
        val id2tok = MutableList(token2id.size + 1) { "" }
        token2id.forEach { (tok, id) -> if (id in id2tok.indices) id2tok[id] = tok }
        return id2tok
    }

    private fun tokenToId(token: String): Int {
        return id2token.indexOf(token).takeIf { it >= 0 } ?: 0
    }

    /**
     * Декодирование списка токенов в строку с обрезкой BPE-префиксов.
     */
    fun generateCaptionText(bitmap: Bitmap): String {
        Log.d("ScreenAI", "ScrenAI: Начало обработки 2 ")
        val ids = generateCaptionIds(bitmap)
        Log.d("ScreenAI", "ScrenAI: Начало обработки 3")
        val sb = StringBuilder()
        Log.d("ScreenAI", "ScrenAI: Начало обработки 4 ${ids.size}")
        for (id in ids) {
            Log.d("ScreenAI", "ScrenAI: Начало обработки 5 ${id}")
            if (id !in id2token.indices) continue
            val tok = id2token[id]
            if (tok in listOf("<s>", "</s>", "<pad>")) continue
            // Обработка BPE-префиксов: U+2581 и U+0120
            when {
                tok.startsWith("▁") -> sb.append(' ').append(tok.substring(1))
                tok.startsWith("Ġ") -> sb.append(' ').append(tok.substring(1))
                else -> sb.append(tok)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Поэтапная генерация: передаём ids и mask для autoregressive loop.
     */
    private fun generateCaptionIds(bitmap: Bitmap): List<Int> {
        Log.d("ScreenAI", "ScrenAI: Начало обработки 21")
        val pxTensor = bitmapToTensor(bitmap)
        Log.d("ScreenAI", "ScrenAI: Начало обработки 22")
        val ids = MutableList(MAX_SEQ_LEN) { 0L }
        Log.d("ScreenAI", "ScrenAI: Начало обработки 23")
        val mask = MutableList(MAX_SEQ_LEN) { 0L }
        Log.d("ScreenAI", "ScrenAI: Начало обработки 24")
        ids[0] = bosId.toLong()
        Log.d("ScreenAI", "ScrenAI: Начало обработки 25")
        mask[0] = 1L
        Log.d("ScreenAI", "ScrenAI: Начало обработки 26")

        val outIds = mutableListOf<Int>()
        Log.d("ScreenAI", "ScrenAI: Начало обработки 27")
        for (step in 1 until MAX_SEQ_LEN) {
            val idsT = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(ids.toLongArray()),
                longArrayOf(1, MAX_SEQ_LEN.toLong())
            )
            val maskT = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(mask.toLongArray()),
                longArrayOf(1, MAX_SEQ_LEN.toLong())
            )
            val inputs = mapOf(
                session.inputNames.elementAt(0) to pxTensor,
                session.inputNames.elementAt(1) to idsT,
                session.inputNames.elementAt(2) to maskT
            )
            Log.d("ScreenAI", "ScrenAI: Начало обработки 28")
            val results = session.run(inputs)
            Log.d("ScreenAI", "ScrenAI: Начало обработки 29")
            val outTensor = results[0] as OnnxTensor
            val vocabSize = (outTensor.info as TensorInfo).shape[2].toInt()
            val logits = outTensor.floatBuffer.rewind() as FloatBuffer

            // greedy decode: выбираем токен с max логитом
            val base = (step - 1) * vocabSize
            var bestIdx = 0;
            var bestScore = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) {
                val score = logits.get(base + v)
                if (score > bestScore) {
                    bestScore = score; bestIdx = v
                }
            }
            outIds.add(bestIdx)
            ids[step] = bestIdx.toLong()
            mask[step] = 1L
            results.forEach { (it as? OnnxTensor)?.close() }
            idsT.close(); maskT.close()
            if (bestIdx == eosId) break
        }
        pxTensor.close()
        return outIds
    }

    /**
     * Преобразование Bitmap в тензор с нормализацией.
     */
    private fun bitmapToTensor(bmp: Bitmap): OnnxTensor {
        val resized = bmp.scale(INPUT_WIDTH, INPUT_HEIGHT)
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT).also {
            resized.getPixels(
                it,
                0,
                INPUT_WIDTH,
                0,
                0,
                INPUT_WIDTH,
                INPUT_HEIGHT
            )
        }
        val fb = FloatBuffer.allocate(1 * 3 * INPUT_WIDTH * INPUT_HEIGHT)
        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = ((px) and 0xFF) / 255f
            fb.put((r - MEAN[0]) / STD[0])
            fb.put((g - MEAN[1]) / STD[1])
            fb.put((b - MEAN[2]) / STD[2])
        }
        fb.rewind()
        return OnnxTensor.createTensor(
            env,
            fb,
            longArrayOf(1, 3, INPUT_WIDTH.toLong(), INPUT_HEIGHT.toLong())
        )
    }

    fun close() {
        session.close()
        env.close()
    }
}
