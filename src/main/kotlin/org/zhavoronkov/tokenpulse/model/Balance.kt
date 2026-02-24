package org.zhavoronkov.tokenpulse.model

import java.math.BigDecimal

data class Credits(
    val total: BigDecimal? = null,
    val used: BigDecimal? = null,
    val remaining: BigDecimal? = null
)

data class Tokens(
    val total: Long? = null,
    val used: Long? = null,
    val remaining: Long? = null
)

data class Balance(
    val credits: Credits? = null,
    val tokens: Tokens? = null
)
