package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MapHubScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 4.dp),
    ) {
        Text("Radar ČHMÚ", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Srážky, nowcast, blesky a družicová oblačnost",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = Color(0xFF0B1117),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        ) {
            ChmiWebScreen(RADAR_APP_URL)
        }
        Text(
            "Měření po 5 minutách · nowcast +60 min · data ČHMÚ",
            color = Color.White.copy(alpha = 0.46f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 5.dp, top = 7.dp),
        )
    }
}
