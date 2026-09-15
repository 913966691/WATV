package com.github.tvbox.osc.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.ui.dialog.BaseDialog
import com.google.android.material.snackbar.Snackbar
import android.Manifest
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import java.util.Locale
import android.widget.Toast

/**
 * AI助手对话框
 */
class AiAssistantDialog(context: Context) : BaseDialog(context) {

    // 构造器参数 context 在此处即调用方传入的 MainActivity 实例(Dialog 内部会被包成
    // ContextThemeWrapper, getContext() 不再是 MainActivity)。直接在此捕获活动引用,
    // 供引擎使用,避免后续强转失败。
    private val activity = context as? MainActivity

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var ivSend: ImageView
    private lateinit var ivClose: ImageView
    private lateinit var ivSettings: ImageView
    private lateinit var llInputArea: LinearLayout
    private lateinit var flLoading: View
    private lateinit var ivMic: ImageView
    private lateinit var tvSpeechStatus: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    private lateinit var messageAdapter: MessageAdapter
    private var engine: VideoAssistantEngine? = null
    
    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ai_assistant, null)
        setContentView(view)
        
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        
        initView(view)
        initEngine()
        setupListeners()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须让窗口占满屏幕高度，否则 ConstraintLayout 里 0dp 的 RecyclerView 会塌陷为 0
        val window = window ?: return
        val lp = window.attributes
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        window.attributes = lp
        Log.d("WATV_AI", "onCreate: 设置对话框高度 MATCH_PARENT")
    }
    
    private fun initView(view: View) {
        rvMessages = view.findViewById(R.id.rv_messages)
        etInput = view.findViewById(R.id.et_input)
        ivSend = view.findViewById(R.id.iv_send)
        ivClose = view.findViewById(R.id.iv_close)
        ivSettings = view.findViewById(R.id.iv_settings)
        llInputArea = view.findViewById(R.id.ll_input_area)
        flLoading = view.findViewById(R.id.fl_loading)
        ivMic = view.findViewById(R.id.iv_mic)
        tvSpeechStatus = view.findViewById(R.id.tv_speech_status)

        initSpeech()
        
        // 初始化消息列表
        // 卡片点击：带着片源信息跳转到详情页（不直接起播，交由详情页选择集数/线路）
        messageAdapter = MessageAdapter { map ->
            val vodId = map["vod_id"] as? String ?: return@MessageAdapter
            val sourceKey = map["source_key"] as? String ?: return@MessageAdapter
            val intent = Intent(activity, DetailActivity::class.java).apply {
                putExtra("id", vodId)
                putExtra("sourceKey", sourceKey)
            }
            activity?.startActivity(intent)
            dismiss()
        }
        rvMessages.layoutManager = LinearLayoutManager(context)
        rvMessages.adapter = messageAdapter
        
        // 添加欢迎消息
        appendMessage(MessageItem(
            role = MessageRole.ASSISTANT,
            content = "你好！我是视频助手，可以帮你搜索、播放、续播视频。试试说\"找一下流浪地球\"或\"继续看庆余年\"。"
        ))
    }

    /**
     * 追加消息并自动滚动到底部，确保新消息始终可见
     */
    private fun appendMessage(item: MessageItem) {
        messageAdapter.addMessage(item)
        rvMessages.post {
            val last = messageAdapter.itemCount - 1
            if (last >= 0) rvMessages.scrollToPosition(last)
        }
    }
    
    private fun initEngine() {
        // 必须由 MainActivity 上下文创建（入口处需传 MainActivity）
        engine = activity?.let { VideoAssistantEngine(it) }
    }
    
    private fun setupListeners() {
        ivClose.setOnClickListener {
            dismiss()
        }
        
        ivSettings.setOnClickListener {
            showSettingsDialog()
        }
        
        ivSend.setOnClickListener {
            sendMessage()
        }

        ivMic.setOnClickListener {
            toggleSpeechInput()
        }

        tvSpeechStatus.setOnClickListener {
            stopSpeech()
        }
        
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                return@setOnEditorActionListener true
            }
            false
        }
    }
    
    private fun sendMessage() {
        val input = etInput.text.toString().trim()
        if (input.isEmpty()) return

        Log.d("WATV_AI", "sendMessage: input='$input'")

        // 隐藏键盘
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        
        // 添加用户消息
        appendMessage(MessageItem(
            role = MessageRole.USER,
            content = input
        ))
        
        // 清空输入框
        etInput.text.clear()
        
        // 显示加载状态
        showLoading(true)
        
        // 检查LLM配置
        if (!LlmConfig.isConfigured()) {
            Log.w("WATV_AI", "LLM 未配置，终止请求")
            showLoading(false)
            showMessage("请先配置LLM参数", "点击右上角设置，填写 API 地址 / Key / 模型ID")
            return
        }

        if (engine == null) {
            Log.e("WATV_AI", "engine 为 null，无法处理请求")
            showLoading(false)
            showMessage("初始化失败", "请以 Activity 上下文打开本对话框")
            return
        }

        Log.d("WATV_AI", "调用引擎处理输入，provider=${LlmConfig.getProvider().name} model=${LlmConfig.getModelId()}")

        // 调用引擎处理用户输入
        engine!!.processInput(input, object : VideoAssistantEngine.ProcessCallback {
            override fun onResponse(response: String) {
                Log.d("WATV_AI", "onResponse: '${response.take(80)}'")
                showLoading(false)
                appendMessage(MessageItem(MessageRole.ASSISTANT, response))
            }

            override fun onError(error: String) {
                Log.e("WATV_AI", "onError: $error")
                showLoading(false)
                appendMessage(MessageItem(MessageRole.SYSTEM, "⚠️ $error"))
                Snackbar.make(rvMessages, "⚠️ $error", Snackbar.LENGTH_LONG).show()
            }

            override fun onSearchResults(results: List<Map<String, Any>>) {
                Log.d("WATV_AI", "onSearchResults: ${results.size} 条")
                if (results.isEmpty()) return
                appendMessage(MessageItem(MessageRole.SEARCH_RESULTS, results = results))
            }
        })
    }
    
    private fun showLoading(show: Boolean) {
        flLoading.visibility = if (show) View.VISIBLE else View.GONE
        ivSend.isEnabled = !show
        etInput.isEnabled = !show
    }
    
    private fun showMessage(title: String, message: String) {
        Snackbar.make(rvMessages, "$title: $message", Snackbar.LENGTH_LONG).show()
    }
    
    /**
     * 初始化语音识别器；设备不支持或上下文缺失时禁用麦克风按钮
     */
    private fun initSpeech() {
        val act = activity
        if (act == null) {
            disableMic()
            Log.d("WATV_AI", "initSpeech: 上下文缺失，禁用语音按钮")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            Log.d("WATV_AI", "initSpeech: 内联识别不可用，将使用系统 RecognizerIntent 兜底")
            return
        }
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(act).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        showSpeechStatus()
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        finishSpeech()
                        val msg = getSpeechErrorMessage(error)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        Log.w("WATV_AI", "语音识别错误: $msg")
                    }
                    override fun onResults(results: Bundle?) {
                        fillSpeechText(results)
                        finishSpeech()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        fillSpeechText(partialResults)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            disableMic()
            Log.e("WATV_AI", "createSpeechRecognizer 失败: ${e.message}")
        }
    }

    private fun disableMic() {
        ivMic.isEnabled = false
        ivMic.alpha = 0.4f
    }

    /**
     * 点击麦克风：切换录音状态（未录音则启动，录音中则停止）
     */
    private fun toggleSpeechInput() {
        if (isListening) {
            stopSpeech()
            return
        }
        val act = activity ?: run {
            Toast.makeText(context, "语音功能需要 Activity 上下文", Toast.LENGTH_SHORT).show()
            return
        }
        if (SpeechRecognizer.isRecognitionAvailable(act)) {
            // 内联识别可用，走实时识别（体验更好）
            if (XXPermissions.isGranted(act, Manifest.permission.RECORD_AUDIO)) {
                startListening()
            } else {
                XXPermissions.with(act)
                    .permission(Manifest.permission.RECORD_AUDIO)
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: List<String>, all: Boolean) {
                            startListening()
                        }
                        override fun onDenied(permissions: List<String>, never: Boolean) {
                            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
                        }
                    })
            }
        } else {
            // 兜底：调起系统语音输入 Activity（澎湃 OS / 无内联服务设备）
            Log.d("WATV_AI", "toggleSpeechInput: 内联不可用，尝试系统 RecognizerIntent 兜底")
            if (!act.startSpeechRecognition()) {
                Toast.makeText(context, "当前设备未提供系统语音输入，请使用键盘语音输入", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListening() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        isListening = true
        showSpeechStatus()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
        Log.d("WATV_AI", "startListening: 启动语音识别")
    }

    private fun stopSpeech() {
        if (!isListening) return
        speechRecognizer?.stopListening()
        finishSpeech()
        Log.d("WATV_AI", "stopSpeech: 停止语音识别")
    }

    /**
     * 把识别结果（部分或最终）回填到输入框
     */
    private fun fillSpeechText(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val text = matches[0]
            etInput.setText(text)
            etInput.setSelection(text.length)
        }
    }

    private fun showSpeechStatus() {
        tvSpeechStatus.visibility = View.VISIBLE
    }

    private fun hideSpeechStatus() {
        tvSpeechStatus.visibility = View.GONE
    }

    private fun finishSpeech() {
        isListening = false
        hideSpeechStatus()
    }

    private fun getSpeechErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
            SpeechRecognizer.ERROR_CLIENT -> "语音识别出错"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限不足"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误，请检查网络"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍候"
            SpeechRecognizer.ERROR_SERVER -> "识别服务异常"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
            else -> "语音识别失败($error)"
        }
    }

    /**
     * MainActivity 的 RecognizerIntent 兜底返回后，把识别文本回填到输入框
     */
    fun fillInput(text: String) {
        if (!isShowing) return
        etInput.setText(text)
        etInput.setSelection(text.length)
        etInput.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etInput, 0)
    }

    private fun showSettingsDialog() {
        LlmSettingsDialog(context).show()
    }
    
    override fun show() {
        super.show()
        // 自动弹出键盘
        etInput.postDelayed({
            etInput.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etInput, 0)
        }, 200)
    }
    
    override fun dismiss() {
        // 隐藏键盘
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        // 释放语音识别器，避免 Activity 销毁后泄漏
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.dismiss()
    }
}

