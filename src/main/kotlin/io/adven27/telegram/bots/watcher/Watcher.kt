package io.adven27.telegram.bots.watcher

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import io.adven27.telegram.bots.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.testcontainers.shaded.org.apache.commons.lang.StringEscapeUtils
import java.util.*
import kotlin.concurrent.fixedRateTimer

@Service
@ConditionalOnProperty(prefix = "bot.watcher", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class Watcher(
    @Value("\${bot.watcher.token}") private val token: String,
    @Value("\${bot.watcher.rate-minutes:15}") private val rateMinutes: Long,
    @Value("\${bot.watcher.admin}") private val admin: Long,
    private val chatRepository: ChatRepository,
    private val scriptsRepository: ScriptsRepository,
    private val fetch: (url: String) -> Item,
) : Bot {
    val dbWizard: DbWizard = DbWizard()

    override fun start() {
        with(TelegramBot(token)) {
            fixedRateTimer("default", true, 0L, 1000 * 60 * rateMinutes) { updateItems() }
            setUpdatesListener { handle(it) }
        }
    }

    private fun TelegramBot.updateItems() {
        try {
            chatRepository.findAll().forEach { chat ->
                chatRepository.save(
                    chat.data
                        .let {
                            it.copy(
                                wishList = WishList(it.wishList!!.items.map { (url, _, oldPrice) ->
                                    fetch(url).apply { notify(chat.chatId, oldPrice, this) }
                                }.fold(listOf<Item>()) { r, i -> r + i }.toSet())
                            )
                        }.let { chat.apply { data = it } }
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to refresh", e)
        }
    }

    private fun TelegramBot.notify(user: Long, oldPrice: Double, item: Item) {
        fun Item.priceDown() = "Цена упала!\nСейчас \"${name}\" стоит:\n${price}"
        fun Item.noMore() = "А всё! Нету больше \"${name}\". Надо было раньше думать..."
        when {
            oldPrice != 0.0 && item.price == 0.0 -> send(user, item.noMore())
            item.price < oldPrice -> send(user, item.priceDown())
        }
    }

    private fun TelegramBot.handle(updates: MutableList<Update>): Int {
        updates.forEach {
            when {
                it.callbackQuery() != null -> handleCallback(it.callbackQuery())
                it.message() != null -> handleMessage(it.message())
            }
        }
        return UpdatesListener.CONFIRMED_UPDATES_ALL
    }

    private fun TelegramBot.handleMessage(msg: Message) {
        val url = extractUrl(msg)
        when {
            url.isNotBlank() -> follow(url, msg)
            msg.text().startsWith("/list") -> list(msg.chat().id())
            msg.chat().id() == admin && msg.text().startsWith("/db") -> db(msg)
            msg.chat().id() == admin && dbWizard.inProgress() -> dbInProgress(msg)
        }
    }

    private fun extractUrl(msg: Message) =
        "(http|https)://[a-zA-Z0-9\\-.]+\\.[a-zA-Z]{2,3}(/\\S*)?".toRegex()
            .find(msg.text())?.groupValues?.get(0) ?: ""

    private fun TelegramBot.handleCallback(cb: CallbackQuery) {
        val chatId = cb.chatId()
        val messageId = cb.messageId()
        when {
            cb.data() == "list" -> {
                listItems(chatId).ifPresent { pair ->
                    edit(chatId, messageId, "Вот за чем я слежу:") { it.replyMarkup(pair.second) }
                }
            }
            cb.data().startsWith(CB_OPEN) -> {
                val open = cb.data().substring(CB_OPEN.length)
                chatRepository.findByChatId(chatId).map { info -> info.data.wishList?.items?.find { it.url == open } }
                    .orElseThrow().also { item ->
                        edit(chatId, messageId, "${item!!.name}\nЦена: ${item.price}\nКол-во: ${item.quantity}\n${item.url}") {
                            it.replyMarkup(
                                InlineKeyboardMarkup(
                                    arrayOf(
                                        InlineKeyboardButton("\uD83C\uDF10").url(item.url),
                                        InlineKeyboardButton("❌").callbackData("del#${item.url}"),
                                    ),
                                    arrayOf(InlineKeyboardButton("<< back").callbackData("list"))
                                )
                            )
                        }
                    }
            }
            cb.data().startsWith(CB_DEL) -> {
                val del = cb.data().substring(CB_DEL.length)
                chatRepository.findByChatId(chatId).ifPresent { info ->
                    val wishList = info.data.wishList!!
                    chatRepository.save(
                        info.apply {
                            data = data.copy(wishList = wishList.copy(items = wishList.items.filter { it.url != del }
                                .toSet()))
                        }
                    )
                }
                listItems(chatId).ifPresent { pair ->
                    edit(chatId, messageId, "Вот за чем я слежу:") { it.replyMarkup(pair.second) }
                }
            }
        }
    }

    private fun listItems(chatId: Long): Optional<Pair<String, InlineKeyboardMarkup>> =
        chatRepository.findByChatId(chatId).map { chat ->
            "Вот за чем я слежу:" to InlineKeyboardMarkup(
                *chat.data.wishList?.items?.map { item -> item.toButtons() }?.toTypedArray() ?: emptyArray()
            )
        }

    private fun TelegramBot.list(chatId: Long) = listItems(chatId).ifPresentOrElse(
        { pair -> send(chatId, pair.first) { it.replyMarkup(pair.second) } },
        { send(chatId, "Пока список пуст. Попробуй отправить мне ссылку.") }
    )

    private fun Item.toButtons() = arrayOf(InlineKeyboardButton("${name.take(25)} - $price").callbackData("$CB_OPEN$url"))

    private fun TelegramBot.dbInProgress(msg: Message) {
        if (dbWizard.finished()) {
            scriptsRepository.save(Script(pattern = dbWizard.steps[1].resp, script = dbWizard.steps[2].resp))
            send(admin, "committed")
            dbWizard.reset()
        } else {
            send(admin, dbWizard.next(msg.text()).msg)
        }
    }

    private fun TelegramBot.db(msg: Message) {
        dbWizard.reset()
        send(
            admin,
            scriptsRepository.findAll()
                .joinToString(separator = "\n\n") { it.toString() } + "\n\n" + dbWizard.next("").msg
        )
    }

    //TODO internal queue and retries
    private fun TelegramBot.follow(url: String, msg: Message) {
        logger.info("URL to follow [$url]")
        val chatId = msg.chat().id()
        try {
            val result = fetch(url)
            chatRepository.findByChatId(chatId).ifPresentOrElse(
                { chat ->
                    chatRepository.save(
                        chat.apply {
                            data =
                                data.copy(wishList = data.wishList!!.copy(items = data.wishList!!.items + result))
                        }
                    )
                },
                { chatRepository.save(Chat(chatId = chatId, data = ChatData(WishList(setOf(result))))) }
            )
            send(chatId, "ОК! Буду следить.\nСейчас \"${result.name}\" стоит:\n${result.price}") {
                it.replyToMessageId(msg.messageId())
                    .replyMarkup(InlineKeyboardMarkup(InlineKeyboardButton("Список").callbackData("list")))
            }
        } catch (nf: ScriptNotFound) {
            send(chatId, "Извините... Пока не умею следить за такими ссылками ${nf.message}")
        } catch (e: Exception) {
            logger.error("Fail to follow", e)
            send(chatId, "Извините... Что-то пошло не так =(")
        }
    }

    companion object : KLogging() {
        const val CB_OPEN = "open#"
        const val CB_DEL = "del#"
    }
}

@Serializable
data class Item(val url: String, var name: String = "noname", var price: Double = 0.0, var quantity: Int = 0) {
    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        javaClass != other?.javaClass -> false
        else -> url == (other as Item).url
    }

    override fun hashCode(): Int = url.hashCode()
}

@JsonDeserialize(using = ItemDeserializer::class)
@JsonSerialize(using = ItemSerializer::class)
@Serializable
data class ChatData(val wishList: WishList? = null)

@Serializable
data class WishList(val items: Set<Item>)

class ItemDeserializer @JvmOverloads constructor(vc: Class<*>? = null) : StdDeserializer<ChatData?>(vc) {
    override fun deserialize(jp: JsonParser, ctxt: DeserializationContext?): ChatData =
        with((jp.codec.readTree(jp) as TreeNode).toString()) {
            Json { ignoreUnknownKeys = true }.decodeFromString(
                StringEscapeUtils.unescapeJava(if (startsWith("\"")) subSequence(1, lastIndex).toString() else this)
            )
        }
}

class ItemSerializer @JvmOverloads constructor(vc: Class<ChatData?>? = null) : StdSerializer<ChatData?>(vc) {
    override fun serialize(value: ChatData?, gen: JsonGenerator, provider: SerializerProvider?) =
        gen.writeString(Json { ignoreUnknownKeys = true }.encodeToString(value))
}

class DbWizard(
    val steps: List<Step> = listOf(Step("off"), Step("pattern:"), Step("script:"), Step("commit?")),
    var currentStep: Int = 0
) {
    data class Step(val msg: String, var resp: String = "")

    fun finished() = currentStep == steps.lastIndex

    fun inProgress() = currentStep != 0

    fun next(text: String): Step {
        steps[currentStep].resp = text
        currentStep++
        return steps[currentStep]
    }

    fun reset() {
        currentStep = 0
    }
}