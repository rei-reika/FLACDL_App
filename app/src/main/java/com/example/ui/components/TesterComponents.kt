package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.config.TestMode
import com.example.data.model.ConnectionStatus
import com.example.data.model.WorkerJobStatus
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnectedContainer
import com.example.ui.theme.StatusDisconnected
import com.example.ui.theme.StatusDisconnectedContainer
import com.example.ui.theme.StatusTesting
import com.example.ui.theme.StatusTestingContainer
import com.example.ui.theme.StatusUnknown
import com.example.ui.theme.StatusUnknownContainer

@Composable
fun TesterSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, HairlineBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun ConnectionStatusBadge(
    status: ConnectionStatus,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (dotColor, bgColor) = when (status) {
        ConnectionStatus.CONNECTED -> StatusConnected to StatusConnectedContainer
        ConnectionStatus.TESTING -> StatusTesting to StatusTestingContainer
        ConnectionStatus.DISCONNECTED -> StatusDisconnected to StatusDisconnectedContainer
    }
    StatusPill(
        text = if (compact) status.compactLabel else status.label,
        dotColor = dotColor,
        containerColor = bgColor,
        modifier = modifier
    )
}

@Composable
fun WorkerStatusBadge(
    status: WorkerJobStatus,
    modifier: Modifier = Modifier
) {
    val (dotColor, bgColor) = when (status) {
        WorkerJobStatus.COMPLETED -> StatusConnected to StatusConnectedContainer
        WorkerJobStatus.METADATA -> StatusTesting to StatusTestingContainer
        WorkerJobStatus.FAILED -> StatusDisconnected to StatusDisconnectedContainer
        WorkerJobStatus.UNKNOWN -> StatusUnknown to StatusUnknownContainer
    }
    StatusPill(
        text = status.wireValue,
        dotColor = dotColor,
        containerColor = bgColor,
        modifier = modifier
    )
}

@Composable
fun StatusPill(
    text: String,
    dotColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun ModeChipButton(
    mode: TestMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLive = mode == TestMode.LIVE
    val accent = if (isLive) StatusConnected else MaterialTheme.colorScheme.primary
    val bg = if (isLive) StatusConnectedContainer else MaterialTheme.colorScheme.primaryContainer

    Surface(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag("mode_config_button"),
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = "Pengaturan Mode & API",
                tint = accent,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
fun TrackArtwork(
    coverUrl: String?,
    contentDescription: String,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (coverUrl.isNullOrBlank()) {
            Image(
                painter = painterResource(id = R.drawable.img_cover_placeholder),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                placeholder = painterResource(id = R.drawable.img_cover_placeholder),
                error = painterResource(id = R.drawable.img_cover_placeholder),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun KeyValueTelemetryRow(
    label: String,
    value: String,
    monospacedValue: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = if (monospacedValue) {
                MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily)
            } else {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
fun EmptyStatePanel(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    TesterSectionCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon()
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
