package com.github.digitallyrefined.androidipcamera

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.*

class StreamingServer(private val context: Context, private val fps: Int) {

    @Volatile
    private var isStopping = false

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private var isRunning = false

    data class Client(
        val socket: Socket,
        val outputStream: OutputStream,
        val writer: PrintWriter
    )

    fun start() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val useCertificate = prefs.getBoolean("use_certificate", false)
        val certificatePath = prefs.getString("certificate_path", null)
        val certificatePassword = prefs.getString("certificate_password", "")
            ?.takeIf { it.isNotBlank() }?.toCharArray()

        val port = 4444
        val maxClients = 3

        try {
            serverSocket = if (useCertificate && certificatePath != null && certificatePassword != null) {
                createSslServerSocket(certificatePath.toUri(), certificatePassword, port)
            } else {
                ServerSocket(port).apply {
                    reuseAddress = true
                    soTimeout = 30000
                }
            }

            Log.i("StreamingServer", "Server started on port $port")
            isRunning = true

            Thread {
                while (isRunning) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        continue
                    } ?: continue

                    if (clients.size >= maxClients) {
                        socket.getOutputStream().use {
                            it.write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray())
                            it.flush()
                        }
                        socket.close()
                        continue
                    }

                    Thread {
                        handleClient(socket)
                    }.start()
                }
            }.start()

        } catch (e: IOException) {
            Log.e("StreamingServer", "Error starting server: ${e.message}")
        }
    }

    fun stop() {
        isStopping = true
        isRunning = false
        try {
            clients.forEach {
                try {
                    it.socket.close()
                } catch (_: IOException) {
                }
            }
            clients.clear()
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e("StreamingServer", "Error closing server: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        try {
            val output = socket.getOutputStream()
            val writer = PrintWriter(output, true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            val headers = buildList {
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    add(line!!)
                }
            }

            if (username.isNotEmpty() && password.isNotEmpty()) {
                val authHeader = headers.find { it.startsWith("Authorization: Basic ") }
                val isAuthValid = authHeader?.substringAfter("Authorization: Basic ")?.let { encoded ->
                    val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
                    decoded == "$username:$password"
                } ?: false

                if (!isAuthValid) {
                    writer.println("HTTP/1.1 401 Unauthorized")
                    writer.println("WWW-Authenticate: Basic realm=\"Android IP Camera\"")
                    writer.println("Connection: close")
                    writer.println()
                    socket.close()
                    return
                }
            }

            writer.println("HTTP/1.0 200 OK")
            writer.println("Connection: close")
            writer.println("Cache-Control: no-cache")
            writer.println("Content-Type: multipart/x-mixed-replace; boundary=frame")
            writer.println()
            writer.flush()

            clients.add(Client(socket, output, writer))
            Log.i("StreamingServer", "Client connected: ${socket.inetAddress.hostAddress}")

        } catch (e: IOException) {
            Log.e("StreamingServer", "Client error: ${e.message}")
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    fun sendFrame(jpegBytes: ByteArray) {
        if (isStopping) return

        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()

            if (client.socket.isClosed || !client.socket.isConnected) {
                iterator.remove()
                continue
            }

            try {
                client.writer.print("--frame\r\n")
                client.writer.print("Content-Type: image/jpeg\r\n")
                client.writer.print("Content-Length: ${jpegBytes.size}\r\n\r\n")
                client.writer.flush()
                client.outputStream.write(jpegBytes)
                client.outputStream.flush()
            } catch (e: IOException) {
                Log.e("StreamingServer", "Failed to send frame: ${e.message}")
                try {
                    client.writer.close()
                } catch (_: IOException) {}

                try {
                    client.outputStream.close()
                } catch (_: IOException) {}

                try {
                    client.socket.close()
                } catch (_: IOException) {}

                clients.remove(client)
                Log.i("StreamingServer", "Removed client due to send failure.")
                Log.w("StreamingServer", "Skipping closed socket client: ${client.socket.isClosed}, connected: ${client.socket.isConnected}")

            }
        }
    }


    private fun createSslServerSocket(uri: Uri, password: CharArray, port: Int): SSLServerSocket {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open certificate URI: $uri")

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(inputStream, password)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }

        val sslContext = SSLContext.getInstance("TLSv1.2").apply {
            init(kmf.keyManagers, null, null)
        }

        return (sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.2")
            reuseAddress = true
            soTimeout = 30000
        }
    }
}
