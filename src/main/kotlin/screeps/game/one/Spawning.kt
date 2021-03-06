package screeps.game.one

import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.parse
import kotlinx.serialization.stringify
import screeps.api.*
import screeps.api.structures.SpawnOptions
import screeps.api.structures.StructureSpawn
import screeps.game.one.kreeps.BodyDefinition

fun StructureSpawn.spawn(bodyDefinition: BodyDefinition, spawnOptions: KreepSpawnOptions? = null): ScreepsReturnCode {
    if (room.energyAvailable < bodyDefinition.cost) return ERR_NOT_ENOUGH_ENERGY

    val body = bodyDefinition.getBiggest(room.energyAvailable)
    val newName = "${bodyDefinition.name}_T${body.tier}_${Game.time}"

    val actualSpawnOptions = spawnOptions ?: GlobalSpawnQueue.defaultSpawnOptions
    println("actual mission = ${actualSpawnOptions.toSpawnOptions().memory?.missionId}")
    val code = this.spawnCreep(
        body.body.toTypedArray(),
        newName,
        actualSpawnOptions.toSpawnOptions()
    )

    if (code == OK) println("spawning $newName with spawnOptions $actualSpawnOptions")
    return code
}

object GlobalSpawnQueue {

    @Serializable
    private val queue: MutableList<SpawnInfo>
    val spawnQueue: List<SpawnInfo>
        get() = queue

    private var modified: Boolean = false
    val defaultSpawnOptions = KreepSpawnOptions(state = CreepState.IDLE)

    init {
        // load from memory
        queue = try {
            Memory.globalSpawnQueue?.queue?.toMutableList() ?: ArrayList()
        } catch (e: Error) {
            println("Error while initializing GlobalSpawnQueue: $e")
            ArrayList()
        }
        println("spawnqueue initialized to $queue")
    }

    fun push(bodyDefinition: BodyDefinition, spawnOptions: KreepSpawnOptions? = null) {
        queue.add(SpawnInfo(bodyDefinition, spawnOptions ?: defaultSpawnOptions))
        modified = true
    }

    fun spawnCreeps(spawns: List<StructureSpawn>) {
        if (queue.isEmpty()) return
        if (queue.size > 5) println("spawnqueue has size ${queue.size} with first 10 ${queue.take(10)}")
        for (spawn in spawns) {
            if (queue.isEmpty() || spawn.spawning != null) continue

            val (bodyDefinition, spawnOptions) = queue.first()
            val code = spawn.spawn(bodyDefinition, spawnOptions)

            when (code) {
                OK -> {
                    queue.removeAt(0)
                    modified = true
                }

                ERR_NOT_ENOUGH_ENERGY -> {
                    if (bodyDefinition.cost > spawn.room.energyCapacityAvailable) {
                        println("creep ${bodyDefinition.name} with body ${bodyDefinition.body} (cost ${bodyDefinition.cost}) is to expensive for ${spawn.name}")
                    }

                    val creep = queue.removeAt(0)
                    queue.add(creep)
                    modified = true
                }

                else -> println("Unexpected return code $code when spawning creep ${bodyDefinition.name} with $spawnOptions")
            }
        }
    }

    /**
     * Save content of the queue to memory
     */
    fun save() {
        if (modified) Memory.globalSpawnQueue = CreepSpawnList(queue)
        modified = false
    }


    @UseExperimental(ImplicitReflectionSerializer::class)
    private var Memory.globalSpawnQueue: CreepSpawnList?
        get() {
            val internal = this.asDynamic().globalSpawnQueue
            return if (internal == null) null else Json.parse(internal)
        }
        set(value) {
            val stringyfied = if (value == null) null else Json.stringify(value)
            this.asDynamic().globalSpawnQueue = stringyfied
        }
}

fun requestCreep(bodyDefinition: BodyDefinition, spawnOptions: KreepSpawnOptions) {

    val candidate =
        Context.creeps.values.firstOrNull { it.memory.state == CreepState.IDLE && it.body.contentEquals(bodyDefinition.body) }
    if (candidate != null) {
        spawnOptions.transfer(candidate.memory)
    } else {
        GlobalSpawnQueue.push(bodyDefinition, spawnOptions)
    }

}

fun requestCreepOnce(bodyDefinition: BodyDefinition, spawnOptions: KreepSpawnOptions) {

    if (GlobalSpawnQueue.spawnQueue.none { it.spawnOptions == spawnOptions && it.bodyDefinition == bodyDefinition }) {
        requestCreep(bodyDefinition, spawnOptions)
    }
}

@Serializable
data class SpawnInfo(val bodyDefinition: BodyDefinition, val spawnOptions: KreepSpawnOptions)

@Serializable
data class CreepSpawnList(val queue: List<SpawnInfo>)

@Serializable
data class KreepSpawnOptions(
    val state: CreepState = CreepState.IDLE,
    val missionId: String? = null,
    val targetId: String? = null,
    val assignedEnergySource: String? = null
) {
    fun toSpawnOptions(): SpawnOptions = options {
        memory = object : CreepMemory {}.apply { transfer(this) }
    }

    fun transfer(memory: CreepMemory) {
        memory.state = state
        memory.missionId = missionId
        memory.targetId = targetId
        memory.assignedEnergySource = assignedEnergySource
    }
}


