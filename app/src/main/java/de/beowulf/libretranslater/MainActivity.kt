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
import android.widget.TextView

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
            }
        })


        binding.RemoveSourceText.setOnClickListener {
            //Ask if the text really should be removed
            //ToDo: Maybe use another solution then Snackbar
            Snackbar.make(
                binding.RemoveSourceText,
                getString(R.string.rlyRemoveText),
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction("Remove") {
                    //remove text
                    binding.SourceText.text = null
                    binding.TranslatedTV.text = null
                }
                .show()
            //Hide keyboard
            val imm: InputMethodManager =
                getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            val view: View? = currentFocus
            imm.hideSoftInputFromWindow(view?.windowToken, 0)
        }

        //actions with the translated text
        binding.CopyTranslation.setOnClickListener {
            if (binding.TranslatedTV.text != "") {
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
            if (binding.TranslatedTV.text != "") {
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
            val tv1 = about.findViewById<TextView>(R.id.aboutTV1)
            val tv2 = about.findViewById<TextView>(R.id.aboutTV2)
            val tv3 = about.findViewById<TextView>(R.id.aboutTV3)
            tv1.movementMethod = LinkMovementMethod.getInstance()
            tv2.movementMethod = LinkMovementMethod.getInstance()
            tv3.movementMethod = LinkMovementMethod.getInstance()
            val popUp = AlertDialog.Builder(this, R.style.AlertDialog)
            popUp.setView(about)
                .setPositiveButton(getString(R.string.close)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()

        }
    }

    private fun translateText() {
        if (binding.SourceText.text.toString() != "") {
            val url = URL("https://libretranslate.de/translate")
            val connection: HttpsURLConnection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("accept", "application/json")
            val data =
                "q=${binding.SourceText.text.replace(Regex("&"), "%26")}" +
                        "&source=${resources.getStringArray(R.array.LangCodes)[sourceLangId]}" +
                        "&target=${resources.getStringArray(R.array.LangCodes)[targetLangId]}"
            val out = data.toByteArray(Charsets.UTF_8)
            @Suppress("BlockingMethodInNonBlockingContext")
            CoroutineScope(Dispatchers.IO).launch {
                val transString: String? = try {
                    val stream: OutputStream = connection.outputStream
                    stream.write(out)
                    val inputStream = DataInputStream(connection.inputStream)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    JSONObject(reader.readLine()).getString("translatedText")
                } catch (e: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    binding.TranslatedTV.text = transString
                }
            }
        } else {
            binding.TranslatedTV.text = ""
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
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
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
