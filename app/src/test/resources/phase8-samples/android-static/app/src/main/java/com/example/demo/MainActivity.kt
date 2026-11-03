package com.example.demo

import android.os.Handler
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val title = findViewById<TextView>(R.id.title)
        title.text = getString(R.string.missing_title)
        val client = OkHttpClient()
        val unused = 42
        val name: String? = intent.getStringExtra("name")
        Log.d("Demo", name!!)
        try {
            Thread.sleep(500)
        } catch (e: Exception) {
        }
    }
}
