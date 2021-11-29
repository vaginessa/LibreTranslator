package de.beowulf.libretranslater

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import de.beowulf.libretranslater.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.WindowManager
import android.widget.CheckBox

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var settings: SharedPreferences
    private var sourceLangId = 0
    private var targetLangId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(theme())
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (settings.getBoolean("shrink", false))
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        sourceLangId = settings.getInt("Source", 2)
        setSourceLang()
        targetLangId = settings.getInt("Target", 4)
        setTargetLang()

        if (intent.action == Intent.ACTION_SEND) {
            binding.SourceText.setText(intent.extras!!.getString(Intent.EXTRA_TEXT))
            translateText()
        }

        //check if source text changes and translate
        binding.SourceText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            val handler = Handler(Looper.getMainLooper())
            val workRunnable: Runnable = Runnable {
                //Don't call API for every changed letter
                translateText()
            }

            override fun afterTextChanged(editable: Editable) {
                handler.removeCallbacks(workRunnable)
                handler.postDelayed(workRunnable, 500 /*delay*/)
                binding.translationPending.visibility = View.VISIBLE
            }
        })


        binding.RemoveSourceText.setOnClickListener {
            if (binding.SourceText.text.toString() != "") {
                if (settings.getBoolean("ask", true)) {
                    MaterialAlertDialogBuilder(this, R.style.AlertDialog)
                        .setMessage(getString(R.string.rlyRemoveText))
                        .setPositiveButton(R.string.remove) { dialog, _ ->
                            binding.SourceText.text = null
                            binding.TranslatedTV.text = null
                            dialog.dismiss()
                        }
                        .setNeutralButton(getString(R.string.neverAskAgain)) { dialog, _ ->
                            settings.edit()
                                .putBoolean("ask", false)
                                .apply()
                            binding.SourceText.text = null
                            binding.TranslatedTV.text = null
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    binding.SourceText.text = null
                    binding.TranslatedTV.text = null
                }

                //Hide keyboard
                val imm: InputMethodManager =
                    getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
                val view: View? = currentFocus
                imm.hideSoftInputFromWindow(view?.windowToken, 0)
            }
        }

        //actions with the translated text
        binding.CopyTranslation.setOnClickListener {
            if (binding.TranslatedTV.text.toString() != "") {
                //copy translated text to clipboard
                val clipboard: ClipboardManager =
                    getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip: ClipData =
                    ClipData.newPlainText("translated text", binding.TranslatedTV.text)
                clipboard.setPrimaryClip(clip)
                //Message to inform the user
                Snackbar.make(
                    binding.CopyTranslation,
                    getString(R.string.copiedClipboard),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        binding.ShareTranslation.setOnClickListener {
            if (binding.TranslatedTV.text.toString() != "") {
                //create share intent
                val share = Intent.createChooser(Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, binding.TranslatedTV.text)
                }, null)
                //share translation
                startActivity(share)
            }
        }

        //language chooser
        binding.SwitchLanguages.setOnClickListener {
            val cacheLang = sourceLangId
            sourceLangId = targetLangId
            setSourceLang()
            targetLangId = cacheLang
            setTargetLang()
            translateText()
        }

        binding.SourceLanguageBot.setOnClickListener {
            chooseLang(true)
        }

        binding.TargetLanguageBot.setOnClickListener {
            chooseLang(false)
        }

        //theme switcher
        binding.themeSwitcher.setOnClickListener {
            var newTheme = settings.getInt("Theme", 1) + 1
            if (newTheme == 5) {
                newTheme = 0
            }
            settings.edit()
                .putInt("Theme", newTheme)
                .apply()
            finish()
            startActivity(intent)
        }

        //About dialog
        binding.info.setOnClickListener {
            val about: View = layoutInflater.inflate(R.layout.about, null)
            val serverET = about.findViewById<EditText>(R.id.Server)
            val apiET = about.findViewById<EditText>(R.id.Api)
            val shrinkCB = about.findViewById<CheckBox>(R.id.Shrink)
            val tv1 = about.findViewById<TextView>(R.id.aboutTV1)
            val tv2 = about.findViewById<TextView>(R.id.aboutTV2)
            val tv3 = about.findViewById<TextView>(R.id.aboutTV3)
            var server: String? = settings.getString("server", "libretranslate.de")
            val apiKey: String? = settings.getString("apiKey", "")
            val shrink: Boolean = settings.getBoolean("shrink", false)
            serverET.setText(server)
            apiET.setText(apiKey)
            shrinkCB.isChecked = shrink
            tv1.movementMethod = LinkMovementMethod.getInstance()
            tv2.movementMethod = LinkMovementMethod.getInstance()
            tv3.movementMethod = LinkMovementMethod.getInstance()
            val popUp = AlertDialog.Builder(this, R.style.AlertDialog)
            popUp.setView(about)
                .setTitle(getString(R.string.app_name))
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    //Remove http/https/www and /translate to prevent errors
                    server = serverET.text.toString().replace("http://", "")
                        .replace("https://", "")
                        .replace("www.", "")
                        .replace("/translate", "")
                    settings.edit()
                        .putString("server", server)
                        .putString("apiKey", apiET.text.toString())
                        .apply()

                    if (shrink != shrinkCB.isChecked) {
                        settings.edit()
                            .putBoolean("shrink", shrinkCB.isChecked)
                            .apply()
                        finish()
                        startActivity(intent)
                    }
                }
                .setNegativeButton(getString(R.string.close)) {_ , _ -> }
                .show()

        }
    }

    private fun translateText() {
        if (binding.SourceText.text.toString() != "") {
            val server: String? = settings.getString("server", "libretranslate.de")
            val apiKey: String? = settings.getString("apiKey", "")
            val url = URL("https://$server/translate")
            val connection: HttpsURLConnection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("accept", "application/json")
            val data =
                "q=${binding.SourceText.text.replace(Regex("&"), "%26")}" +
                        "&source=${resources.getStringArray(R.array.LangCodes)[sourceLangId]}" +
                        "&target=${resources.getStringArray(R.array.LangCodes)[targetLangId]}" +
                        if (apiKey != "") {
                            "&api_key=$apiKey"
                        } else {
                            ""
                        }
            val out = data.toByteArray(Charsets.UTF_8)
            @Suppress("BlockingMethodInNonBlockingContext")
            CoroutineScope(Dispatchers.IO).launch {
                var serverError = ""
                val transString: String? = try {
                    val stream: OutputStream = connection.outputStream
                    stream.write(out)
                    val inputStream = DataInputStream(connection.inputStream)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    JSONObject(reader.readLine()).getString("translatedText")
                } catch (e: Exception) {
                    serverError = try {
                        JSONObject(connection.errorStream.reader().readText()).getString("error")
                    } catch (e: Exception) {
                        getString(R.string.netError)
                    }
                    null
                }
                withContext(Dispatchers.Main) {
                    if (transString == null)
                        Toast.makeText(this@MainActivity, serverError, Toast.LENGTH_SHORT).show()
                    binding.TranslatedTV.text = transString
                    binding.translationPending.visibility = View.GONE
                }
            }
        } else {
            binding.TranslatedTV.text = ""
            binding.translationPending.visibility = View.GONE
        }
    }

    private fun setSourceLang() {
        val sourceLang = resources.getStringArray(R.array.Lang)[sourceLangId]
        binding.SourceLanguageTop.text = sourceLang
        binding.SourceLanguageBot.text = sourceLang
        settings.edit()
            .putInt("Source", sourceLangId)
            .apply()
    }

    private fun setTargetLang() {
        val targetLang = resources.getStringArray(R.array.Lang)[targetLangId]
        binding.TargetLanguageTop.text = targetLang
        binding.TargetLanguageBot.text = targetLang
        settings.edit()
            .putInt("Target", targetLangId)
            .apply()
    }

    private fun chooseLang(source: Boolean) {
        AlertDialog.Builder(
            this, R.style.AlertDialog
        )
            .setTitle(getString(R.string.chooseLang))
            .setItems(resources.getStringArray(R.array.Lang)) { _, id ->
                if (source) {
                    sourceLangId = id
                    setSourceLang()
                } else {
                    targetLangId = id
                    setTargetLang()
                }
                translateText()
            }
            .setNegativeButton(getString(R.string.abort)) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun theme(): Int {
        settings = getSharedPreferences("de.beowulf.libretranslater", 0)
        return when (settings.getInt("Theme", 1)) {
            1 -> {
                R.style.DarkTheme
            }
            2 -> {
                R.style.LilaTheme
            }
            3 -> {
                R.style.SandTheme
            }
            4 -> {
                R.style.BlueTheme
            }
            else -> {
                R.style.LightTheme
            }
        }
    }
}
