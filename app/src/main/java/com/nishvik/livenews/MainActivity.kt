package com.nishvik.livenews

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Live News home screen. Reads channels.json and the channel logos from this app's external
 * files folder and opens the chosen live stream in
 * the YouTube TV app. Layout numbers are written for a 1920x1080 screen and scaled to fit.
 */
class MainActivity : Activity() {

    // ---- data ---------------------------------------------------------------------------

    private class News(val t: String, val d: String, val ts: Long)

    private class Item(
        val group: String, val name: String, val region: String, val id: String,
        val watching: Long, val icon: String, val news: List<News>, val newsSrc: String,
        val since: Long = 0
    ) {
        val live: Boolean get() = id.isNotEmpty()
    }

    private class Weather(val temp: Int, val desc: String, val icon: String, val day: Boolean, val ts: Long)

    private class Loaded(
        val items: List<Item>, val updated: Long, val weather: Weather?, val error: String?
    )

    private class Card(val item: Item, val ago: String)

    private class Row(val key: String, val title: String, val note: String, val cards: List<Card>)

    private data class Theme(
        val key: String, val greet: String,
        val bg1: Int, val bg2: Int, val bg3: Int, val b1: Int, val b2: Int, val b3: Int,
        val ink: Int, val sub: Int, val g1: Int, val g2: Int, val edge: Int, val focus: Int,
        val focusInk: Int, val focusSub: Int, val accent: Int, val rule: Int, val band: Int
    )

    private class RowHolder(
        val key: String, val band: View, val tiles: View,
        val box: View, val handle: View, val glyph: Glyph
    )

    private class TileHolder(
        val item: Item, val rowKey: String, val name: TextView, val region: TextView?,
        val count: TextView, val eye: Glyph?
    )

    // ---- state --------------------------------------------------------------------------

    private var u = 1f
    private lateinit var th: Theme
    private var sora: Typeface = Typeface.DEFAULT_BOLD
    private var loaded: Loaded = Loaded(emptyList(), 0, null, null)
    private var loadedMod = 0L
    private var renderedTheme = ""
    private var selected: Item? = null
    private var activeRow = ""
    private val rowHolders = ArrayList<RowHolder>()
    private val rowBoxes = ArrayList<View>()
    private val shownKeys = ArrayList<String>()
    private val visual = ArrayList<String>()
    private var moving: String? = null
    private var movePitch = 0
    private var swallowUp = -1
    private var pulse: android.animation.ObjectAnimator? = null
    private val bitmaps = HashMap<String, Bitmap?>()
    private val handler = Handler(Looper.getMainLooper())

