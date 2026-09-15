package com.mockonomy.testclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * A client that proves Mockonomy's cleartext story.
 *
 * The error message is printed on screen **as is** — this is the text users will run into,
 * and the setup guide recognizes and explains exactly that text. So the error is not
 * wrapped or prettified here.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { ClientScreen() }
            }
        }
    }
}

private val client = OkHttpClient.Builder()
    .callTimeout(10, TimeUnit.SECONDS)
    .build()

@Composable
private fun ClientScreen() {
    var url by remember { mutableStateOf("http://127.0.0.1:8080/ping") }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Mock Test Client", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                result = "…"
                scope.launch {
                    result = withContext(Dispatchers.IO) { request(url) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send request")
        }

        Text(
            text = result,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun request(url: String): String = try {
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        "HTTP ${response.code}\n\n${response.body?.string().orEmpty()}"
    }
} catch (error: Exception) {
    // Deliberately raw: this is exactly the error the user will see.
    "${error::class.java.name}\n\n${error.message}"
}
