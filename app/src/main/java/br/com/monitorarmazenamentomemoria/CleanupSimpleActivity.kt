package br.com.monitorarmazenamentomemoria

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class CleanupSimpleActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setBackgroundColor(Color.rgb(246, 248, 252))

        val contentScroll = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(18), dp(18), dp(12))
        contentScroll.addView(root)

        main.addView(contentScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        main.addView(bottomNav())
        setContentView(main)

        drawHome()
    }

    private fun drawHome() {
        root.removeAllViews()

        val data = Monitor.read(this)

        val title = TextView(this)
        title.text = "Limpeza"
        title.textSize = 30f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        root.addView(title)

        val subtitle = TextView(this)
        subtitle.text = "Gerenciador visual de arquivos"
        subtitle.textSize = 15f
        subtitle.setTextColor(Color.rgb(80, 90, 110))
        subtitle.setPadding(0, dp(4), 0, dp(14))
        root.addView(subtitle)

        val summary = card()
        val summaryTitle = titleText("Resumo do aparelho")
        summary.addView(summaryTitle)

        val summaryBody = TextView(this)
        summaryBody.text =
            "Armazenamento usado: ${data.storageUsedPercent}%\n" +
            "Livre: ${Monitor.format(data.storageFree)}\n" +
            "RAM usada: ${data.memoryUsedPercent}%\n" +
            "RAM livre: ${Monitor.format(data.memoryFree)}"
        summaryBody.textSize = 16f
        summaryBody.setTextColor(Color.rgb(70, 80, 100))
        summaryBody.setPadding(0, dp(8), 0, 0)
        summary.addView(summaryBody)

        root.addView(summary)

        val actions = card()
        actions.addView(titleText("Ações rápidas"))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val update = Button(this)
        update.text = "Atualizar"
        update.setOnClickListener {
            MonitorWidgetProvider.updateAll(this)
            Toast.makeText(this, "Leitura atualizada", Toast.LENGTH_SHORT).show()
            drawHome()
        }

        val androidStorage = Button(this)
        androidStorage.text = "Armazenamento"
        androidStorage.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        row.addView(update, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(androidStorage, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(row)

        val memory = Button(this)
        memory.text = "Analisar memória"
        memory.setOnClickListener {
            val now = Monitor.read(this)
            AlertDialog.Builder(this)
                .setTitle("Análise de memória")
                .setMessage(
                    "RAM usada: ${now.memoryUsedPercent}%\n" +
                    "RAM livre: ${Monitor.format(now.memoryFree)}\n\n" +
                    "O Android gerencia a RAM automaticamente. Esta função atualiza a leitura e ajuda você a acompanhar o uso."
                )
                .setPositiveButton("OK", null)
                .show()
        }
        actions.addView(memory)

        root.addView(actions)

        val categoriesTitle = TextView(this)
        categoriesTitle.text = "Categorias"
        categoriesTitle.textSize = 22f
        categoriesTitle.setTypeface(null, Typeface.BOLD)
        categoriesTitle.setTextColor(Color.rgb(14, 26, 56))
        categoriesTitle.setPadding(0, dp(8), 0, dp(10))
        root.addView(categoriesTitle)

        addCategoryRow("📦", "Arquivos grandes", "Encontrar arquivos pesados", "🎬", "Vídeos", "Filtrar vídeos grandes")
        addCategoryRow("🖼", "Imagens", "Fotos e imagens grandes", "🎵", "Áudios", "Áudios e mensagens de voz")
        addCategoryRow("💬", "WhatsApp", "Mídias e backups do WhatsApp", "🗄", "Backups", "Bancos de dados e cópias")
        addCategoryRow("🕒", "Recentes", "Arquivos que entraram recentemente", "⚠", "Sensíveis", "Arquivos que exigem cuidado")
        addCategoryRow("⬇", "Downloads", "Arquivos baixados", "📱", "APKs", "Instaladores antigos")

        val note = card()
        note.addView(titleText("Próxima etapa"))
        val noteText = TextView(this)
        noteText.text =
            "Nesta versão, organizamos a Limpeza como gerenciador visual.\n\n" +
            "Na próxima etapa segura, cada categoria vai abrir uma lista filtrada com arquivos reais, tamanho, data, local e botão Abrir."
        noteText.textSize = 15f
        noteText.setTextColor(Color.rgb(70, 80, 100))
        noteText.setPadding(0, dp(8), 0, 0)
        note.addView(noteText)
        root.addView(note)
    }

    private fun addCategoryRow(icon1: String, title1: String, desc1: String, icon2: String, title2: String, desc2: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(categoryCard(icon1, title1, desc1), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(categoryCard(icon2, title2, desc2), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
    }

    private fun categoryCard(icon: String, title: String, desc: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        box.background = rounded(Color.WHITE, Color.rgb(225, 230, 240), dp(18))

        val t = TextView(this)
        t.text = "$icon\n$title"
        t.textSize = 16f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.rgb(14, 26, 56))
        box.addView(t)

        val d = TextView(this)
        d.text = desc
        d.textSize = 13f
        d.setTextColor(Color.rgb(80, 90, 110))
        d.setPadding(0, dp(6), 0, 0)
        box.addView(d)

        box.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Na próxima etapa, esta categoria vai abrir a lista real de arquivos filtrados.")
                .setPositiveButton("OK", null)
                .show()
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(dp(4), dp(4), dp(4), dp(8))
        box.layoutParams = params

        return box
    }

    private fun bottomNav(): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        nav.setPadding(dp(10), dp(8), dp(10), dp(8))
        nav.setBackgroundColor(Color.WHITE)

        nav.addView(navItem("📊\nPainel", false) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        nav.addView(navItem("▦\nWidget", false) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        nav.addView(navItem("🧹\nLimpeza", true) {
            drawHome()
        })

        nav.addView(navItem("⚙\nConfig.", false) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        return nav
    }

    private fun navItem(textValue: String, active: Boolean, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.rgb(20, 92, 210) else Color.rgb(80, 90, 110))
            setPadding(dp(6), dp(5), dp(6), dp(5))
            if (active) background = rounded(Color.rgb(232, 241, 255), Color.TRANSPARENT, dp(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, Color.rgb(225, 230, 240), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(14))
            }
        }
    }

    private fun titleText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(14, 26, 56))
        }
    }

    private fun rounded(bg: Int, stroke: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bg)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
