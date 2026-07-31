package com.feelyeon.nasviewer

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.feelyeon.nasviewer.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val scales = floatArrayOf(0.85f, 0.93f, 1f, 1.12f, 1.25f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.accountText.text = Prefs.account(this).ifBlank { "로그인 정보 없음" }
        binding.versionText.text = "NAScroll  " + packageManager.getPackageInfo(packageName, 0).versionName
        val current = Prefs.uiTextScale(this)
        binding.uiFontSizeSeekBar.progress = scales.indices.minByOrNull { kotlin.math.abs(scales[it] - current) } ?: 2
        updateUiScaleLabel(binding.uiFontSizeSeekBar.progress)
        binding.uiFontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateUiScaleLabel(progress)
                if (fromUser) Prefs.setUiTextScale(this@SettingsActivity, scales[progress])
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        binding.backBtn.setOnClickListener { finish() }
        binding.updateBtn.setOnClickListener {
            binding.updateBtn.isEnabled = false
            binding.updateBtn.text = "확인 중..."
            lifecycleScope.launch {
                val update = UpdateChecker.checkNow(this@SettingsActivity)
                binding.updateBtn.isEnabled = true
                binding.updateBtn.text = "업데이트 확인"
                if (update != null) {
                    UpdateChecker.showUpdateDialog(this@SettingsActivity, update)
                } else {
                    android.widget.Toast.makeText(this@SettingsActivity, "현재 최신 버전입니다.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.logoutBtn.setOnClickListener {
            Prefs.clear(this)
            SynologyApi.logout()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    private fun updateUiScaleLabel(index: Int) {
        binding.uiFontSizeText.text = when (index) {
            0 -> "작게"
            1 -> "조금 작게"
            2 -> "보통"
            3 -> "조금 크게"
            else -> "크게"
        }
    }
}
