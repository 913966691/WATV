package com.github.tvbox.osc.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM客户端 - OpenAI兼容接口
 * 支持千问、豆包、自定义OpenAI兼容接口
 */
class LlmClient {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    // 媒体类型
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    /**
     * 发送对话请求（带Function Calling支持）
     * @param messages 对话历史
     * @param tools 工具定义（可选）
     * @param callback 回调
     */
    fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        callback: ChatCallback
    ) {
        if (!LlmConfig.isConfigured()) {
            callback.onError("请先配置LLM参数（API Key、模型ID等）")
            return
        }
        
        try {
            // 构建请求体
            val requestBody = buildRequestBody(messages, tools)
            val apiUrl = LlmConfig.getApiUrl()
            val apiKey = LlmConfig.getApiKey()
            
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback.onError("请求失败: ${e.message}")
                }
                
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errorBody = resp.body?.string() ?: "未知错误"
                            callback.onError("API错误(${resp.code}): $errorBody")
                            return
                        }
                        
                        val responseBody = resp.body?.string()
                        if (responseBody.isNullOrBlank()) {
                            callback.onError("响应为空")
                            return
                        }
                        
                        try {
                            val result = parseResponse(responseBody)
                            callback.onSuccess(result)
                        } catch (e: Exception) {
                            callback.onError("解析响应失败: ${e.message}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            callback.onError("请求异常: ${e.message}")
        }
    }
    
    /**
     * 构建请求体
     */
    private fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?
    ): String {
        val json = JsonObject()
        json.addProperty("model", LlmConfig.getModelId())
        json.addProperty("temperature", 0.7)
        
        // 添加消息
        val messagesArray = JsonArray()
        messages.forEach { msg ->
            val msgObj = JsonObject()
            msgObj.addProperty("role", msg.role)
            msgObj.addProperty("content", msg.content)
            
            // 如果是function_call响应
            if (msg.toolCallId != null) {
                msgObj.addProperty("tool_call_id", msg.toolCallId)
                msgObj.addProperty("name", msg.name ?: "")
            }
            
            // 如果有tool_calls（LLM请求调用工具）
            if (msg.toolCalls != null) {
                val toolCallsArray = JsonArray()
                msg.toolCalls.forEach { tc ->
                    val tcObj = JsonObject()
                    tcObj.addProperty("id", tc.id)
                    tcObj.addProperty("type", "function")
                    val funcObj = JsonObject()
                    funcObj.addProperty("name", tc.name)
                    funcObj.addProperty("arguments", tc.arguments.toString())
                    tcObj.add("function", funcObj)
                    toolCallsArray.add(tcObj)
                }
                msgObj.add("tool_calls", toolCallsArray)
            }
            
            messagesArray.add(msgObj)
        }
        json.add("messages", messagesArray)
        
        // 添加工具定义
        if (tools != null && tools.isNotEmpty()) {
            val toolsArray = JsonArray()
            tools.forEach { tool ->
                val toolObj = JsonObject()
                toolObj.addProperty("type", "function")
                val funcObj = JsonObject()
                funcObj.addProperty("name", tool.name)
                funcObj.addProperty("description", tool.description)
                funcObj.add("parameters", gson.toJsonTree(tool.parameters))
                toolObj.add("function", funcObj)
                toolsArray.add(toolObj)
            }
            json.add("tools", toolsArray)
            json.addProperty("tool_choice", "auto")
        }
        
        return gson.toJson(json)
    }
    
    /**
     * 解析响应
     */
    private fun parseResponse(responseBody: String): ChatResponse {
        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val choices = json.getAsJsonArray("choices")
        
        if (choices == null || choices.size() == 0) {
            throw Exception("响应中没有choices")
        }
        
        val firstChoice = choices[0].asJsonObject
        val message = firstChoice.getAsJsonObject("message")
        
        val content = message.get("content")?.asString
        val finishReason = firstChoice.get("finish_reason")?.asString
        
        // 检查是否有tool_calls
        val toolCalls = mutableListOf<ToolCall>()
        if (message.has("tool_calls")) {
            val toolCallsArray = message.getAsJsonArray("tool_calls")
            toolCallsArray.forEach { tcElem ->
                val tcObj = tcElem.asJsonObject
                val funcObj = tcObj.getAsJsonObject("function")
                toolCalls.add(
                    ToolCall(
                        id = tcObj.get("id").asString,
                        name = funcObj.get("name").asString,
                        arguments = gson.fromJson(funcObj.get("arguments").asString, Map::class.java) as Map<String, Any>
                    )
                )
            }
        }
        
        return ChatResponse(
            content = content,
            toolCalls = toolCalls.ifEmpty { null },
            finishReason = finishReason
        )
    }
    
    /**
     * 回调接口
     */
    interface ChatCallback {
        fun onSuccess(response: ChatResponse)
        fun onError(error: String)
    }
}

/**
 * 对话消息
 */
data class ChatMessage(
    val role: String, // "user", "assistant", "system", "tool"
    val content: String?,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String?, toolCalls: List<ToolCall>? = null) = 
            ChatMessage("assistant", content, toolCalls = toolCalls)
        fun tool(toolCallId: String, name: String, content: String) = 
            ChatMessage("tool", content, name = name, toolCallId = toolCallId)
    }
}

/**
 * 对话响应
 */
data class ChatResponse(
    val content: String?,
    val toolCalls: List<ToolCall>?,
    val finishReason: String?
) {
    fun hasToolCalls(): Boolean = toolCalls != null && toolCalls!!.isNotEmpty()
}

/**
 * 工具调用
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any>
)

/**
 * 工具定义
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)
