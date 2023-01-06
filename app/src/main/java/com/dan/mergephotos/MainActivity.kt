
package com.dan.mergephotos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader


class MainActivity : AppCompatActivity() {
    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        const val REQUEST_PERMISSIONS = 1
    }

    private val stack = mutableListOf<Pair<String, AppFragment>>()
    val settings: Settings by lazy { Settings(this) }

    init {
        BusyDialog.create(this)
    }

    private fun popView(homeButton: Boolean = false): Boolean {
        if (stack.size <= 1) return false

        val prevFragment = stack.removeLast().second
        val item = stack.last()

        prevFragment.onBack(homeButton)

        val transaction = supportFragmentManager.beginTransaction()
        transaction.show(item.second)
        transaction.remove(prevFragment)
        transaction.commit()

        supportActionBar?.setDisplayHomeAsUpEnabled(stack.size > 1)
        supportActionBar?.title = item.first

        return true
    }

    fun pushView(title: String, fragment: AppFragment) {
        val prevFragment: AppFragment? = if (stack.isEmpty()) null else stack.last().second

        stack.add( Pair(title, fragment) )

        val transaction = supportFragmentManager.beginTransaction()
        if (null != prevFragment) transaction.hide(prevFragment)
        transaction.add(R.id.app_fragment, fragment)
        transaction.commit()

        supportActionBar?.setDisplayHomeAsUpEnabled(stack.size > 1)
        supportActionBar?.title = title
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(grantResults)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (android.R.id.home == item.itemId) {
            popView(true)
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (!popView( false)) super.onBackPressed()
    }

    fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun exitApp() {
        setResult(0)
        finish()
    }

    private fun fatalError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(msg)
            .setIcon(android.R.drawable.stat_notify_error)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> exitApp() }
            .show()
    }

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }

    private fun handleRequestPermissions(grantResults: IntArray) {
        var allowedAll = grantResults.size >= PERMISSIONS.size

        if (grantResults.size >= PERMISSIONS.size) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allowedAll = false
                    break
                }
            }
        }

        if (allowedAll) onPermissionsAllowed()
        else fatalError("You must allow permissions !")
    }

    private fun onPermissionsAllowed() {
        if (!OpenCVLoader.initDebug()) fatalError("Failed to initialize OpenCV")
        System.loadLibrary("native-lib")

        setContentView(R.layout.activity_main)
        MainFragment.show(this)
    }
}