package com.example.pos_hma.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.example.pos_hma.R
import com.example.pos_hma.databinding.ActivitySplashBinding
import com.example.pos_hma.ui.login.LoginActivity

class SplashActivity : ComponentActivity() {

    private val splashDelayMs = 600L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var binding: ActivitySplashBinding

    private val launchRunnable = Runnable {
        startActivity(Intent(this, LoginActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handler.postDelayed(launchRunnable, splashDelayMs)
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchRunnable)
        super.onDestroy()
    }
}
