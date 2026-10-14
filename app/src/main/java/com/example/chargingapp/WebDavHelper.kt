package com.example.chargingapp

import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import java.io.File

object WebDavHelper {
    private var sardine: Sardine? = null
    private var baseUrl: String = ""

    fun configure(url: String, user: String, pass: String) {
        baseUrl = url.trimEnd('/')
        sardine = OkHttpSardine().apply {
            setCredentials(user, pass)
        }
    }

    fun isConfigured(): Boolean = sardine != null && baseUrl.isNotEmpty()

    fun isAvailable(): Boolean {
        return try {
            sardine?.exists(baseUrl) ?: false
        } catch (e: Exception) { false }
    }

    fun upload(localFile: File, remoteName: String): Boolean {
        return try {
            val s = sardine ?: return false
            val remote = "$baseUrl/$remoteName"
            localFile.inputStream().use { input ->
                s.put(remote, input, "application/octet-stream")
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun download(remoteName: String, localFile: File): Boolean {
        return try {
            val s = sardine ?: return false
            val remote = "$baseUrl/$remoteName"
            val input = s.get(remote)
            localFile.outputStream().use { output ->
                input.copyTo(output)
            }
            true
        } catch (e: Exception) { false }
    }
}
