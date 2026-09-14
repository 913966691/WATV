package com.github.tvbox.osc.ai

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.dialog.BaseDialog
import com.google.android.material.button.MaterialButton

/**
 * LLM设置对话框
 */
class LlmSettingsDialog(context: Context) : BaseDialog(context) {
    
    private lateinit var spinnerProvider: Spinner
    private lateinit var etApiUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModelId: EditText
    private lateinit var btnClear: MaterialButton
    private lateinit var btnSave: MaterialButton
    
    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_llm_settings, null)
        setContentView(view)
        
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        
        initView(view)
        loadConfig()
        setupListeners()
    }
    
    private fun initView(view: View) {
        spinnerProvider = view.findViewById(R.id.spinner_provider)
        etApiUrl = view.findViewById(R.id.et_api_url)
        etApiKey = view.findViewById(R.id.et_api_key)
        etModelId = view.findViewById(R.id.et_model_id)
        btnClear = view.findViewById(R.id.btn_clear)
        btnSave = view.findViewById(R.id.btn_save)
        
        // 设置Provider下拉
        val providers = arrayOf(
            LlmConfig.Provider.QWEN.displayName,
            LlmConfig.Provider.DOUBAO.displayName,
            LlmConfig.Provider.CUSTOM.displayName
        )
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProvider.adapter = adapter
    }
    
    private fun loadConfig() {
        val provider = LlmConfig.getProvider()
        spinnerProvider.setSelection(when (provider) {
            LlmConfig.Provider.QWEN -> 0
            LlmConfig.Provider.DOUBAO -> 1
            LlmConfig.Provider.CUSTOM -> 2
        })
        
        etApiUrl.setText(LlmConfig.getApiUrl())
        etApiKey.setText(LlmConfig.getApiKey())
        etModelId.setText(LlmConfig.getModelId())
        
        // 根据选择的Provider更新API URL提示
        updateApiUrlHint()
    }
    
    private fun updateApiUrlHint() {
        val position = spinnerProvider.selectedItemPosition
        etApiUrl.isEnabled = position == 2 // 只有自定义时才启用URL编辑
        
        when (position) {
            0 -> etApiUrl.hint = "通义千问API地址（自动填充）"
            1 -> etApiUrl.hint = "豆包API地址（自动填充）"
            2 -> etApiUrl.hint = "自定义API地址"
        }
    }
    
    private fun setupListeners() {
        spinnerProvider.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateApiUrlHint()
                
                // 自动填充API URL
                val provider = when (position) {
                    0 -> LlmConfig.Provider.QWEN
                    1 -> LlmConfig.Provider.DOUBAO
                    else -> LlmConfig.Provider.CUSTOM
                }
                
                if (provider != LlmConfig.Provider.CUSTOM) {
                    etApiUrl.setText(provider.apiUrl)
                } else {
                    etApiUrl.setText("")
                }
            }
            
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        
        btnClear.setOnClickListener {
            LlmConfig.clear()
            etApiUrl.setText("")
            etApiKey.setText("")
            etModelId.setText("")
            spinnerProvider.setSelection(0)
            android.widget.Toast.makeText(context, "已清除配置", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        btnSave.setOnClickListener {
            val provider = when (spinnerProvider.selectedItemPosition) {
                0 -> LlmConfig.Provider.QWEN
                1 -> LlmConfig.Provider.DOUBAO
                else -> LlmConfig.Provider.CUSTOM
            }
            
            LlmConfig.setProvider(provider)
            
            if (provider == LlmConfig.Provider.CUSTOM) {
                LlmConfig.setCustomUrl(etApiUrl.text.toString().trim())
            }
            
            LlmConfig.setApiKey(etApiKey.text.toString().trim())
            LlmConfig.setModelId(etModelId.text.toString().trim())
            
            if (LlmConfig.isConfigured()) {
                android.widget.Toast.makeText(context, "配置已保存", android.widget.Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                android.widget.Toast.makeText(context, "请填写完整的配置信息", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
