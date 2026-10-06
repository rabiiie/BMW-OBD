package com.rabie.bmwobd

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.ui.Bmw
import com.rabie.bmwobd.ui.severityColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Burbuja flotante con turbo, temperaturas y el indicio mas grave, para verla encima del
 * navegador. Sale sola cuando hay conexion y la app no esta en pantalla; se arrastra con el dedo
 * y un toque vuelve a la app. Necesita el permiso de mostrar sobre otras aplicaciones.
 */
class Bubble(private val context: Context, private val controller: ObdController) {

    private val windows = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("bubble", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val appVisible = MutableStateFlow(true)
    private var view: View? = null
    private var updates: Job? = null

    init {
        scope.launch {
            combine(_enabled, appVisible, controller.state) { enabled, visible, state ->
                enabled && !visible && state.phase == Phase.LIVE && hasPermission()
            }.distinctUntilChanged().collect { wanted -> if (wanted) show() else hide() }
        }
    }

    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun onAppVisible(visible: Boolean) {
        appVisible.value = visible
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun show() {
        if (view != null) return
        val frame = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Bmw.Surface.copy(alpha = 0.92f).toArgb())
            setStroke(dp(1), Bmw.Amber.toArgb())
        }
        val boost = text(24f, Bmw.Text.toArgb())
        val temps = text(13f, Bmw.TextDim.toArgb())
        val note = text(12f, Bmw.Text.toArgb()).apply { maxWidth = dp(150) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = frame
            addView(boost)
            addView(temps)
            addView(note)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(16))
            y = prefs.getInt(KEY_Y, dp(140))
        }
        makeDraggable(box, params)

        try {
            windows.addView(box, params)
        } catch (e: RuntimeException) {
            return
        }
        view = box
        updates = scope.launch {
            controller.state.collect { state ->
                val values = state.values
                boost.text = Pids.boostBar(values)?.let { "%.2f bar".format(it) } ?: "— bar"
                temps.text = listOfNotNull(
                    values[Pids.COOLANT]?.let { "Agua %.0f°".format(it) },
                    values[Pids.OIL]?.let { "Aceite %.0f°".format(it) },
                ).joinToString("  ")
                val top = state.advice.firstOrNull { it.severity != Severity.INFO } ?: state.advice.firstOrNull()
                note.visibility = if (top == null) View.GONE else View.VISIBLE
                note.text = top?.title.orEmpty()
                val color = top?.let { severityColor(it.severity).toArgb() } ?: Bmw.Amber.toArgb()
                note.setTextColor(color)
                frame.setStroke(dp(if (top?.severity == Severity.ALERT) 2 else 1), color)
            }
        }
    }

    private fun hide() {
        updates?.cancel()
        updates = null
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
    }

    /** Arrastrar mueve la burbuja y guarda donde queda; un toque sin mover abre la app. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(box: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        box.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windows.updateViewLayout(box, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        openApp()
                    }
                }
            }
            true
        }
    }

    private fun openApp() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}
