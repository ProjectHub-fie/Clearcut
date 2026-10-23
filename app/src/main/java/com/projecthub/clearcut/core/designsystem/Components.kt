package com.projecthub.clearcut.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.OperationState

// ---------- Surfaces ----------

@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSystemInDarkTheme()) ClearCutColors.CardDark else Color.White,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSystemInDarkTheme()) ClearCutColors.OutlineDark else ClearCutColors.OutlineLight,
        ),
        content = { Column(modifier = Modifier.fillMaxWidth(), content = content) },
    )
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
            }
        }
        trailing?.invoke(this)
    }
}

// ---------- Animated scan loader ----------

@Composable
fun ScanProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ClearCutColors.Accent,
    strokeWidth: Dp = 3.dp,
) {
    val transition = rememberInfiniteTransition(label = "scan")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "phase",
    )
    Box(modifier = modifier.semantics { contentDescription = "Scan indicator" }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(52.dp),
            color = color.copy(alpha = 0.22f),
            strokeWidth = strokeWidth,
            trackColor = Color.Transparent,
        )
        CircularProgressIndicator(
            modifier = Modifier.size(52.dp),
            color = color,
            strokeWidth = strokeWidth,
            strokeCap = StrokeCap.Round,
            progress = { 0.25f + 0.75f * phase },
        )
        Box(
            Modifier.size(14.dp).clip(CircleShape).background(
                lerp(color.copy(alpha = 0.15f), color, phase)
            ),
        )
    }
}

@Composable
fun WaveformLoader(
    modifier: Modifier = Modifier,
    color: Color = ClearCutColors.Accent,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val bars = listOf(0f, 0.4f, 0.8f, 0.3f, 0.9f, 0.5f, 0.2f, 0.7f, 0.35f, 0.85f, 0.6f, 0.15f)
    Row(modifier = modifier.semantics { contentDescription = "Audio wave animation" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        bars.forEachIndexed { i, base ->
            val delay = i * 70
            val scale by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900, delayMillis = delay), RepeatMode.Reverse),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(4.dp)
                    .height((12 + 20 * base * (0.4f + 0.6f * scale)).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(lerp(color.copy(alpha = 0.14f), color, scale)),
            )
        }
    }
}

@Composable
fun IndeterminateBar(modifier: Modifier = Modifier, color: Color = ClearCutColors.Accent) {
    val transition = rememberInfiniteTransition(label = "indet")
    val shift by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "shift")
    val width = 0.35f
    val startFraction = (shift * (1f + width)) - width
    Box(modifier = modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.16f))) {
        Box(
            Modifier.fillMaxWidth(1f).height(4.dp)
                .padding(start = (startFraction * 200).dp, end = ((1f - startFraction - width) * 200).dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

/**
 * A reusable processing view that renders every [OperationState] consistently.
 */
@Composable
fun ProcessingStateView(
    state: OperationState<*>,
    modifier: Modifier = Modifier,
    idleContent: (@Composable () -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onDismissError: (() -> Unit)? = null,
) {
    when (state) {
        OperationState.Idle -> idleContent?.invoke()
        is OperationState.Validating -> ProcessingRow(state.detail, indeterminate = true)
        is OperationState.InProgress -> {
            Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    ScanProgressIndicator()
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(state.phase, style = MaterialTheme.typography.titleSmall)
                        if (state.progress != null) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.width(190.dp),
                                color = ClearCutColors.Accent,
                                trackColor = ClearCutColors.Accent.copy(alpha = 0.15f),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${(state.progress * 100).toInt()}% ·  ${formatElapsed(state.elapsedMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClearCutColors.OnInkDim,
                            )
                        } else {
                            Spacer(Modifier.height(6.dp))
                            IndeterminateBar(Modifier.width(190.dp))
                            Spacer(Modifier.height(4.dp))
                            Text("${formatElapsed(state.elapsedMs)}", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.OnInkDim)
                        }
                    }
                }
                if (onCancel != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
        is OperationState.Waiting -> ProcessingRow("${state.detail} · ${formatElapsed(state.elapsedMs)}", indeterminate = true)
        is OperationState.Completed<*> -> Unit
        is OperationState.CompletedWithWarnings<*> -> Unit
        is OperationState.Cancelled -> ProcessingRow("Cancelled · ${state.phaseAtCancel}", infoColor = ClearCutColors.Warning)
        is OperationState.Failed -> Column(
            modifier = modifier.fillMaxWidth().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Something went wrong", style = MaterialTheme.typography.titleSmall, color = ClearCutColors.Error)
            Spacer(Modifier.height(6.dp))
            Text(
                state.error.userMessage(),
                style = MaterialTheme.typography.bodySmall,
                color = ClearCutColors.OnInkDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                if (state.error.recoverable && onRetry != null) {
                    Button(onClick = onRetry) { Text("Retry") }
                }
                if (onDismissError != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
            if (state.error.code == "PERMISSION" && onRetry != null) {
                Spacer(Modifier.height(10.dp))
                Text("You can retry after granting access in system settings.", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.OnInkFaint)
            }
        }
    }
}

fun ClearCutError.userMessage(): String = when {
    message.isNotBlank() -> message
    else -> "Operation failed (${code.lowercase()})."
}

@Composable
private fun ProcessingRow(text: String, infoColor: Color = ClearCutColors.OnInkDim, indeterminate: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (indeterminate) {
            WaveformLoader()
            Spacer(Modifier.width(12.dp))
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(infoColor))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = infoColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = ClearCutColors.Accent) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (isSystemInDarkTheme()) ClearCutColors.InkRaised else Color(0xFFF2F2EE),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = ClearCutColors.OnInkDim)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmptyState(
    icon: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))
                .background(if (isSystemInDarkTheme()) ClearCutColors.InkRaised else Color(0xFFEDEDE8)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(icon, style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = ClearCutColors.OnInkDim,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

fun formatElapsed(ms: Long): String {
    if (ms <= 0) return ""
    val totalS = ms / 1000
    val m = totalS / 60
    val s = totalS % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}