package com.xw.vvtts.engine

import java.util.Locale

/** The shipped Android voice catalog. Native parity is checked by tests/contracts.py. */
object VoiceRegistry {
    data class CapableVoice(val voiceName: String, val locale: Locale, val dialect: Int)
    val voices = listOf(
        CapableVoice("en-US", Locale.US, EloquenceEngine.DIALECT_EN_US),
        CapableVoice("en-GB", Locale.UK, EloquenceEngine.DIALECT_EN_GB),
        CapableVoice("de-DE", Locale.GERMANY, EloquenceEngine.DIALECT_DE_DE),
        CapableVoice("fr-FR", Locale.FRANCE, EloquenceEngine.DIALECT_FR_FR),
        CapableVoice("fr-CA", Locale.CANADA_FRENCH, EloquenceEngine.DIALECT_FR_CA),
        CapableVoice("es-ES", Locale("es", "ES"), EloquenceEngine.DIALECT_ES_ES),
        CapableVoice("es-US", Locale("es", "US"), EloquenceEngine.DIALECT_ES_US),   // [2.1] esus
        CapableVoice("es-MX", Locale("es", "MX"), EloquenceEngine.DIALECT_ES_MX),
        CapableVoice("it-IT", Locale.ITALY, EloquenceEngine.DIALECT_IT_IT),
        CapableVoice("ja-JP", Locale.JAPAN, EloquenceEngine.DIALECT_JA_JP),
        CapableVoice("pl-PL", Locale("pl", "PL"), EloquenceEngine.DIALECT_PL_PL),   // [11.0] plpl
        CapableVoice("pt-BR", Locale("pt", "BR"), EloquenceEngine.DIALECT_PT_BR),
        CapableVoice("fi-FI", Locale("fi", "FI"), EloquenceEngine.DIALECT_FI_FI),
        CapableVoice("zh-CN", Locale("zh", "CN"), EloquenceEngine.DIALECT_ZH_CN)
    )


    fun normalizeLanguage(language: String?): String {
        val lang = (language ?: "").lowercase(Locale.ROOT)
        return voices.firstOrNull { it.locale.isO3Language == lang }?.locale?.language ?: lang
    }

    fun find(language: String?, country: String? = null): CapableVoice? {
        val lang = normalizeLanguage(language)
        if (lang == "zh" && (country.equals("TW", true) || country.equals("TWN", true))) return null
        val candidates = voices.filter { it.locale.language == lang }
        return candidates.firstOrNull {
            it.locale.country.equals(country, true) || it.locale.isO3Country.equals(country, true)
        } ?: candidates.firstOrNull()
    }

    fun sample(voice: CapableVoice): String = when (voice.locale.language) {
        "de" -> "Hallo, dies ist ein Test der Eloquence Sprachausgabe."
        "fr" -> "Bonjour, ceci est un test de synthèse vocale Eloquence."
        "es" -> "Hola, esta es una prueba de síntesis de voz Eloquence."
        "it" -> "Ciao, questa è una prova della sintesi vocale Eloquence."
        "pt" -> "Olá, este é um teste de síntese de voz Eloquence."
        "fi" -> "Hei, tämä on Eloquence puhesynteesin testi."
        "pl" -> "Witaj, to jest test syntezy mowy Eloquence."
        "ja" -> "こんにちは、これは音声合成のテストです。"
        "zh" -> "你好，这是语音合成测试。"
        else -> "Hello, this is an Eloquence speech synthesis test."
    }
}
