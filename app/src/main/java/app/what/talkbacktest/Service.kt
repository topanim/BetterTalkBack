package app.what.talkbacktest


import android.Manifest.permission.CALL_PHONE
import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class TalkBackCloneService : AccessibilityService() {
    companion object {
        private const val TAG = "TalkBackCloneService"
        private const val EVENT_PROCESSING_INTERVAL_MS = 500L
        private const val GESTURE_TIMEOUT_MS = 1000L
    }

    private val imageCaptionModel: ImageCaptionModel by lazy {
        ImageCaptionModel(resources, this.cacheDir)
    }
    private var tts: TextToSpeech? = null
    private val isProcessing = AtomicBoolean(false)
    private val languageIdentifier = LanguageIdentification.getClient()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var isServiceRunning = false
    private var lastEventProcessedTime = 0L
    private var windowManager: WindowManager? = null
    private var borderView: BorderView? = null
    private var touchOverlayView: TouchOverlayView? = null
    private var statusBarHeight: Int = 0
    private val gesturePoints = mutableListOf<Pair<Float, Float>>()
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val handler = Handler(Looper.getMainLooper())
    private val handlerExecutor = Executor { command -> handler.post(command) }
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val paint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Сервис подключен")

        //imageCaptionModel = ImageCaptionModel(resources, this.cacheDir)

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH or
                    AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES
        }

        serviceInfo = info

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru")
                tts?.setPitch(0.8f)
                tts?.setSpeechRate(0.9f)
                Log.d(TAG, "TTS инициализирован")
            } else {
                Log.e(TAG, "Ошибка инициализации TTS: $status")
            }
        }

        statusBarHeight = getStatusBarHeight()
        Log.d(TAG, "Высота статус-бара: $statusBarHeight")

        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val isTouchExplorationEnabled = accessibilityManager.isTouchExplorationEnabled
        Log.d(TAG, "Touch exploration enabled: $isTouchExplorationEnabled")

        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        enabledServices.forEach {
            Log.d(TAG, "Активный сервис: ${it.id}")
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        borderView = BorderView(this)
        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        try {
            windowManager?.addView(borderView, layoutParams)
            Log.d(TAG, "BorderView добавлен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка добавления BorderView: ${e.message}")
        }

        isServiceRunning = true
    }

    private fun hideTouchOverlayView() {
        touchOverlayView?.let { view ->
            windowManager?.removeView(view)
            touchOverlayView = null
        }
    }

    private fun touchOverlayView(onFinished: (String?) -> Unit) {
        touchOverlayView = TouchOverlayView(this, onFinished)
        val gestureLayoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL // <-- важно для касаний
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        try {
            windowManager?.addView(touchOverlayView, gestureLayoutParams)
            Log.d(TAG, "GestureOverlayView добавлен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка добавления GestureOverlayView: ${e.message}")
        }
    }


    @RequiresApi(Build.VERSION_CODES.S)
    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        Log.d(
            TAG,
            "GestureEvent: ${AccessibilityGestureEvent.gestureIdToString(gestureEvent.gestureId)}"
        )
        return when (gestureEvent.gestureId) {
            GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD -> {
                describeScreenContent()
                true
            }

            GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD -> {
                if (isProcessing.get()) return false
                isProcessing.set(true)
                takeScreenshot()
                handler.postDelayed({ isProcessing.set(false) }, 1000) // Сброс через 1 секунду
                true
            }

            GESTURE_2_FINGER_TRIPLE_TAP -> {
                touchOverlayView { text ->
                    actuallySpeak("Распознан символ: ${text}")
                    when (text?.lowercase()) {
                        "e" -> {
                            actuallySpeak("Совершаю экстренный звонок", false)
                            makeDirectCall(this, "")
                        }

                        "h" -> {
                            actuallySpeak("Строю маршрут до дома", false)
                            startNavigationToHome(this, "")
                        }

                        "t" -> {
                            speakCurrentDateTime()
                        }

                        "b" -> {
                            speakBatteryLevel(this)
                        }

                        "c" -> {
                            actuallySpeak("Открываю камеру", false)
                            openCamera(this)
                        }

                        else -> {}
                    }

                    hideTouchOverlayView()
                    Log.e(TAG, "Слой уничтожен")
                }
                true
            }

            GESTURE_3_FINGER_SWIPE_UP -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                true
            }

            GESTURE_3_FINGER_SWIPE_RIGHT -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                gestureHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_RECENTS) }, 400L)
                true
            }

            GESTURE_3_FINGER_SWIPE_DOWN -> {
                readStatusBar()
                true
            }

            GESTURE_2_FINGER_DOUBLE_TAP -> {
                tts?.stop()
                true
//                gestureHandler.removeCallbacks(gestureTimeoutRunnable)
//
//                val source = gestureEvent.motionEvents.firstOrNull()
//
//                if (source != null) {
//                    val x = source.x
//                    val y = source.y
//
//                    if (gesturePoints.isEmpty() || gesturePoints.last() != Pair(x, y)) {
//                        gesturePoints.add(Pair(x, y))
//                        Log.d(
//                            TAG,
//                            "Добавлена точка жеста: ($x, $y), points=${gesturePoints.size}"
//                        )
//                        borderView?.setGesturePoints(gesturePoints)
//                    }
//                    source.recycle()
//                } else {
//                    Log.w(
//                        TAG,
//                        "Источник события ${AccessibilityGestureEvent.gestureIdToString(gestureEvent.gestureId)} недоступен"
//                    )
//                }
//
//                gestureHandler.postDelayed(gestureTimeoutRunnable, GESTURE_TIMEOUT_MS)
            }

            else -> super.onGesture(gestureEvent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> {
                // Продолжаем обработку
            }

            else -> {
                Log.d(
                    TAG,
                    "Событие пропущено: event=$event, isServiceRunning=$isServiceRunning, isProcessing=${isProcessing.get()}"
                )
                return
            }
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventProcessedTime < EVENT_PROCESSING_INTERVAL_MS &&
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> false

                else -> true
            }
        ) {
            Log.d(TAG, "Слишком частая обработка событий, пропускаем")
            return
        }


        Log.d(TAG, "Событие: ${AccessibilityEvent.eventTypeToString(event.eventType)}")

        if (!isProcessing.compareAndSet(false, true)) {
            Log.w(TAG, "Обработка уже выполняется, пропускаем событие")
            return
        }

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> {
                }

                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                    processInteractionSource(event)
                }

                AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                    val source = event.source
                    if (source != null) {
                        val text = getElementName(source)
                        speak("Выбран элемент: $text")
                        source.recycle()
                    }
                }

                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    val notification = event.parcelableData as? Notification
                    if (notification != null) {
                        val appName = notification.extras.getString("android.title")
                            ?: "неизвестного приложения"
                        speak("Шторка уведомлений опущена. Уведомление от $appName")
                    }
                }

                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    processInteractionSource(event)
                }

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val windowTitle = event.className?.toString() ?: ""
                    if (windowTitle.contains("Keyguard")) {
                        speak("Экран блокировки")
                    } else {
                        describeScreenContent()
                    }
                    borderView?.setBorderRect(null)
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val source = event.source
                    if (source != null) {
                        val text = getElementName(source)
                        val action = if (text.isNotEmpty()) "Нажата $text" else "Нажат элемент"
                        speak(action)
                        source.recycle()
                    }
                    borderView?.setBorderRect(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки события: ${e.message}")
            speak("Ошибка обработки интерфейса")
        } finally {
            isProcessing.set(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun readStatusBar() {
        val summary = buildStatusBarSummary()
        Log.d(TAG, "Строка состояния: $summary")
        speak(summary)
    }

    // ----

    @SuppressLint("InlinedApi")
    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Для API 28+ используем takeScreenshot
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                handlerExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                        buffer.close() // Закрываем HardwareBuffer
                        if (hwBitmap != null) {
                            // Копируем в ARGB_8888‑битмап для дальнейшей обработки
                            val bmp = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            // Убираем исходный аппаратный битмап
                            hwBitmap.recycle()
                            // Передаём скопированный битмап на обработку
                            processScreenshot(bmp)
                        } else {
                            Log.e(TAG, "Failed to create Bitmap from HardwareBuffer")
                        }
                    }


                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    }
                }
            )
        }
    }

    private fun processScreenshot(bitmap: Bitmap) {
        // Запускаем корутину в нашем scope (она будет выполнена в потоке из Dispatchers.IO)
        processingScope.launch {
            try {
                Log.d(TAG, "ScrenAI: Начало обработки (в корутине)")
                // Эта функция теперь будет выполняться в фоновом потоке (IO)
                val caption = imageCaptionModel.generateCaptionText(bitmap)
                Log.d(TAG, "Generated caption: $caption")
            } catch (e: CancellationException) {
                // Корутина была отменена (например, при вызове scope.cancel())
                Log.i(TAG, "Обработка скриншота отменена.")
                // Важно повторно выбросить CancellationException, если не обрабатываешь его полностью
                throw e
            } catch (e: Exception) {
                // Ловим другие ошибки во время генерации описания
                Log.e(TAG, "Error processing screenshot: ${e.message}", e) // Логируем стектрейс
            } finally {
                Log.d(TAG, "Recycling bitmap in finally block.")
                bitmap.recycle()
            }
        }
        // Функция processScreenshotAsync завершается сразу, не дожидаясь окончания корутины
        Log.d(TAG, "processScreenshotAsync: Запуск фоновой обработки...")
    }

    // ----

    fun makeDirectCall(context: Context, phoneNumber: String): Boolean {
        val tag = "DirectCallUtil"

        // 1. Проверяем, есть ли разрешение на звонок
        if (ContextCompat.checkSelfPermission(
                context,
                CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(tag, "Разрешение CALL_PHONE не предоставлено. Невозможно совершить звонок.")
            // Вызывающий код должен был запросить разрешение заранее.
            // Вы можете бросить исключение здесь, если хотите:
            // throw SecurityException("Разрешение CALL_PHONE не предоставлено")
            return false // Сигнализируем о неудаче
        }

        // 2. Проверяем, не пустой ли номер
        if (phoneNumber.isBlank()) {
            Log.e(tag, "Номер телефона пуст. Невозможно совершить звонок.")
            return false
        }

        // 3. Создаем Intent для прямого вызова
        try {
            // Используем схему "tel:" для номера телефона
            val callUri = "tel:${phoneNumber}".toUri()
            val callIntent = Intent(Intent.ACTION_CALL, callUri)

            // ВАЖНО: Добавляем флаг FLAG_ACTIVITY_NEW_TASK.
            // Это необходимо, если вызов инициируется из контекста, не являющегося Activity
            // (например, из Service или BroadcastReceiver), чтобы система могла создать
            // новый task для экрана вызова. Это безопасно и для вызова из Activity.
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            Log.d(tag, "Инициируем звонок на номер: $phoneNumber")
            context.startActivity(callIntent)
            return true // Попытка инициирована

        } catch (e: SecurityException) {
            // Эта ошибка может возникнуть, если разрешение было отозвано между проверкой и вызовом,
            // или если есть другие ограничения безопасности (редко).
            Log.e(tag, "Ошибка безопасности при попытке вызова: ${e.message}", e)
            return false
        } catch (e: Exception) {
            // Любые другие ошибки (например, невозможность совершить звонок на устройстве,
            // неверный формат URI, хотя "tel:" должен быть безопасен)
            Log.e(tag, "Не удалось инициировать звонок: ${e.message}", e)
            return false
        }
    }

    fun openCamera(context: Context) {
        // Стандартный Intent для запуска приложения камеры с целью сделать снимок
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

        // --- ВАЖНО (как и в прошлый раз): Флаг для запуска из Service ---
        // Если есть вероятность вызова этой функции НЕ из Activity (например, из Service),
        // этот флаг необходим. Он безопасен для добавления, даже если вызов идет из Activity.
        cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // ----------------------------------------------------------------

        // Проверяем, есть ли вообще приложение, способное обработать этот Intent
        // Это хорошая практика перед вызовом startActivity, хотя try-catch все равно нужен.
        // resolveActivity возвращает null, если нет подходящего Activity.
        if (cameraIntent.resolveActivity(context.packageManager) != null) {
            try {
                Log.i("OpenCamera", "Попытка запустить системное приложение камеры.")
                context.startActivity(cameraIntent)
                // Обычно не нужно показывать Toast при успешном запуске,
                // т.к. пользователь увидит открытие камеры.
            } catch (e: ActivityNotFoundException) {
                // Эта ветка маловероятна из-за предварительной проверки resolveActivity,
                // но оставляем для полноты картины.
                Log.e(
                    "OpenCamera",
                    "Не найдено приложение камеры (несмотря на проверку resolveActivity).",
                    e
                )
            } catch (e: SecurityException) {
                // На случай, если есть какие-то ограничения безопасности на запуск камеры
                Log.e("OpenCamera", "Ошибка безопасности при запуске камеры.", e)
            } catch (e: Exception) {
                // Ловим любые другие непредвиденные ошибки
                Log.e("OpenCamera", "Неизвестная ошибка при запуске камеры.", e)
            }
        } else {
            // Сюда мы попадем, если resolveActivity вернул null
            Log.e(
                "OpenCamera",
                "Не найдено приложение, способное обработать Intent ${MediaStore.ACTION_IMAGE_CAPTURE}"
            )
        }
    }

    fun speakBatteryLevel(context: Context) {
        try {
            // 1. Получаем BatteryManager через системные службы
            // Используем безопасное приведение типов с 'as?' на случай, если сервис недоступен
            val batteryManager = context.getSystemService(BATTERY_SERVICE) as? BatteryManager

            if (batteryManager == null) {
                Log.e("SpeakBattery", "Could not get BatteryManager service.")
                // Опционально: Озвучить сообщение об ошибке, если TTS доступен
                speak("Не удалось получить информацию о батарее.")
                return
            }

            // 2. Получаем текущий уровень заряда батареи в процентах
            // BATTERY_PROPERTY_CAPACITY возвращает текущую емкость батареи в виде целочисленного процента.
            val batteryLevel =
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            // 3. Проверяем, удалось ли получить значение (-1 часто используется как индикатор ошибки/неизвестности)
            if (batteryLevel == -1) {
                Log.w("SpeakBattery", "Could not determine battery level (returned -1).")
                speak("Уровень заряда батареи неизвестен.")
                return
            }

            // 4. Формируем строку для озвучивания
            val textToSpeak = "Заряд батареи — $batteryLevel%"
            Log.i("SpeakBattery", "Prepared text: $textToSpeak")

            // 5. Озвучиваем текст
            speak(textToSpeak)

        } catch (e: SecurityException) {
            // Хотя для BATTERY_PROPERTY_CAPACITY обычно не требуется спец. разрешений,
            // оставим перехват SecurityException на всякий случай.
            Log.e("SpeakBattery", "SecurityException while accessing battery status.", e)
            speak("Нет разрешения на доступ к статусу батареи.")
        } catch (e: Exception) {
            // Ловим другие возможные ошибки
            Log.e("SpeakBattery", "Error getting or speaking battery level", e)
            // Можно добавить озвучивание общей ошибки, если TTS все еще работает
            // tts.speak("Ошибка при получении уровня заряда.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun startNavigationToHome(context: Context, homeAddress: String) {
        // Проверяем, не пустой ли адрес
        if (homeAddress.isBlank()) {
            Log.w("NavigationHelper", "Домашний адрес пуст или не задан.")
            return // Выходим, если адрес пустой
        }

        // Кодируем адрес для безопасного использования в URI
        // Это важно, если адрес содержит пробелы, запятые и т.д.
        val encodedAddress = Uri.encode(homeAddress)

        // Создаем URI для запуска навигации в Google Maps
        // Формат "google.navigation:q=АДРЕС" инициирует именно построение маршрута
        // Параметр 'mode=d' можно добавить для указания режима вождения (d - driving, w - walking, b - bicycling)
        // val gmmIntentUri = Uri.parse("google.navigation:q=$encodedAddress&mode=d")
        val gmmIntentUri = "google.navigation:q=$encodedAddress".toUri()

        // Создаем Intent с действием ACTION_VIEW и нашим URI
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Указываем пакет Google Maps, чтобы попытаться открыть именно его.
        // Если Google Maps не установлен, будет поймано исключение ActivityNotFoundException.
        // Это необязательно, можно убрать setPackage, тогда система предложит выбор
        // из установленных навигационных приложений, если их несколько.
        mapIntent.setPackage("com.google.android.apps.maps")

        try {
            // Пытаемся запустить Activity с созданным Intent
            Log.i("NavigationHelper", "Попытка запустить навигацию к: $homeAddress")
            context.startActivity(mapIntent)
        } catch (e: ActivityNotFoundException) {
            // Обрабатываем случай, когда Google Maps (или другое подходящее приложение) не найдено
            Log.e("NavigationHelper", "Приложение для навигации (Google Maps) не найдено.", e)
            // Можно показать сообщение пользователю
            speak("Не удалось найти приложение Google Maps для навигации.")
        } catch (e: Exception) {
            // Обработка других возможных ошибок при запуске Activity
            Log.e("NavigationHelper", "Неизвестная ошибка при запуске навигации.", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildStatusBarSummary(): String {
        val sb = StringBuilder()

        // 1. Текущее время
        val time = DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
        sb.append("Время: $time. ")

        // 2. Количество уведомлений
        // val notificationCount = getNotificationCount()
        // sb.append("Уведомлений: $notificationCount. ")

        // 3. Сеть
        val networkStatus = getNetworkStatus()
        sb.append("Сеть: $networkStatus. ")

        // 4. Качество связи
        val signalStrength = getSignalStrength()
        sb.append("Качество связи: $signalStrength. ")

        // 5. Заряд батареи
        val batteryStatus = getBatteryStatus()
        sb.append("Батарея: $batteryStatus.")

        return sb.toString()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun speakCurrentDateTime() {
        try {
            // 1. Получаем текущую дату и время
            val now = LocalDateTime.now()

            // 2. Задаем русскую локаль для форматирования (чтобы получить "мая" вместо "May")
            val locale = Locale.getDefault()

            // 3. Создаем форматтер для нужного вида строки
            //    HH - часы (00-23)
            //    mm - минуты (00-59)
            //    dd - день месяца (01-31)
            //    MMMM - полное название месяца (например, "мая", "января")
            val formatter = DateTimeFormatter.ofPattern("HH:mm, dd MMMM", locale)

            // 4. Форматируем дату и время
            val formattedDateTime = now.format(formatter)

            // 5. Формируем итоговую фразу для озвучивания
            val textToSpeak = "Сейчас $formattedDateTime"
            Log.i("SpeakDateTime", "Prepared text: $textToSpeak")

            // 6. Озвучиваем текст
            //    QUEUE_FLUSH: Прерывает текущее воспроизведение и начинает новое.
            //    params: Дополнительные параметры (здесь не нужны).
            //    utteranceId: Уникальный идентификатор для отслеживания (здесь не нужен).
            speak(textToSpeak)

        } catch (e: Exception) {
            // Ловим возможные ошибки при форматировании или работе с TTS
            Log.e("SpeakDateTime", "Error formatting or speaking date/time", e)
        }
    }

    private fun getNotificationCount(): Int {
        // Предполагается, что NotificationListener интегрирован
        val notifications = try {
            val service = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            service?.activeNotifications
        } catch (e: SecurityException) {
            Log.e(TAG, "Нет доступа к уведомлениям: ${e.message}")
            return -1 // Индикатор отсутствия доступа
        }

        return notifications?.size ?: 0
    }

    private fun getNetworkStatus(): String {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        return if (networkInfo?.isConnected == true) {
            when (networkInfo.type) {
                ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
                ConnectivityManager.TYPE_MOBILE -> "Мобильная сеть"
                else -> "Подключено"
            }
        } else {
            "Нет подключения"
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun getSignalStrength(): String {
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        return try {
            val signalStrength = telephonyManager.signalStrength
            if (signalStrength == null) {
                "Нет сигнала"
            } else {
                when (signalStrength.level) {
                    1 -> "Плохой"
                    2 -> "Средний"
                    3 -> "Хороший"
                    4 -> "Отличный"
                    else -> "Неизвестно или отсутствует"
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Ошибка получения качества связи: ${e.message}")
            "Недоступно"
        }
    }

    private fun getBatteryStatus(): String {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return if (batteryPct >= 0) {
            "$batteryPct%${if (isCharging) ", заряжается" else ""}"
        } else {
            "Недоступно"
        }
    }

    private fun speak(text: String, playNow: Boolean = true) {
        if (!isServiceRunning || tts == null || text.isEmpty()) {
            Log.w(TAG, "TTS не инициализирован, сервис остановлен или текст пустой: $text")
            return
        }

        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                if (languageCode == "und") {
                    Log.i(TAG, "Не получается определить язык")
                    actuallySpeak(text, playNow)
                    return@addOnSuccessListener
                }

                Log.i(TAG, "Язык определен: $languageCode")

                val systemLanguage = Locale.getDefault().language
                if (languageCode == systemLanguage) {
                    actuallySpeak(text, playNow)
                    return@addOnSuccessListener
                }

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(languageCode)
                    .setTargetLanguage(systemLanguage)
                    .build()
                val translator = Translation.getClient(options)
                val conditions = DownloadConditions.Builder().build()

                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        translator.translate(text)
                            .addOnSuccessListener { translatedText ->
                                Log.d(TAG, "Переведенный текст: $translatedText")
                                actuallySpeak(translatedText, playNow)
                            }
                            .addOnFailureListener {
                                Log.e(TAG, "Перевод не удался: ${it.message}")
                            }
                            .addOnCompleteListener {
                                translator.close()
                            }
                    }
                    .addOnFailureListener {
                        Log.e(TAG, "Ошибка загрузки модели: ${it.message}")
                        translator.close()
                    }
            }
            .addOnFailureListener {
                Log.e(TAG, "Ошибка определения языка: ${it.message}")
            }
    }

    private fun actuallySpeak(text: String, playNow: Boolean = true) {
        Log.i(TAG, "Текст ан озвучку: $text")
        tts?.speak(
            text,
            if (playNow) TextToSpeech.QUEUE_FLUSH
            else TextToSpeech.QUEUE_ADD,
            null,
            null
        )
    }

    private fun describeScreenContent() {
        Log.d(TAG, "Начало описания экрана")
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val description = if (isHomeScreen(rootNode)) {
                describeHomeScreen(rootNode)
            } else {
                describeAppScreen(rootNode)
            }
            speak(description)
            rootNode.recycle()
        } else {
            actuallySpeak("Не удалось определить содержимое экрана")
        }
    }

    private fun isHomeScreen(node: AccessibilityNodeInfo): Boolean {
        val packageName = node.packageName?.toString() ?: ""
        return packageName.contains("launcher", ignoreCase = true)
    }

    private fun describeHomeScreen(node: AccessibilityNodeInfo): String {
        return "Вы на главном экране"
    }

    private fun describeAppScreen(node: AccessibilityNodeInfo): String {
        val appName = node.packageName?.toString()?.split(".")?.last() ?: "неизвестного приложения"
        val description = StringBuilder("Вы на экране приложения $appName. ")
        val keyElements = mutableListOf<String>()
        traverseNode(node, keyElements)
        if (keyElements.isNotEmpty()) {
            description.append("На экране: ${keyElements.joinToString(", ")}.")
        } else {
            description.append("Экран пустой.")
        }
        return description.toString()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Сервис прерван")
        tts?.stop()
    }

    private fun processInteractionSource(event: AccessibilityEvent) {
        val source = event.source
        if (source != null) {
            val rect = Rect()
            source.getBoundsInScreen(rect)
            rect.top -= statusBarHeight
            rect.bottom -= statusBarHeight
            Log.d(TAG, "Скорректированные границы: $rect")
            borderView?.setBorderRect(rect)
            Log.d(TAG, "Рамка установлена для элемента: $rect")

            val text = when {
                event.text.isNotEmpty() -> event.text.joinToString()
                source.text?.isNotEmpty() == true -> source.text.toString()
                source.contentDescription?.isNotEmpty() == true -> source.contentDescription.toString()
                else -> ""
            }
            Log.w(TAG, "Обработка ${AccessibilityEvent.eventTypeToString(event.eventType)}: $text")
            speak(text)
            source.recycle()
        } else {
            borderView?.setBorderRect(null)
            Log.d(TAG, "Источник события недоступен, рамка сброшена")
        }
    }

    private fun generateScreenDescription(rootNode: AccessibilityNodeInfo): String {
        val keyElements = mutableListOf<String>()
        traverseNode(rootNode, keyElements)
        return if (keyElements.isEmpty()) {
            "Экран пустой или не содержит значимых элементов."
        } else {
            "${keyElements.joinToString(", ")}."
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, keyElements: MutableList<String>) {
        val elementText = getElementName(node)
        if (elementText.isNotEmpty()) {
            val elementType = getElementType(node)
            keyElements.add("$elementType '$elementText'")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, keyElements)
                child.recycle()
            }
        }
    }

    private fun getElementName(node: AccessibilityNodeInfo): String {
        return when {
            node.text?.isNotEmpty() == true -> node.text.toString()
            node.contentDescription?.isNotEmpty() == true -> node.contentDescription.toString()
            else -> ""
        }
    }

    private fun getElementType(node: AccessibilityNodeInfo): String {
        return when {
            node.className?.contains("TextView") == true -> "текст"
            node.className?.contains("Button") == true -> "кнопка"
            node.className?.contains("EditText") == true -> "поле ввода"
            node.className?.contains("ListView") == true || node.className?.contains("RecyclerView") == true -> "список"
            else -> "элемент"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageCaptionModel.close()
        processingScope.cancel()
        isServiceRunning = false
        tts?.shutdown()
        tts = null
        gestureHandler.removeCallbacksAndMessages(null)
        try {
            borderView?.let {
                windowManager?.removeView(it)
                Log.d(TAG, "BorderView удален")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка удаления BorderView: ${e.message}")
        }
        borderView = null
        windowManager = null
        Log.d(TAG, "Сервис уничтожен")
    }

    private fun getStatusBarHeight(): Int {
        var height = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            height = resources.getDimensionPixelSize(resourceId)
        }
        return height
    }

    private class BorderView(context: Context) : View(context) {
        private val borderPaint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }
        private val pointPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private var borderRect: Rect? = null
        private var gesturePoints: List<Pair<Float, Float>> = emptyList()

        fun setBorderRect(rect: Rect?) {
            borderRect = rect
            invalidate()
        }

        fun setGesturePoints(points: List<Pair<Float, Float>>) {
            gesturePoints = points
            invalidate()
        }

        fun clearPoints() {
            gesturePoints = emptyList()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            borderRect?.let {
                canvas.drawRect(it, borderPaint)
            }
            gesturePoints.forEach { point ->
                canvas.drawCircle(point.first, point.second, 10f, pointPaint)
            }
        }
    }
}