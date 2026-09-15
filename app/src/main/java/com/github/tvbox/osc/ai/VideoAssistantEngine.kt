package com.github.tvbox.osc.ai

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.orhanobut.hawk.Hawk
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.*

/**
 * 视频AI助手引擎
 * 处理Function Calling的工具执行
 */
class VideoAssistantEngine(private val activity: MainActivity) {
    
    companion object {
        // 失败源短期黑名单：记录源 key -> 上次失败时间戳，冷却期内跳过该源
        private val failedSources = mutableMapOf<String, Long>()
        private const val SOURCE_COOLDOWN_MS = 5 * 60 * 1000L // 5 分钟

        @Synchronized
        fun isSourceInCooldown(key: String): Boolean {
            val last = failedSources[key] ?: return false
            return System.currentTimeMillis() - last < SOURCE_COOLDOWN_MS
        }

        @Synchronized
        fun markSourceFailed(key: String) {
            failedSources[key] = System.currentTimeMillis()
        }

        @Synchronized
        fun markSourceSucceeded(key: String) {
            failedSources.remove(key)
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 工具定义
    val tools = listOf(
        ToolDefinition(
            name = "search_videos",
            description = "搜索视频内容，根据关键词查找片源",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "keyword" to mapOf(
                        "type" to "string",
                        "description" to "搜索关键词，如电影名、电视剧名等"
                    ),
                    "type" to mapOf(
                        "type" to "string",
                        "enum" to listOf("movie", "tv", "all"),
                        "description" to "内容类型：movie=电影，tv=电视剧，all=全部"
                    )
                ),
                "required" to listOf("keyword")
            )
        ),
        ToolDefinition(
            name = "get_play_history",
            description = "获取用户的播放历史记录",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "limit" to mapOf(
                        "type" to "integer",
                        "description" to "返回记录数量，默认10"
                    ),
                    "title" to mapOf(
                        "type" to "string",
                        "description" to "按标题过滤历史记录"
                    )
                )
            )
        ),
        ToolDefinition(
            name = "get_favorites",
            description = "获取用户的收藏列表",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "limit" to mapOf(
                        "type" to "integer",
                        "description" to "返回数量，默认10"
                    )
                )
            )
        ),
        ToolDefinition(
            name = "play_video",
            description = "播放指定的视频，可以指定集数",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "vod_id" to mapOf(
                        "type" to "string",
                        "description" to "视频ID（从搜索结果或历史记录中获取）"
                    ),
                    "source_key" to mapOf(
                        "type" to "string",
                        "description" to "片源key（从搜索结果或历史记录中获取）"
                    ),
                    "episode_index" to mapOf(
                        "type" to "integer",
                        "description" to "集数索引。-1=续播(使用历史进度)；-2=最新一集；>=0=指定集(从0开始)"
                    ),
                    "play_flag" to mapOf(
                        "type" to "string",
                        "description" to "可选，播放线路名称（如'线路1'）。不填则用默认线路"
                    )
                ),
                "required" to listOf("vod_id", "source_key")
            )
        ),
        ToolDefinition(
            name = "add_to_favorites",
            description = "将视频添加到收藏",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "vod_id" to mapOf(
                        "type" to "string",
                        "description" to "视频ID"
                    ),
                    "source_key" to mapOf(
                        "type" to "string",
                        "description" to "片源key"
                    ),
                    "title" to mapOf(
                        "type" to "string",
                        "description" to "视频标题"
                    )
                ),
                "required" to listOf("vod_id", "source_key", "title")
            )
        )
    )
    
    // 对话历史（保持最近10轮）
    private val conversationHistory = mutableListOf<ChatMessage>()
    private val maxHistorySize = 20
    
    // 系统提示词
    private val systemPrompt = """
你是蛙TV的视频助手，一个智能的影视搜索和播放助手。

你的主要功能：
1. 帮助用户搜索电影、电视剧等视频内容
2. 查询播放历史和收藏
3. 播放指定视频（可以指定集数）
4. 续播上次观看的内容
5. 管理收藏列表

使用工具时的注意事项：
- 搜索时，如果用户没有指定类型，默认搜索全部
- 搜索后会返回多个片源，每个含 vod_id 和 source_key，播放时必须带上这两个值
- 播放时 episode_index 含义：-1=续播(用历史进度)；-2=最新一集；>=0=指定集(从0开始)
- 续播时，先调用 get_play_history 找到对应视频，再用 play_video 播放(episode_index=-1)
- 如果用户说"继续看XXX"或"接着看XXX"，先查历史，找到后播放
- 如果用户说"看最新的XXX"，先 search_videos，再用 play_video(episode_index=-2) 播放最新一集
- 如果用户说"看XXX第N集"，先 search_videos，再用 play_video(episode_index=N-1) 播放指定集
- 播放前请简短告知用户即将播放的片名和集数

回复风格：
- 简洁友好，不超过100字
- 适当使用emoji增加亲切感
- 如果执行成功，告诉用户操作结果
- 如果需要更多信息，礼貌地询问
""".trimIndent()
    
    init {
        conversationHistory.add(ChatMessage.system(systemPrompt))
    }
    
    /**
     * 处理用户输入
     */
    fun processInput(userInput: String, callback: ProcessCallback) {
        // 添加用户消息
        conversationHistory.add(ChatMessage.user(userInput))
        
        // 调用LLM
        callLLM(callback)
    }
    
    /**
     * 调用LLM
     */
    private fun callLLM(callback: ProcessCallback, retryCount: Int = 0) {
        if (retryCount > 3) {
            Log.e("WATV_AI", "重试次数过多，终止")
            callback.onError("重试次数过多，请检查LLM配置")
            return
        }

        Log.d("WATV_AI", "callLLM 入口: retry=$retryCount historySize=${conversationHistory.size}")

        val client = LlmClient()
        val messages = conversationHistory.takeLast(maxHistorySize)

        client.chat(messages, tools, object : LlmClient.ChatCallback {
            override fun onSuccess(response: ChatResponse) {
                scope.launch {
                    // 如果有工具调用
                    if (response.hasToolCalls()) {
                        Log.d("WATV_AI", "LLM 返回工具调用: ${response.toolCalls!!.map { it.name }}")
                        // 添加助手消息（包含tool_calls）
                        conversationHistory.add(
                            ChatMessage.assistant(response.content, response.toolCalls)
                        )
                        
                        // 执行所有工具调用
                        val toolResults = executeToolCalls(response.toolCalls!!)
                        
                        // 添加工具结果到历史
                        toolResults.forEach { (toolCallId, name, result) ->
                            conversationHistory.add(
                                ChatMessage.tool(toolCallId, name, result)
                            )
                        }

                        // 若本轮包含搜索结果，回传给对话框以卡片形式展示
                        toolResults.forEach { (_, name, result) ->
                            if (name == "search_videos") {
                                parseSearchResults(result)?.let {
                                    Log.d("WATV_AI", "回传搜索结果卡片: ${it.size} 条")
                                    callback.onSearchResults(it)
                                }
                            }
                        }

                        // 继续调用LLM处理工具结果
                        callLLM(callback, retryCount + 1)
                    } else {
                        // 普通回复
                        val content = response.content ?: "抱歉，我没有理解您的意思"
                        Log.d("WATV_AI", "LLM 普通回复: '${(content).take(60)}'")
                        conversationHistory.add(ChatMessage.assistant(content))
                        callback.onResponse(content)
                    }
                }
            }
            
            override fun onError(error: String) {
                Log.e("WATV_AI", "callLLM onError: $error")
                scope.launch {
                    callback.onError(error)
                }
            }
        })
    }
    
    /**
     * 执行工具调用
     */
    private suspend fun executeToolCalls(toolCalls: List<ToolCall>): List<Triple<String, String, String>> {
        val results = mutableListOf<Triple<String, String, String>>()
        
        toolCalls.forEach { toolCall ->
            val result = when (toolCall.name) {
                "search_videos" -> executeSearchVideos(toolCall.arguments)
                "get_play_history" -> executeGetHistory(toolCall.arguments)
                "get_favorites" -> executeGetFavorites(toolCall.arguments)
                "play_video" -> executePlayVideo(toolCall.arguments)
                "add_to_favorites" -> executeAddToFavorites(toolCall.arguments)
                else -> """{"error": "未知工具: ${toolCall.name}"}"""
            }
            results.add(Triple(toolCall.id, toolCall.name, result))
        }
        
        return results
    }
    
    /**
     * 执行搜索视频：遍历所有已配置源，聚合结果
     */
    private suspend fun executeSearchVideos(arguments: Map<String, Any>): String {
        return withContext(Dispatchers.IO) {
            try {
                val keyword = arguments["keyword"] as? String
                    ?: return@withContext """{"error": "缺少keyword参数"}"""
                val vm = SourceViewModel()
                val sources = ApiConfig.get().getSourceBeanList()
                if (sources.isEmpty()) {
                    return@withContext """{"count": 0, "message": "当前没有可用的视频源，请先在设置中配置订阅源"}"""
                }
                val results = mutableListOf<Map<String, Any>>()
                for (source in sources) {
                    val key = source.getKey()
                    // 冷却期内跳过此前连续失败的源，避免反复请求已失效的源拖慢搜索
                    if (isSourceInCooldown(key)) {
                        Log.d("WATV_AI", "搜索源[$key]处于冷却期，跳过")
                        continue
                    }
                    try {
                        val list = vm.searchSync(key, keyword)
                        if (!list.isNullOrEmpty()) {
                            markSourceSucceeded(key)
                            for (v in list) {
                                if (results.size >= 20) break
                                results.add(mapOf(
                                    "vod_id" to (v.id ?: ""),
                                    "source_key" to (v.sourceKey ?: key),
                                    "title" to (v.name ?: ""),
                                    "remark" to (v.note ?: "")
                                ))
                            }
                        }
                    } catch (e: Throwable) {
                        // 单个源失败不影响其他源；记录源标识与原因便于排查失效源
                        Log.w("WATV_AI", "搜索源[$key]失败: ${e.message}")
                        markSourceFailed(key)
                    }
                }
                if (results.isEmpty()) {
                    return@withContext com.google.gson.Gson().toJson(
                        mapOf("count" to 0, "message" to "未找到与'$keyword'相关的片源")
                    )
                }
                return@withContext com.google.gson.Gson().toJson(
                    mapOf("count" to results.size, "results" to results)
                )
            } catch (e: Exception) {
                """{"error": "搜索失败: ${e.message}"}"""
            }
        }
    }
    
    /**
     * 执行获取播放历史
     */
    private suspend fun executeGetHistory(arguments: Map<String, Any>): String {
        return withContext(Dispatchers.IO) {
            try {
                val limit = toIntArg(arguments["limit"], 10)
                val titleFilter = arguments["title"] as? String
                
                val history = RoomDataManger.getAllVodRecord(100)
                
                // 过滤
                val filtered = if (titleFilter != null) {
                    history.filter { it.name?.contains(titleFilter, ignoreCase = true) == true }
                } else {
                    history
                }
                
                // 转换为简单格式
                val result = filtered.take(limit).map { vodInfo ->
                    mapOf(
                        "vod_id" to vodInfo.id,
                        "source_key" to vodInfo.sourceKey,
                        "title" to vodInfo.name,
                        "episode" to (vodInfo.playIndex + 1),
                        "update_time" to vodInfo.last
                    )
                }
                
                return@withContext com.google.gson.Gson().toJson(mapOf(
                    "count" to result.size,
                    "history" to result
                ))
                
            } catch (e: Exception) {
                """{"error": "获取历史记录失败: ${e.message}"}"""
            }
        }
    }
    
    /**
     * 执行获取收藏
     */
    private suspend fun executeGetFavorites(arguments: Map<String, Any>): String {
        return withContext(Dispatchers.IO) {
            try {
                val limit = toIntArg(arguments["limit"], 10)

                val favorites = RoomDataManger.getAllVodCollect()
                
                val result = favorites.take(limit).map { collect ->
                    mapOf(
                        "vod_id" to collect.vodId,
                        "source_key" to collect.sourceKey,
                        "title" to collect.name,
                        "pic" to collect.pic,
                        "update_time" to collect.updateTime
                    )
                }
                
                return@withContext com.google.gson.Gson().toJson(mapOf(
                    "count" to result.size,
                    "favorites" to result
                ))
                
            } catch (e: Exception) {
                """{"error": "获取收藏失败: ${e.message}"}"""
            }
        }
    }
    
    /**
     * 执行播放视频：通过 DetailActivity 的 autoPlay 意图直接起播
     * episode_index: -1=续播, -2=最新, >=0=指定集
     */
    private suspend fun executePlayVideo(arguments: Map<String, Any>): String {
        return withContext(Dispatchers.IO) {
            try {
                val vodId = arguments["vod_id"] as? String
                    ?: return@withContext """{"error": "缺少vod_id参数"}"""
                val sourceKey = arguments["source_key"] as? String
                    ?: return@withContext """{"error": "缺少source_key参数"}"""
                val episodeIndex = toIntArg(arguments["episode_index"], -1)
                val playFlag = arguments["play_flag"] as? String?

                withContext(Dispatchers.Main) {
                    val intent = Intent(activity, DetailActivity::class.java).apply {
                        putExtra("id", vodId)
                        putExtra("sourceKey", sourceKey)
                        putExtra("autoPlay", true)
                        putExtra("playIndex", episodeIndex)
                        if (!playFlag.isNullOrEmpty()) putExtra("playFlag", playFlag)
                    }
                    activity.startActivity(intent)
                }

                return@withContext """{"success": true, "message": "正在播放..."}"""
            } catch (e: Exception) {
                """{"error": "播放失败: ${e.message}"}"""
            }
        }
    }

    /**
     * 将 LLM 返回的参数统一转为 Int（Gson 解析 Map 时数字可能是 Double）
     */
    private fun toIntArg(value: Any?, default: Int): Int {
        return when (value) {
            is Int -> value
            is Double -> value.toInt()
            is Float -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    /**
     * 从 search_videos 工具返回的 JSON 中提取 results 列表（解析失败或无结果返回 null）
     */
    private fun parseSearchResults(json: String): List<Map<String, Any>>? {
        return try {
            val root = Gson().fromJson(json, Map::class.java) as? Map<*, *>
            val list = root?.get("results") as? List<*>
            if (list.isNullOrEmpty()) null else list.filterIsInstance<Map<String, Any>>()
        } catch (_: Exception) {
            null
        }
    }
    
    /**
     * 执行添加到收藏
     */
    private suspend fun executeAddToFavorites(arguments: Map<String, Any>): String {
        return withContext(Dispatchers.IO) {
            try {
                val vodId = arguments["vod_id"] as? String ?: return@withContext """{"error": "缺少vod_id参数"}"""
                val sourceKey = arguments["source_key"] as? String ?: return@withContext """{"error": "缺少source_key参数"}"""
                val title = arguments["title"] as? String ?: return@withContext """{"error": "缺少title参数"}"""
                
                val vodInfo = VodInfo()
                vodInfo.id = vodId
                vodInfo.sourceKey = sourceKey
                vodInfo.name = title
                
                RoomDataManger.insertVodCollect(sourceKey, vodInfo)
                
                return@withContext """{"success": true, "message": "已添加到收藏: $title"}"""
                
            } catch (e: Exception) {
                """{"error": "添加收藏失败: ${e.message}"}"""
            }
        }
    }
    
    /**
     * 清除对话历史
     */
    fun clearHistory() {
        conversationHistory.clear()
        conversationHistory.add(ChatMessage.system(systemPrompt))
    }
    
    /**
     * 处理回调
     */
    interface ProcessCallback {
        fun onResponse(response: String)
        fun onError(error: String)
        fun onSearchResults(results: List<Map<String, Any>>)
    }
}
