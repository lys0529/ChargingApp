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

    // 上传：直接传 File 给 sardine
    fun upload(localFile: File, remoteName: String): Boolean {
        return try {
            val s = sardine ?: return false
            val remote = "$baseUrl/$remoteName"
            s.put(remote, localFile, "application/octet-stream")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 下载：把远端内容写入本地 File
    fun download(remoteName: String, localFile: File): Boolean {
        return try {
            val s = sardine ?: return false
            val remote = "$baseUrl/$remoteName"
            val bytes = s.get(remote).readBytes()
            localFile.writeBytes(bytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
