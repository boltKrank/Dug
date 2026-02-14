package com.boltkrank.Dug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DugApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DugApp(vm: DigViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("dig-mini (UDP)") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = state.inputName,
                onValueChange = vm::setInputName,
                label = { Text("Hostname") },
                placeholder = { Text("www.google.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.serverIp,
                    onValueChange = vm::setServerIp,
                    label = { Text("DNS server") },
                    placeholder = { Text("8.8.8.8") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.port.toString(),
                    onValueChange = { vm.setPort(it) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = vm::runHelloWorldQuery,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isBusy) "Querying..." else "Query (A + NS)")
            }

            Spacer(Modifier.height(12.dp))

            val scroll = rememberScrollState()
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Text(
                    text = state.output,
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(scroll),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
