package app.fluvour.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.ui.theme.Fluvour
import app.fluvour.ui.theme.FluvourFonts
import kotlin.math.PI
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Fonts. Themes without designFonts (Calm, Playful) keep the system font.
// ---------------------------------------------------------------------------------------------
@Composable @ReadOnlyComposable fun serifFont(): FontFamily = if (Fluvour.style.designFonts) FluvourFonts.Serif else FontFamily.Default
/** Font for text in the language being learned. */
@Composable @ReadOnlyComposable fun targetFont(): FontFamily = if (Fluvour.style.designFonts) FluvourFonts.target(Fluvour.lang) else FontFamily.Default
@Composable @ReadOnlyComposable fun uiWeight(): FontWeight = if (Fluvour.style.designFonts) FontWeight.Medium else FontWeight.Bold

// ---------------------------------------------------------------------------------------------
// Text styles from the design: eyebrow, h1, h2, small
// ---------------------------------------------------------------------------------------------
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Fluvour.colors.mute) =
    Text(text.uppercase(), modifier, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.8.sp)

/** The big serif title. [accentSuffix] is drawn in the accent colour (the design's full stop after the greeting). */
@Composable
fun H1(text: String, modifier: Modifier = Modifier, size: TextUnit = 32.sp, accentSuffix: String? = null, font: FontFamily? = null) {
    val c = Fluvour.colors
    val t = buildAnnotatedString {
        append(text)
        if (accentSuffix != null) withStyle(SpanStyle(color = c.accent)) { append(accentSuffix) }
    }
    Text(t, modifier, color = c.ink, fontFamily = font ?: serifFont(), fontSize = size, lineHeight = size * 1.12f, letterSpacing = (-0.015).em(size))
}

@Composable
fun H2(text: String, modifier: Modifier = Modifier, size: TextUnit = 21.sp, color: Color = Fluvour.colors.ink, font: FontFamily? = null, align: TextAlign? = null) =
    Text(text, modifier, color = color, fontFamily = font ?: serifFont(), fontSize = size, lineHeight = size * 1.25f, textAlign = align)

@Composable
fun Sm(text: String, modifier: Modifier = Modifier, color: Color = Fluvour.colors.mute, size: TextUnit = 13.sp, align: TextAlign? = null) =
    Text(text, modifier, color = color, fontSize = size, lineHeight = size * 1.45f, textAlign = align)

private fun Double.em(size: TextUnit): TextUnit = (this * size.value).sp

/** Text in the language being learned (serif, language-specific font). */
@Composable
fun TargetText(text: String, modifier: Modifier = Modifier, size: TextUnit = 20.sp, color: Color = Fluvour.colors.ink,
               lineHeight: TextUnit = size * 1.45f, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, color = color, fontFamily = targetFont(), fontSize = size, lineHeight = lineHeight, textAlign = align, maxLines = maxLines)

// ---------------------------------------------------------------------------------------------
// Cards and buttons
// ---------------------------------------------------------------------------------------------
@Composable
fun FCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(Fluvour.shapes.card)
    val frame = if (Fluvour.style.flat) Modifier.border(1.dp, c.line, shape) else Modifier.shadow(Fluvour.style.cardShadow, shape, clip = false)
    Column(
        modifier.then(frame).clip(shape).background(c.card, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content
    )
}

@Composable
fun FButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, outlined: Boolean = false, enabled: Boolean = true,
    soft: Boolean = false, hot: Boolean = false, icon: ImageVector? = null
) {
    val c = Fluvour.colors; val flat = Fluvour.style.flat
    val shape = RoundedCornerShape(Fluvour.shapes.button)
    val h = if (flat) 46.dp else 48.dp
    val label: @Composable RowScope.() -> Unit = {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = uiWeight(), fontSize = 14.sp)
    }
    when {
        outlined || hot -> OutlinedButton(onClick, modifier.height(h), enabled, shape,
            border = BorderStroke(if (flat) 1.dp else 2.dp, if (hot) c.accent else if (flat) c.line else c.ink),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hot) c.accent else c.ink), content = label)
        soft -> Button(onClick, modifier.height(h), enabled, shape,
            colors = ButtonDefaults.buttonColors(containerColor = c.sky, contentColor = c.mute), content = label)
        else -> Button(onClick, modifier.height(h), enabled, shape,
            colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary,
                disabledContainerColor = c.sky, disabledContentColor = c.mute), content = label)
    }
}

/** Small button used inside rows ("Add back", "Generate"). */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, soft: Boolean = false) {
    val c = Fluvour.colors; val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(36.dp).clip(shape).then(if (soft) Modifier.background(c.sky, shape) else Modifier.border(1.dp, c.line, shape))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (soft) c.mute else c.ink) }
}

