package com.example.mafia.game.model

enum class Faction(val title: String) {
    TOWN("Мирные"),
    MAFIA("Мафия"),
    NEUTRAL("Нейтралы")
}

enum class NightAction(val title: String, val prompt: String) {
    KILL("Выстрел", "Выберите, в кого стрелять"),
    HEAL("Лечение", "Выберите, кого лечить"),
    CHECK("Проверка", "Выберите, кого проверить"),
    ALIBI("Алиби", "Выберите, кому выдать алиби"),
    SILENCE("Запрет голоса", "Выберите, кому запретить голосовать")
}

enum class DayAction(val title: String, val prompt: String) {
    BLESS("Благословение", "Выберите, чей голос усилить")
}

enum class Role(
    val defaultFaction: Faction,
    val nightAction: NightAction?,
    val dayAction: DayAction?,
    val defaultCanKill: Boolean,
    val mandatory: Boolean = false,
    val canTargetSelf: Boolean = false,
    val title: String,
    val description: String
) {
    CIVILIAN(
        Faction.TOWN, null, null, false, mandatory = true,
        title = "Мирный житель",
        description = "Днём обсуждаете и голосуете, ночью спите."
    ),
    COMMISSAR(
        Faction.TOWN, NightAction.CHECK, null, false,
        title = "Комиссар",
        description = "Ночью проверяете игрока и узнаёте, умеет ли он убивать."
    ),
    DOCTOR(
        Faction.TOWN, NightAction.HEAL, null, false,
        title = "Доктор",
        description = "Ночью лечите одного игрока. Себя лечить нельзя, одного и того же — сколько угодно раз."
    ),
    PROSTITUTE(
        Faction.TOWN, NightAction.ALIBI, null, false,
        title = "Проститутка",
        description = "Ночью выдаёте игроку алиби: на следующем голосовании его нельзя казнить."
    ),
    BELIEVER(
        Faction.TOWN, null, DayAction.BLESS, false, canTargetSelf = true,
        title = "Верующий",
        description = "Днём перед голосованием усиливаете голос одного игрока — он считается за два."
    ),
    MAFIA_KILLER(
        Faction.MAFIA, NightAction.KILL, null, true, mandatory = true,
        title = "Киллер мафии",
        description = "Ночью выбираете жертву и стреляете."
    ),
    MAFIA_BOSS(
        Faction.MAFIA, NightAction.SILENCE, null, false,
        title = "Босс мафии",
        description = "Ночью запрещаете одному игроку голосовать на следующем голосовании."
    ),
    MANIAC(
        Faction.NEUTRAL, NightAction.KILL, null, true,
        title = "Маньяк",
        description = "Ночью убиваете. Побеждаете, когда мирных не больше, чем маньяков, и мафии не осталось."
    ),
    MASOCHIST(
        Faction.NEUTRAL, null, null, false,
        title = "Мазохист",
        description = "Побеждаете, только если вас казнят днём на голосовании."
    )
}

data class RoleDefinition(
    val role: Role,
    val faction: Faction,
    val canKill: Boolean
)

class RoleCatalog(private val definitions: Map<Role, RoleDefinition>) {

    operator fun get(role: Role): RoleDefinition =
        definitions[role] ?: RoleDefinition(role, role.defaultFaction, role.defaultCanKill)

    fun factionOf(role: Role): Faction = get(role).faction

    fun canKill(role: Role): Boolean = get(role).canKill
}
