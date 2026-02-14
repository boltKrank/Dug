package com.boltkrank.dug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

data class DugUiState(
    val inputName: String = "www.google.com",
    val serverIp: String = "8.8.8.8",
    val port: Int = 53,
    val isBusy: Boolean = false,
    val output: String = ""
)

class DugViewModel : ViewModel() {
    private val _state = MutableStateFlow(DugUiState())
    val state: StateFlow<DugUiState> = _state

    fun setInputName(v: String) = _state.update { it.copy(inputName = v.trim()) }
    fun setServerIp(v: String) = _state.update { it.copy(serverIp = v.trim()) }

    fun setPort(v: String) {
        val n = v.toIntOrNull()
        if (n != null && n in 1..65535) _state.update { it.copy(port = n) }
    }

    fun runHelloWorldQuery() {
        _state.update { it.copy(isBusy = true, output = "") }

        viewModelScope.launch(Dispatchers.IO) {
            val name = state.value.inputName.ifBlank { "www.google.com" }.trimEnd('.')
            val server = state.value.serverIp.ifBlank { "8.8.8.8" }
            val port = state.value.port

            val zoneGuess = guessZoneForNsLookup(name)

            val sb = StringBuilder()
            sb.appendLine("Input: $name")
            sb.appendLine("NS zone guess: $zoneGuess")
            sb.appendLine("Server: $server:$port")
            sb.appendLine()

            val client = DnsClientUdp(server, port)

            // Query A for the hostname
            val (aText, aMs) = doQuery(client, name, DnsType.A)
            sb.appendLine("=== Query: $name A  (time ${aMs}ms) ===")
            sb.appendLine(aText)
            sb.appendLine()

            // Query AAAA too (optional but useful)
            val (aaaaText, aaaaMs) = doQuery(client, name, DnsType.AAAA)
            sb.appendLine("=== Query: $name AAAA  (time ${aaaaMs}ms) ===")
            sb.appendLine(aaaaText)
            sb.appendLine()

            // Query NS for guessed zone
            val (nsText, nsMs) = doQuery(client, zoneGuess, DnsType.NS)
            sb.appendLine("=== Query: $zoneGuess NS  (time ${nsMs}ms) ===")
            sb.appendLine(nsText)
            sb.appendLine()

            _state.update { it.copy(isBusy = false, output = sb.toString()) }
        }
    }

    private fun doQuery(client: DnsClientUdp, qname: String, qtype: DnsType): Pair<String, Long> {
        var out = ""
        val ms = measureTimeMillis {
            try {
                val respBytes = client.query(qname, qtype)
                val msg = DnsCodec.decodeMessage(respBytes)
                out = DugFormatter.format(msg)
            } catch (e: Exception) {
                out = "ERROR: ${e.message}"
            }
        }
        return out to ms
    }

    // Very naive: "www.google.com" -> "google.com"
    private fun guessZoneForNsLookup(name: String): String {
        val labels = name.split('.').filter { it.isNotBlank() }
        if (labels.size <= 2) return name
        return labels.takeLast(2).joinToString(".")
    }
}
