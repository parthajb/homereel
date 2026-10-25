package com.parthajb.homereel.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.parthajb.homereel.shared.Api
import java.util.concurrent.Executors

/** Finds the phone on the Wi-Fi (or takes its address) and pairs with the code the phone shows. */
class ConnectActivity : FragmentActivity() {
    private lateinit var discovery: Discovery
    private val worker = Executors.newSingleThreadExecutor()
    private val found = LinkedHashMap<String, Discovery.Found>()
    private var chosen: Discovery.Found? = null

    private lateinit var status: TextView
    private lateinit var servers: LinearLayout
    private lateinit var address: EditText
    private lateinit var codeSection: View
    private lateinit var codeLabel: TextView
    private lateinit var code: EditText
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        status = findViewById(R.id.status)
        servers = findViewById(R.id.servers)
        address = findViewById(R.id.address)
        codeSection = findViewById(R.id.code_section)
        codeLabel = findViewById(R.id.code_label)
        code = findViewById(R.id.code)
        error = findViewById(R.id.error)

        findViewById<Button>(R.id.use_address).setOnClickListener { useTypedAddress() }
        address.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO).also { if (it) useTypedAddress() }
        }
        findViewById<Button>(R.id.pair).setOnClickListener { pair() }
        code.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO).also { if (it) pair() }
        }

        discovery = Discovery(this) { server ->
            found[server.name] = server
            showServers()
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    private fun showServers() {
        servers.removeAllViews()
        status.setText(if (found.isEmpty()) R.string.connect_searching else R.string.connect_found)
        for (server in found.values) {
            val button = Button(this).apply {
                text = getString(R.string.connect_server, server.name, server.host)
                setOnClickListener { choose(server) }
            }
            servers.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        if (chosen == null) servers.getChildAt(0)?.requestFocus()
    }

    private fun useTypedAddress() {
        val text = address.text.toString().trim()
        if (text.isEmpty()) return
        val host = text.substringBeforeLast(':').takeIf { text.count { it == ':' } == 1 } ?: text
        val port = if (text.count { it == ':' } == 1) text.substringAfterLast(':').toIntOrNull() ?: Api.DEFAULT_PORT else Api.DEFAULT_PORT
        error.text = ""
        status.setText(R.string.connect_checking)
        worker.execute {
            val result = runCatching { ApiClient(host, port, null).info() }
            // Any answer from a HomeReel server, even "not paired", proves the address is right.
            val reachable = result.isSuccess || result.exceptionOrNull() is ApiException
            runOnUiThread {
                if (reachable) {
                    choose(Discovery.Found(host, host, port))
                } else {
                    status.setText(R.string.connect_found)
                    error.text = getString(R.string.connect_unreachable, text, result.exceptionOrNull()?.message ?: "")
                }
            }
        }
    }

    private fun choose(server: Discovery.Found) {
        chosen = server
        error.text = ""
        codeSection.visibility = View.VISIBLE
        codeLabel.text = getString(R.string.connect_code, server.name)
        code.setText("")
        code.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(code, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun pair() {
        val server = chosen ?: return
        val digits = code.text.toString().filter { it.isDigit() }
        if (digits.length != 6) {
            error.setText(R.string.connect_six_digits)
            return
        }
        error.text = ""
        status.setText(R.string.connect_pairing)
        worker.execute {
            val result = runCatching { ApiClient(server.host, server.port, null).pair(digits, "TV: ${Build.MODEL}") }
            runOnUiThread {
                result.onSuccess { response ->
                    ServerStore(this).save(server.host, server.port, response.serverName, response.token)
                    Images.clear()
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }.onFailure {
                    status.setText(R.string.connect_found)
                    error.text = it.message
                    code.requestFocus()
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
