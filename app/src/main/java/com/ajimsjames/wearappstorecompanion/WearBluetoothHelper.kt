package com.ajimsjames.wearappstorecompanion

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object WearBluetoothHelper {
    private const val TAG = "WearBluetoothHelper"

    suspend fun getWatchNode(context: Context): Node? = withContext(Dispatchers.IO) {
        try {
            val nodeClient = Wearable.getNodeClient(context)
            val nodes = Tasks.await(nodeClient.connectedNodes)
            nodes.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get connected nodes", e)
            null
        }
    }

    suspend fun sendFile(context: Context, localFile: File, path: String, targetFolderOnWatch: String?, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val node = getWatchNode(context) ?: run {
            onProgress("No watch found over Bluetooth!")
            return@withContext false
        }

        val channelClient = Wearable.getChannelClient(context)
        val channelPath = if (path == "/apk_install_channel") "/apk_install_channel" else "/upload_file_channel"
        
        try {
            onProgress("Opening Bluetooth Channel...")
            val channel = Tasks.await(channelClient.openChannel(node.id, channelPath))
            val outputStream = Tasks.await(channelClient.getOutputStream(channel))

            val targetName = if (targetFolderOnWatch != null) "$targetFolderOnWatch/${localFile.name}" else localFile.name
            val nameBytes = targetName.toByteArray(Charsets.UTF_8)
            val nameLengthBytes = java.nio.ByteBuffer.allocate(4).putInt(nameBytes.size).array()
            
            outputStream.write(nameLengthBytes)
            outputStream.write(nameBytes)
            
            val fileInputStream = FileInputStream(localFile)
            val buffer = ByteArray(16384)
            val fileLength = localFile.length()
            var bytesWritten = 0L

            while (true) {
                val read = fileInputStream.read(buffer)
                if (read == -1) break
                outputStream.write(buffer, 0, read)
                bytesWritten += read
                val progress = ((bytesWritten * 100) / fileLength).toInt()
                onProgress("Bluetooth Transfer: $progress%")
            }
            outputStream.flush()
            outputStream.close()
            fileInputStream.close()

            Tasks.await(channelClient.close(channel))
            onProgress("Bluetooth Transfer Complete!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending file via channel", e)
            onProgress("Bluetooth Error: ${e.localizedMessage}")
            false
        }
    }

    suspend fun sendMessage(context: Context, path: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val node = getWatchNode(context) ?: return@withContext false
        try {
            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, path, data))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            false
        }
    }
}
