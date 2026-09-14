package com.github.tvbox.osc.ai

import android.content.Intent
import android.os.Bundle
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.util.SourceUtil
import com.orhanobut.hawk.Hawk
import kotlinx.coroutines.*
import java.util.*

/**
 * 视频AI助手引擎
 * 处理Function Calling的工具执行
 */
class VideoAssistantEngine(private val activity: MainActivity) {
    
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
                        "description" to "集数索引，从0开始。-1表示续播（使用上次进度）"
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
- 播放时，episode_index=-1表示续播（使用上次进度）
- 续播时，先从历史记录中找到对应视频，然后使用play_video播放
- 如果用户说"继续看XXX"或"接着看XXX"，先搜索历史记录，找到后直接播放
- 如果用户说"看最新的XXX"或"看最新的第X集"，先搜索，然后播放最后一集

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
            callback.onError("重试次数过多，请检查LLM配置")
            return
        }
        
        val client = LlmClient()
        val messages = conversationHistory.takeLast(maxHistorySize)
        
        client.chat(messages, tools, object : LlmClient.ChatCallback {
            override fun onSuccess(response: ChatResponse) {
                scope.launch {
                    // 如果有工具调用
                    if (response.hasToolCalls()) {
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
                        
                        // 继续调用LLM处理工具结果
                        callLLM(callback, retryCount + 1)
                    } else {
                        // 普通回复
                        val content = response.content ?: "抱歉，我没有理解您的意思"
                        conversationHistory.add(ChatMessage.assistant(content))
                        callback.onResponse(content)
                    }
                }
            }
            
            override fun onError(error: String) {
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
     * 执行搜索视频
     */
    private suspend fun executeSearchVideos(arguments: Map<String, Any>): String {
        return withContext(Dispatchers.IO) {
            try {
                val keyword = arguments["keyword"] as? String ?: return@withContext """{"error": "缺少keyword参数"}"""
                val type = arguments["type"] as? String ?: "all"
                
                // 获取所有可用源
                val sources = SourceUtil.getSearchSources()
                if (sources.isEmpty()) {
                    return@withContext """{"error": "没有可用的搜索源"}"""
                }
                
                // 执行搜索（简化版，实际应该异步执行）
                val results = mutableListOf<Map<String, Any>>()
                
                // 这里暂时返回提示，实际搜索逻辑较复杂，需要调用SourceViewModel
                // 后续会实现完整的搜索逻辑
                return@withContext """{"message": "搜索'$keyword'中...", "keyword": "$keyword", "type": "$type"}"""
                
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
                val limit = (arguments["limit"] as? Int) ?: 10
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
                        "update_time" to vodInfo.updateTime
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
                val limit = (arguments["limit"] as? Int) ?: 10
                
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
     * 执行播放视频
     */
    private suspend fun executePlayVideo(arguments: Map<String, Any>): String {
        return withContext(Dispatchers.IO) {
            try {
                val vodId = arguments["vod_id"] as? String ?: return@withContext """{"error": "缺少vod_id参数"}"""
                val sourceKey = arguments["source_key"] as? String ?: return@withContext """{"error": "缺少source_key参数"}"""
                val episodeIndex = (arguments["episode_index"] as? Int) ?: -1
                
                // 构造VodInfo
                val vodInfo = VodInfo()
                vodInfo.id = vodId
                vodInfo.sourceKey = sourceKey
                vodInfo.playIndex = if (episodeIndex >= 0) episodeIndex else 0
                vodInfo.playFlag = Hawk.get("last_play_flag_$vodId", "")
                
                // 保存到当前引擎
                com.github.tvbox.osc.base.App.getInstance().vodInfo = vodInfo
                
                // 启动播放
                scope.launch(Dispatchers.Main) {
                    val bundle = Bundle()
                    bundle.putString("id", vodId)
                    bundle.putString("sourceKey", sourceKey)
                    
                    val intent = Intent(activity, DetailActivity::class.java)
                    intent.putExtras(bundle)
                    activity.startActivity(intent)
                }
                
                return@withContext """{"success": true, "message": "正在播放..."}"""
                
            } catch (e: Exception) {
                """{"error": "播放失败: ${e.message}"}"""
            }
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