/** Quiet text link in the accent colour ("Browse cards", "Random"). */
@Composable
fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Text(text, modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        color = Fluvour.colors.accent, fontSize = 13.sp)

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
        H1(title, size = 30.sp)
        if (subtitle != null) Sm(subtitle, Modifier.padding(top = 6.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Rows, tags, tabs
// ---------------------------------------------------------------------------------------------
@Composable
fun ChevronGo() = Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Fluvour.colors.mute)

/** The list row used everywhere: serif title, small subtitle, optional leading art and trailing slot. */
@Composable
fun FRow(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, titleFont: FontFamily? = null, titleSize: TextUnit = 17.sp,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = { ChevronGo() },
    onClick: (() -> Unit)? = null, divider: Boolean = true
) {
    val c = Fluvour.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, color = c.ink, fontFamily = titleFont ?: serifFont(), fontSize = titleSize, lineHeight = titleSize * 1.3f)
                if (subtitle != null) Text(subtitle, color = c.mute, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            trailing?.invoke()
        }
        if (divider) HorizontalDivider(color = c.line)
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, font: FontFamily? = null) {
    val c = Fluvour.colors
    Text(
        if (font == null) text.uppercase() else text, modifier.border(1.dp, c.line, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp),
        color = c.mute, fontSize = if (font == null) 11.sp else 13.sp, fontWeight = FontWeight.Medium,
        letterSpacing = if (font == null) 1.3.sp else 0.sp, fontFamily = font
    )
}

@Composable
fun UnderlineTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Fluvour.colors
    Box(modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = c.line)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items.forEachIndexed { i, label ->
                val on = i == selected
                Column(Modifier.clickable { onSelect(i) }.padding(top = 10.dp)) {
                    Text(label, fontSize = 13.5.sp, color = if (on) c.ink else c.mute, modifier = Modifier.padding(bottom = 10.dp))
                    Box(Modifier.height(2.dp).fillMaxWidth().background(if (on) c.accent else Color.Transparent))
                }
            }
        }
    }
}

@Composable
fun HairlineProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 2.dp) {
    val c = Fluvour.colors
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(1.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
    }
}

@Composable
fun IconBtn(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Fluvour.colors.ink) =
    IconButton(onClick, modifier.size(44.dp)) { Icon(icon, description, Modifier.size(20.dp), tint = tint) }

@Composable
fun PageBar(onBack: (() -> Unit)? = null, title: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null, closeIcon: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(if (closeIcon) Icons.Outlined.Close else Icons.Outlined.ArrowBack, if (closeIcon) "Close" else "Back", onBack)
        else Spacer(Modifier.size(44.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (title != null) Eyebrow(title) }
        if (trailing != null) Row(content = trailing) else Spacer(Modifier.size(44.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Settings kit: switch, stepper, option row, setting row
// ---------------------------------------------------------------------------------------------
@Composable
fun FSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Fluvour.colors
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, label = "knob")
    Box(modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(if (checked) c.accent else c.line)) {
        Box(Modifier.offset(x, 3.dp).size(20.dp).clip(CircleShape).background(c.card))
    }
}

@Composable
fun SettingRow(label: String, modifier: Modifier = Modifier, sub: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Fluvour.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = c.ink, fontSize = 15.5.sp)
                if (!sub.isNullOrBlank()) Text(sub, color = c.mute, fontSize = 12.5.sp, modifier = Modifier.padding(top = 1.dp))
            }
            trailing?.invoke()
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) =
    SettingRow(label, sub = sub, onClick = { onChange(!checked) }, trailing = { FSwitch(checked) })

@Composable
fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null, sub: String? = null, valueFont: FontFamily? = null) =
    SettingRow(label, sub = sub, onClick = onClick, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, color = Fluvour.colors.mute, fontSize = 14.sp, fontFamily = valueFont)
            if (onClick != null) ChevronGo()
        }
    })

@Composable
fun FStepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    val c = Fluvour.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(44.dp).border(1.dp, c.line, CircleShape).clip(CircleShape).clickable(onClick = onMinus), Alignment.Center) {
            Icon(Icons.Outlined.Remove, "Less", Modifier.size(16.dp), tint = c.ink)
        }
        Text(value, Modifier.widthIn(min = 34.dp), color = c.ink, fontFamily = serifFont(), fontSize = 21.sp, textAlign = TextAlign.Center)
        Box(Modifier.size(44.dp).border(1.dp, c.line, CircleShape).clip(CircleShape).clickable(onClick = onPlus), Alignment.Center) {
            Icon(Icons.Outlined.Add, "More", Modifier.size(16.dp), tint = c.ink)
        }
    }
}

/** Radio-style option row (the design's .op). */
@Composable
fun OptionRow(title: String, selected: Boolean, onClick: () -> Unit, sub: String? = null, trailing: (@Composable () -> Unit)? = null, titleFont: FontFamily? = null) {
    val c = Fluvour.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(20.dp).border(1.5.dp, if (selected) c.accent else c.line, CircleShape), Alignment.Center) {
                if (selected) Box(Modifier.size(9.dp).background(c.accent, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = c.ink, fontFamily = titleFont ?: serifFont(), fontSize = 18.sp)
                if (sub != null) Text(sub, color = c.mute, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            trailing?.invoke()
        }
        HorizontalDivider(color = c.line)
    }
}

