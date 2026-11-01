package com.study.bullfrog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/** Hosts the emulated front panel, the audio engine and the Android-specific services. */
class MainActivity : Activity(), Host {

    private val settings = Settings()
    private lateinit var store: SampleStore
    private lateinit var storage: Storage
    private lateinit var engine: Engine
    private lateinit var ctl: Controller
    private lateinit var panel: PanelView
    private var importFolder = 0

    private companion object {
        const val REQ_MIC = 1
        const val REQ_IMPORT = 10
        const val REQ_EXPORT = 11
        const val REQ_RESTORE = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        volumeControlStream = AudioManager.STREAM_MUSIC

        store = SampleStore(filesDir)
        store.loadUser()
        storage = Storage(filesDir)
        engine = Engine(store, settings)
        ctl = Controller(this, engine, store, settings, storage)
        ctl.loadState()
        panel = PanelView(this, ctl)
        setContentView(panel)

        // Pre-render the procedural factory samples in the background so that browsing is instant.
        val warm = Thread {
            for (f in 0 until 7) for (i in 0 until 32) store.get(f, i)
        }
        warm.priority = Thread.MIN_PRIORITY
        warm.start()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        engine.startAudio()
    }

    override fun onPause() {
        ctl.onPause()
        engine.stopAudio()
        super.onPause()
    }

    // ------------------------------------------------------------------ Host

    override fun requestMic(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        return false
    }

    override fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    @Suppress("DEPRECATION")
    override fun importWavs(folder: Int) {
        importFolder = folder
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/*"))
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_IMPORT)
    }

    @Suppress("DEPRECATION")
    override fun exportBackup() {
        ctl.persist()
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "bullfrog-drums-backup.json")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Suppress("DEPRECATION")
    override fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_RESTORE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                toast("Microfone liberado: segure SAMPLE e toque REC novamente")
            else toast("Permissão de microfone negada")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMPORT -> {
                val uris = ArrayList<Uri>()
                val cd = data.clipData
                if (cd != null) {
                    for (n in 0 until cd.itemCount) uris.add(cd.getItemAt(n).uri)
                } else {
                    val u = data.data
                    if (u != null) uris.add(u)
                }
                Thread { importUris(uris) }.start()
            }
            REQ_EXPORT -> {
                val u = data.data ?: return
                try {
                    val raw = storage.readRaw()
                    if (raw != null) {
                        val os = contentResolver.openOutputStream(u)
                        if (os != null) { os.write(raw); os.close() }
                    }
                    toast("Backup salvo")
                } catch (e: Exception) {
                    toast("Falha ao salvar o backup")
                }
            }
            REQ_RESTORE -> {
                val u = data.data ?: return
                try {
                    val ins = contentResolver.openInputStream(u)
                    val bytes = if (ins != null) ins.readBytes() else null
                    ins?.close()
                    if (bytes != null) {
                        storage.writeRaw(bytes)
                        ctl.loadState()
                        toast("Backup restaurado")
                    }
                } catch (e: Exception) {
                    toast("Falha ao restaurar o backup")
                }
            }
        }
    }

    private fun importUris(uris: List<Uri>) {
        var ok = 0
        for (u in uris) {
            try {
                val ins = contentResolver.openInputStream(u) ?: continue
                val bytes = ins.readBytes()
                ins.close()
                val d = WavIO.read(bytes) ?: continue
                val idx = store.firstFree(importFolder)
                if (idx < 0) break
                if (store.putUser(importFolder, idx, d)) ok++
            } catch (e: Exception) {
            }
        }
        toast("" + ok + " sample(s) importado(s) na pasta " + folderLetter(importFolder))
    }
}
