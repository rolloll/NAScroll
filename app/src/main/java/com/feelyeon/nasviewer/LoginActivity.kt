package com.feelyeon.nasviewer

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.feelyeon.nasviewer.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Prefs.hasAccount(this)) {
            binding.fBaseUrl.setText(Prefs.baseUrl(this))
            binding.fAccount.setText(Prefs.account(this))
            attemptAutoLogin()
        }

        binding.loginBtn.setOnClickListener { attemptLogin() }
    }

    private fun attemptAutoLogin() {
        setLoading(true)
        lifecycleScope.launch {
            val ok = SynologyApi.ensureLoggedIn(this@LoginActivity, force = true)
            if (ok) {
                goToBrowser()
            } else {
                setLoading(false)
            }
        }
    }

    private fun attemptLogin() {
        val baseUrl = binding.fBaseUrl.text.toString().trim().trimEnd('/')
        val account = binding.fAccount.text.toString().trim()
        val password = binding.fPassword.text.toString()

        if (baseUrl.isBlank() || account.isBlank() || password.isBlank()) {
            showError("모든 항목을 입력해주세요.")
            return
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            showError("NAS 주소는 http:// 또는 https:// 로 시작해야 합니다.")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                SynologyApi.login(baseUrl, account, password)
                Prefs.setBaseUrl(this@LoginActivity, baseUrl)
                Prefs.setAccount(this@LoginActivity, account)
                Prefs.setPassword(this@LoginActivity, password)
                goToBrowser()
            } catch (e: Exception) {
                setLoading(false)
                showError(e.message ?: "로그인에 실패했습니다.")
            }
        }
    }

    private fun goToBrowser() {
        val path = Prefs.lastPath(this).ifBlank { "/" }
        val intent = Intent(this, BrowserActivity::class.java)
        intent.putExtra(BrowserActivity.EXTRA_PATH, path)
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.loginBtn.isEnabled = !loading
        binding.loginBtn.text = if (loading) "로그인 중..." else "로그인"
    }

    private fun showError(msg: String) {
        binding.errorBox.text = msg
        binding.errorBox.visibility = View.VISIBLE
    }
}
