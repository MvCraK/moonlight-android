package com.limelight.ss

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.ComponentDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.preferences.handleGamepadConfirm
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.AppDialogStyler
import com.limelight.utils.appAccentColor
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class SsPanel private constructor(private val activity: Activity) {
    companion object {
        private const val IMPORT = 24032
        private const val IMPORT_TAG = "vplus-ss-import"
        private val panels = java.util.WeakHashMap<Activity, SsPanel>()

        fun of(activity: Activity): SsPanel = panels.getOrPut(activity) { SsPanel(activity) }
        fun release(activity: Activity) { panels.remove(activity)?.dismiss() }
    }

    /**
     * Receives the document picker result without touching the host activity's
     * onActivityResult: framework fragments get their own results delivered.
     */
    class ImportFragment : android.app.Fragment() {
        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == IMPORT && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri -> activity?.let { of(it).import(uri) } }
            }
        }
    }

    /** One consistent read of SsRoutes per recomposition. */
    private class Snap(
        val enabled: Boolean = SsRoutes.enabled,
        val configured: Boolean = SsRoutes.configured(),
        val status: SsRoutes.Status = SsRoutes.status(),
        val nodes: List<Pair<String, Int>> = SsRoutes.nodes(),
        val probes: List<SsRoutes.Probe>? = SsRoutes.probes,
        val probing: Boolean = SsRoutes.probing,
        val updated: Long = SsRoutes.updated(),
    )

    private var dialog: ComponentDialog? = null
    private var busy by mutableStateOf<Int?>(null)      // progress message while working
    private var failed by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)        // bumps when SsRoutes changes

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialog?.isShowing == true) return
        failed = false
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { Panel() }
        }
        dialog = ComponentDialog(activity, R.style.AppDialogStyle).apply {
            setContentView(view)
            show()
            AppDialogStyler.applyCustomContent(this, activity)
        }
        SsRoutes.probeAsync()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun import(uri: Uri) {
        perform(R.string.ss_busy_import) {
            val config = activity.contentResolver.openInputStream(uri)?.use { SsRoutes.readText(it) }
                ?: error("无法打开文件")
            SsRoutes.refresh(config)
        }
        show()
    }

    private fun pickConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        val fm = activity.fragmentManager
        @Suppress("DEPRECATION")
        val fragment = fm.findFragmentByTag(IMPORT_TAG) as? ImportFragment
            ?: ImportFragment().also {
                fm.beginTransaction().add(it, IMPORT_TAG).commit()
                fm.executePendingTransactions()
            }
        @Suppress("DEPRECATION")
        fragment.startActivityForResult(intent, IMPORT)
    }

    private fun perform(progress: Int, task: () -> Unit) {
        if (busy != null) return
        busy = progress
        failed = false
        SsRoutes.worker.execute {
            val result = runCatching(task)
            activity.runOnUiThread {
                busy = null
                // Network/parser exceptions can contain the subscription URL or raw YAML.
                failed = result.isFailure
                revision++
            }
        }
    }

    @Composable
    private fun Panel() {
        DisposableEffect(Unit) {
            val listener: () -> Unit = { revision += 1 }
            SsRoutes.addListener(listener)
            onDispose { SsRoutes.removeListener(listener) }
        }
        val snap = remember(revision) { Snap() }
        val enabled = snap.enabled
        val configured = snap.configured
        val status = snap.status
        var received by remember { mutableStateOf(SsNative.ssReceivedBytes()) }
        LaunchedEffect(enabled) {
            while (enabled) {
                received = SsNative.ssReceivedBytes()
                delay(1000)
            }
        }

        val accent = appAccentColor()
        val panel = colorResource(R.color.easytier_panel_background)
        val section = colorResource(R.color.easytier_section_background)
        val input = colorResource(R.color.easytier_input_background)
        val textPrimary = colorResource(R.color.easytier_text_primary)
        val textSecondary = colorResource(R.color.easytier_text_secondary)
        val primary = if (panel.luminance() > 0.5f) lerp(accent, Color.Black, 0.3f) else accent
        val statusColor = statusColor(status)

        MaterialTheme(colorScheme = darkColorScheme(primary = primary, surface = panel, onSurface = textPrimary)) {
            Box(Modifier.padding(10.dp)) {
                Surface(
                    modifier = Modifier.widthIn(max = 520.dp),
                    shape = AppShapes.medium,
                    color = panel,
                    shadowElevation = 12.dp
                ) {
                    Column(Modifier.padding(18.dp)) {
                        // Header: state badge, title + state line, close.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(status)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ss_panel_title), color = textPrimary,
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(if (!configured) R.string.ss_state_unconfigured else stateLabel(status)),
                                    color = if (status == SsRoutes.Status.OFF) textSecondary else statusColor,
                                    fontSize = 12.5.sp, fontWeight = FontWeight.Medium
                                )
                            }
                            CloseButton(input, textPrimary) { dismiss() }
                        }
                        Spacer(Modifier.height(16.dp))

                        Column(
                            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!configured) {
                                Card(section) {
                                    Text(stringResource(R.string.ss_empty_title), color = textPrimary,
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp))
                                    Text(stringResource(R.string.ss_empty_body), color = textSecondary, fontSize = 13.sp)
                                }
                            } else {
                                Card(section, stringResource(R.string.ss_section_nodes), textSecondary) {
                                    val names = listOf(R.string.ss_node_home_tcp, R.string.ss_node_home_udp, R.string.ss_node_company)
                                    val probes = snap.probes
                                    snap.nodes.forEachIndexed { i, (host, port) ->
                                        if (i > 0) Spacer(Modifier.height(10.dp))
                                        NodeRow(
                                            name = stringResource(names.getOrElse(i) { R.string.ss_node_company }),
                                            host = "$host:$port",
                                            probe = probes?.getOrNull(i),
                                            enabled = enabled,
                                            probing = snap.probing,
                                            textPrimary = textPrimary,
                                            textSecondary = textSecondary
                                        )
                                    }
                                }

                                Card(section, stringResource(R.string.ss_section_routes), textSecondary) {
                                    RouteRow("10.0.0.0/24", stringResource(R.string.ss_route_home),
                                        stringResource(R.string.ss_route_home_detail), textPrimary, textSecondary)
                                    Spacer(Modifier.height(10.dp))
                                    RouteRow("10.1.1.0/24", stringResource(R.string.ss_route_company),
                                        stringResource(R.string.ss_route_company_detail), textPrimary, textSecondary)
                                    Spacer(Modifier.height(10.dp))
                                    Text(stringResource(R.string.ss_direct_note), color = textSecondary, fontSize = 11.5.sp)
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Stat(Modifier.weight(1f), section, stringResource(R.string.ss_stat_received),
                                        Formatter.formatShortFileSize(LocalContext.current, received), textPrimary, textSecondary)
                                    val updated = snap.updated
                                    Stat(Modifier.weight(1f), section, stringResource(R.string.ss_stat_updated),
                                        if (updated > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                            .format(Date(updated)) else "—",
                                        textPrimary, textSecondary)
                                }
                            }
                        }

                        val note = busy?.let { stringResource(it) }
                            ?: if (failed) stringResource(R.string.ss_error_generic) else null
                        if (note != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(note, fontSize = 12.5.sp,
                                color = if (busy != null) textSecondary else colorResource(R.color.easytier_warning))
                        }

                        // Prominent connect / disconnect action at the bottom, like master's panel.
                        Spacer(Modifier.height(14.dp))
                        val warning = colorResource(R.color.easytier_warning)
                        val onAccent = colorResource(R.color.app_dialog_text_primary)
                        when {
                            !configured -> PrimaryButton(stringResource(R.string.ss_action_import), accent, onAccent,
                                null, busy == null, ::pickConfig)
                            enabled -> PrimaryButton(stringResource(R.string.ss_action_disconnect),
                                warning.copy(alpha = 0.12f), warning, BorderStroke(1.dp, warning.copy(alpha = 0.6f)),
                                busy == null) { perform(R.string.ss_busy_toggle) { SsRoutes.toggle() } }
                            else -> PrimaryButton(stringResource(R.string.ss_action_connect), accent, onAccent,
                                null, busy == null) { perform(R.string.ss_busy_toggle) { SsRoutes.toggle() } }
                        }
                        if (configured) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton(stringResource(R.string.ss_action_import), input, textPrimary,
                                    busy == null, Modifier.weight(1f), ::pickConfig)
                                ActionButton(stringResource(R.string.ss_action_refresh), input, textPrimary,
                                    busy == null, Modifier.weight(1f)) {
                                    perform(R.string.ss_busy_refresh) { SsRoutes.refresh() }
                                }
                                ActionButton(stringResource(R.string.ss_action_probe), input, textPrimary,
                                    enabled && !snap.probing, Modifier.weight(1f)) { SsRoutes.probeAsync(force = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun statusColor(status: SsRoutes.Status) = when (status) {
        SsRoutes.Status.OFF -> colorResource(R.color.ss_status_off)
        SsRoutes.Status.CHECKING, SsRoutes.Status.ONLINE -> colorResource(R.color.ss_status_online)
        SsRoutes.Status.DEGRADED -> colorResource(R.color.ss_status_warning)
    }

    private fun stateLabel(status: SsRoutes.Status) = when (status) {
        SsRoutes.Status.OFF -> R.string.ss_state_off
        SsRoutes.Status.CHECKING -> R.string.ss_state_checking
        SsRoutes.Status.ONLINE -> R.string.ss_state_online
        SsRoutes.Status.DEGRADED -> R.string.ss_state_degraded
    }

    @Composable
    private fun StatusBadge(status: SsRoutes.Status) {
        val context = LocalContext.current
        val drawable = remember { SsStatusDrawable(context) }
        drawable.status = status
        Canvas(Modifier.size(44.dp)) {
            drawIntoCanvas {
                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawable.draw(it.nativeCanvas)
            }
        }
    }

    @Composable
    private fun Card(
        background: Color, title: String? = null, titleColor: Color = Color.Unspecified,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column {
            if (title != null) {
                Text(title, color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
            }
            Column(
                Modifier.fillMaxWidth().background(background, AppShapes.small).padding(horizontal = 14.dp, vertical = 12.dp),
                content = content
            )
        }
    }

    @Composable
    private fun NodeRow(
        name: String, host: String, probe: SsRoutes.Probe?, enabled: Boolean, probing: Boolean,
        textPrimary: Color, textSecondary: Color
    ) {
        val online = colorResource(R.color.ss_status_online)
        val warning = colorResource(R.color.ss_status_warning)
        val off = colorResource(R.color.ss_status_off)
        val (dot, label) = when {
            !enabled -> off to stringResource(R.string.ss_latency_idle)
            probe?.skipped == true -> off to stringResource(R.string.ss_latency_skipped)
            probe == null -> off to stringResource(if (probing) R.string.ss_latency_checking else R.string.ss_latency_idle)
            probe.latencyMs == null -> warning to stringResource(R.string.ss_latency_unreachable)
            else -> online to stringResource(R.string.ss_latency_ms, probe.latencyMs.toInt())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(dot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(host, color = textSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = if (dot == off) textSecondary else dot, fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun RouteRow(cidr: String, target: String, detail: String, textPrimary: Color, textSecondary: Color) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cidr, color = textPrimary, fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(target, color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(detail, color = textSecondary, fontSize = 11.5.sp)
        }
    }

    @Composable
    private fun Stat(modifier: Modifier, background: Color, label: String, value: String, textPrimary: Color, textSecondary: Color) {
        Column(modifier.background(background, AppShapes.small).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, color = textSecondary, fontSize = 11.5.sp)
            Spacer(Modifier.height(2.dp))
            Text(value, color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun PrimaryButton(
        label: String, container: Color, content: Color, border: BorderStroke?, enabled: Boolean, onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp).handleGamepadConfirm { if (enabled) onClick() },
            shape = AppShapes.medium,
            border = border,
            colors = ButtonDefaults.buttonColors(
                containerColor = container, contentColor = content,
                disabledContainerColor = container.copy(alpha = container.alpha * 0.4f),
                disabledContentColor = content.copy(alpha = 0.5f)
            )
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun CloseButton(background: Color, tint: Color, onClick: () -> Unit) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(background)
                .clickable(onClick = onClick).handleGamepadConfirm(onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(12.dp)) {
                val w = 2.dp.toPx()
                drawLine(tint, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
                drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
            }
        }
    }

    @Composable
    private fun ActionButton(
        label: String, container: Color, content: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.handleGamepadConfirm { if (enabled) onClick() },
            shape = AppShapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = container, contentColor = content,
                disabledContainerColor = container.copy(alpha = 0.4f), disabledContentColor = content.copy(alpha = 0.5f)
            ),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
}
