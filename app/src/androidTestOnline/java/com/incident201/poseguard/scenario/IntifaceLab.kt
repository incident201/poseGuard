package com.incident201.poseguard.scenario

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.net.HttpURLConnection
import java.net.URL

internal object IntifaceLab {
    const val url = "ws://127.0.0.1:12345"
    private const val control = "http://127.0.0.1:8787"

    fun requireEnabled() {
        assumeTrue("Run with the local Intiface lab", InstrumentationRegistry.getArguments().getString("poseguardIntiface") == "true")
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                "com.incident201.poseguard", Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        check(JSONObject(request("/health")).getString("lab") == "poseguard-intiface")
    }

    fun reset() { request("/reset", "{}") }
    fun fault(mode: String) { request("/fault", JSONObject().put("mode", mode).toString()) }
    fun device(device: String, connected: Boolean) {
        request("/device", JSONObject().put("device", device).put("connected", connected).toString())
    }
    fun awaitFaultApplied(mode: String) = awaitCondition("in-flight $mode command") {
        val events = JSONArray(request("/events"))
        (0 until events.length()).map { events.getJSONObject(it) }.any {
            it.optString("kind") == "fault_applied" && it.optString("mode") == mode
        }
    }

    data class MotorCommand(val device: String, val motor: Int, val value: Int)
    fun commands(): List<MotorCommand> {
        val events = JSONArray(request("/events"))
        return (0 until events.length()).map { events.getJSONObject(it) }
            .filter { it.optString("kind") == "device_command" }
            .map { MotorCommand(it.getString("device"), it.getInt("motor"), it.getInt("value")) }
    }

    fun awaitValue(value: Int, device: String = "A") = awaitCondition("virtual device $device receiving $value") {
        commands().filter { it.device == device && it.value == value }.map { it.motor }.toSet() ==
            (if (device == "A") setOf(0, 1) else setOf(0))
    }

    private fun request(path: String, body: String? = null): String {
        val connection = URL(control + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            check(connection.responseCode == 200)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
