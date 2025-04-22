package app.what.talkbacktest


 import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class DigitalRecognizer{
    private var recognizer: DigitalInkRecognizer? = null

    init {
        initializeRecognizer()
    }
    private fun initializeRecognizer() {
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        if (modelIdentifier != null) {
            val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
            val modelManager = RemoteModelManager.getInstance()
            val conditions = DownloadConditions.Builder().build()
            modelManager.download(model,conditions)
                .addOnSuccessListener {
                    Log.d("DigitalInk", "Модель успешно загружена.")
                    recognizer = DigitalInkRecognition.getClient(
                        DigitalInkRecognizerOptions.builder(model).build()
                    )
                }
                .addOnFailureListener { e ->
                    Log.e("DigitalInk", "Ошибка загрузки модели: ${e.message}", e)
                }
        }
    }

    // Функция распознавания чернил
    suspend fun recognizedInk(strokes: List<List<Offset>>): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                Log.d("DigitalInk", "Получены штрихи для распознавания: $strokes")

                val ink = Ink.builder().apply {
                    strokes.forEach { stroke ->
                        val strokeBuilder = Ink.Stroke.builder()
                        stroke.forEach { point ->
                            strokeBuilder.addPoint(Ink.Point.create(point.x, point.y))
                        }
                        addStroke(strokeBuilder.build())
                    }
                }.build()

                // Проверка на инициализацию распознавателя
                if (recognizer == null) {
                    return@withContext Result.failure(IllegalStateException("Recognizer is not initialized"))
                }

                // Выполнение распознавания
                val result = recognizer?.recognize(ink)?.await()
                    ?: return@withContext Result.failure(IllegalStateException("Ошибка при распознавании: результат пуст"))

                val rawText = result.candidates.firstOrNull()?.text
                if (rawText.isNullOrEmpty()) {
                    return@withContext Result.failure(IllegalStateException("Не удалось распознать текст"))
                }

                Result.success(rawText)
            } catch (e: Exception) {
                Log.e("DigitalInk", "Ошибка распознавания: ${e.message}", e)
                Result.failure(e)
            }
        }
    private suspend fun <T> Task<T>.await(): T = suspendCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { exception -> continuation.resumeWithException(exception) }
    }
}