package app.what.talkbacktest

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import app.what.talkbacktest.ui.theme.TalkBackTestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TalkBackTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(5) {
            Text(
                text = "Инч ка чка, брат, цавт танем!",
                fontSize = 40.sp,
                textAlign = TextAlign.Center,
                modifier = modifier
                    .padding(8.dp)
            )

            Text(
                text = "TalkBackCloneService is an innovative accessibility tool designed to enhance user interaction with Android devices. By leveraging the Accessibility API, it provides real-time spoken feedback and gesture recognition, making it easier for visually impaired users to navigate apps and interfaces seamlessly",
                fontSize = 40.sp,
                textAlign = TextAlign.Center,
                modifier = modifier
                    .padding(8.dp)
            )
        }
    }
}