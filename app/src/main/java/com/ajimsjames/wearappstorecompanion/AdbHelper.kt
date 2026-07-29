package com.ajimsjames.wearappstorecompanion

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.PairingConnectionCtx
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.Date
import java.util.concurrent.TimeUnit

object AdbHelper {
    private const val TAG = "AdbHelper"
    private const val KEY_FILE_NAME = "adb_key.pk8"
    private const val CERT_FILE_NAME = "adb_cert.crt"

    private var activeConnection: AdbConnection? = null

    // Key / Certificate Generation & Storage
    private fun generateAdbKeyAndCertificate(): Pair<PrivateKey, Certificate> {
        Log.i(TAG, "Generating RSA Keypair and Self-Signed Certificate...")
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        val sub = X500Name("CN=WearStoreCompanion")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 1000L * 60 * 60)
        val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10) // 10 years

        val certBuilder = JcaX509v3CertificateBuilder(
            sub,
            serial,
            notBefore,
            notAfter,
            sub,
            keyPair.public
        )

        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = certBuilder.build(contentSigner)
        val certificate = JcaX509CertificateConverter().getCertificate(certHolder)

        return Pair(keyPair.private, certificate)
    }

    private fun getKeys(context: Context): Pair<PrivateKey, Certificate> {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        val certFile = File(context.filesDir, CERT_FILE_NAME)

        return if (keyFile.exists() && certFile.exists()) {
            try {
                val keyBytes = keyFile.readBytes()
                val certBytes = certFile.readBytes()

                val keyFactory = java.security.KeyFactory.getInstance("RSA")
                val privateKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))

                val certFactory = CertificateFactory.getInstance("X.509")
                val certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes))

                Pair(privateKey, certificate)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading existing keys, generating new ones", e)
                val pair = generateAdbKeyAndCertificate()
                saveKeys(context, pair.first, pair.second)
                pair
            }
        } else {
            val pair = generateAdbKeyAndCertificate()
            saveKeys(context, pair.first, pair.second)
            pair
        }
    }

    private fun saveKeys(context: Context, privateKey: PrivateKey, certificate: Certificate) {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        val certFile = File(context.filesDir, CERT_FILE_NAME)
        keyFile.writeBytes(privateKey.encoded)
        certFile.writeBytes(certificate.encoded)
    }

    @Synchronized
    fun pair(context: Context, ip: String, pairingPort: Int, pairingCode: String, onStatusUpdate: (String) -> Unit): Boolean {
        disconnect()
        try {
            onStatusUpdate("Pairing with watch on port $pairingPort...")
            val keys = getKeys(context)
            val codeBytes = pairingCode.toByteArray(Charsets.UTF_8)
            val deviceName = "WearStoreCompanion"

            val pairingClient = PairingConnectionCtx(
                ip,
                pairingPort,
                codeBytes,
                keys.first,
                keys.second,
                deviceName
            )
            pairingClient.use { client ->
                client.start() // Blocks until pairing succeeds or throws exception
            }
            onStatusUpdate("Pairing successful! You can now Connect.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed", e)
            onStatusUpdate("Pairing failed: ${e.localizedMessage}")
            return false
        }
    }

    @Synchronized
    fun connect(context: Context, ip: String, port: Int, onStatusUpdate: (String) -> Unit): Boolean {
        disconnect()
        try {
            onStatusUpdate("Connecting to watch on port $port...")
            val keys = getKeys(context)

            val conn = AdbConnection.create(ip, port, keys.first, keys.second)
            conn.setDeviceName("WearStoreCompanion")

            onStatusUpdate("Initiating secure connection...")
            // Connect with timeout of 5 seconds
            val success = conn.connect(5000, TimeUnit.MILLISECONDS, false)
            if (success && conn.isConnected && conn.isConnectionEstablished) {
                activeConnection = conn
                onStatusUpdate("Connected successfully!")
                return true
            } else {
                conn.close()
                onStatusUpdate("Connection failed (make sure Wireless Debugging is on and paired!)")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB Connection failed", e)
            onStatusUpdate("Connection failed: ${e.localizedMessage}")
            disconnect()
            return false
        }
    }

    @Synchronized
    fun disconnect() {
        try {
            activeConnection?.close()
        } catch (e: Exception) {
            // ignore
        }
        activeConnection = null
    }

    fun isConnected(): Boolean {
        return activeConnection != null && activeConnection!!.isConnected && activeConnection!!.isConnectionEstablished
    }

    fun installApk(apkFile: File, onProgressUpdate: (String) -> Unit): Boolean {
        val conn = activeConnection ?: throw IllegalStateException("Not connected to ADB")
        
        try {
            val remoteTmpPath = "/data/local/tmp/temp_app.apk"
            onProgressUpdate("Pushing APK to watch...")

            val uploadStream = conn.open("shell:cat > $remoteTmpPath")
            val fileLength = apkFile.length()
            val fileStream = FileInputStream(apkFile)
            val buffer = ByteArray(16384)
            var bytesWritten = 0L

            while (true) {
                val bytesRead = fileStream.read(buffer)
                if (bytesRead == -1) break
                uploadStream.write(buffer, 0, bytesRead)
                bytesWritten += bytesRead
                val progress = ((bytesWritten * 100) / fileLength).toInt()
                onProgressUpdate("Pushing APK: $progress%")
            }
            fileStream.close()
            uploadStream.close()

            // Wait a small moment for OS file sync
            Thread.sleep(500)

            onProgressUpdate("Triggering package installation on watch...")
            val installStream = conn.open("shell:pm install -r $remoteTmpPath")
            val installResultBuilder = StringBuilder()
            val readBuf = ByteArray(1024)

            try {
                while (true) {
                    val bytesRead = installStream.read(readBuf, 0, readBuf.size)
                    if (bytesRead == -1) break
                    val chunk = String(readBuf, 0, bytesRead)
                    installResultBuilder.append(chunk)
                }
            } catch (e: Exception) {
                // Stream read might throw on close, ignore
            }
            installStream.close()

            val installOutput = installResultBuilder.toString()
            onProgressUpdate("Installation result: $installOutput")

            // Clean up the temp file
            onProgressUpdate("Cleaning up temporary files...")
            val cleanupStream = conn.open("shell:rm -f $remoteTmpPath")
            try {
                while (cleanupStream.read(readBuf, 0, readBuf.size) != -1) { /* drain stream */ }
            } catch (e: Exception) {}
            cleanupStream.close()

            val success = installOutput.contains("Success", ignoreCase = true)
            if (success) {
                onProgressUpdate("Successfully installed!")
                return true
            } else {
                onProgressUpdate("Installation failed: $installOutput")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
            onProgressUpdate("Error: ${e.localizedMessage}")
            return false
        }
    }

    data class WatchFile(
        val name: String,
        val isDirectory: Boolean,
        val size: Long
    )

    fun listDirectory(remotePath: String): List<WatchFile> {
        val conn = activeConnection ?: return emptyList()
        val list = mutableListOf<WatchFile>()
        
        try {
            val stream = conn.open("shell:ls -la \"$remotePath\"")
            val outputBuilder = StringBuilder()
            val buffer = ByteArray(1024)
            
            try {
                while (true) {
                    val bytesRead = stream.read(buffer, 0, buffer.size)
                    if (bytesRead == -1) break
                    outputBuilder.append(String(buffer, 0, bytesRead))
                }
            } catch (e: Exception) {}
            stream.close()

            val lines = outputBuilder.toString().split("\n")
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("total")) continue

                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 8) {
                    val isDir = trimmed.startsWith("d")
                    val size = parts[4].toLongOrNull() ?: 0L
                    
                    val name = parts.subList(7, parts.size).joinToString(" ").trim()
                    if (name == "." || name == "..") continue
                    
                    list.add(WatchFile(name, isDir, size))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing directory $remotePath", e)
        }
        return list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun deleteFile(remotePath: String): Boolean {
        val conn = activeConnection ?: return false
        try {
            val stream = conn.open("shell:rm -rf \"$remotePath\"")
            val readBuf = ByteArray(512)
            try {
                while (stream.read(readBuf, 0, readBuf.size) != -1) {}
            } catch (e: Exception) {}
            stream.close()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote file $remotePath", e)
            return false
        }
    }

    fun createDirectory(remotePath: String): Boolean {
        val conn = activeConnection ?: return false
        try {
            val stream = conn.open("shell:mkdir -p \"$remotePath\"")
            val readBuf = ByteArray(512)
            try {
                while (stream.read(readBuf, 0, readBuf.size) != -1) {}
            } catch (e: Exception) {}
            stream.close()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create remote directory $remotePath", e)
            return false
        }
    }

    fun pushFile(localFile: File, remotePath: String, onProgressUpdate: (String) -> Unit): Boolean {
        val conn = activeConnection ?: return false
        try {
            val uploadStream = conn.open("shell:cat > \"$remotePath\"")
            val fileLength = localFile.length()
            val fileStream = FileInputStream(localFile)
            val buffer = ByteArray(16384)
            var bytesWritten = 0L

            while (true) {
                val bytesRead = fileStream.read(buffer)
                if (bytesRead == -1) break
                uploadStream.write(buffer, 0, bytesRead)
                bytesWritten += bytesRead
                val progress = ((bytesWritten * 100) / fileLength).toInt()
                onProgressUpdate("Uploading: $progress%")
            }
            fileStream.close()
            uploadStream.close()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push file to $remotePath", e)
            onProgressUpdate("Error: ${e.localizedMessage}")
            return false
        }
    }

    fun runShellCommand(cmd: String): String {
        val conn = activeConnection ?: return "Error: Not connected to ADB"
        return try {
            val stream = conn.open("shell:$cmd")
            val outputBuilder = StringBuilder()
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val bytesRead = stream.read(buffer, 0, buffer.size)
                    if (bytesRead == -1) break
                    outputBuilder.append(String(buffer, 0, bytesRead))
                }
            } catch (e: Exception) {
                // Ignore stream read exceptions on close
            }
            stream.close()
            outputBuilder.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run shell command: $cmd", e)
            "Error: ${e.localizedMessage}"
        }
    }
}