/** Underline text field (the design's .fd). */
@Composable
fun UnderlineField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    singleLine: Boolean = false, size: TextUnit = 19.sp, font: FontFamily? = null, minLines: Int = 1, readOnly: Boolean = false
) {
    val c = Fluvour.colors
    Column(modifier.fillMaxWidth().padding(top = 20.dp)) {
        Eyebrow(label)
        TextField(
            value, onChange, Modifier.fillMaxWidth(), readOnly = readOnly, singleLine = singleLine, minLines = minLines,
            textStyle = TextStyle(fontFamily = font ?: serifFont(), fontSize = size, color = c.ink, lineHeight = size * 1.4f),
            placeholder = { Text(placeholder, color = c.mute, fontSize = size, fontFamily = font ?: serifFont()) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = c.accent, unfocusedIndicatorColor = c.line, cursorColor = c.accent
            )
        )
    }
}

/** Search field with a hairline underline (the design's .sf). */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    val c = Fluvour.colors
    TextField(
        value, onChange, modifier.fillMaxWidth(), singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = c.mute) },
        placeholder = { Text(placeholder, color = c.mute, fontSize = 16.sp) },
        textStyle = TextStyle(fontSize = 16.sp, color = c.ink),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = c.accent, unfocusedIndicatorColor = c.line, cursorColor = c.accent
        )
    )
}

// ---------------------------------------------------------------------------------------------
// Bottom sheet
// ---------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FSheet(onDismiss: () -> Unit, tall: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Fluvour.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = tall)
    val r = Fluvour.shapes.sheet
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = c.paper, scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = r, topEnd = r),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(32.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) }
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp).then(if (tall) Modifier.fillMaxHeight(0.92f) else Modifier)
                .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 26.dp),
            content = content
        )
    }
}

@Composable
fun SheetHeader(title: String, onClose: () -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(title, Modifier.weight(1f).padding(top = 8.dp))
        trailing?.let { Row(content = it) }
        IconBtn(Icons.Outlined.Close, "Close", onClose)
    }

// ---------------------------------------------------------------------------------------------
// Visual pieces
// ---------------------------------------------------------------------------------------------
/** "Ring with a percentage", used on result screens. */
@Composable
fun RingPercent(percent: Int, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val c = Fluvour.colors
    val sweep by animateFloatAsState(percent / 100f * 360f, tween(1200, 200), label = "ring")
    Box(modifier.size(size), Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            drawArc(c.line, 0f, 360f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(c.accent, -90f, sweep, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("$percent%", color = c.ink, fontFamily = serifFont(), fontSize = 26.sp)
    }
}

/** Timer ring that drains. [fraction] 1 = full. */
@Composable
fun TimerRing(fraction: Float, label: String, modifier: Modifier = Modifier, size: Dp = 104.dp) {
    val c = Fluvour.colors
    Box(modifier.size(size), Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            drawArc(c.line, 0f, 360f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(c.accent, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(label, color = c.ink, fontFamily = serifFont(), fontSize = 28.sp)
    }
}

/** Voice waveform. Moves while [active]. */
@Composable
fun Wave(active: Boolean, modifier: Modifier = Modifier, bars: Int = 25) {
    val c = Fluvour.colors
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    Row(modifier.fillMaxWidth().height(44.dp), Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
        for (i in 0 until bars) {
            val k = if (active) (sin((i * 0.55 + phase * 2 * PI)) * 0.5 + 0.5).toFloat() else 0f
            Box(Modifier.width(2.dp).height((6 + 30 * k).dp).clip(RoundedCornerShape(1.dp)).background(c.accent))
        }
    }
}

/** Round accent button used for play, record and the mic. */
@Composable
fun RoundAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 58.dp, ring: Boolean = false) {
    val c = Fluvour.colors
    Box(
        modifier.size(size).then(if (ring) Modifier.border(10.dp, c.accent.copy(alpha = 0.15f), CircleShape) else Modifier)
            .clip(CircleShape).background(c.accent).clickable(onClick = onClick), Alignment.Center
    ) { Icon(icon, description, Modifier.size(size * 0.38f), tint = c.onPrimary) }
}

/** A printed cover or thumbnail from the design's illustration kit. See ui/art/Art.kt for the scene names. */
@Composable
fun CoverArt(key: String, modifier: Modifier = Modifier, corner: Dp = 8.dp, small: Boolean = false) =
    app.fluvour.ui.art.Art(key, modifier, small, corner)

@Composable
fun GoRow(label: String, sub: String?, onClick: () -> Unit) = FRow(label, subtitle = sub, onClick = onClick)

/** Shown where a feature exists in the design but is not built yet. Keeps the screen honest while the feature is added later. */
@Composable
fun NotBuiltNote(text: String, modifier: Modifier = Modifier) {
    val c = Fluvour.colors
    Row(modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.size(14.dp).padding(top = 2.dp), tint = c.mute)
        Text(text, color = c.mute, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

/** Small toast-like message line. Screens that need a toast keep a SnackbarHost; this is the in-flow version. */
@Composable
fun SectionEyebrow(text: String, modifier: Modifier = Modifier, top: Dp = 28.dp) = Eyebrow(text, modifier.padding(top = top, bottom = 2.dp))
