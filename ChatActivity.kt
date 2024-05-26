package ai.mlc.mlcchat

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.aigc.generation.GenerationResult
import com.alibaba.dashscope.utils.Constants
import ai.mlc.mlcchat.data.ChatDatabase
import ai.mlc.mlcchat.data.ChatHistory
import ai.mlc.mlcchat.data.ChatHistoryDao
import ai.mlc.mlcchat.data.UserProfile
import ai.mlc.mlcchat.data.UserProfileDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatActivity : AppCompatActivity() {
    private lateinit var chatDao: ChatHistoryDao
    private lateinit var userProfileDao: UserProfileDao
    private lateinit var generation: Generation
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        Constants.apiKey = "your-llm-api-key" // 设置你的 API key
        chatDao = ChatDatabase.getDatabase(this).chatHistoryDao()
        userProfileDao = ChatDatabase.getDatabase(this).userProfileDao()
        generation = Generation()

        val inputField = findViewById<EditText>(R.id.inputField)
        val sendButton = findViewById<Button>(R.id.sendButton)
        val viewProfileButton = findViewById<Button>(R.id.viewProfileButton)
        recyclerView = findViewById(R.id.recyclerViewChat)

        chatAdapter = ChatAdapter(mutableListOf())
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 加载最近的聊天记录
        loadRecentChatHistory(10)

        sendButton.setOnClickListener {
            val userInput = inputField.text.toString().trim()
            if (userInput.isNotEmpty()) {
                lifecycleScope.launch {
                    try {
                        val response = generateResponse(userInput)
                        val recentChats = chatDao.getRecentChatHistory(10) // 获取最近的聊天记录
                        updateChatHistory(userInput, response, recentChats)
                        inputField.text.clear()
                    } catch (e: Exception) {
                        Toast.makeText(this@ChatActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        viewProfileButton.setOnClickListener {
            val intent = Intent(this, UserProfileActivity::class.java)
            startActivity(intent)
        }
    }

    private suspend fun generateResponse(inputText: String): String {
        val recentChats = chatDao.getRecentChatHistory(10)
        val chatHistory = recentChats.reversed().joinToString(separator = "\n") { chat ->
            val role = if (chat.isUser) "用户" else "模型"
            "$role：${chat.text}"
        }
        val keywords = extractKeywords(inputText)
        val prompt = "以下是用户与模型的聊天记录：\n$chatHistory\n用户说：$inputText。\n请根据这些关键词“${keywords.joinToString("、")}”生成一个具体且有价值的问题。"

        val param = GenerationParam.builder()
            .model("qwen-plus")
            .prompt(prompt)
            .build()

        return try {
            val result: GenerationResult = withContext(Dispatchers.IO) { generation.call(param) }
            result.output?.text ?: "无有效回答"
        } catch (e: Exception) {
            "生成问题失败: ${e.message}"
        }
    }

    private fun extractKeywords(text: String): List<String> {
        return text.split(" ").filter { it.length > 2 }
    }

    private fun updateChatHistory(userInput: String, response: String, recentChats: List<ChatHistory>) {
        val userId = "local_user"

        lifecycleScope.launch(Dispatchers.IO) {
            val userChat = ChatHistory(UUID.randomUUID(), userId, "User", userInput, true)
            val botChat = ChatHistory(UUID.randomUUID(), userId, "Bot", response, false)
            chatDao.insert(userChat)
            chatDao.insert(botChat)
            withContext(Dispatchers.Main) {
                chatAdapter.addChat(userChat)
                chatAdapter.addChat(botChat)
                recyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                saveUserProfile(userInput, recentChats)  // 保存用户性格和世界观等信息
            }
        }
    }

    private fun loadRecentChatHistory(limit: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val recentChats = chatDao.getRecentChatHistory(limit)
            withContext(Dispatchers.Main) {
                chatAdapter.chatList.clear()
                chatAdapter.chatList.addAll(recentChats.reversed())
                chatAdapter.notifyDataSetChanged()
                recyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    private fun saveUserProfile(userInput: String, recentChats: List<ChatHistory>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val chatHistorySummary = generateChatHistorySummary(recentChats)
            val personality = generatePersonality(recentChats, userInput)
            val worldview = generateWorldview(recentChats, userInput)
            val userProfile = UserProfile(
                userId = "local_user",
                personality = personality,
                worldview = worldview,
                chatHistory = chatHistorySummary
            )

            userProfileDao.insert(userProfile)
        }
    }

    private suspend fun generateChatHistorySummary(recentChats: List<ChatHistory>): String {
        val chatHistory = recentChats.reversed().joinToString(separator = "\n") { chat ->
            val role = if (chat.isUser) "用户" else "模型"
            "$role：${chat.text}"
        }
        val prompt = "以下是用户与模型的聊天记录：\n$chatHistory\n请根据这些聊天记录生成一句话总结。"

        val param = GenerationParam.builder()
            .model("qwen-plus")
            .prompt(prompt)
            .build()

        return try {
            val result: GenerationResult = withContext(Dispatchers.IO) { generation.call(param) }
            result.output?.text ?: "无总结"
        } catch (e: Exception) {
            "生成总结失败: ${e.message}"
        }
    }

    private suspend fun generatePersonality(recentChats: List<ChatHistory>, userInput: String): String {
        val chatHistory = recentChats.reversed().joinToString(separator = "\n") { chat ->
            val role = if (chat.isUser) "用户" else "模型"
            "$role：${chat.text}"
        }
        val prompt = "以下是用户与模型的聊天记录：\n$chatHistory\n请根据这些聊天记录用一句话生成用户的性格总结，最后要用两个词来总结。"

        val param = GenerationParam.builder()
            .model("qwen-plus")
            .prompt(prompt)
            .build()

        return try {
            val result: GenerationResult = withContext(Dispatchers.IO) { generation.call(param) }
            result.output?.text ?: "无性格总结"
        } catch (e: Exception) {
            "生成性格总结失败: ${e.message}"
        }
    }

    private suspend fun generateWorldview(recentChats: List<ChatHistory>, userInput: String): String {
        val chatHistory = recentChats.reversed().joinToString(separator = "\n") { chat ->
            val role = if (chat.isUser) "用户" else "模型"
            "$role：${chat.text}"
        }
        val prompt = "以下是用户与模型的聊天记录：\n$chatHistory\n请根据这些聊天记录从心理学角度细腻分析用户的世界观，最后要用三个词来总结。"

        val param = GenerationParam.builder()
            .model("qwen-plus")
            .prompt(prompt)
            .build()

        return try {
            val result: GenerationResult = withContext(Dispatchers.IO) { generation.call(param) }
            result.output?.text ?: "无世界观总结"
        } catch (e: Exception) {
            "生成世界观总结失败: ${e.message}"
        }
    }
}
