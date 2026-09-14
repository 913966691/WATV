package com.github.tvbox.osc.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.github.tvbox.osc.ui.dialog.BaseDialog
import com.google.android.material.snackbar.Snackbar

/**
 * AI助手对话框
 */
class AiAssistantDialog(context: Context) : BaseDialog(context) {
    
    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var ivSend: ImageView
    private lateinit var ivClose: ImageView
    private lateinit var ivSettings: ImageView
    private lateinit var llInputArea: LinearLayout
    private lateinit var flLoading: View
    
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var engine: VideoAssistantEngine
    
    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ai_assistant, null)
        setContentView(view)
        
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        
        initView(view)
        initEngine()
        setupListeners()
    }
    
    private fun initView(view: View) {
        rvMessages = view.findViewById(R.id.rv_messages)
        etInput = view.findViewById(R.id.et_input)
        ivSend = view.findViewById(R.id.iv_send)
        ivClose = view.findViewById(R.id.iv_close)
        ivSettings = view.findViewById(R.id.iv_settings)
        llInputArea = view.findViewById(R.id.ll_input_area)
        flLoading = view.findViewById(R.id.fl_loading)
        
        // 初始化消息列表
        messageAdapter = MessageAdapter()
        rvMessages.layoutManager = LinearLayoutManager(context)
        rvMessages.adapter = messageAdapter
        
        // 添加欢迎消息
        messageAdapter.addMessage(MessageItem(
            role = MessageRole.ASSISTANT,
            content = "你好！我是视频助手，可以帮你搜索、播放、续播视频。试试说\"找一下流浪地球\"或\"继续看庆余年\"。"
        ))
    }
    
    private fun initEngine() {
        // 注意：这里需要传入MainActivity实例，但Dialog中无法直接获取
        // 实际使用时需要通过Activity获取，这里先禁用engine功能
        // engine = VideoAssistantEngine(activity as MainActivity)
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
        
        // 隐藏键盘
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        
        // 添加用户消息
        messageAdapter.addMessage(MessageItem(
            role = MessageRole.USER,
            content = input
        ))
        
        // 清空输入框
        etInput.text.clear()
        
        // 显示加载状态
        showLoading(true)
        
        // 检查LLM配置
        if (!LlmConfig.isConfigured()) {
            showLoading(false)
            showMessage("请先配置LLM参数", "点击设置按钮配置API Key和模型ID")
            return
        }
        
        // TODO: 实际调用engine处理
        // 这里先用模拟回复
        Handler(Looper.getMainLooper()).postDelayed({
            showLoading(false)
            messageAdapter.addMessage(MessageItem(
                role = MessageRole.ASSISTANT,
                content = "收到：$input\n\n（功能开发中，请配置LLM参数后使用）"
            ))
        }, 1000)
    }
    
    private fun showLoading(show: Boolean) {
        flLoading.visibility = if (show) View.VISIBLE else View.GONE
        ivSend.isEnabled = !show
        etInput.isEnabled = !show
    }
    
    private fun showMessage(title: String, message: String) {
        Snackbar.make(rvMessages, "$title: $message", Snackbar.LENGTH_LONG).show()
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
        super.dismiss()
    }
}

/**
 * 消息角色
 */
enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

/**
 * 消息项
 */
data class MessageItem(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 消息列表适配器
 */
class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
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
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            when (viewType) {
                0 -> R.layout.item_message_user
                1 -> R.layout.item_message_assistant
                else -> R.layout.item_message_system
            },
            parent,
            false
        )
        return MessageViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        holder.itemView.findViewById<TextView>(R.id.tv_content).text = message.content
    }
    
    override fun getItemCount(): Int = messages.size
    
    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
