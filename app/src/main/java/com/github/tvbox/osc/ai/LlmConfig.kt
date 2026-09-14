package com.github.tvbox.osc.ai

import com.orhanobut.hawk.Hawk

/**
 * LLM配置管理
 * 支持OpenAI兼容接口，优先适配千问和豆包
 */
object LlmConfig {
    
    // 预设Provider
    enum class Provider(val displayName: String, val apiUrl: String) {
        QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
        DOUBAO("豆包", "https://ark.cn-beijing.volces.com/api/v3/chat/completions"),
        CUSTOM("自定义", "")
    }
    
    // Hawk Keys
    private const val KEY_PROVIDER = "llm_provider"
    private const val KEY_CUSTOM_URL = "llm_custom_url"
    private const val KEY_API_KEY = "llm_api_key"
    private const val KEY_MODEL_ID = "llm_model_id"
    
    /**
     * 获取当前选择的Provider
     */
    fun getProvider(): Provider {
        val name = Hawk.get(KEY_PROVIDER, Provider.QWEN.name)
        return try {
            Provider.valueOf(name)
        } catch (e: Exception) {
            Provider.QWEN
        }
    }
    
    /**
     * 设置Provider
     */
    fun setProvider(provider: Provider) {
        Hawk.put(KEY_PROVIDER, provider.name)
    }
    
    /**
     * 获取API URL（根据Provider自动选择）
     */
    fun getApiUrl(): String {
        return when (getProvider()) {
            Provider.QWEN -> Provider.QWEN.apiUrl
            Provider.DOUBAO -> Provider.DOUBAO.apiUrl
            Provider.CUSTOM -> Hawk.get(KEY_CUSTOM_URL, "")
        }
    }
    
    /**
     * 设置自定义URL
     */
    fun setCustomUrl(url: String) {
        Hawk.put(KEY_CUSTOM_URL, url)
    }
    
    /**
     * 获取API Key
     */
    fun getApiKey(): String {
        return Hawk.get(KEY_API_KEY, "")
    }
    
    /**
     * 设置API Key
     */
    fun setApiKey(key: String) {
        Hawk.put(KEY_API_KEY, key)
    }
    
    /**
     * 获取模型ID
     */
    fun getModelId(): String {
        return Hawk.get(KEY_MODEL_ID, "")
    }
    
    /**
     * 设置模型ID
     */
    fun setModelId(modelId: String) {
        Hawk.put(KEY_MODEL_ID, modelId)
    }
    
    /**
     * 检查配置是否完整
     */
    fun isConfigured(): Boolean {
        val apiUrl = getApiUrl()
        val apiKey = getApiKey()
        val modelId = getModelId()
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()
    }
    
    /**
     * 清除所有配置
     */
    fun clear() {
        Hawk.delete(KEY_PROVIDER)
        Hawk.delete(KEY_CUSTOM_URL)
        Hawk.delete(KEY_API_KEY)
        Hawk.delete(KEY_MODEL_ID)
    }
}
