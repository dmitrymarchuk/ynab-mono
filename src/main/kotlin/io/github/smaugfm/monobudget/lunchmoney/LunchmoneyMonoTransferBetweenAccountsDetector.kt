package io.github.smaugfm.monobudget.lunchmoney

import io.github.smaugfm.lunchmoney.model.LunchmoneyTransaction
import io.github.smaugfm.monobudget.common.account.TransferBetweenAccountsDetector
import io.github.smaugfm.monobudget.common.lifecycle.StatementProcessingScopeComponent
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

@Scoped
@Scope(StatementProcessingScopeComponent::class)
class LunchmoneyMonoTransferBetweenAccountsDetector :
    TransferBetweenAccountsDetector<LunchmoneyTransaction>()
