package com.pockettavern.app.ui.screens.live2d

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

object Live2DModelManager {
    private const val MAX_FILES = 1200
    private const val MAX_UNCOMPRESSED_BYTES = 250L * 1024L * 1024L

    fun rootDir(context: Context): File = File(context.filesDir, "live2d-models").apply { mkdirs() }

    fun allModels(context: Context): List<Live2DModel> = bundledLive2DModels + importedModels(context)

    private fun importedModels(context: Context): List<Live2DModel> = rootDir(context)
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .mapNotNull { directory ->
            runCatching {
                val metadata = JSONObject(File(directory, "aicompanion-model.json").readText())
                val relativeManifest = metadata.getString("manifest")
                val manifestFile = File(directory, relativeManifest).canonicalFile
                if (!manifestFile.exists() || !manifestFile.path.startsWith(directory.canonicalPath + File.separator)) {
                    return@runCatching null
                }
                Live2DModel(
                    id = "user:${directory.name}",
                    displayName = metadata.optString("name", manifestFile.nameWithoutExtension),
                    modelPath = "/live2d-user/${directory.name}/${relativeManifest.replace(File.separatorChar, '/')}"
                )
            }.getOrNull()
        }
        .sortedBy { it.displayName.lowercase() }

    suspend fun importZip(context: Context, uri: Uri): Result<Live2DModel> = withContext(Dispatchers.IO) {
        runCatching {
            val id = UUID.randomUUID().toString()
            val destination = File(rootDir(context), id).apply { mkdirs() }.canonicalFile
            var fileCount = 0
            var totalBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            try {
                val source = context.contentResolver.openInputStream(uri)
                    ?: error("无法读取所选文件")
                ZipInputStream(source.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        fileCount++
                        require(fileCount <= MAX_FILES) { "压缩包文件数量过多" }
                        val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                        require(normalizedName.isNotBlank()) { "压缩包包含无效路径" }
                        val output = File(destination, normalizedName).canonicalFile
                        require(output.path.startsWith(destination.path + File.separator)) { "压缩包包含越界路径" }
                        if (entry.isDirectory) {
                            output.mkdirs()
                        } else {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { out ->
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read <= 0) break
                                    totalBytes += read
                                    require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "模型解压后超过 250MB" }
                                    out.write(buffer, 0, read)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }

                val manifests = destination.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".model3.json", ignoreCase = true) }
                    .toList()
                require(manifests.isNotEmpty()) { "没有找到 .model3.json 文件" }
                val manifest = manifests.minBy { it.relativeTo(destination).invariantSeparatorsPath.count { c -> c == '/' } }
                val relativeManifest = manifest.relativeTo(destination).invariantSeparatorsPath
                val displayName = manifest.name.substringBefore(".model3", manifest.nameWithoutExtension)
                    .ifBlank { "导入的皮套" }
                    .take(48)
                File(destination, "aicompanion-model.json").writeText(
                    JSONObject()
                        .put("name", displayName)
                        .put("manifest", relativeManifest)
                        .toString(2)
                )
                Live2DModel(
                    id = "user:$id",
                    displayName = displayName,
                    modelPath = "/live2d-user/$id/$relativeManifest"
                )
            } catch (error: Throwable) {
                destination.deleteRecursively()
                throw error
            }
        }
    }
}