    private var topSlot: FrameLayout? = null
    private var rowsBox: LinearLayout? = null
    private var themePill: View? = null
    private var themeDialog: Dialog? = null
    private var panel: LinearLayout? = null
    private var itemsAvail = 0
    private var itemsWidth = 0

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 30_000)
        }
    }

    // ---- small helpers ------------------------------------------------------------------

    private fun px(v: Float): Int = (v * u + 0.5f).toInt()
    private fun px(v: Int): Int = px(v.toFloat())
    private fun rgba(r: Int, g: Int, b: Int, a: Double): Int = Color.argb((a * 255).toInt(), r, g, b)
    private fun hex(s: String): Int = Color.parseColor(s)

    private fun ll(
        w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

    private fun text(
        s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1, face: Typeface? = null
    ): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u)
        setTextColor(color)
        if (face != null) typeface = face else if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun glass(radius: Float, c1: Int = th.g1, c2: Int = th.g2, stroke: Int = th.edge): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            cornerRadius = radius * u
            setStroke(maxOf(1, px(1f)), stroke)
        }

    private fun fmt(v: Long): String = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0).replace(".0M", "M")
        v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1_000.0).replace(".0K", "K")
        else -> v.toString()
    }

    private fun groupName(g: String): String = when (g) {
        "hindi" -> "India Hindi"
        "english" -> "India English"
        "business" -> "Business"
        else -> "International"
    }

    // ---- themes -------------------------------------------------------------------------

    private fun themeFor(hour: Int): Theme = when (hour) {
        in 5..11 -> Theme(
            "morning", "Good morning",
            hex("#FFE9D6"), hex("#FFD3DF"), hex("#D5E4FF"), hex("#FFB36B"), hex("#FF8DB0"), hex("#8FC4FF"),
            hex("#1D1D1F"), hex("#4F4F57"), rgba(255, 255, 255, .66), rgba(255, 255, 255, .28),
            rgba(255, 255, 255, .7), rgba(255, 255, 255, .94), hex("#1D1D1F"), hex("#4F4F57"),
            hex("#E8553A"), rgba(60, 40, 40, .12), rgba(120, 70, 60, .11)
        )
        in 12..16 -> Theme(
            "day", "Good afternoon",
            hex("#EAF3F8"), hex("#E8F5EF"), hex("#FFF3D6"), hex("#B5D7EC"), hex("#A9E6CF"), hex("#FFE3A0"),
            hex("#14212B"), hex("#43515C"), rgba(255, 255, 255, .66), rgba(255, 255, 255, .28),
            rgba(255, 255, 255, .7), rgba(255, 255, 255, .94), hex("#14212B"), hex("#43515C"),
            hex("#3F6F95"), rgba(20, 40, 60, .12), rgba(30, 60, 85, .09)
        )
        in 17..20 -> Theme(
            "evening", "Good evening",
            hex("#FFD9C9"), hex("#E9CCF5"), hex("#BCC9FF"), hex("#FF9A76"), hex("#C77DFF"), hex("#6C8CFF"),
            hex("#1F1830"), hex("#4D4560"), rgba(255, 255, 255, .62), rgba(255, 255, 255, .24),
            rgba(255, 255, 255, .68), rgba(255, 255, 255, .93), hex("#1F1830"), hex("#4D4560"),
            hex("#6B4CF0"), rgba(50, 30, 80, .12), rgba(60, 30, 100, .10)
        )
        else -> Theme(
            "night", "Good night",
            hex("#10173A"), hex("#1E1B4B"), hex("#0D3A4D"), hex("#5B5BFF"), hex("#B04BFF"), hex("#00B3C7"),
            hex("#F4F5FF"), hex("#B7BCDD"), rgba(255, 255, 255, .16), rgba(255, 255, 255, .05),
            rgba(255, 255, 255, .28), rgba(255, 255, 255, .93), hex("#14172B"), hex("#4A4F73"),
            hex("#7C93FF"), rgba(255, 255, 255, .14), rgba(255, 255, 255, .09)
        )
    }

    /** The two fixed look-alikes that do not follow the clock: true black and clean white. */
    private fun themeByKey(key: String): Theme? = when (key) {
        "black" -> Theme(
            "black", "",
            hex("#000000"), hex("#08080B"), hex("#000000"), hex("#2B2B40"), hex("#241B33"), hex("#1B2A33"),
            hex("#F5F5F7"), hex("#A3A3AD"), rgba(255, 255, 255, .12), rgba(255, 255, 255, .04),
            rgba(255, 255, 255, .20), rgba(255, 255, 255, .95), hex("#111114"), hex("#55555E"),
            hex("#9DB4FF"), rgba(255, 255, 255, .16), rgba(255, 255, 255, .08)
        )
        "white" -> Theme(
            "white", "",
            hex("#FFFFFF"), hex("#F7F7FA"), hex("#FFFFFF"), hex("#E6ECFF"), hex("#F1E6FF"), hex("#E2F3EA"),
            hex("#111114"), hex("#5C5C66"), rgba(244, 244, 248, .92), rgba(250, 250, 252, .9),
            rgba(0, 0, 0, .10), rgba(24, 24, 28, .96), hex("#FFFFFF"), hex("#C9C9D2"),
            hex("#3B5BDB"), rgba(0, 0, 0, .10), rgba(0, 0, 0, .05)
        )
        else -> null
    }

    private val themeOptions = listOf(
        Triple("dynamic", "Dynamic", "Changes with the time of day"),
        Triple("morning", "Morning", "Warm peach and sky"),
        Triple("day", "Day", "Light blue and mint"),
        Triple("evening", "Evening", "Dusk pink and violet"),
        Triple("night", "Night", "Deep indigo"),
        Triple("black", "Black", "True black, easy on the eyes"),
        Triple("white", "White", "Clean and bright")
    )

    private fun themePref(): String {
        val v = getSharedPreferences("live", MODE_PRIVATE).getString("theme", "dynamic") ?: "dynamic"
        return if (themeOptions.any { it.first == v }) v else "dynamic"
    }

    /** Colours follow the chosen theme (or the clock when Dynamic); the greeting always follows the clock. */
    private fun currentTheme(): Theme {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val clock = themeFor(hour)
        themeByKey(themePref())?.let { return it.copy(greet = clock.greet) }
        val pickHour = when (themePref()) {
            "morning" -> 8
            "day" -> 14
            "evening" -> 18
            "night" -> 22
            else -> hour
        }
        return themeFor(pickHour).copy(greet = clock.greet)
    }

    private fun themeLabel(key: String): String = themeOptions.first { it.first == key }.second

    private fun themeSwatch(key: String): GradientDrawable {
        val fixed = when (key) {
            "black" -> intArrayOf(hex("#000000"), hex("#1C1C22"), hex("#3A3A46"))
            "white" -> intArrayOf(hex("#FFFFFF"), hex("#F1F1F5"), hex("#DADAE3"))
            else -> null
        }
        if (fixed != null) {
            return GradientDrawable(GradientDrawable.Orientation.TL_BR, fixed).apply {
                shape = GradientDrawable.OVAL
                setStroke(maxOf(1, px(1f)), if (key == "white") Color.argb(60, 0, 0, 0) else Color.argb(90, 255, 255, 255))
            }
        }
        val t = when (key) {
            "morning" -> themeFor(8)
            "day" -> themeFor(14)
            "evening" -> themeFor(18)
            "night" -> themeFor(22)
            else -> themeFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.b1, t.b2, t.b3)).apply {
            shape = GradientDrawable.OVAL
            setStroke(maxOf(1, px(1f)), th.edge)
        }
    }

    private fun focusRing(radius: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius * u
            setColor(th.focus)
            setStroke(maxOf(2, px(3f)), th.accent)
        }

    private fun buildThemePill(): View {
        val pick = themePref()
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), 0, px(16), 0)
            background = glass(26f)
            isFocusable = true
            isClickable = true
        }
        pill.addView(View(this).apply { background = themeSwatch(pick) }, ll(px(20), px(20), r = px(10)))
        val label = text(themeLabel(pick), 18f, th.ink)
        pill.addView(label, ll(-2, -2, r = px(10)))
        val chev = Glyph(this, Glyph.CHEV, th.sub)
        pill.addView(chev, ll(px(16), px(16)))
        pill.setOnFocusChangeListener { _, f ->
            pill.background = if (f) focusRing(26f) else glass(26f)
            label.setTextColor(if (f) th.focusInk else th.ink)
            chev.color = if (f) th.focusInk else th.sub
            chev.invalidate()
            pill.translationZ = if (f) px(8f).toFloat() else 0f
            pill.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(120).start()
        }
        pill.setOnClickListener { showThemeDialog() }
        return pill
    }

    private fun showThemeDialog() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(28), px(24), px(28), px(24))
            background = GradientDrawable().apply {
                cornerRadius = 32f * u
                setColor(when (th.key) { "night" -> hex("#262A5E"); "black" -> hex("#1C1C20"); else -> rgba(255, 255, 255, .97) })
                setStroke(maxOf(1, px(1f)), th.edge)
            }
        }
        box.addView(text("Theme", 28f, th.ink, face = sora), ll(-2, -2, b = px(14)))
        val current = themePref()
        var currentRow: View? = null
        for ((key, name, note) in themeOptions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(18), 0, px(18), 0)
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply { cornerRadius = 20f * u; setColor(Color.TRANSPARENT) }
            }
            row.addView(View(this).apply { background = themeSwatch(key) }, ll(px(30), px(30), r = px(16)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val nameView = text(name, 22f, th.ink)
            val noteView = text(note, 14f, th.sub)
            col.addView(nameView)
            col.addView(noteView, ll(-2, -2, t = px(2)))
            row.addView(col, ll(0, -2, 1f))
            val chk = Glyph(this, Glyph.CHECK, th.accent).apply { visibility = if (key == current) View.VISIBLE else View.INVISIBLE }
            row.addView(chk, ll(px(24), px(24), l = px(12)))
            row.setOnFocusChangeListener { _, f ->
                row.background = if (f) focusRing(20f) else GradientDrawable().apply { cornerRadius = 20f * u; setColor(Color.TRANSPARENT) }
                nameView.setTextColor(if (f) th.focusInk else th.ink)
                noteView.setTextColor(if (f) th.focusSub else th.sub)
                chk.color = if (f) th.focusInk else th.accent
                chk.invalidate()
            }
            row.setOnClickListener {
                getSharedPreferences("live", MODE_PRIVATE).edit().putString("theme", key).apply()
                d.dismiss()
                render()
                themePill?.requestFocus()
            }
            box.addView(row, ll(px(520), px(68), b = px(6)))
            if (key == current) currentRow = row
        }
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.setOnDismissListener {
            themeDialog = null
            themePill?.requestFocus()
        }
        themeDialog = d
        d.show()
        currentRow?.requestFocus()
    }

    /** Soft colour glows on a three-stop gradient: a cheap stand-in for the blurred blobs. */
    private class GlowDrawable(val t: Theme) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val s = w / 1920f
            p.shader = LinearGradient(
                0f, 0f, w, h, intArrayOf(t.bg1, t.bg2, t.bg3), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, p)
            glow(canvas, 250 * s, 190 * s, 520 * s, t.b1)
            glow(canvas, 1680 * s, 560 * s, 560 * s, t.b2)
            glow(canvas, 870 * s, 1100 * s, 520 * s, t.b3)
        }

        private fun glow(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
            val inner = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
            val outer = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            p.shader = RadialGradient(cx, cy, r, intArrayOf(inner, outer), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /** Tiny vector-style icons drawn in code: eye, play triangle, RSS-off, warning, chevron, check. */
    private class Glyph(context: Context, val kind: Int, var color: Int) : View(context) {
        var dimUp = false
        var dimDown = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.color = color
            when (kind) {
                EYE -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = w * 0.11f
                    val path = Path()
                    path.moveTo(w * .06f, h * .5f)
                    path.quadTo(w * .5f, -h * .1f, w * .94f, h * .5f)
                    path.quadTo(w * .5f, h * 1.1f, w * .06f, h * .5f)
                    c.drawPath(path, p)
                    c.drawCircle(w * .5f, h * .5f, h * .17f, p)
                }
                PLAY -> {
                    p.style = Paint.Style.FILL
                    val path = Path()
                    path.moveTo(w * .3f, h * .18f)
                    path.lineTo(w * .3f, h * .82f)
                    path.lineTo(w * .84f, h * .5f)
                    path.close()
                    c.drawPath(path, p)
                }
                RSS -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = w * 0.075f
                    c.drawArc(-w * .55f, h * .45f, w * .55f, h * 1.55f, 270f, 90f, false, p)
                    c.drawArc(-w * 1.05f, -h * .05f, w * 1.05f, h * 1.95f, 270f, 90f, false, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(w * .17f, h * .83f, w * .06f, p)
                    p.style = Paint.Style.STROKE
                    p.color = Color.parseColor("#FF3B30")
                    p.strokeWidth = w * 0.08f
                    c.drawLine(w * .1f, h * .1f, w * .9f, h * .9f, p)
                }
                CHEV -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = w * 0.14f
                    p.strokeJoin = Paint.Join.ROUND
                    val path = Path()
                    path.moveTo(w * .12f, h * .32f)
                    path.lineTo(w * .5f, h * .70f)
                    path.lineTo(w * .88f, h * .32f)
                    c.drawPath(path, p)
                }
                CHECK -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = w * 0.13f
                    p.strokeJoin = Paint.Join.ROUND
                    val path = Path()
                    path.moveTo(w * .14f, h * .54f)
                    path.lineTo(w * .40f, h * .78f)
                    path.lineTo(w * .86f, h * .24f)
                    c.drawPath(path, p)
                }
                HANDLE -> {
                    p.style = Paint.Style.STROKE
                    p.strokeJoin = Paint.Join.ROUND
                    p.strokeWidth = w * 0.11f
                    p.alpha = if (dimUp) 70 else 255
                    val up = Path()
                    up.moveTo(w * .22f, h * .19f)
                    up.lineTo(w * .5f, h * .07f)
                    up.lineTo(w * .78f, h * .19f)
                    c.drawPath(up, p)
                    p.color = color
                    p.strokeWidth = w * 0.10f
                    for (f in floatArrayOf(.37f, .5f, .63f)) c.drawLine(w * .2f, h * f, w * .8f, h * f, p)
                    p.alpha = if (dimDown) 70 else 255
                    p.strokeWidth = w * 0.11f
                    val dn = Path()
                    dn.moveTo(w * .22f, h * .81f)
                    dn.lineTo(w * .5f, h * .93f)
                    dn.lineTo(w * .78f, h * .81f)
                    c.drawPath(dn, p)
                }
                else -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = w * 0.09f
                    p.strokeJoin = Paint.Join.ROUND
                    val path = Path()
                    path.moveTo(w * .5f, h * .12f)
                    path.lineTo(w * .94f, h * .86f)
                    path.lineTo(w * .06f, h * .86f)
                    path.close()
                    c.drawPath(path, p)
                    c.drawLine(w * .5f, h * .4f, w * .5f, h * .62f, p)
                    c.drawPoint(w * .5f, h * .75f, p)
                }
            }
        }

        companion object {
            const val EYE = 0
            const val PLAY = 1
            const val RSS = 2
            const val WARN = 3
            const val CHEV = 4
            const val CHECK = 5
            const val HANDLE = 6
        }
    }

    // ---- loading ------------------------------------------------------------------------

    private fun load(): Loaded {
        val f = File(getExternalFilesDir(null), "channels.json")
        loadedMod = if (f.exists()) f.lastModified() else 0L
        if (!f.exists()) return Loaded(emptyList(), 0, null, if (feedLast != 0L && !feedBusy) MSG_UNAVAILABLE else MSG_LOADING)
        return try {
            val root = JSONObject(f.readText())
            contactText = root.optString("contact", contactText)
            val arr = root.getJSONArray("channels")
            val out = ArrayList<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val newsArr = o.optJSONArray("news")
                val news = ArrayList<News>()
                if (newsArr != null) {
                    for (j in 0 until newsArr.length()) {
                        val n = newsArr.getJSONObject(j)
                        news.add(News(n.optString("t"), n.optString("d"), n.optLong("ts", 0)))
                    }
                }
                out.add(
                    Item(
                        o.optString("group", "world"), o.getString("name"), o.optString("region", ""),
                        o.optString("id", ""), o.optLong("watching", 0), o.optString("icon", ""),
                        news, o.optString("news_src", ""), o.optLong("since", 0)
                    )
                )
            }
            val updated = root.optLong("updated_epoch", f.lastModified() / 1000)
            val w = root.optJSONObject("weather")
            val weather = if (w != null) {
                cityName = w.optString("city", cityName)
                Weather(
                    w.optInt("temp"), w.optString("desc"), w.optString("icon"),
                    w.optInt("day", 1) == 1, w.optLong("ts", 0)
                )
            } else null
            if (out.isEmpty()) Loaded(out, updated, weather, MSG_UNAVAILABLE)
            else Loaded(out, updated, weather, null)
        } catch (e: Exception) {
            Loaded(emptyList(), 0, null, MSG_UNAVAILABLE)
        }
    }

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        true
    }

    private fun youtubeInstalled(): Boolean = try {
        packageManager.getPackageInfo(YOUTUBE_TV, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // ---- last viewed ----------------------------------------------------------------------

    private fun recents(): List<Pair<String, Long>> {
        val raw = getSharedPreferences("live", MODE_PRIVATE).getString("recent", "") ?: ""
        return raw.split(";").mapNotNull {
            val p = it.split("|")
            if (p.size == 2) p[1].toLongOrNull()?.let { t -> Pair(p[0], t) } else null
        }
    }

    private fun remember(item: Item) {
        val list = recents().filter { it.first != item.name }.toMutableList()
        list.add(0, Pair(item.name, System.currentTimeMillis() / 1000))
        val raw = list.take(5).joinToString(";") { it.first + "|" + it.second }
        getSharedPreferences("live", MODE_PRIVATE).edit().putString("recent", raw).apply()
    }

    private fun agoShort(epoch: Long): String {
        val mins = ((System.currentTimeMillis() / 1000 - epoch) / 60).coerceAtLeast(0)
        return when {
            mins < 60 -> "${maxOf(mins, 1)}m"
            mins < 1440 -> "${mins / 60}h"
            else -> "${mins / 1440}d"
        }
    }

    private fun agoLong(epoch: Long): String {
        val mins = ((System.currentTimeMillis() / 1000 - epoch) / 60).coerceAtLeast(0)
        return when {
            mins < 1 -> "JUST NOW"
            mins < 60 -> "$mins MIN AGO"
            mins < 1440 -> "${mins / 60} H AGO"
            else -> "${mins / 1440} D AGO"
        }
    }

    // ---- actions ------------------------------------------------------------------------

    private fun play(item: Item) {
        if (!item.live) {
            Toast.makeText(this, "${item.name} is not live right now", Toast.LENGTH_SHORT).show()
            return
        }
        if (!youtubeInstalled()) {
            Toast.makeText(this, "YouTube app not found", Toast.LENGTH_LONG).show()
            return
        }
        if (!isOnline()) {
            Toast.makeText(this, "No internet", Toast.LENGTH_LONG).show()
            return
        }
        remember(item)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${item.id}"))
            .setPackage(YOUTUBE_TV)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "YouTube app not found", Toast.LENGTH_LONG).show()
        }
    }

    // ---- web feed -----------------------------------------------------------------------

    private var visible = false
    private var feedBusy = false
    private var feedLast = 0L
    private var feedSync: FeedSync? = null
    private var cityName = "Bengaluru"
    private var contactText = "Contact App Developer"
    private val MSG_LOADING = "Loading channels..."
    private val MSG_UNAVAILABLE = "Channel list unavailable. Please try again shortly."

    /** Downloads the list from the web feed, at most every 5 minutes, on a background thread. */
    private fun syncFeed() {
        if (BuildConfig.FEED_URL.isEmpty() || feedBusy) return
        val now = System.currentTimeMillis()
        if (feedLast != 0L && now - feedLast < 300_000) return
        val dir = getExternalFilesDir(null) ?: return
        feedBusy = true
        feedLast = now
        val sync = feedSync ?: FeedSync(dir, BuildConfig.FEED_URL).also { feedSync = it }
        Thread {
            val changed = try { sync.run() } catch (e: Exception) { false }
            handler.post {
                feedBusy = false
                if (changed && visible && moving == null) onTick()
                else if (visible && loaded.error == MSG_LOADING) render()   // first download failed: switch to the "unavailable" text
            }
        }.start()
    }

    // ---- lifecycle ----------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        visible = true
        render()
        syncFeed()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 30_000)
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(tick)
        pulse?.cancel()
        pulse = null
        moving = null
        super.onPause()
    }

    override fun onStop() {
        // Hidden (for example YouTube is in front): drop the screen so the app holds very little memory.
        // onResume builds it again when the viewer comes back, on the same tile.
        pulse?.cancel()
        pulse = null
        moving = null
        themeDialog?.dismiss()
        rowHolders.clear()
        rowBoxes.clear()
        shownKeys.clear()
        rowsBox = null
        pv = null
        panel = null
        topSlot = null
        themePill = null
        playCircle = null
        bitmaps.clear()
        setContentView(View(this))
        super.onStop()
        handler.postDelayed({ System.gc() }, 500)
    }

    private fun onTick() {
        syncFeed()
        if (moving != null) return
        val f = File(getExternalFilesDir(null), "channels.json")
        val mod = if (f.exists()) f.lastModified() else 0L
        val t = currentTheme()
        if (t.key + "|" + t.greet != renderedTheme || mod != loadedMod) {
            if (themeDialog?.isShowing != true) render()
        } else if (themeDialog?.isShowing != true) {
            buildTopBar()
        }
    }

    // ---- images -------------------------------------------------------------------------

    private fun decodeScaled(path: String, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun bitmapFor(item: Item): Bitmap? {
        if (item.icon.isEmpty()) return null
        if (bitmaps.containsKey(item.icon)) return bitmaps[item.icon]
        val file = File(File(getExternalFilesDir(null), "icons"), item.icon)
        val bmp = if (file.exists()) decodeScaled(file.path, px(96)) else null
        bitmaps[item.icon] = bmp
        return bmp
    }

    private val palette = intArrayOf(
        Color.parseColor("#5E5CE6"), Color.parseColor("#FF9F0A"), Color.parseColor("#30B0C7"),
        Color.parseColor("#FF375F"), Color.parseColor("#34C759"), Color.parseColor("#AF52DE")
    )

    private fun paletteColor(item: Item): Int = palette[Math.abs(item.name.hashCode()) % palette.size]

    /** Brands whose logo does not give a good dominant colour (grey, black, or mostly white). */
    private val brandOverride = mapOf(
        "nhk world" to "#6B7280", "firstpost" to "#2B2B30", "zee news" to "#D8232A",
        "times now" to "#D2232A", "zee business" to "#B8860B", "nbc news" to "#058BC8"
    )
    private val brandCache = HashMap<String, Int>()

    /**
     * The colour a logo is best known for: the strongest saturated hue in its centre circle,
     * ignoring white, black and grey. A plain average turns red-on-white into pink and then grey.
     */
    private fun dominantColor(bmp: Bitmap): Int? {
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val n = 32
        val small = Bitmap.createScaledBitmap(square, n, n, true)
        val sums = Array(12) { DoubleArray(4) }
        var total = 0
        val hsv = FloatArray(3)
        val mid = (n - 1) / 2f
        for (y in 0 until n) for (x in 0 until n) {
            if ((x - mid) * (x - mid) + (y - mid) * (y - mid) > mid * mid) continue
            total++
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue
            val e = sums[(hsv[0] / 30f).toInt() % 12]
            val w = (hsv[1] * hsv[2]).toDouble()
            e[0] += w
            e[1] += Color.red(c) * w
            e[2] += Color.green(c) * w
            e[3] += Color.blue(c) * w
        }
        var best = 0
        for (k in 1 until 12) if (sums[k][0] > sums[best][0]) best = k
        val e = sums[best]
        if (total == 0 || e[0] / total < 0.06) return null
        return Color.rgb((e[1] / e[0]).toInt(), (e[2] / e[0]).toInt(), (e[3] / e[0]).toInt())
    }

    private fun brandColor(item: Item): Int {
        brandOverride[item.name.lowercase(Locale.ROOT)]?.let { return hex(it) }
        brandCache[item.icon]?.let { if (item.icon.isNotEmpty()) return it }
        val bmp = bitmapFor(item) ?: return paletteColor(item)
        val c = dominantColor(bmp) ?: th.accent
        brandCache[item.icon] = c
        return c
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun logo(item: Item, size: Int): View {
        val gray = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val bmp = bitmapFor(item)
        if (bmp != null) {
            return ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = oval(Color.WHITE)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                if (!item.live) colorFilter = gray
            }
        }
        val initials = item.name.split(" ", "-").filter { it.isNotEmpty() }
            .take(2).joinToString("") { it.first().uppercase(Locale.ROOT) }
        return text(initials, size * 0.36f / u, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = oval(paletteColor(item))
            if (!item.live) {
                // grey fallback for a channel that is not live
                background = oval(Color.parseColor("#8E8E93"))
            }
        }
    }

    // ---- screen -------------------------------------------------------------------------

    private fun render() {
        val dm = resources.displayMetrics
        u = minOf(dm.widthPixels / 1920f, dm.heightPixels / 1080f)
        th = currentTheme()
        renderedTheme = th.key + "|" + th.greet
        try {
            sora = resources.getFont(R.font.sora_semibold)
        } catch (e: Exception) {
            sora = Typeface.DEFAULT_BOLD
        }
        bitmaps.clear()
        brandCache.clear()
        loaded = load()

        val keepName = selected?.name
        val keepRow = activeRow
        rowHolders.clear()
        rowBoxes.clear()
        shownKeys.clear()
        rowsBox = null
        pulse?.cancel()
        pulse = null
        moving = null
        pv = null
        selected = null

        val root = FrameLayout(this).apply {
            background = GlowDrawable(th)
            clipChildren = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(56), px(30), px(56), px(40))
            clipChildren = false
            clipToPadding = false
        }
        val slot = FrameLayout(this).apply { clipChildren = false }
        topSlot = slot
        content.addView(slot, ll(ViewGroup.LayoutParams.MATCH_PARENT, px(TOP_H), b = px(16)))
        buildTopBar()

        var firstTile: View? = null
        var restoreTile: View? = null
        if (loaded.error != null) {
            content.addView(emptyCard(loaded.error!!), ll(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            val rows = buildRows()
            val tw = tileWidth()
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
            rowsBox = left
            rows.forEachIndexed { i, row ->
                if (i > 0) left.addView(Space(this), ll(1, 0, 1f))
                left.addView(rowView(row, tw) { tile, holder ->
                    if (firstTile == null) firstTile = tile
                    if (restoreTile == null && keepName != null && holder.item.name == keepName &&
                        holder.rowKey == keepRow
                    ) {
                        restoreTile = tile
                    }
                }, ll(ViewGroup.LayoutParams.MATCH_PARENT, px(ROW_H)))
            }
            body.addView(left, ll(px(1210), ViewGroup.LayoutParams.MATCH_PARENT))
            val panelView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = glass(30f)
                clipChildren = false
            }
            panel = panelView
            buildPanel(panelView)
            body.addView(panelView, ll(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f, l = px(32)))
            content.addView(body, ll(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val bodyH = dm.heightPixels - px(30) - px(40) - px(TOP_H) - px(16)
            val panelW = dm.widthPixels - px(112) - px(1210) - px(32)
            itemsWidth = panelW - px(48)
            itemsAvail = bodyH - px(104) - 1 - px(14) - px(4) - px(26) - px(2) - px(4)
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val stamp = text("v" + BuildConfig.BUILD_STAMP, 11f, th.sub).apply { alpha = 0.7f }
        root.addView(
            stamp,
            FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = px(40)
                bottomMargin = px(12)
            }
        )
        setContentView(root)
        (restoreTile ?: firstTile)?.requestFocus()
    }

    private fun tileWidth(): Int {
        val groups = listOf("hindi", "english", "business", "world")
        val maxRow = maxOf(8, groups.maxOf { g -> loaded.items.count { it.group == g } })
        return (px(1210) - px(4) - (maxRow - 1) * px(6)) / maxRow
    }

    private fun buildRows(): List<Row> {
        val items = loaded.items
        val rows = ArrayList<Row>()
        val byName = items.associateBy { it.name }
        val recent = recents().mapNotNull { r -> byName[r.first]?.let { Card(it, if (it.live) agoShort(r.second) else "off") } }
        if (recent.isNotEmpty()) rows.add(Row("last", "Last viewed", "Pick up where you left off", recent))
        val popular = items.filter { it.live }.sortedByDescending { it.watching }.take(8).map { Card(it, "") }
        if (popular.isNotEmpty()) rows.add(Row("pop", "Popular right now", "Most watched by live viewers", popular))
        for (g in listOf("hindi", "english", "business", "world")) {
            val cards = items.filter { it.group == g }.sortedByDescending { it.watching }.map { Card(it, "") }
            if (cards.isNotEmpty()) rows.add(Row(g, groupName(g), "", cards))
        }
        val order = savedOrder()
        return rows.sortedBy { order.indexOf(it.key) }
    }

    private val defaultOrder = listOf("last", "pop", "hindi", "english", "business", "world")

    /** Row order the viewer chose, always a full list of the six row keys. */
    private fun savedOrder(): List<String> {
        val raw = getSharedPreferences("live", MODE_PRIVATE).getString("rowOrder", "") ?: ""
        val out = ArrayList<String>()
        for (k in raw.split(",")) if (k in defaultOrder && k !in out) out.add(k)
        for (k in defaultOrder) if (k !in out) out.add(k)
        return out
    }

    private fun saveOrder(a: String, b: String) {
        val full = ArrayList(savedOrder())
        val i = full.indexOf(a)
        val j = full.indexOf(b)
        if (i < 0 || j < 0) return
        full[i] = b
        full[j] = a
        getSharedPreferences("live", MODE_PRIVATE).edit().putString("rowOrder", full.joinToString(",")).apply()
    }

    private fun emptyCard(title: String): View {
        val wrap = FrameLayout(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glass(40f)
            setPadding(px(40), px(56), px(40), px(56))
        }
        card.addView(text(title, 34f, th.ink, face = sora).apply { gravity = Gravity.CENTER })
        if (title != MSG_LOADING) {
            card.addView(
                text(contactText, 20f, th.sub).apply { gravity = Gravity.CENTER },
                ll(-2, -2, t = px(14))
            )
        }
        wrap.addView(card, FrameLayout.LayoutParams(px(760), -2, Gravity.CENTER))
        return wrap
    }

    // ---- top bar ------------------------------------------------------------------------

    private fun buildTopBar() {
        val slot = topSlot ?: return
        val hadFocus = themePill?.hasFocus() == true
        slot.removeAllViews()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }

        val now = System.currentTimeMillis() / 1000
        val age = now - loaded.updated
        val stale = loaded.updated > 0 && age > 3 * 3600
        val sinceText = SimpleDateFormat("EEE h:mm a", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(java.util.Date(loaded.updated * 1000))
        val status = when {
            loaded.updated == 0L -> "Live news"
            stale -> "List not updated since $sinceText"
            age < 60 -> "Live news · Updated just now"
            age < 3600 -> "Live news · Updated ${age / 60} min ago"
            else -> "Live news · Updated ${age / 3600} h ago"
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val greet = text(th.greet, 38f, th.ink, face = sora).apply {
            ellipsize = null
            setHorizontallyScrolling(true)
        }
        val greetW = Math.ceil(greet.paint.measureText(th.greet).toDouble()).toInt() + px(28)
        left.addView(greet, ll(greetW, -2))
        val sub = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (stale) {
            sub.addView(
                View(this).apply { background = oval(Color.parseColor("#FF9F0A")) },
                ll(px(9), px(9), r = px(8))
            )
        }
        sub.addView(text(status, 15f, th.sub))
        left.addView(sub, ll(-2, -2, t = px(6)))
        bar.addView(left, ll(-2, -2))

        // centre: alert pill when something is wrong
        val centre = FrameLayout(this)
        val ytOk = youtubeInstalled()
        val online = isOnline()
        if (!ytOk || !online) {
            val amber = online.not() && ytOk
            val fg = if (!ytOk) Color.parseColor("#D70015") else Color.parseColor("#B35C00")
            val titleText = if (!ytOk) "YouTube app not found" else "No internet"
            val bodyText = if (!ytOk) "Install YouTube for Android TV, then reopen Live News (India)"
            else "Streams will not play until the TV is back online"
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(20), 0, px(20), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 26f * u
                    if (!ytOk) {
                        setColor(rgba(255, 59, 48, .22))
                        setStroke(maxOf(1, px(1f)), rgba(255, 59, 48, .7))
                    } else {
                        setColor(rgba(255, 159, 10, .28))
                        setStroke(maxOf(1, px(1f)), rgba(255, 159, 10, .75))
                    }
                }
            }
            if (amber || !ytOk) {
                pill.addView(Glyph(this, Glyph.WARN, fg), ll(px(26), px(26), r = px(12)))
            }
            val msg = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * u)
                setTextColor(th.ink)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                val s = android.text.SpannableString("$titleText · $bodyText")
                s.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, titleText.length, 0)
                s.setSpan(android.text.style.ForegroundColorSpan(fg), 0, titleText.length, 0)
                text = s
            }
            pill.addView(msg, ll(0, -2, 1f))
            centre.addView(pill, FrameLayout.LayoutParams(-1, px(52), Gravity.CENTER_VERTICAL))
        }
        bar.addView(centre, ll(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f, l = px(36), r = px(36)))

        // theme selector
        val tp = buildThemePill()
        themePill = tp
        bar.addView(tp, ll(-2, px(52), r = px(14)))

        // weather pill (right of the theme selector): only when a recent reading is in the list
        val w = loaded.weather
        if (w != null && now - w.ts <= 3 * 3600) {
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), 0, px(20), 0)
                background = glass(26f)
            }
            val emoji = when (w.icon) {
                "clear" -> if (w.day) "☀️" else "🌙"
                "partly" -> "⛅"
                "cloud" -> "☁️"
                "fog" -> "🌫️"
                "rain" -> "🌧️"
                "storm" -> "⛈️"
                else -> "☁️"
            }
            pill.addView(text(emoji, 26f, th.ink), ll(-2, -2, r = px(10)))
            pill.addView(text("${w.temp}°", 22f, th.ink, true), ll(-2, -2, r = px(10)))
            pill.addView(text("$cityName · ${w.desc}", 14f, th.sub))
            bar.addView(pill, ll(-2, px(52), r = px(14)))
        }

        // clock pill
        val cal = Calendar.getInstance()
        val clockPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22), 0, px(22), 0)
            background = glass(26f)
        }
        clockPill.addView(text(SimpleDateFormat("h:mm", Locale.US).format(cal.time), 28f, th.ink, true))
        clockPill.addView(
            text(SimpleDateFormat("a", Locale.US).format(cal.time).uppercase(Locale.US), 14f, th.sub),
            ll(-2, -2, l = px(6), t = px(6))
        )
        bar.addView(clockPill, ll(-2, px(52)))
        slot.addView(bar, FrameLayout.LayoutParams(-1, -1))
        if (hadFocus) tp.requestFocus()
        updatePlayState()
    }

    // ---- rows and tiles -----------------------------------------------------------------

    private fun rowView(row: Row, tw: Int, onTile: (View, TileHolder) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), 0, 0, 0)
        }
        titleRow.addView(text(row.title, 24f, th.ink, face = sora))
        if (row.note.isNotEmpty()) titleRow.addView(text(row.note, 14f, th.sub), ll(-2, -2, l = px(14)))
        box.addView(titleRow, ll(ViewGroup.LayoutParams.MATCH_PARENT, px(30)))

        val area = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val band = View(this).apply {
            alpha = 0f
            background = GradientDrawable().apply {
                cornerRadius = 28f * u
                setColor(Color.argb(0x2B, Color.red(th.accent), Color.green(th.accent), Color.blue(th.accent)))
                setStroke(maxOf(1, px(1f)), Color.argb(0x4D, Color.red(th.accent), Color.green(th.accent), Color.blue(th.accent)))
            }
        }
        area.addView(
            band,
            FrameLayout.LayoutParams(-1, px(122)).apply {
                topMargin = -px(2)
                leftMargin = -px(8)
                rightMargin = -px(8)
            }
        )
        val tiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(px(2), 0, 0, 0)
        }
        row.cards.forEachIndexed { i, card ->
            val holderTile = tile(card, row.key, tw)
            tiles.addView(holderTile.first, ll(tw, px(118), l = if (i == 0) 0 else px(6)))
            onTile(holderTile.first, holderTile.second)
        }
        area.addView(tiles, FrameLayout.LayoutParams(-1, -1))
        box.addView(area, ll(ViewGroup.LayoutParams.MATCH_PARENT, px(118)))
        // reorder handle: sits just left of the row, only shown while the row is being moved
        val ac = th.accent
        val lum = (0.299 * Color.red(ac) + 0.587 * Color.green(ac) + 0.114 * Color.blue(ac)) / 255
        val glyph = Glyph(this, Glyph.HANDLE, if (lum > 0.62) hex("#1F1830") else Color.WHITE)
        val handle = FrameLayout(this).apply {
            alpha = 0f
            background = GradientDrawable().apply {
                cornerRadius = 15f * u
                setColor(ac)
            }
        }
        handle.addView(glyph, FrameLayout.LayoutParams(-1, -1).apply { setMargins(px(5), px(7), px(5), px(7)) })
        area.addView(handle, FrameLayout.LayoutParams(px(34), px(72)).apply {
            leftMargin = -px(46)
            topMargin = px(23)
        })
        rowHolders.add(RowHolder(row.key, band, tiles, box, handle, glyph))
        rowBoxes.add(box)
        shownKeys.add(row.key)
        return box
    }

    /** Ring colour for the focused logo: the colour the channel is known for. */
    private fun ringColor(item: Item): Int = brandColor(item)

    /** Logo-only tile: a round logo with its name and viewers below. The focused one grows and gets a ring. */
    private fun tile(card: Card, rowKey: String, tw: Int): Pair<View, TileHolder> {
        val item = card.item
        val ring = ringColor(item)
        val top = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val root = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            setOnClickListener { play(item) }
            isLongClickable = true
            setOnLongClickListener { startMove(rowKey); true }
            if (!item.live) alpha = 0.6f
        }

        val ringSize = px(69)
        val ringView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(maxOf(2, px(2.7f)), ring)
            }
            alpha = 0f
            if (Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = ring
        }
        root.addView(
            ringView,
            FrameLayout.LayoutParams(ringSize, ringSize, top).apply { topMargin = px(9) - (ringSize - px(60)) / 2 }
        )
        val logoView = logo(item, px(60))
        root.addView(logoView, FrameLayout.LayoutParams(px(60), px(60), top).apply { topMargin = px(9) })

        val name = text(item.name, 13f, th.ink, true).apply { gravity = Gravity.CENTER }
        root.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = px(76) })

        val countRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        var eye: Glyph? = null
        val countText: TextView
        if (card.ago.isNotEmpty()) {
            countText = text(card.ago, 12f, th.sub)
        } else if (!item.live) {
            countText = text("Not live", 12f, th.sub)
        } else {
            eye = Glyph(this, Glyph.EYE, th.sub)
            countRow.addView(eye, ll(px(12), px(12), r = px(3)))
            countText = text(fmt(item.watching), 12f, th.sub)
        }
        countRow.addView(countText)
        root.addView(countRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = px(95) })

        // viewer pill that sits on the lower edge of the focused logo
        val pillInk = hex("#1F1830")
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(9), px(2), px(9), px(2))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 12f * u
            }
            alpha = 0f
        }
        if (card.ago.isEmpty() && item.live) {
            pill.addView(Glyph(this, Glyph.EYE, pillInk), ll(px(13), px(13), r = px(4)))
            pill.addView(text(fmt(item.watching), 13f, pillInk, true))
        } else {
            pill.addView(text(if (item.live) card.ago else "Not live", 13f, pillInk, true))
        }
        root.addView(pill, FrameLayout.LayoutParams(-2, -2, top).apply { topMargin = px(79) })

        val holder = TileHolder(item, rowKey, name, null, countText, eye)
        root.tag = holder

        root.setOnFocusChangeListener { _, has ->
            val d = 130L
            val s = if (has) 1.5f else 1f
            val dy = if (has) px(6f).toFloat() else 0f
            logoView.animate().cancel()
            logoView.animate().scaleX(s).scaleY(s).translationY(dy)
                .translationZ(if (has) px(15f).toFloat() else 0f).setDuration(d).start()
            ringView.animate().cancel()
            ringView.animate().scaleX(s).scaleY(s).translationY(dy).alpha(if (has) 1f else 0f)
                .translationZ(if (has) px(14f).toFloat() else 0f).setDuration(d).start()
            name.animate().cancel()
            name.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f)
                .translationY(if (has) px(24f).toFloat() else 0f).setDuration(d).start()
            countRow.animate().cancel()
            countRow.animate().alpha(if (has) 0f else 1f).setDuration(d).start()
            pill.animate().cancel()
            pill.animate().alpha(if (has) 1f else 0f)
                .translationZ(if (has) px(20f).toFloat() else 0f).setDuration(d).start()
            root.alpha = if (item.live) 1f else if (has) 0.9f else 0.6f
            if (has) {
                selected = item
                activeRow = rowKey
                applyEmphasis()
                updatePanel()
            }
        }
        return Pair(root, holder)
    }

    // ---- moving a row ---------------------------------------------------------------------

    private fun startMove(key: String) {
        if (moving != null || rowHolders.size < 2) return
        val h = rowHolders.firstOrNull { it.key == key } ?: return
        moving = key
        visual.clear()
        visual.addAll(shownKeys)
        movePitch = if (rowBoxes.size > 1) rowBoxes[1].top - rowBoxes[0].top else 0
        val ac = th.accent
        (h.band.background as? GradientDrawable)?.apply {
            setColor(Color.argb(0x66, Color.red(ac), Color.green(ac), Color.blue(ac)))
            setStroke(maxOf(2, px(2.5f)), Color.argb(0xE6, Color.red(ac), Color.green(ac), Color.blue(ac)))
        }
        h.band.alpha = 1f
        h.box.translationZ = px(24f).toFloat()
        h.handle.scaleX = 0.7f
        h.handle.scaleY = 0.7f
        h.handle.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        pulse?.cancel()
        pulse = android.animation.ObjectAnimator.ofFloat(h.handle, View.ALPHA, 1f, 0.6f).apply {
            startDelay = 400
            duration = 700
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
        refreshHandle()
    }

    private fun refreshHandle() {
        val key = moving ?: return
        val h = rowHolders.firstOrNull { it.key == key } ?: return
        val i = visual.indexOf(key)
        h.glyph.dimUp = i == 0
        h.glyph.dimDown = i == visual.size - 1
        h.glyph.invalidate()
    }

    private fun slideRows(bounceKey: String? = null, bounce: Int = 0) {
        for (h in rowHolders) {
            val shift = (visual.indexOf(h.key) - shownKeys.indexOf(h.key)) * movePitch
            val extra = if (h.key == bounceKey) bounce else 0
            h.box.animate().cancel()
            if (extra != 0) {
                h.box.animate().translationY((shift + extra).toFloat()).setDuration(80)
                    .withEndAction { h.box.animate().translationY(shift.toFloat()).setDuration(120).start() }.start()
            } else {
                h.box.animate().translationY(shift.toFloat()).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
    }

    private fun moveRow(dir: Int) {
        val key = moving ?: return
        val i = visual.indexOf(key)
        val j = i + dir
        if (j < 0 || j >= visual.size) {
            slideRows(key, dir * px(10f))
            return
        }
        val other = visual[j]
        visual[i] = other
        visual[j] = key
        saveOrder(key, other)
        refreshHandle()
        slideRows()
    }

    private fun endMove() {
        if (moving == null) return
        pulse?.cancel()
        pulse = null
        moving = null
        applyOrderInPlace()
    }

    /**
     * After a move the rows are already shown in their new places (by translation). Put the same row views in that
     * order in their container and clear the move styling. Building the whole screen again here created about 500
     * new views for every finished move, and the old ones waited for the garbage collector (that was the memory
     * saw-tooth seen in the memory test). Falls back to the full rebuild if anything looks unexpected.
     */
    private fun applyOrderInPlace() {
        val box = rowsBox
        val byKey = rowHolders.associateBy { it.key }
        val order = visual.toList()
        if (box == null || order.size != rowHolders.size || order.any { it !in byKey }) {
            render()
            return
        }
        val keep = currentFocus
        val ac = th.accent
        box.removeAllViews()
        rowHolders.clear()
        rowBoxes.clear()
        shownKeys.clear()
        order.forEachIndexed { i, k ->
            val h = byKey.getValue(k)
            h.box.animate().cancel()
            h.box.translationY = 0f
            h.box.translationZ = 0f
            h.handle.animate().cancel()
            h.handle.alpha = 0f
            h.handle.scaleX = 1f
            h.handle.scaleY = 1f
            (h.band.background as? GradientDrawable)?.apply {
                setColor(Color.argb(0x2B, Color.red(ac), Color.green(ac), Color.blue(ac)))
                setStroke(maxOf(1, px(1f)), Color.argb(0x4D, Color.red(ac), Color.green(ac), Color.blue(ac)))
            }
            if (i > 0) box.addView(Space(this), ll(1, 0, 1f))
            box.addView(h.box, ll(ViewGroup.LayoutParams.MATCH_PARENT, px(ROW_H)))
            rowHolders.add(h)
            rowBoxes.add(h.box)
            shownKeys.add(k)
        }
        keep?.requestFocus()
        applyEmphasis()
    }

    private var tileBeforePill: View? = null

    /**
     * Arrow keys do what the arrows say.
     * Left and Right stay inside the row (nothing happens at either end).
     * Up and Down go to the same position in the next row; Up from the top row reaches the theme selector.
     * From the theme selector only Down does anything: it returns to the tile you left.
     * Returns true when the key was handled here.
     */
    private fun navigate(code: Int): Boolean {
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN &&
            code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        val f = currentFocus ?: return false
        val pill = themePill
        if (f === pill) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                val back = tileBeforePill?.takeIf { it.isAttachedToWindow }
                    ?: (rowHolders.firstOrNull()?.tiles as? ViewGroup)?.getChildAt(0)
                back?.requestFocus()
            }
            return true
        }
        val h = f.tag as? TileHolder ?: return false
        val ri = rowHolders.indexOfFirst { it.key == h.rowKey }
        if (ri < 0) return false
        val tiles = rowHolders[ri].tiles as? ViewGroup ?: return false
        val ci = tiles.indexOfChild(f)
        if (ci < 0) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (ci > 0) tiles.getChildAt(ci - 1).requestFocus()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (ci < tiles.childCount - 1) tiles.getChildAt(ci + 1).requestFocus()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val step = if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                val to = ri + step
                if (to < 0) {
                    if (pill != null) {
                        tileBeforePill = f
                        pill.requestFocus()
                    }
                } else if (to < rowHolders.size) {
                    val next = rowHolders[to].tiles as? ViewGroup
                    if (next != null && next.childCount > 0) next.getChildAt(minOf(ci, next.childCount - 1)).requestFocus()
                }
            }
        }
        return true
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP && e.keyCode == swallowUp) {
            swallowUp = -1
            return true
        }
        if (moving == null && e.action == KeyEvent.ACTION_DOWN && navigate(e.keyCode)) return true
        if (moving == null) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    swallowUp = e.keyCode
                    endMove()
                }
            }
        }
        return true
    }

    private fun applyEmphasis() {
        for (r in rowHolders) {
            val active = r.key == activeRow
            r.band.animate().cancel()
            r.band.animate().alpha(if (active) 1f else 0f).setDuration(150).start()
            r.tiles.animate().cancel()
            r.tiles.animate().alpha(if (active) 1f else 0.42f).setDuration(150).start()
        }
    }

    // ---- right panel --------------------------------------------------------------------

    private var playCircle: View? = null

    private fun canPlay(item: Item?): Boolean = item != null && item.live && youtubeInstalled() && isOnline()

    private fun updatePlayState() {
        playCircle?.alpha = if (canPlay(selected)) 1f else 0.3f
    }

    /** "45 min", "12 h", "1 d 13 h", "6 d" (up to 30 days); "since Feb 2022" for an older one. */
    private fun liveFor(since: Long): String {
        if (since <= 0) return ""
        val mins = ((System.currentTimeMillis() / 1000 - since) / 60).coerceAtLeast(0)
        return when {
            mins < 60 -> "$mins min"
            mins < 1440 -> "${mins / 60} h"
            mins < 2880 -> "1 d ${(mins - 1440) / 60} h"
            mins < 43200 -> "${mins / 1440} d"
            else -> {
                val f = SimpleDateFormat("MMM yyyy", Locale.US)
                f.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                "since " + f.format(java.util.Date(since * 1000))
            }
        }
    }

    // The right panel is built once per screen build and then only its text and colours change.
    // (Building it again on every focus move created about 60 views each time, which piled up.)
    private class NewsHolder(
        val root: LinearLayout, val divider: View, val ago: TextView, val title: TextView, val desc: TextView
    )

    private class PanelViews(
        val header: LinearLayout, val logoImg: ImageView, val logoTxt: TextView,
        val name: TextView, val region: TextView, val dot: View, val liveTxt: TextView, val upTxt: TextView,
        val count: TextView, val group: TextView, val newsSrc: TextView,
        val items: List<NewsHolder>, val empty: View, val itemsBox: LinearLayout
    )

    private var pv: PanelViews? = null

    private fun buildPanel(p: LinearLayout) {
        p.removeAllViews()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), 0, px(22), 0)
        }
        val logoSlot = FrameLayout(this)
        val logoImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = oval(Color.WHITE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        val logoTxt = text("", 21.6f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        logoSlot.addView(logoImg, FrameLayout.LayoutParams(-1, -1))
        logoSlot.addView(logoTxt, FrameLayout.LayoutParams(-1, -1))
        header.addView(logoSlot, ll(px(60), px(60), r = px(16)))

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val name = text("", 25f, th.ink, face = sora)
        val region = text("", 17f, th.sub)
        nameLine.addView(name)
        nameLine.addView(region, ll(-2, -2, l = px(8)))
        info.addView(nameLine)
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this)
        val liveTxt = text("", 14.5f, th.sub, true)
        val upTxt = text("", 14.5f, th.sub, true)
        val eye = Glyph(this, Glyph.EYE, th.sub)
        val count = text("", 14.5f, th.sub, true)
        val group = text("", 14.5f, th.sub)
        meta.addView(dot, ll(px(10), px(10), r = px(9)))
        meta.addView(liveTxt, ll(-2, -2, r = px(9)))
        meta.addView(upTxt, ll(-2, -2, r = px(9)))
        meta.addView(eye, ll(px(16), px(16), r = px(4)))
        meta.addView(count, ll(-2, -2, r = px(9)))
        meta.addView(group, ll(0, -2, 1f))
        info.addView(meta, ll(-2, -2, t = px(7)))
        header.addView(info, ll(0, -2, 1f))
        val circle = FrameLayout(this).apply {
            background = oval(th.accent)
            elevation = px(4f).toFloat()
        }
        circle.addView(Glyph(this, Glyph.PLAY, Color.WHITE), FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER))
        playCircle = circle
        header.addView(circle, ll(px(48), px(48)))
        p.addView(header, ll(ViewGroup.LayoutParams.MATCH_PARENT, px(104)))
        p.addView(View(this).apply { setBackgroundColor(th.rule) }, ll(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(14), px(24), px(4))
        }
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        label.addView(text("Top headlines", 17f, th.ink, true), ll(0, -2, 1f))
        val newsSrc = text("", 13f, th.sub)
        label.addView(newsSrc)
        body.addView(label, ll(ViewGroup.LayoutParams.MATCH_PARENT, px(26)))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(2), 0, 0)
        }
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        empty.addView(Glyph(this, Glyph.RSS, th.sub), ll(px(54), px(54)))
        empty.addView(text("No headlines right now", 26f, th.ink, face = sora), ll(-2, -2, t = px(12)))
        empty.addView(text(contactText, 17f, th.sub), ll(-2, -2, t = px(8)))
        box.addView(empty, ll(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        val items = ArrayList<NewsHolder>()
        repeat(MAX_HEADLINES) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val divider = View(this).apply { setBackgroundColor(th.rule) }
            v.addView(divider, ll(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(11), 0, px(10))
            }
            val ago = text("", 12f, th.accent, true).apply { letterSpacing = 0.04f }
            inner.addView(ago, ll(-2, -2, b = px(3)))
            val title = text("", 18f, th.ink, true, 2).apply { setLineSpacing(0f, 1.05f) }
            inner.addView(title)
            val desc = text("", 14f, th.sub, false, 2).apply { setLineSpacing(0f, 1.05f) }
            inner.addView(desc, ll(-2, -2, t = px(4)))
            v.addView(inner)
            box.addView(v, ll(ViewGroup.LayoutParams.MATCH_PARENT, -2))
            items.add(NewsHolder(v, divider, ago, title, desc))
        }
        body.addView(box, ll(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        p.addView(body, ll(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pv = PanelViews(header, logoImg, logoTxt, name, region, dot, liveTxt, upTxt, count, group, newsSrc, items, empty, box)
    }

    private fun updatePanel() {
        val v = pv ?: return
        val item = selected ?: return
        val live = item.live

        // header
        val rgb = brandColor(item)
        val r = 30f * u
        v.header.background = if (live) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.argb(52, Color.red(rgb), Color.green(rgb), Color.blue(rgb)),
                    Color.argb(10, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
                )
            ).apply { cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        } else null
        val bmp = bitmapFor(item)
        if (bmp != null) {
            v.logoImg.visibility = View.VISIBLE
            v.logoTxt.visibility = View.GONE
            v.logoImg.setImageBitmap(bmp)
            v.logoImg.colorFilter = if (live) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        } else {
            v.logoImg.visibility = View.GONE
            v.logoTxt.visibility = View.VISIBLE
            v.logoTxt.text = item.name.split(" ", "-").filter { it.isNotEmpty() }
                .take(2).joinToString("") { it.first().uppercase(Locale.ROOT) }
            v.logoTxt.background = oval(if (live) paletteColor(item) else Color.parseColor("#8E8E93"))
        }
        v.name.text = item.name
        v.region.text = item.region
        v.region.visibility = if (item.region.isNotEmpty()) View.VISIBLE else View.GONE
        val dotColor = if (live) Color.parseColor("#FF3B30") else Color.parseColor("#8E8E93")
        v.dot.background = oval(dotColor)
        v.liveTxt.text = if (live) "LIVE" else "NOT LIVE"
        v.liveTxt.setTextColor(dotColor)
        val upFor = if (live) liveFor(item.since) else ""
        v.upTxt.text = if (upFor.isNotEmpty()) "·  $upFor" else ""
        v.upTxt.visibility = if (upFor.isNotEmpty()) View.VISIBLE else View.GONE
        v.count.text = fmt(item.watching)
        v.group.text = "·  " + groupName(item.group)

        // headlines: as many whole headlines as fit
        v.newsSrc.text = if (item.news.isNotEmpty() && item.newsSrc.isNotEmpty()) item.newsSrc else ""
        if (item.news.isEmpty()) {
            v.empty.visibility = View.VISIBLE
            v.empty.layoutParams = (v.empty.layoutParams as LinearLayout.LayoutParams).apply { height = itemsAvail - px(2) }
            for (h in v.items) h.root.visibility = View.GONE
        } else {
            v.empty.visibility = View.GONE
            var used = px(2)
            var fits = true
            v.items.forEachIndexed { i, h ->
                val n = item.news.getOrNull(i)
                if (n == null || !fits) {
                    h.root.visibility = View.GONE
                    return@forEachIndexed
                }
                h.divider.visibility = if (i > 0) View.VISIBLE else View.GONE
                h.ago.visibility = if (n.ts > 0) View.VISIBLE else View.GONE
                if (n.ts > 0) h.ago.text = agoLong(n.ts)
                h.title.text = n.t
                h.desc.text = n.d
                h.desc.visibility = if (n.d.isNotEmpty()) View.VISIBLE else View.GONE
                h.root.measure(
                    View.MeasureSpec.makeMeasureSpec(itemsWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                if (used + h.root.measuredHeight > itemsAvail) {
                    fits = false
                    h.root.visibility = View.GONE
                } else {
                    used += h.root.measuredHeight
                    h.root.visibility = View.VISIBLE
                }
            }
        }
        updatePlayState()
    }

    companion object {
        private const val YOUTUBE_TV = "com.google.android.youtube.tv"
        private const val ROW_H = 148
        private const val TOP_H = 76
        private const val MAX_HEADLINES = 8
    }
}
