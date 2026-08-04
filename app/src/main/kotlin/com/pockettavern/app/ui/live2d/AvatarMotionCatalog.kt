package com.pockettavern.app.ui.live2d

import android.content.Context
import com.pockettavern.app.ui.screens.live2d.Live2DModelManager
import com.pockettavern.app.ui.screens.live2d.bundledLive2DModels
import org.json.JSONObject
import java.io.File

/**
 * 运行时扫描真实存在的 model3.json（内置 assets + 用户导入 filesDir），
 * 产出每个模型实际可用的动作组 / 表情 / 口型参数。绝不编造文件。
 */
data class ModelMotionInfo(
    val modelId: String,
    val motionGroups: Map<String, List<String>> = emptyMap(),
    val expressionNames: List<String> = emptyList(),
    val lipSyncParamIds: List<String> = emptyList()
) {
    val hasLipSync: Boolean get() = lipSyncParamIds.isNotEmpty()
    val motionFiles: List<Pair<String, String>> get() = motionGroups.entries.flatMap { (g, files) -> files.map { g to it } }

    fun motionIndexFor(group: String, keyword: String? = null): Int? {
        val files = motionGroups[group] ?: return null
        if (files.isEmpty()) return null
        if (keyword.isNullOrBlank()) return 0
        val k = keyword.lowercase()
        val hit = files.indexOfFirst { it.substringBeforeLast('.').lowercase().contains(k) }
        return if (hit >= 0) hit else 0
    }
}

object AvatarMotionCatalog {

    @Volatile
    private var cache: Map<String, ModelMotionInfo>? = null

    fun all(context: Context): Map<String, ModelMotionInfo> {
        cache?.let { return it }
        val result = linkedMapOf<String, ModelMotionInfo>()
        for (model in bundledLive2DModels) {
            val relative = model.modelPath.removePrefix("/assets/")
            try {
                val json = JSONObject(context.assets.open(relative).bufferedReader().use { it.readText() })
                result[model.id] = parseModel3(model.id, json)
            } catch (e: Exception) {
                com.pockettavern.app.util.DebugLogger.log("[AvatarCatalog] 读取 ${model.id} 失败: ${e.message}")
            }
        }
        val root = Live2DModelManager.rootDir(context)
        root.listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
            try {
                val metadata = JSONObject(File(dir, "aicompanion-model.json").readText())
                val manifestFile = File(dir, metadata.getString("manifest")).canonicalFile
                if (!manifestFile.exists() ||
                    !manifestFile.path.startsWith(dir.canonicalPath + File.separator)
                ) return@forEach
                val json = JSONObject(manifestFile.readText())
                result["user:${dir.name}"] = parseModel3("user:${dir.name}", json)
            } catch (_: Exception) {
            }
        }
        cache = result
        return result
    }

    fun forModel(context: Context, modelId: String): ModelMotionInfo? = all(context)[modelId]

    private fun parseModel3(modelId: String, json: JSONObject): ModelMotionInfo {
        val refs = json.optJSONObject("FileReferences") ?: JSONObject()

        val motionGroups = linkedMapOf<String, List<String>>()
        refs.optJSONObject("Motions")?.keys()?.forEach { group ->
            val files = refs.optJSONObject("Motions")
                ?.optJSONArray(group)
                ?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("File", "")?.takeIf { it.isNotBlank() }
                    }
                }
                .orEmpty()
            if (files.isNotEmpty()) motionGroups[group] = files
        }

        val expressions = refs.optJSONArray("Expressions")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("Name", "")?.takeIf { it.isNotBlank() }
                }
            }
            .orEmpty()

        val lipSync = json.optJSONArray("Groups")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    if (obj.optString("Target") == "Parameter" && obj.optString("Name") == "LipSync") {
                        obj.optJSONArray("Ids")?.let { ids ->
                            (0 until ids.length()).mapNotNull { j -> ids.optString(j).takeIf { it.isNotBlank() } }
                        } ?: emptyList()
                    } else null
                }
            }
            ?.flatten()
            .orEmpty()

        return ModelMotionInfo(
            modelId = modelId,
            motionGroups = motionGroups,
            expressionNames = expressions,
            lipSyncParamIds = lipSync
        )
    }

    fun invalidate() {
        cache = null
    }
}
