package com.syconx.us2us.shot

import java.io.File

/** A rectangle in points (= dp), origin at the top-left of the 402 × 874 screen. */
data class Frame(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    /** The largest difference of any edge or of the size. */
    fun distanceTo(other: Frame): Float = maxOf(
        kotlin.math.abs(x - other.x),
        kotlin.math.abs(y - other.y),
        kotlin.math.abs(w - other.w),
        kotlin.math.abs(h - other.h),
    )

    fun offset(dx: Float, dy: Float): Frame = copy(x = x + dx, y = y + dy)

    override fun toString(): String = "x=${x.fmt()} y=${y.fmt()} w=${w.fmt()} h=${h.fmt()}"

    internal companion object {
        fun of(value: Any?): Frame? {
            val map = value as? Map<*, *> ?: return null
            fun n(key: String) = (map[key] as? Number)?.toFloat() ?: 0f
            return Frame(n("x"), n("y"), n("w"), n("h"))
        }
    }
}

internal fun Float.fmt(): String = if (this == Math.round(this).toFloat()) Math.round(this).toString() else "%.2f".format(java.util.Locale.ROOT, this)

/** A colour of the design: its token (`text`, `accent300`, `text·9%` …) and the computed dark and light values. */
data class LayoutColor(val token: String?, val dark: String?, val light: String?) {
    fun forTheme(theme: ShotTheme): String? = if (theme.isDark) dark else light
}

/** The computed font of a text element. */
data class LayoutFont(
    val family: String,
    val weight: Float,
    val size: Float,
    val lineHeight: Float,
    val letterSpacing: String?,
    val textAlign: String?,
    val textTransform: String?,
    val fontVariationSettings: String?,
)

/** One element of `ios/design/layout/<screen>.json` (ids are the DOM paths used in inventory.md, e.g. `frame.1.0.1`). */
data class LayoutElement(
    val id: String,
    val tag: String,
    /** text, shape, stack, icon, svg, box, spacer or canvas. */
    val kind: String,
    val frame: Frame,
    val text: String?,
    val fullText: String?,
    val textLineRects: List<Frame>,
    val font: LayoutFont?,
    val color: LayoutColor?,
    val background: LayoutColor?,
    /** The Phosphor glyph of an icon element (`caret-left`, `regular`, 22). */
    val icon: Map<String, Any?>?,
    /** The CSS the design declared for the element, with token names. */
    val declared: String?,
    val decorative: Boolean,
    val children: List<LayoutElement>,
    /** Everything else of the element (`layout`, `paint`, `svg` …) as parsed JSON. */
    val raw: Map<String, Any?>,
) {
    /** This element and all below it, depth first. */
    fun flatten(): Sequence<LayoutElement> = sequence {
        yield(this@LayoutElement)
        for (child in children) yieldAll(child.flatten())
    }

    @Suppress("UNCHECKED_CAST")
    val layout: Map<String, Any?> get() = raw["layout"] as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    val paint: Map<String, Any?> get() = raw["paint"] as? Map<String, Any?> ?: emptyMap()

    override fun toString(): String = "$id <$tag> $kind [$frame]" + (text?.let { " \"$it\"" } ?: "")

    internal companion object {
        @Suppress("UNCHECKED_CAST")
        fun of(map: Map<String, Any?>): LayoutElement {
            fun color(key: String): LayoutColor? = (map["paint"] as? Map<String, Any?>)?.get(key)?.let { value ->
                val c = value as? Map<String, Any?> ?: return@let null
                LayoutColor(c["token"] as? String, c["dark"] as? String, c["light"] as? String)
            }
            val font = (map["font"] as? Map<String, Any?>)?.let { f ->
                LayoutFont(
                    family = f["family"] as? String ?: "",
                    weight = (f["weight"] as? Number)?.toFloat() ?: 400f,
                    size = (f["size"] as? Number)?.toFloat() ?: 0f,
                    lineHeight = (f["lineHeight"] as? Number)?.toFloat() ?: 0f,
                    letterSpacing = f["letterSpacing"]?.toString(),
                    textAlign = f["textAlign"] as? String,
                    textTransform = f["textTransform"] as? String,
                    fontVariationSettings = f["fontVariationSettings"] as? String,
                )
            }
            return LayoutElement(
                id = map["id"] as String,
                tag = map["tag"] as? String ?: "",
                kind = map["kind"] as? String ?: "",
                frame = Frame.of(map["frame"]) ?: Frame(0f, 0f, 0f, 0f),
                text = map["text"] as? String,
                fullText = map["fullText"] as? String,
                textLineRects = (map["textLineRects"] as? List<Any?>).orEmpty().mapNotNull(Frame::of),
                font = font,
                color = color("color"),
                background = color("background"),
                icon = map["icon"] as? Map<String, Any?>,
                declared = map["declared"] as? String,
                decorative = map["decorative"] == true,
                children = (map["children"] as? List<Map<String, Any?>>).orEmpty().map(::of),
                raw = map - "children",
            )
        }
    }
}

