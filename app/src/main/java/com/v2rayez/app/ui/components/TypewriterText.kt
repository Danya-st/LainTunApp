package com.v2rayez.app.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.v2rayez.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/** Неразрывный пробел: «клетка» под курсор после последней буквы. */
private const val CURSOR_CELL = '\u00A0'

/**
 * Текст, который печатается побуквенно, как в старых терминалах (моноширинный шрифт, блочный курсор).
 *
 * - Ещё не напечатанная часть рисуется прозрачной, а не отсутствует: размер блока сразу финальный,
 *   поэтому ничего не «прыгает» и слова не перескакивают на новую строку по ходу печати.
 * - Курсор — подсвеченная клетка на месте следующей буквы (раскладку не двигает). Во время печати горит
 *   постоянно, после — мигает; при «Убрать анимацию» в системе текст появляется сразу, курсор не мигает.
 * - Новый [text] печатается заново с начала. [onFinished] вызывается, когда строка допечатана.
 * - TalkBack получает полный текст сразу (AnnotatedString содержит все символы).
 */
@Composable
fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    cursor: Boolean = false,
    cursorColor: Color = if (color.isSpecified) color else Color.White,
    charDelayMs: Long = 35L,
    reduceMotion: Boolean = LocalReduceMotion.current,
    onFinished: () -> Unit = {}
) {
    val finished by rememberUpdatedState(onFinished)
    var typed by remember(text, reduceMotion) { mutableIntStateOf(if (reduceMotion) text.length else 0) }
    var blinkOn by remember { mutableStateOf(true) }

    LaunchedEffect(text, reduceMotion) {
        if (reduceMotion) {
            typed = text.length
        } else {
            typed = 0
            while (typed < text.length) {
                delay(charDelayMs)
                typed++
            }
        }
        finished()
    }

    val done = typed >= text.length
    LaunchedEffect(cursor, done, reduceMotion) {
        blinkOn = true
        if (cursor && done && !reduceMotion) {
            while (true) {
                delay(530)
                blinkOn = !blinkOn
            }
        }
    }

    val full = text + CURSOR_CELL
    val shown = typed.coerceIn(0, text.length)
    val hidden = SpanStyle(color = Color.Transparent)
    val annotated = buildAnnotatedString {
        append(full.substring(0, shown))
        val cursorSpan =
            if (cursor && blinkOn) SpanStyle(color = Color.Transparent, background = cursorColor) else hidden
        withStyle(cursorSpan) { append(full[shown]) }
        if (shown + 1 < full.length) withStyle(hidden) { append(full.substring(shown + 1)) }
    }
    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        style = style.copy(fontFamily = FontFamily.Monospace)
    )
}
