package com.github.smaugfm.service.telegram

import com.elbekd.bot.model.ChatId
import com.elbekd.bot.types.CallbackQuery
import com.elbekd.bot.types.InlineKeyboardMarkup
import com.elbekd.bot.types.Message
import com.elbekd.bot.types.MessageEntity
import com.elbekd.bot.types.ParseMode
import com.github.smaugfm.api.TelegramApi
import com.github.smaugfm.api.YnabApi
import com.github.smaugfm.models.TransactionUpdateType
import com.github.smaugfm.models.TransactionUpdateType.Companion.buttonWord
import com.github.smaugfm.models.ynab.YnabTransactionDetail
import com.github.smaugfm.models.settings.Mappings
import com.github.smaugfm.service.ynab.YnabTransactionTelegramMessageFormatter.Companion.formatHTMLStatementMessage
import com.github.smaugfm.service.ynab.YnabTransactionTelegramMessageFormatter.Companion.formatInlineKeyboard
import com.github.smaugfm.service.ynab.RetryWithYnabRateLimit
import mu.KotlinLogging
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

class TelegramCallbackHandler(
    private val telegram: TelegramApi,
    private val retryWithRateLimit: RetryWithYnabRateLimit,
    private val ynabApi: YnabApi,
    val mappings: Mappings,
) {
    suspend operator fun invoke(callbackQuery: CallbackQuery) {
        if (callbackQuery.from.id !in mappings.getTelegramChatIds()) {
            logger.warn { "Received Telegram callbackQuery from unknown chatId: ${callbackQuery.from.id}" }
            return
        }

        val (callbackQueryId, data, message) =
            extractFromCallbackQuery(callbackQuery) ?: return

        val type = TransactionUpdateType.deserialize(data, message)
            ?: return Unit.also {
                telegram.answerCallbackQuery(
                    callbackQueryId,
                    TelegramApi.UNKNOWN_ERROR_MSG
                )
            }

        retryWithRateLimit(ChatId.IntegerId(message.chat.id)) {
            updateAndSendMessage(type, callbackQueryId, message)
        }
    }

    private suspend fun updateAndSendMessage(
        type: TransactionUpdateType,
        callbackQueryId: String,
        message: Message
    ) {
        val updatedTransaction = updateTransaction(type).also {
            telegram.answerCallbackQuery(callbackQueryId)
        }

        val updatedText = updateHTMLStatementMessage(updatedTransaction, message)
        val updatedMarkup = updateMarkupKeyboard(type, message.replyMarkup!!)

        if (stripHTMLTagsFromMessage(updatedText) != message.text ||
            updatedMarkup != message.replyMarkup
        ) {
            with(message) {
                telegram.editMessage(
                    ChatId.IntegerId(chat.id),
                    messageId,
                    updatedText,
                    ParseMode.Html,
                    updatedMarkup
                )
            }
        }
    }

    private suspend fun updateTransaction(
        type: TransactionUpdateType,
    ): YnabTransactionDetail {
        val transactionDetail = ynabApi.getTransaction(type.transactionId)
        val saveTransaction = transactionDetail.toSaveTransaction()

        val newTransaction = when (type) {
            is TransactionUpdateType.Uncategorize ->
                saveTransaction.copy(categoryId = null, payeeName = null, payeeId = null)

            is TransactionUpdateType.Unapprove ->
                saveTransaction.copy(approved = false)

            is TransactionUpdateType.Unknown -> saveTransaction.copy(
                payeeId = mappings.unknownPayeeId,
                categoryId = mappings.unknownCategoryId,
                payeeName = null
            )

            is TransactionUpdateType.MakePayee -> saveTransaction.copy(payeeId = null, payeeName = type.payee)
        }

        return ynabApi.updateTransaction(transactionDetail.id, newTransaction)
    }

    @Suppress("MagicNumber")
    private fun updateHTMLStatementMessage(
        updatedTransaction: YnabTransactionDetail,
        oldMessage: Message,
    ): String {
        val oldText = oldMessage.text!!
        val oldTextLines = oldText.split("\n").filter { it.isNotBlank() }
        val description = oldMessage.entities.find { it.type == MessageEntity.Type.BOLD }
            ?.run { oldMessage.text!!.substring(offset, offset + length) }!!

        val mcc = oldTextLines[2].trim()
        val currencyText = oldTextLines[3].trim()
        val id = oldTextLines[6].trim()

        return formatHTMLStatementMessage(
            null,
            description,
            mcc,
            currencyText,
            updatedTransaction.categoryName ?: "",
            updatedTransaction.payeeName ?: "",
            id
        )
    }

    private fun extractFromCallbackQuery(callbackQuery: CallbackQuery): Triple<String, String, Message>? {
        val callbackQueryId = callbackQuery.id
        val data = callbackQuery.data.takeUnless { it.isNullOrBlank() }
            ?: logger.warn { "Received Telegram callbackQuery with empty data.\n$callbackQuery" }
                .let { return null }
        val message =
            callbackQuery.message ?: logger.warn { "Received Telegram callbackQuery with empty message" }
                .let { return null }

        return Triple(callbackQueryId, data, message)
    }

    private fun pressedButtons(oldKeyboard: InlineKeyboardMarkup): Set<KClass<out TransactionUpdateType>> =
        oldKeyboard
            .inlineKeyboard
            .flatten()
            .filter { it.text.contains(TransactionUpdateType.pressedChar) }
            .mapNotNull { button ->
                TransactionUpdateType::class.sealedSubclasses.find {
                    button.text.contains(it.buttonWord())
                }
            }.toSet()

    private fun updateMarkupKeyboard(
        type: TransactionUpdateType,
        oldKeyboard: InlineKeyboardMarkup,
    ): InlineKeyboardMarkup =
        formatInlineKeyboard(pressedButtons(oldKeyboard) + type::class)

    companion object {
        internal fun stripHTMLTagsFromMessage(messageText: String): String {
            val replaceHtml = Regex("<.*?>")
            return replaceHtml.replace(messageText, "")
        }
    }
}
