package app.what.talkbacktest


import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class TouchOverlayView(
    context: Context,
    private val onFinish: (String?) -> Unit
) : View(context), CoroutineScope by MainScope() {
    private val recognizer = DigitalRecognizer()
    private val path = Path()
    private val paint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    private val currentStroke = mutableListOf<Offset>()
    private val strokes = mutableListOf<List<Offset>>()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(event.x, event.y)
                currentStroke.clear()
                currentStroke.add(Offset(event.x, event.y))
            }

            MotionEvent.ACTION_MOVE -> {
                path.lineTo(event.x, event.y)
                currentStroke.add(Offset(event.x, event.y))
            }

            MotionEvent.ACTION_UP -> {
                strokes.add(currentStroke.toList())

                val strokesToRecognize = strokes.map { it.toList() }
                launch {
                    val result = recognizer.recognizedInk(strokesToRecognize)
                    if (result.isSuccess) {
                        Log.d("DigitalInk", "Распознано: ${result.getOrNull() ?: "null"}")
                        onFinish(result.getOrNull())
                    } else {
                        Log.e(
                            "DigitalInk",
                            "Ошибка распознавания: ${result.exceptionOrNull() ?: "null"}"
                        )
                    }
                }
                // После распознавания очищаем экран
                path.reset()
                strokes.clear()
                currentStroke.clear()
                invalidate()
            }
        }
        invalidate()
        return false
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

}