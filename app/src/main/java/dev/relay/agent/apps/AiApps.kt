package dev.relay.agent.apps

import java.util.Locale

/** Detects AI assistants among installed apps that are not in the built-in catalog. */
object AiApps {
    private val LABEL_RX = Regex(
        """(^|[\s\-:])(ai|ии|gpt|chatgpt|claude|gemini|copilot|perplexity|deepseek|grok|mistral|le chat|qwen|kimi|gigachat|гигачат|алиса|alice|assistant|ассистент|нейро\S*|neuro\S*|llm|chatbot|чат-?бот|hark|poe|character)($|[\s\-:.!])""",
        RegexOption.IGNORE_CASE,
    )
    private val PKG_RX = Regex(
        """openai|anthropic|\.bard|gemini|perplexity|deepseek|\.grok|copilot|mistral|qwen|moonshot|kimi|gigachat|sdkit|aliceapp|hark|poe\.|character\.ai|chatbot|\.ai\.|^ai\.""",
        RegexOption.IGNORE_CASE,
    )
    private val SKIP_PKG = Regex("""^(com\.android\.|com\.google\.android\.(gms|gsf|inputmethod|apps\.maps|apps\.photos|apps\.docs|calendar|contacts|dialer)|com\.samsung\.android\.(app\.contacts|dialer))""")

    fun looksLikeAi(label: String, pkg: String): Boolean {
        if (SKIP_PKG.containsMatchIn(pkg)) return false
        return LABEL_RX.containsMatchIn(label.lowercase(Locale.ROOT)) || PKG_RX.containsMatchIn(pkg)
    }
}
