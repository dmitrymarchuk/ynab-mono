package com.github.smaugfm.ynab

import java.util.concurrent.ConcurrentHashMap

class YnabTransferPayeeIdsCache(private val ynab: YnabApi) {
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun get(accountId: String): String =
        cache.getOrPut(accountId) {
            ynab.getAccount(accountId).transfer_payee_id
        }
}
