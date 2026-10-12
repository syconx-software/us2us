package com.syconx.us2us.ui.brand

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.syconx.us2us.domain.AvatarPresetCatalog
import com.syconx.us2us.domain.sticker.StickerText
import com.syconx.us2us.ui.brand.StickerFace.Brows
import com.syconx.us2us.ui.brand.StickerFace.Eyes
import com.syconx.us2us.ui.brand.StickerFace.Mouth
import com.syconx.us2us.ui.tokens.U2ColorTokens
import com.syconx.us2us.ui.tokens.U2ThemedColor

// The preset profile avatars: the sticker set's cup mascot in a circle, for people who would
// rather not upload a photo. Every preset is drawn in code (`BrandAvatarView`) from the same
// mascot, inks and pen as the stickers. Ids, keys and labels are the domain's
// `AvatarPresetCatalog`; this adds how each one looks.

/** What the cup's face shows: the sticker set's own expressions. */
enum class BrandAvatarExpression {
    /** Round eyes, a small smile: the mascot as on the app icon. */
    Smile,

    /** Round eyes, an open grin. */
    Grin,

    /** Closed smiling eyes (^ ^). */
    Happy,

    /** Closed smiling eyes and a grin. */
    Joy,

    /** Squeezed shut, laughing. */
    Laugh,

    /** A wink and the tip of the tongue. */
    Wink,

    /** Asleep. */
    Sleepy,

    /** Sunglasses and a smirk. */
    Cool,

    /** Wide eyes looking up, brows raised. */
    Curious,

    /** Heart eyes. */
    Love,

    /** Round eyes looking aside, a smirk. */
    Smirk,

    /** The ghost's hollow eyes and “o”. */
    Ghost,
    ;

    internal val face: StickerFace
        get() = when (this) {
            Smile -> StickerFace(eyes = Eyes.Dots(), mouth = Mouth.Smile)
            Grin -> StickerFace(eyes = Eyes.Dots(), mouth = Mouth.Grin)
            Happy -> StickerFace(eyes = Eyes.Happy, mouth = Mouth.Smile)
            Joy -> StickerFace(eyes = Eyes.Happy, mouth = Mouth.Grin)
            Laugh -> StickerFace(eyes = Eyes.Squeezed, mouth = Mouth.Laugh)
            Wink -> StickerFace(eyes = Eyes.Wink, mouth = Mouth.Tongue)
            Sleepy -> StickerFace(eyes = Eyes.Sleepy, mouth = Mouth.SmallO)
            Cool -> StickerFace(eyes = Eyes.Sunglasses, mouth = Mouth.Smirk, blush = false)
            Curious -> StickerFace(eyes = Eyes.Wide(p(0.9, -1.1)), mouth = Mouth.SmallO, brows = Brows.Raised)
            Love -> StickerFace(eyes = Eyes.Hearts, mouth = Mouth.Grin)
            Smirk -> StickerFace(eyes = Eyes.Dots(p(1.3, 0)), mouth = Mouth.Smirk)
            Ghost -> StickerFace(eyes = Eyes.Hollow, mouth = Mouth.O)
        }
}

/** What the cup wears or holds. Each one changes the silhouette, so presets stay apart at 28 dp. */
enum class BrandAvatarAccessory {
    None,
    Headphones,
    Beanie,

    /** Round glasses: two rings and a bar, the line mark of the app icon. */
    Glasses,
    Flower,
    Cap,
    Scarf,

    /** A sheet over the cup, hem and all. */
    GhostSheet,
    PartyHat,
    Nightcap,

    /** The string tied into a heart beside the cup. */
    StringHeart,
    Crown,
    Bow,

    /** A coffee sleeve and steam. */
    Coffee,
    CatEars,
    ChefHat,
    Antennae,
}

/**
 * The disc behind the cup: Nocturne's accent and neutral ramps, plus four quiet accents in the same
 * lightness (deep in the dark theme, pale in the light one), so a lilac cup with an ink outline
 * reads on every one.
 */
enum class BrandAvatarBackdrop(val token: U2ThemedColor) {
    /** `accent800`: the initials avatar's palette A. */
    Violet(U2ColorTokens.accent800),

    /** `accent700`: palette B. */
    Plum(U2ColorTokens.accent700),

    /** `accent900`: palette D, the user's own. */
    Ink(U2ColorTokens.accent900),

    /** `neutral800`: palette C. */
    Slate(U2ColorTokens.neutral800),

    // Deep: oklch ≈ 0.39 · 0.07 at the hue; pale: ≈ 0.93 · 0.03.
    Rose(U2ThemedColor(dark = 0x6b3a50, light = 0xf6d9dd)),
    Mint(U2ThemedColor(dark = 0x24564d, light = 0xd1eee1)),
    Sky(U2ThemedColor(dark = 0x2e4a76, light = 0xd5e3fa)),
    Sand(U2ThemedColor(dark = 0x5d4d34, light = 0xf1e3c6)),
    ;

    /** The disc's colour in a theme. */
    fun color(dark: Boolean): Color = token.resolve(dark)

    /** Pale enough in the light theme to need a hairline on `bg`. */
    val needsLightHairline: Boolean get() = this == Ink || this == Violet || this == Slate
}

