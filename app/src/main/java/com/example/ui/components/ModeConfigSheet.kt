package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.config.TestMode
import com.example.core.config.TesterSessionConfig
import com.example.core.security.LogSanitizer
import com.example.core.security.UrlValidationResult
import com.example.core.security.UrlValidator
import com.example.ui.theme.HairlineBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeConfigBottomSheet(
    config: TesterSessionConfig,
    onDismiss: () -> Unit,
    onSaveConfig: (
        mode: TestMode,
        baseUrl: String,
        healthPath: String,
        searchPath: String,
        handoffPath: String,
        reportPath: String,
        reportVerified: Boolean,
        bearerToken: String
    ) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedMode by remember(config.mode) { mutableStateOf(config.mode) }
    var baseUrl by remember(config.vpsBaseUrl) { mutableStateOf(config.vpsBaseUrl) }
    var healthPath by remember(config.healthEndpointPath) { mutableStateOf(config.healthEndpointPath) }
    var searchPath by remember(config.searchEndpointPath) { mutableStateOf(config.searchEndpointPath) }
    var handoffPath by remember(config.handoffEndpointPath) { mutableStateOf(config.handoffEndpointPath) }
    var reportPath by remember(config.diagnosticReportPath) { mutableStateOf(config.diagnosticReportPath) }
    var reportVerified by remember(config.diagnosticReportContractVerified) {
        mutableStateOf(config.diagnosticReportContractVerified)
    }
    var bearerToken by remember(config.bearerToken) { mutableStateOf(config.bearerToken) }
    var showToken by remember { mutableStateOf(false) }

    val urlValidation = remember(selectedMode, baseUrl) {
        if (selectedMode == TestMode.LIVE && baseUrl.isNotBlank()) {
            UrlValidator.validateVpsBaseUrl(baseUrl)
        } else {
            null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Mode Pengujian & Konfigurasi API",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Demo Mode memakai data simulasi lokal tanpa akses VPS. Live Mode menghubungi API VPS HTTPS yang Anda konfigurasi. Token hanya disimpan di RAM selama sesi aktif.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = selectedMode == TestMode.DEMO,
                    onClick = { selectedMode = TestMode.DEMO },
                    label = { Text("Demo Mode") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chip_demo_mode")
                )
                FilterChip(
                    selected = selectedMode == TestMode.LIVE,
                    onClick = { selectedMode = TestMode.LIVE },
                    label = { Text("Live Mode") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chip_live_mode")
                )
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("VPS API Base URL (HTTPS)") },
                placeholder = { Text("https://api-staging.domain-anda.example") },
                singleLine = true,
                supportingText = {
                    when (urlValidation) {
                        is UrlValidationResult.Invalid -> {
                            Text(
                                text = urlValidation.reason,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            Text("Jangan gunakan localhost/127.0.0.1 atau HTTP publik.")
                        }
                    }
                },
                isError = urlValidation is UrlValidationResult.Invalid,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_vps_base_url")
            )

            OutlinedTextField(
                value = healthPath,
                onValueChange = { healthPath = it },
                label = { Text("Endpoint Health Check") },
                placeholder = { Text("/poc/v1/health") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_health_endpoint")
            )

            OutlinedTextField(
                value = searchPath,
                onValueChange = { searchPath = it },
                label = { Text("Endpoint Search") },
                placeholder = { Text("/poc/v1/search") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_search_endpoint")
            )

            OutlinedTextField(
                value = handoffPath,
                onValueChange = { handoffPath = it },
                label = { Text("Endpoint Handoff") },
                placeholder = { Text("/poc/v1/handoff") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_handoff_endpoint")
            )

            OutlinedTextField(
                value = bearerToken,
                onValueChange = { bearerToken = it },
                label = { Text("Session Bearer Token (In-Memory)") },
                placeholder = { Text("Opsional, hanya disimpan di RAM sesi") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            imageVector = if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showToken) "Sembunyikan token" else "Tampilkan token"
                        )
                    }
                },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                supportingText = {
                    Text("Preview saat ini: ${LogSanitizer.maskTokenPreview(bearerToken)}")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_bearer_token")
            )

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, HairlineBorder)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Kontrak Endpoint Report Terverifikasi",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Laporan diagnostik ke VPS hanya aktif bila kontrak endpoint sudah diverifikasi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = reportVerified,
                            onCheckedChange = { reportVerified = it }
                        )
                    }
                    OutlinedTextField(
                        value = reportPath,
                        onValueChange = { reportPath = it },
                        label = { Text("Endpoint Report Diagnostik") },
                        placeholder = { Text("/poc/v1/report") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_report_endpoint")
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { bearerToken = "" },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Hapus Token")
                }
                Button(
                    onClick = {
                        onSaveConfig(
                            selectedMode,
                            baseUrl.trim(),
                            healthPath.trim().ifBlank { "/poc/v1/health" },
                            searchPath.trim().ifBlank { "/poc/v1/search" },
                            handoffPath.trim().ifBlank { "/poc/v1/handoff" },
                            reportPath.trim().ifBlank { "/poc/v1/report" },
                            reportVerified,
                            bearerToken.trim()
                        )
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_config_button")
                ) {
                    Text("Simpan Sesi")
                }
            }
        }
    }
}
