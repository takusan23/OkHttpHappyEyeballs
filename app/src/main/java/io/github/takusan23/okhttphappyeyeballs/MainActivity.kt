package io.github.takusan23.okhttphappyeyeballs

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.contentValuesOf
import io.github.takusan23.okhttphappyeyeballs.ui.theme.OkHttpHappyEyeballsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OkHttpHappyEyeballsTheme {
                MainScreen()
            }
        }
    }
}

/** IPv4 アドレスを優先する OkHttp DNS 実装。IPv6 アドレスを後ろに追いやっている */
class PriorityIpv4Dns() : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return Dns.SYSTEM.lookup(hostname).sortedBy { Inet6Address::class.java.isInstance(it) }
    }
}

/** OkHttp 非同期モードのコールバックを Kotlin Coroutines に対応させたもの */
private suspend fun Call.suspendExecute() = suspendCancellableCoroutine { cancellableContinuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cancellableContinuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cancellableContinuation.resume(response)
        }
    })
    cancellableContinuation.invokeOnCancellation { this.cancel() }
}

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val errorDialogText = remember { mutableStateOf<String?>(null) }
    val downloadUrl = remember { mutableStateOf("https://takusan.negitoro.dev/icon.png") }
    val isUseHappyEyeballs = remember { mutableStateOf(false) }
    val isPriorityIpv4 = remember { mutableStateOf(false) }

    fun startDownload() {
        scope.launch(Dispatchers.IO) {
            val okHttpClient = OkHttpClient.Builder().apply {
                // Happy Eyeballs を有効
                fastFallback(isUseHappyEyeballs.value)
                // IPv4 を優先
                if (isPriorityIpv4.value) {
                    dns(PriorityIpv4Dns())
                }
            }.build()
            val request = Request.Builder().apply {
                url(downloadUrl.value)
                get()
            }.build()
            try {
                // 指定時間以内に終わらなければキャンセルする suspendExecute()
                withTimeout(10_000) {
                    okHttpClient.newCall(request).suspendExecute()
                }.use { response ->
                    // エラーは return
                    if (!response.isSuccessful) {
                        errorDialogText.value = response.code.toString()
                        return@launch
                    }
                    // Downloads/OkHttpHappyEyeballs フォルダに保存
                    val fileContentValues = contentValuesOf(
                        MediaStore.Downloads.DISPLAY_NAME to System.currentTimeMillis().toString(),
                        MediaStore.Downloads.RELATIVE_PATH to "${Environment.DIRECTORY_DOWNLOADS}/OkHttpHappyEyeballs"
                    )
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, fileContentValues)!!
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        response.body.byteStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "ダウンロードが完了しました", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                errorDialogText.value = e.toString()
                // キャンセル系は再スロー
                if (e is CancellationException) {
                    throw e
                }
            }
        }
    }

    if (errorDialogText.value != null) {
        AlertDialog(
            onDismissRequest = { errorDialogText.value = null },
            title = { Text(text = "OkHttp エラー") },
            text = { Text(text = errorDialogText.value!!) },
            confirmButton = {
                Button(onClick = { errorDialogText.value = null }) {
                    Text(text = "閉じる")
                }
            }
        )
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(10.dp)
        ) {

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = downloadUrl.value,
                onValueChange = { downloadUrl.value = it },
                label = { Text(text = "画像の URL") }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "HappyEyeballs を有効")
                Switch(
                    checked = isUseHappyEyeballs.value,
                    onCheckedChange = { isUseHappyEyeballs.value = it }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "IPv4 を優先する")
                Switch(
                    checked = isPriorityIpv4.value,
                    onCheckedChange = { isPriorityIpv4.value = it }
                )
            }
            Button(onClick = { startDownload() }) {
                Text(text = "ダウンロード開始")
            }
        }
    }
}