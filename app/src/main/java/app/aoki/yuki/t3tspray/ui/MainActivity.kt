package app.aoki.yuki.t3tspray.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.aoki.yuki.t3tspray.R
import app.aoki.yuki.t3tspray.config.ConfigRepository
import app.aoki.yuki.t3tspray.config.SprayConfig

class MainActivity : AppCompatActivity() {

    private lateinit var repository: ConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = ConfigRepository(this)
        val current = repository.load()

        val enableSwitch: Switch = findViewById(R.id.switch_enable)
        val idmInput: EditText = findViewById(R.id.input_idm)
        val systemCodesInput: EditText = findViewById(R.id.input_system_codes)
        val statusText: TextView = findViewById(R.id.text_status)

        enableSwitch.isChecked = current.enabled
        idmInput.setText(current.idm)
        systemCodesInput.setText(current.systemCodes.joinToString(","))

        findViewById<Button>(R.id.button_save).setOnClickListener {
            val updated = SprayConfig(
                enabled = enableSwitch.isChecked,
                idm = idmInput.text.toString(),
                systemCodes = systemCodesInput.text.toString()
                    .split(",", "\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            )
            repository.save(updated)
            statusText.text = getString(R.string.status_saved)
            Toast.makeText(this, R.string.status_saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.button_launch_manager).setOnClickListener {
            val launched = launchManager()
            val message = if (launched) {
                getString(R.string.status_launch_success)
            } else {
                getString(R.string.status_launch_fail)
            }
            statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchManager(): Boolean {
        val managerIntent = packageManager.getLaunchIntentForPackage("org.lsposed.manager")
            ?: packageManager.getLaunchIntentForPackage("de.robv.android.xposed.installer")
        return managerIntent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
            true
        } ?: false
    }
}
