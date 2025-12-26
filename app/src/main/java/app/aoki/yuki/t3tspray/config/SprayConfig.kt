package app.aoki.yuki.t3tspray.config

data class SprayConfig(
    val enabled: Boolean = true,
    val idm: String = DEFAULT_IDM,
    val systemCodes: List<String> = DEFAULT_SYSTEM_CODES
) {
    companion object {
        val DEFAULT_SYSTEM_CODES = listOf("0003", "fe00", "fe0f")
        const val DEFAULT_IDM = "03F0FE0000000000"
    }
}
