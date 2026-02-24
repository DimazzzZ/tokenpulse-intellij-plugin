package org.zhavoronkov.tokenpulse.model

import java.math.BigDecimal

sealed class Balance {
    data class CreditsUsd(val amount: BigDecimal) : Balance()
    data class Tokens(val used: Long, val total: Long?) : Balance()
}
