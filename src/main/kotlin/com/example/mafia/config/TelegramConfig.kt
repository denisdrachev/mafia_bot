package com.example.mafia.config

import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.TelegramUrl
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.net.InetSocketAddress
import java.net.Proxy

@Configuration
class TelegramConfig(
) {

//    @Bean
//    fun okHttpClient(): OkHttpClient {
//        val proxy = Proxy(
//            Proxy.Type.HTTP, // или Proxy.Type.HTTP
//            InetSocketAddress("shy-bonus-c3df.matcha0aamxqo91.workers.dev", 443)
//        )
//        return OkHttpClient.Builder()
//            .proxy(proxy)
//            .build()
//    }

    @Bean
    fun telegramUrl(): TelegramUrl {
        return TelegramUrl.builder()
            .schema("https") // или "https"
            .host("shy-bonus-c3df.matcha0aamxqo91.workers.dev") // ваш хост, например, "my-bot-api-server.com"
            .port(443) // порт вашего локального Bot API сервера
            .build()
    }

    @Bean
    fun telegramClient(/*okHttpClient: OkHttpClient, */properties: MafiaProperties): TelegramClient {
        require(properties.bot.token.isNotBlank()) {
            "Не задан токен бота: установите переменную окружения TELEGRAM_BOT_TOKEN"
        }
        // 1. Создаём объект TelegramUrl с вашим адресом
        val customUrl = TelegramUrl.builder()
            .schema("https") // или "https"
            .host("shy-bonus-c3df.matcha0aamxqo91.workers.dev") // ваш хост, например, "my-bot-api-server.com"
            .port(443) // порт вашего локального Bot API сервера
            .build()
        return OkHttpTelegramClient(/*okHttpClient, */properties.bot.token, customUrl)
    }
}
