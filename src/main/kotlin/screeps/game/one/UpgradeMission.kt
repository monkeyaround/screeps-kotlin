package screeps.game.one

import kotlinx.serialization.Serializable
import screeps.game.one.kreeps.BodyDefinition
import types.base.global.Game
import types.base.prototypes.Creep
import types.base.prototypes.Room
import types.base.prototypes.structures.StructureController

//sealed class UpgradeMission1
//class RoomUpgradeMission : UpgradeMission1()
//sealed class RunningUpgradeMission : UpgradeMission1()
//class EasyUpgradeMission : RunningUpgradeMission()
//class LinkUpgradeMission : RunningUpgradeMission()
//class RCL8UpgradeMission : RunningUpgradeMission()


/**
 * Mission to upgrade a controller using multiple creeps to carry energy
 * Can be cached safely
 *
 * @throws IllegalStateException if it can't be initialized
 */
abstract class UpgradeMission(controllerId: String) : Mission() {
    val controller: StructureController

    init {
        val controllerFromMemory = Game.getObjectById<StructureController>(controllerId)
        controller = controllerFromMemory ?: throw IllegalStateException("could not load controller for controllerId $controllerId") // captured
    }

    abstract fun abort()
}


class RoomUpgradeMission(private val memory: UpgradeMissionMemory) : UpgradeMission(memory.controllerId) {

    companion object {
        const val maxLevel = 8
        fun forRoom(room: Room, state: State = State.EARLY): RoomUpgradeMission {
            val controller = room.controller ?: throw IllegalStateException("Roomcontroller null")
            val memory = UpgradeMissionMemory(controller.id, state)
            val mission = RoomUpgradeMission(memory)
            Missions.missionMemory.upgradeMissionMemory.add(memory)
            Missions.activeMissions.add(mission)
            println("spawning persistent RoomUpgradeMission for room ${room.name}")
            return mission
        }
    }

    enum class State {
        EARLY, LINK, RCL8_MAINTENANCE, RCL8_IDLE
    }

    override val missionId: String = memory.missionId
    var mission: UpgradeMission?

    init {

        @Suppress("WhenWithOnlyElse")
        when (memory.state) {
            else -> mission = EarlyGameUpgradeMission(this, memory.controllerId, if (controller.level == 8) 1 else 3)
        }
    }

    override fun update() {
        if (controller.level == maxLevel) {
            if (memory.state == State.EARLY) {
                memory.state = State.RCL8_MAINTENANCE
            }

            if (memory.state == State.RCL8_IDLE && controller.ticksToDowngrade < 100_000) {
                memory.state = State.RCL8_MAINTENANCE
                mission = EarlyGameUpgradeMission(this, controller.id, 1)
            } else if (memory.state == State.RCL8_MAINTENANCE && controller.ticksToDowngrade > 140_000) {
                memory.state = State.RCL8_IDLE
                mission?.abort()
                mission = null
            }
        }

        mission?.update()
    }

    override fun abort() {
        if (controller.my) throw IllegalStateException("stopping to upgrade my controller in Room ${controller.room}")
    }
}

class EarlyGameUpgradeMission(
    override val parent: UpgradeMission,
    controllerId: String,
    private val minWorkerCount: Int
) : UpgradeMission(controllerId) {

    override val missionId: String
        get() = parent.missionId

    private val workers: MutableList<Creep> = mutableListOf()

    init {
        workers.addAll(Context.creeps.values.filter { it.memory.missionId == missionId })
    }

    override fun update() {

        if (workers.size < minWorkerCount) {
            workers.clear()
            workers.addAll(Context.creeps.values.filter { it.memory.missionId == missionId })

            if (workers.size < minWorkerCount
                && workers.size + GlobalSpawnQueue.spawnQueue.count { it.spawnOptions.missionId == missionId } < minWorkerCount
            ) {
                requestCreep(BodyDefinition.BASIC_WORKER, KreepSpawnOptions(CreepState.UPGRADING, missionId))
                println("requested creep for EarlyGameUpgradeMission $missionId in ${controller.room}")
            }
        }

        for (worker in workers) {
            if (worker.memory.state == CreepState.IDLE) {
                worker.memory.state = CreepState.UPGRADING
                worker.memory.targetId = controller.id
            }
        }
    }

    override fun abort() {
        // return workers to pool
        for (worker in workers) {
            worker.memory.missionId = null
            worker.memory.state = CreepState.IDLE
        }
    }
}

@Serializable
class UpgradeMissionMemory(val controllerId: String, var state: RoomUpgradeMission.State) : MissionMemory<RoomUpgradeMission>() {
    override val missionId: String
        get() = "upgrade_$controllerId"

    override fun restoreMission(): RoomUpgradeMission {
        return RoomUpgradeMission(this)
    }
}