/**
 * 消息角色
 */
enum class MessageRole {
    USER, ASSISTANT, SYSTEM, SEARCH_RESULTS
}

/**
 * 消息项
 */
data class MessageItem(
    val role: MessageRole,
    val content: String = "",
    val results: List<Map<String, Any>> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 消息列表适配器
 */
class MessageAdapter(
    private val onResultClick: (Map<String, Any>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    private val messages = mutableListOf<MessageItem>()
    
    fun addMessage(message: MessageItem) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
    
    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }
    
    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            MessageRole.USER -> 0
            MessageRole.ASSISTANT -> 1
            MessageRole.SYSTEM -> 2
            MessageRole.SEARCH_RESULTS -> 3
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (viewType) {
            0 -> R.layout.item_message_user
            1 -> R.layout.item_message_assistant
            2 -> R.layout.item_message_system
            else -> R.layout.item_message_search_results
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (message.role == MessageRole.SEARCH_RESULTS) {
            bindSearchResults(holder.itemView, message.results)
            return
        }
        holder.itemView.findViewById<TextView>(R.id.tv_content)?.text = message.content
    }

    private fun bindSearchResults(itemView: View, results: List<Map<String, Any>>) {
        val container = itemView.findViewById<LinearLayout>(R.id.card_container) ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        results.forEach { map ->
            val card = inflater.inflate(R.layout.item_search_card, container, false)
            val title = (map["title"] as? String) ?: "未知片源"
            val sourceKey = (map["source_key"] as? String) ?: ""
            val remark = (map["remark"] as? String) ?: ""
            val meta = buildString {
                if (sourceKey.isNotEmpty()) append("源: $sourceKey")
                if (remark.isNotEmpty()) {
                    if (isNotEmpty()) append("  ")
                    append(remark)
                }
            }
            card.findViewById<TextView>(R.id.tv_title).text = title
            card.findViewById<TextView>(R.id.tv_meta).text = meta.ifEmpty { "点击查看详情" }
            card.setOnClickListener { onResultClick(map) }
            container.addView(card)
        }
    }
    
    override fun getItemCount(): Int = messages.size
    
    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
