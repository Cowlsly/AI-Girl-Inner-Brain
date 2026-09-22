package app.maskan.chat.data.remote.providers

object ProviderRegistry {
    private val providers = mutableMapOf<String, AiProvider>()

    fun register(provider: AiProvider) {
        providers[provider.id] = provider
    }

    fun getProvider(id: String): AiProvider? = providers[id]

    fun getAllProviders(): List<AiProvider> = providers.values.toList()

    /**
     * What a user who has never chosen gets.
     *
     * The on-device provider, because it is the only one that answers with no API key. It was
     * DeepSeek, which meant a fresh install's first message failed with "add your API key" and
     * no way forward that did not involve a credit card.
     */
    fun getDefaultProvider(): AiProvider =
        providers[OnDeviceProvider.ID] ?: providers["deepseek"] ?: providers.values.first()
}
