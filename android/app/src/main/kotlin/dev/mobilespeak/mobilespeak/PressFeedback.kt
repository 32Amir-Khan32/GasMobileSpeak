package dev.mobilespeak.mobilespeak

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// shortcut: SwiftUI default fades and curve control points are approximations; remeasure on iOS changes.
internal data class ContentPressFeedback(val pressedAlpha: Float, val releaseMillis: Int) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): Modifier.Node = PressFeedbackNode(interactionSource, this)
}
internal val RowPressFeedback = ContentPressFeedback(0.75f, 350)
internal val ToolbarPressFeedback = ContentPressFeedback(0.4f, 400)
internal val ObservedPressRecovery = CubicBezierEasing(0f, 0f, 0.2f, 1f)
internal object AudioPressFeedback : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): Modifier.Node = PressFeedbackNode(interactionSource, null)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()
}

@Composable
internal fun PressFeedback(feedback: IndicationNodeFactory?, content: @Composable (MutableInteractionSource, Modifier, Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    // Zero ripple alpha still leaves Android's sparkle effect; filter presses, preserving focus/hover.
    val materialSource = remember(source) { object : MutableInteractionSource by source {
        override val interactions = source.interactions.filter {
            it !is PressInteraction.Press && it !is PressInteraction.Release && it !is PressInteraction.Cancel
        }
    } }
    val pressed by source.collectIsPressedAsState()
    content(materialSource, if (feedback == null) Modifier else Modifier.indication(source, feedback), pressed)
}

private class PressFeedbackNode(
    private val source: InteractionSource,
    private val content: ContentPressFeedback?,
) : Modifier.Node(), DrawModifierNode {
    private val progress = Animatable(0f)
    private val scale = Animatable(0.4f)
    private val presses = mutableSetOf<PressInteraction.Press>()
    private var animation: Job? = null
    private val easeOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    override fun onDetach() { presses.clear() }

    override fun onAttach() {
        coroutineScope.launch {
            progress.snapTo(0f)
            scale.snapTo(0.4f)
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                    else -> return@collect
                }
                animation?.cancel()
                animation = launch {
                    val pressed = presses.isNotEmpty()
                    if (content != null) {
                        if (pressed) progress.snapTo(1f)
                        else progress.animateTo(0f, tween(content.releaseMillis, easing = ObservedPressRecovery))
                    } else if (!pressed && interaction is PressInteraction.Cancel) {
                        progress.snapTo(0f)
                        scale.snapTo(0.4f)
                    } else if (pressed) {
                        if (progress.value == 0f) scale.snapTo(0.4f)
                        launch { scale.animateTo(1f, tween(160, easing = easeOut)) }
                        progress.animateTo(1f, tween(160, easing = easeOut))
                    } else progress.animateTo(0f, tween(200, easing = easeOut))
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (content != null && progress.value > 0f) {
            drawIntoCanvas { canvas ->
                // Unread badges extend beyond icons; expand only the paint layer, never the hit target.
                canvas.saveLayer(Rect(-8.dp.toPx(), -8.dp.toPx(), size.width + 8.dp.toPx(), size.height + 8.dp.toPx()), Paint().apply {
                    alpha = 1f - (1f - content.pressedAlpha) * progress.value
                })
                drawContent()
                canvas.restore()
            }
        } else {
            if (content == null) drawCircle(Palette.text.copy(alpha = 0.1f * progress.value), radius = 22.dp.toPx() * scale.value)
            drawContent()
        }
    }
}
