package com.mockonomy.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.screens.setup.DiagnosticStep
import com.mockonomy.ui.screens.setup.SetupGuideViewModel
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The setup guide and connection diagnostics.
 *
 * Every line here was verified by going through it on a real device
 * (`docs/getting-started.md`); the error message is not a guess but the actual message
 * that appears. Users feel the difference.
 *
 * Diagnostics on top, the guide below: when the diagnostics say "the server side is ready",
 * the place the user needs to look is the steps right below.
 */
@Composable
fun SetupGuideScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupGuideViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                MockonomyIcon(
                    icon = MockonomyIcons.ArrowLeft,
                    contentDescription = "Back",
                    tint = MockonomyTheme.colors.onSurface,
                )
            }
            Text(
                text = "Connect your app",
                style = MockonomyTheme.typography.title,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(start = Spacing.s2),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s4),
        ) {
            // --- diagnostics ---
            SectionHeader(text = "Test connection", modifier = Modifier.padding(bottom = Spacing.s2))

            OutlinedButton(
                onClick = { viewModel.runDiagnostics(context.hasNotificationPermission()) },
                enabled = !report.running,
            ) {
                MockonomyIcon(
                    icon = MockonomyIcons.PlayFill,
                    contentDescription = null,
                    tint = MockonomyTheme.colors.onSurfaceVariant,
                    size = 12.dp,
                )
                Text(
                    text = if (report.running) "Checking…" else "Run checks",
                    style = MockonomyTheme.typography.label,
                    modifier = Modifier.padding(start = Spacing.s2),
                )
            }

            report.steps.forEach { StepRow(it) }

            if (report.steps.isNotEmpty()) {
                Text(
                    text = report.clientNotice,
                    style = MockonomyTheme.typography.caption,
                    color = MockonomyTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.s3),
                )
            }

            // --- rehber ---
            SectionHeader(
                text = "If your app can't connect",
                modifier = Modifier.padding(top = Spacing.s4, bottom = Spacing.s2),
            )

            Paragraph(
                "Android blocks cleartext HTTP by default since Android 9. Mockonomy serves over " +
                    "http://127.0.0.1, so an app built with the default configuration cannot reach it.",
            )

            Text(
                text = "The error you'll see",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(top = Spacing.s3, bottom = Spacing.s2),
            )
            CodeBlock(code = ERROR_MESSAGE, onCopy = { scope.launch { clipboard.copy(it) } })
            Paragraph(
                "Seeing this means the server is fine — your app's network policy is what blocks it. " +
                    "Quick check: if `adb shell curl` reaches the port but your app doesn't, this is it.",
            )

            Step(number = 1, title = "Add a network security config")
            Paragraph("Debug builds only — your release configuration stays untouched.")
            CodeBlock(
                code = NETWORK_SECURITY_CONFIG,
                onCopy = { scope.launch { clipboard.copy(it) } },
                label = "src/debug/res/xml/network_security_config.xml",
            )

            Step(number = 2, title = "Reference it from the manifest")
            CodeBlock(
                code = MANIFEST_LINE,
                onCopy = { scope.launch { clipboard.copy(it) } },
                label = "AndroidManifest.xml",
            )

            Step(number = 3, title = "Rebuild and reinstall your app")
            Paragraph(
                "The configuration is packaged at build time — restarting the app is not enough.",
            )

            Text(
                text = "Don't use android:usesCleartextTraffic=\"true\". It is one line, but it opens " +
                    "all cleartext traffic. The config above opens loopback only, and keeps HTTPS " +
                    "enforcement for everything else.",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.s4),
            )

        }
    }
}

@Composable
private fun StepRow(step: DiagnosticStep) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        when (step.outcome) {
            DiagnosticStep.Outcome.Passed ->
                OutcomeIcon(MockonomyIcons.CheckCircleFill, MockonomyTheme.colors.success)
            DiagnosticStep.Outcome.Failed ->
                OutcomeIcon(MockonomyIcons.XCircleFill, MockonomyTheme.colors.error)
            DiagnosticStep.Outcome.Warned ->
                OutcomeIcon(MockonomyIcons.Warning, MockonomyTheme.colors.warning)
            // A step that has not run yet: an empty ring. We do not show a spinner with no result.
            DiagnosticStep.Outcome.Pending ->
                OutcomeIcon(MockonomyIcons.CircleDashed, MockonomyTheme.colors.onSurfaceFaint)
            else -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Column {
            Text(
                text = step.title,
                style = MockonomyTheme.typography.body,
                color = MockonomyTheme.colors.onSurface,
            )
            Text(
                text = step.detail,
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeIcon(@androidx.annotation.DrawableRes icon: Int, tint: Color) {
    MockonomyIcon(icon = icon, contentDescription = null, tint = tint, size = 16.dp)
}

@Composable
private fun Step(number: Int, title: String) {
    Text(
        text = "$number. $title",
        style = MockonomyTheme.typography.label,
        color = MockonomyTheme.colors.onSurface,
        modifier = Modifier.padding(top = Spacing.s4, bottom = Spacing.s2),
    )
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MockonomyTheme.typography.body,
        color = MockonomyTheme.colors.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.s2),
    )
}

/**
 * A title bar + a sunken body. The copy button sits in the title: this block exists only to
 * carry the text over, so nothing competes with it.
 *
 * The body scrolls horizontally: XML lines are long, and wrapping would make the text to
 * copy look malformed.
 */
@Composable
private fun CodeBlock(code: String, onCopy: (String) -> Unit, label: String? = null) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MockonomyTheme.colors.outlineSubtle, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MockonomyTheme.colors.surface)
                .padding(start = 10.dp, end = Spacing.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label != null) {
                MockonomyIcon(
                    icon = MockonomyIcons.FileCode,
                    contentDescription = null,
                    tint = MockonomyTheme.colors.onSurfaceVariant,
                    size = 13.dp,
                )
                Text(
                    text = label,
                    style = MockonomyTheme.typography.monoMeta,
                    color = MockonomyTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Spacing.s2),
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { onCopy(code) }) {
                Text(
                    text = "COPY",
                    style = MockonomyTheme.typography.monoMeta,
                    color = MockonomyTheme.colors.primaryText,
                )
            }
        }
        HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
        Text(
            text = code,
            style = MockonomyTheme.typography.monoBody,
            color = MockonomyTheme.colors.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .background(MockonomyTheme.colors.surfaceSunken)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 11.dp),
        )
    }
}

private fun android.content.Context.hasNotificationPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        // Before API 33 there is no runtime permission; notifications are on by default.
        true
    }

private suspend fun androidx.compose.ui.platform.Clipboard.copy(text: String) {
    setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Mockonomy", text)))
}

private val ERROR_MESSAGE = """
    java.net.UnknownServiceException
    CLEARTEXT communication to 127.0.0.1 not permitted
    by network security policy
""".trimIndent()

private val NETWORK_SECURITY_CONFIG = """
    <?xml version="1.0" encoding="utf-8"?>
    <network-security-config>
        <domain-config cleartextTrafficPermitted="true">
            <domain includeSubdomains="false">127.0.0.1</domain>
            <domain includeSubdomains="false">localhost</domain>
        </domain-config>
    </network-security-config>
""".trimIndent()

private val MANIFEST_LINE = """
    <application
        android:networkSecurityConfig="@xml/network_security_config"
        ... >
""".trimIndent()