/** Which of the two cups of the line: the mascot, or its lighter twin. */
enum class BrandAvatarCup {
    Mascot,
    Twin,
    ;

    internal val style: CupStyle get() = if (this == Mascot) CupStyle.Mid else CupStyle.Light
}

/**
 * One preset avatar. Its id is what an `AvatarChoice` stores (and carries wherever the choice goes),
 * so it is a stable value. Two presets are the same preset when their ids are.
 */
@Immutable
class BrandAvatarPreset(
    val id: Int,
    /** A readable name for code and docs (“cup.headphones”). Not stored. */
    val key: String,
    /** What TalkBack reads in the picker, in the app's language. */
    val label: StickerText,
    val backdrop: BrandAvatarBackdrop,
    val expression: BrandAvatarExpression,
    val accessory: BrandAvatarAccessory,
    val cup: BrandAvatarCup = BrandAvatarCup.Mascot,
) {
    override fun equals(other: Any?): Boolean = other is BrandAvatarPreset && other.id == id

    override fun hashCode(): Int = id

    override fun toString(): String = "BrandAvatarPreset($id, $key)"
}

object BrandAvatarCatalog {
    /** Ids that were used once and must never be handed out again. */
    val retiredIds: Set<Int> get() = AvatarPresetCatalog.retiredIds

    /** In the picker's order. */
    val all: List<BrandAvatarPreset> = listOf(
        preset(1, BrandAvatarBackdrop.Violet, BrandAvatarExpression.Smile, BrandAvatarAccessory.None),
        preset(2, BrandAvatarBackdrop.Mint, BrandAvatarExpression.Happy, BrandAvatarAccessory.Headphones),
        preset(3, BrandAvatarBackdrop.Sky, BrandAvatarExpression.Grin, BrandAvatarAccessory.Beanie),
        preset(4, BrandAvatarBackdrop.Plum, BrandAvatarExpression.Smile, BrandAvatarAccessory.Glasses),
        preset(5, BrandAvatarBackdrop.Rose, BrandAvatarExpression.Happy, BrandAvatarAccessory.Flower),
        preset(6, BrandAvatarBackdrop.Sand, BrandAvatarExpression.Smirk, BrandAvatarAccessory.Cap),
        preset(7, BrandAvatarBackdrop.Slate, BrandAvatarExpression.Joy, BrandAvatarAccessory.Scarf),
        preset(8, BrandAvatarBackdrop.Ink, BrandAvatarExpression.Ghost, BrandAvatarAccessory.GhostSheet),
        preset(9, BrandAvatarBackdrop.Sky, BrandAvatarExpression.Cool, BrandAvatarAccessory.None),
        preset(10, BrandAvatarBackdrop.Violet, BrandAvatarExpression.Laugh, BrandAvatarAccessory.PartyHat),
        preset(11, BrandAvatarBackdrop.Rose, BrandAvatarExpression.Love, BrandAvatarAccessory.StringHeart),
        preset(12, BrandAvatarBackdrop.Sky, BrandAvatarExpression.Sleepy, BrandAvatarAccessory.Nightcap),
        preset(13, BrandAvatarBackdrop.Plum, BrandAvatarExpression.Wink, BrandAvatarAccessory.None),
        preset(14, BrandAvatarBackdrop.Mint, BrandAvatarExpression.Grin, BrandAvatarAccessory.Crown),
        preset(15, BrandAvatarBackdrop.Plum, BrandAvatarExpression.Happy, BrandAvatarAccessory.Bow),
        preset(16, BrandAvatarBackdrop.Sand, BrandAvatarExpression.Happy, BrandAvatarAccessory.Coffee),
        preset(17, BrandAvatarBackdrop.Ink, BrandAvatarExpression.Curious, BrandAvatarAccessory.None),
        preset(18, BrandAvatarBackdrop.Violet, BrandAvatarExpression.Joy, BrandAvatarAccessory.None, cup = BrandAvatarCup.Twin),
        preset(19, BrandAvatarBackdrop.Rose, BrandAvatarExpression.Smile, BrandAvatarAccessory.CatEars),
        preset(20, BrandAvatarBackdrop.Slate, BrandAvatarExpression.Grin, BrandAvatarAccessory.ChefHat),
        preset(21, BrandAvatarBackdrop.Mint, BrandAvatarExpression.Curious, BrandAvatarAccessory.Antennae),
        preset(22, BrandAvatarBackdrop.Sand, BrandAvatarExpression.Laugh, BrandAvatarAccessory.None),
    )

    private val byId: Map<Int, BrandAvatarPreset> = all.associateBy { it.id }

    /** The preset for a stored id; `null` for one this build does not know (a newer client's): the avatar then falls back to the initial. */
    fun preset(id: Int): BrandAvatarPreset? = byId[id]

    private fun preset(
        id: Int,
        backdrop: BrandAvatarBackdrop,
        expression: BrandAvatarExpression,
        accessory: BrandAvatarAccessory,
        cup: BrandAvatarCup = BrandAvatarCup.Mascot,
    ): BrandAvatarPreset {
        val listed = checkNotNull(AvatarPresetCatalog.preset(id)) { "preset $id is not in the domain catalogue" }
        return BrandAvatarPreset(id, listed.key, listed.label, backdrop, expression, accessory, cup)
    }
}
