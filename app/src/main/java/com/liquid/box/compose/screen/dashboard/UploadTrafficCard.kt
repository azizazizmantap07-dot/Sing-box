package com.liquid.box.compose.screen.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liquid.box.R
import com.liquid.box.compose.LineChart
import com.liquid.box.compose.theme.GlassCard
import com.liquid.box.compose.theme.GlassGlowBlue
import com.liquid.box.compose.theme.OnGlassPrimary
import com.liquid.box.compose.theme.OnGlassSecondary
import com.liquid.box.compose.theme.LogBlue

@Composable
fun UploadTrafficCard(
    uplink: String,
    uplinkTotal: String,
    uplinkHistory: List<Float>,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        elevated = true,
        glowColor = GlassGlowBlue,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = LogBlue,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.upload),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnGlassPrimary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = uplink,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = OnGlassPrimary,
            )

            Text(
                text = uplinkTotal,
                style = MaterialTheme.typography.bodyMedium,
                color = OnGlassSecondary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            LineChart(
                data = uplinkHistory,
                lineColor = LogBlue,
                animate = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
