package br.com.monitorarmazenamentomemoria

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object SmartAlertOverlay {

    private var root: View? = null
    private var windowManager: WindowManager? = null
    private var pulseHandler: Handler? = null

    fun canShow(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
    }

    fun showIfAllowed(
        context: Context,
        title: String,
        message: String
    ): Boolean {
        if (!canShow(context)) return false

        dismiss()

        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val handler = Handler(Looper.getMainLooper())

            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 22), dp(context, 22), dp(context, 22), dp(context, 18))
            }

            val titleView = TextView(context).apply {
                text = title
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val messageView = TextView(context).apply {
                text = message
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(context, 14), 0, dp(context, 18))
            }

            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.HORIZONTAL
            }

            val open = Button(context).apply {
                text = "ABRIR LIMPEZA"
                isAllCaps = false
                setOnClickListener {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(
                            context.packageName
                        )

                        intent?.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )

                        context.startActivity(intent)
                    } catch (_: Throwable) {
                    }

                    dismiss()
                }
            }

            val silence = Button(context).apply {
                text = "SILENCIAR"
                isAllCaps = false
                setOnClickListener {
                    SmartAlertEngine.stopTestAlarm()
                    dismiss()
                }
            }

            row.addView(open)
            row.addView(silence)

            box.addView(titleView)
            box.addView(messageView)
            box.addView(row)

            val type =
                if (Build.VERSION.SDK_INT >= 26) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            wm.addView(box, params)

            root = box
            windowManager = wm
            pulseHandler = handler

            var orange = false

            val pulse = object : Runnable {
                override fun run() {
                    if (root == null) return

                    val color =
                        if (orange) Color.rgb(190, 24, 42)
                        else Color.rgb(245, 125, 18)

                    box.background = GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = dp(context, 22).toFloat()
                    }

                    orange = !orange
                    handler.postDelayed(this, 420L)
                }
            }

            handler.post(pulse)

            handler.postDelayed({
                dismiss()
            }, 15000L)

            true
        } catch (_: Throwable) {
            dismiss()
            false
        }
    }

    fun dismiss() {
        try {
            pulseHandler?.removeCallbacksAndMessages(null)
            root?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (_: Throwable) {
        } finally {
            root = null
            windowManager = null
            pulseHandler = null
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
