package com.example.dlsfirebasedetector

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        run_labeling.setOnClickListener {
            startActivity(Intent(this, LabelingActivity::class.java))
        }
        run_detection.setOnClickListener {
            startActivity(Intent(this, DetectionActivity::class.java))
        }


    }
}