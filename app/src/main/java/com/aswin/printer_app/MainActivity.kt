package com.aswin.printer_app

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var button: Button
    private lateinit var textView: TextView
    private lateinit var select : Button
    private lateinit var name : TextView
    private val FILE_SELECT_CODE = 0
    private var selectedFileUri: Uri? = null
    private lateinit var upload : Button
    private var ipAddressFinal = ""
    private val client = OkHttpClient()
    private lateinit var progressDialog: ProgressDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button = findViewById(R.id.button)
        textView = findViewById(R.id.textView)
        select = findViewById(R.id.select_button)
        name = findViewById(R.id.textView2)
        upload = findViewById(R.id.upload_button)

        button.setOnClickListener {
            progressDialog = ProgressDialog(this@MainActivity).apply {
                setMessage("Scanning subnet, please wait...")
                setCancelable(false)
                show()
            }

            CoroutineScope(Dispatchers.IO).launch {
                val localIp = getLocalIPAddress()
                if (localIp != null) {
                    val subnet = localIp.substringBeforeLast(".")
                    val discoveredIp = scanSubnet(subnet)
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        if (discoveredIp != null) {
                            textView.text = "Server IP Address: $discoveredIp"
                            runOnUiThread {
                                ipAddressFinal = discoveredIp
                                select.visibility = View.VISIBLE
                            }
                        } else {
                            textView.text = "No server found."
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        textView.text = "Failed to get local IP address."
                    }
                }
            }
        }
        select.setOnClickListener(){
            showFileChooser()
        }
        upload.setOnClickListener {
            selectedFileUri?.let {
                uploadFile(it)
            } ?: Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
        }

    }
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(intent, "Select a file"), FILE_SELECT_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_SELECT_CODE && resultCode == RESULT_OK) {
            data?.data?.also { uri ->
                selectedFileUri = uri
                name.visibility = View.VISIBLE
                name.text = getFileName(uri)
                upload.visibility = View.VISIBLE
            }
        }
    }

    private fun getFileFromUri(uri: Uri): File {
        val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r", null)
        val inputStream = parcelFileDescriptor?.fileDescriptor?.let { FileInputStream(it) }
        val file = File(cacheDir, getFileName(uri))
        val outputStream = FileOutputStream(file)
        inputStream?.copyTo(outputStream)
        inputStream?.close()
        outputStream.close()
        return file
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        Log.d("Tag" , "I am here")
        var fileName = "unknown"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                fileName = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                Log.d("Tag" , fileName)
            }
        }
        return fileName
    }

    private fun uploadFile(uri: Uri) {
        val client = OkHttpClient()
        val file = getFileFromUri(uri)
        val fileRequestBody = file.asRequestBody("*/*".toMediaType())
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileRequestBody)
            .build()
        if (ipAddressFinal != null) {
            Log.d("Tag" , ipAddressFinal)
        } else {
            Log.d("Tag" , "No ip found")
        }
        val request = Request.Builder()
            .url("http://$ipAddressFinal:5005/control_commands")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    e.message?.let { Log.d("tag" , it) }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "File uploaded successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Upload failed: ${response.message}", Toast.LENGTH_SHORT).show()
                        Log.d("tag" , response.message)
                    }
                }
            }
        })
    }


    private suspend fun getLocalIPAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    // Check if the IP address is a local IP address
                    if (!inetAddress.isLoopbackAddress && inetAddress is InetAddress) {
                        val ipAddress = inetAddress.hostAddress
                        // Filter for IPv4 addresses
                        if (ipAddress.indexOf(':') < 0) {
                            // Return the IP address in IPv4 format
                            return ipAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun scanSubnet(subnet: String): String? {
        // Define the number of concurrent coroutines
        val maxConcurrentRequests = 150
        val dispatcher = Dispatchers.IO.limitedParallelism(maxConcurrentRequests)

        return withContext(dispatcher) {
            val jobs = (1..254).map { i ->
                async {
                    val ipAddress = "$subnet.$i"
                    Log.d("Tag", ipAddress)
                    if (isServerResponding(ipAddress)) {
                        Log.d("MainActivity", "Found server at: $ipAddress")
                        ipAddress
                    } else {
                        null
                    }
                }
            }
            jobs.awaitAll().firstOrNull { it != null }
        }
    }

    private suspend fun isServerResponding(ip: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "http://$ip:5005/testing_command"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                response.isSuccessful && responseBody == "Success"
            } catch (e: Exception) {
                false
            }
        }
    }
}
