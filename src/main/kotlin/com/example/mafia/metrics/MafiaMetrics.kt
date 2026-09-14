package com.example.mafia.metrics

import com.example.mafia.game.model.GamePhase
import com.example.mafia.game.model.Winner
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@Component
class MafiaMetrics(private val registry: MeterRegistry) {

    private val activeGames = AtomicInteger(0)
    private val activeLobbies = AtomicInteger(0)

    init {
        Gauge.builder("mafia.games.active", activeGames) { it.get().toDouble() }
            .description("Игры, которые идут прямо сейчас")
            .register(registry)
        Gauge.builder("mafia.lobbies.active", activeLobbies) { it.get().toDouble() }
            .description("Открытые сборы игроков")
            .register(registry)
    }

    fun gameStarted(playersCount: Int) {
        activeGames.incrementAndGet()
        Counter.builder("mafia.games.started")
            .description("Запущенные игры")
            .register(registry)
            .increment()
        registry.summary("mafia.games.players").record(playersCount.toDouble())
    }

    fun gameFinished(winner: Winner, days: Int, duration: Duration) {
        activeGames.decrementAndGet()
        Counter.builder("mafia.games.finished")
            .tag("winner", winner.name)
            .description("Завершённые игры")
            .register(registry)
            .increment()
        registry.summary("mafia.games.days").record(days.toDouble())
        Timer.builder("mafia.games.duration")
            .tag("winner", winner.name)
            .register(registry)
            .record(duration)
    }

    fun gameAborted(reason: String) {
        activeGames.decrementAndGet()
        Counter.builder("mafia.games.aborted")
            .tag("reason", reason)
            .register(registry)
            .increment()
    }

    fun lobbyOpened() = activeLobbies.incrementAndGet()

    fun lobbyClosed(result: String) {
        activeLobbies.decrementAndGet()
        Counter.builder("mafia.lobbies.closed").tag("result", result).register(registry).increment()
    }

    fun phaseCompleted(phase: GamePhase, duration: Duration) {
        Timer.builder("mafia.phase.duration")
            .tag("phase", phase.name)
            .register(registry)
            .record(duration)
    }

    fun playerAction(action: String) {
        Counter.builder("mafia.player.actions").tag("action", action).register(registry).increment()
    }

    fun error(type: String, cause: Throwable? = null) {
        Counter.builder("mafia.errors")
            .tag("type", type)
            .tag("exception", cause?.let { it::class.simpleName } ?: "none")
            .register(registry)
            .increment()
    }
}