/**
 * One screen of `ios/design/layout/` — the element tree measured in Chrome from the design file,
 * in points from the top-left of the 402 × 874 screen (status bar included). Element frames are
 * what the geometry gate ([assertFrame]) holds the Compose screens to.
 */
class LayoutSpec private constructor(
    /** The file name without `.json`: `3.2-kod-yanlis`, `4.1-sohbet-listesi-urgent`. */
    val name: String,
    /** The design's screen number: `3.2`. */
    val screen: String,
    val title: String,
    val file: File,
    /** Reference PNGs by variant (`dark`, `light`, `urgent` …), relative to `ios/design`. */
    val references: Map<String, String>,
    val width: Float,
    val height: Float,
    val roots: List<LayoutElement>,
) {
    /** Every element, depth first. */
    val elements: List<LayoutElement> by lazy { roots.flatMap { it.flatten().toList() } }

    private val byId: Map<String, LayoutElement> by lazy { elements.associateBy { it.id } }

    fun find(id: String): LayoutElement? = byId[id]

    operator fun get(id: String): LayoutElement = byId[id] ?: throw NoSuchElementException(
        "$name has no element '$id'" + suggestions(id).let { if (it.isEmpty()) "" else "; near: ${it.joinToString()}" },
    )

    /** Elements whose text (or full text) is [text]. */
    fun withText(text: String): List<LayoutElement> = elements.filter { it.text == text || it.fullText == text }

    fun frame(id: String): Frame = this[id].frame

    private fun suggestions(id: String): List<String> {
        val parent = id.substringBeforeLast('.', "")
        return elements.map { it.id }.filter { parent.isNotEmpty() && it.startsWith("$parent.") }.take(8)
    }

    override fun toString(): String = "LayoutSpec($name, ${elements.size} elements)"

    companion object {
        private val cache = HashMap<File, LayoutSpec>()

        /** `ios/design/layout`. */
        val directory: File get() = ShotPaths.layoutDir

        /**
         * Loads a layout by file name (`3.2-kod-yanlis`, `urgent/5.1-sohbet-urgent`, `4.1-sohbet-listesi-urgent`)
         * or by screen number (`3.2`, `9.1`).
         */
        fun load(name: String): LayoutSpec {
            val file = resolve(name) ?: throw NoSuchElementException(
                "no layout '$name' in $directory (have: ${all().joinToString { it.name }})",
            )
            return synchronized(cache) { cache.getOrPut(file) { parse(file) } }
        }

        /** Whether [load] finds a layout for [name]. */
        fun exists(name: String): Boolean = resolve(name) != null

        /** Every layout, the drawn screens first, then `urgent/`. */
        fun all(): List<LayoutSpec> = files().map { load(it.nameWithoutExtension) }

        private fun files(): List<File> {
            val main = directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            val urgent = File(directory, "urgent").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            return main + urgent
        }

        private fun resolve(name: String): File? {
            val stem = name.removeSuffix(".json")
            val candidates = listOf(File(directory, "$stem.json"), File(directory, "urgent/${stem.removePrefix("urgent/")}.json"))
            candidates.firstOrNull { it.isFile }?.let { return it }
            // A screen number: `3.2` → `3.2-kod-yanlis.json` (the drawn screen, not its urgent variant).
            return directory.listFiles { f -> f.extension == "json" && f.name.startsWith("$stem-") }
                .orEmpty().minByOrNull { it.name.length }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parse(file: File): LayoutSpec {
            val root = Json.parse(file.readText()) as Map<String, Any?>
            val size = root["size"] as? Map<String, Any?>
            return LayoutSpec(
                name = file.nameWithoutExtension,
                screen = root["screen"]?.toString() ?: file.nameWithoutExtension.substringBefore('-'),
                title = root["title"] as? String ?: "",
                file = file,
                references = (root["reference"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() },
                width = (size?.get("w") as? Number)?.toFloat() ?: DesignMoment.WIDTH_DP.toFloat(),
                height = (size?.get("h") as? Number)?.toFloat() ?: DesignMoment.HEIGHT_DP.toFloat(),
                roots = (root["elements"] as List<Map<String, Any?>>).map(LayoutElement::of),
            )
        }
    }
}
