package com.exambrowtest.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.exambrowtest.kiosk.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val PREFS_NAME = "ExambrowPrefs"
    private val KEY_EXAM_URL = "exam_url"
    private val KEY_PIN = "admin_pin"
    private val DEFAULT_PIN = "123456"

    // MODE DEVELOPMENT (Ubah ke false saat siap rilis ke siswa agar tidak terkunci saat tes frontend)
    private val isDevMode = true

    private val statusUpdater = object : Runnable {
        override fun run() {
            updateSystemStatus()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isDevMode) {
            // Prevent screenshots & screen recording (Matikan di mode dev agar scrcpy tidak layar hitam)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        // Keep screen ON during exam
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!isDevMode) {
            setupLockTask()
        }
        setupWebView()
        setupTopBar()
        setupOnBackPressed()

        handler.post(statusUpdater)
    }

    override fun onResume() {
        super.onResume()
        if (!isDevMode) {
            enableKioskMode()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusUpdater)
    }

    private fun setupLockTask() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(this, KioskDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setLockTaskPackages(component, arrayOf(packageName))
        }
    }

    private fun enableKioskMode() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupTopBar() {
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }

        binding.btnAdminUnlock.setOnClickListener {
            showAdminUnlockDialog()
        }
    }

    private fun updateSystemStatus() {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.tvClock.text = timeFormat.format(Date())

        val isConnected = NetworkUtils.isConnected(this)
        if (isConnected) {
            binding.tvNetworkStatus.text = getString(R.string.wifi_connected)
            binding.tvNetworkStatus.setTextColor(getColor(R.color.status_green))
        } else {
            binding.tvNetworkStatus.text = getString(R.string.wifi_disconnected)
            binding.tvNetworkStatus.setTextColor(getColor(R.color.status_red))
        }

        val battery = NetworkUtils.getBatteryPercentage(this)
        binding.tvBatteryStatus.text = "$battery%"
    }

    private fun setupWebView() {
        val webView = binding.webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                view?.loadUrl(request?.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
                binding.swipeRefresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Informasi Keamanan Ujian")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        val defaultUrl = getString(R.string.default_exam_url)
        val savedUrl = sharedPreferences.getString(KEY_EXAM_URL, defaultUrl) ?: defaultUrl
        val examUrl = if (savedUrl == "https://www.google.com" || savedUrl == "https://smkn1mejayan.sch.id") defaultUrl else savedUrl
        webView.loadUrl(examUrl)
    }

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    if (isDevMode) {
                        finish() // Di mode dev, bisa langsung keluar/tutup aplikasi seperti biasa
                    } else {
                        Toast.makeText(this@MainActivity, "Sistem Pengaman Aktif. Gunakan ikon pelindung untuk keluar.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isDevMode && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            return true // Prevent volume buttons from cheating or tampering (Bebas pakai tombol volume saat dev mode)
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showAdminUnlockDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pin, null)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val tvPinError = dialogView.findViewById<TextView>(R.id.tvPinError)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_pin_title))
            .setView(dialogView)
            .setPositiveButton("Buka Akses", null)
            .setNegativeButton("Tutup", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val inputPin = etPin.text.toString()
                val currentPin = sharedPreferences.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

                if (inputPin == currentPin || inputPin == "999999") { // Master backup pin
                    dialog.dismiss()
                    showAdminMenu()
                } else {
                    tvPinError.visibility = View.VISIBLE
                    etPin.text.clear()
                }
            }
        }
        dialog.show()
    }

    private fun showAdminMenu() {
        AlertDialog.Builder(this)
            .setTitle("Panel Pengawas Ujian Resmi")
            .setItems(arrayOf("Akhiri Sesi Ujian (Keluar Sistem)", "Konfigurasi Server & PIN Keamanan")) { _, which ->
                when (which) {
                    0 -> exitKioskMode()
                    1 -> showSettingsDialog()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etExamUrl = dialogView.findViewById<EditText>(R.id.etExamUrl)
        val etSettingsPin = dialogView.findViewById<EditText>(R.id.etSettingsPin)

        val defaultUrl = getString(R.string.default_exam_url)
        val savedUrl = sharedPreferences.getString(KEY_EXAM_URL, defaultUrl) ?: defaultUrl
        val currentUrl = if (savedUrl == "https://www.google.com" || savedUrl == "https://smkn1mejayan.sch.id") defaultUrl else savedUrl
        val currentPin = sharedPreferences.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

        etExamUrl.setText(currentUrl)
        etSettingsPin.setText(currentPin)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newUrl = etExamUrl.text.toString().trim()
                val newPin = etSettingsPin.text.toString().trim()

                if (newUrl.isNotEmpty() && newPin.isNotEmpty()) {
                    sharedPreferences.edit().apply {
                        putString(KEY_EXAM_URL, newUrl)
                        putString(KEY_PIN, newPin)
                        apply()
                    }
                    binding.webView.loadUrl(newUrl)
                    Toast.makeText(this, "Konfigurasi sistem berhasil diperbarui", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Parameter server dan PIN wajib diisi!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun exitKioskMode() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finishAffinity()
    }
}
