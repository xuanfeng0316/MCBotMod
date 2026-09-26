/*
 * Copyright (C) 2026 xuanfeng0316
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package com.xuanfeng.mcbotmod.client

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xuanfeng.mcbotmod.client.function.Movement
import com.xuanfeng.mcbotmod.client.function.Rotation
import com.xuanfeng.mcbotmod.client.function.Task
import com.xuanfeng.mcbotmod.client.function.TaskManager
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.AlertScreen
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerStatusPinger
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object MCBotModClient : ClientModInitializer {

	private val LOGGER = LoggerFactory.getLogger("mcbotmod")
	private val GSON = Gson()
	private const val PORT = 25566

	private class Request(
		val id: JsonElement?,
		val method: String,
		val params: JsonObject,
		val writer: BufferedWriter,
	)

	private val queue = ConcurrentLinkedQueue<Request>()
	private val chatSubscribers = ConcurrentHashMap<BufferedWriter, Boolean>()

	@Volatile private var autoRespawn = false

	override fun onInitializeClient() {
		LOGGER.info("MCBotModClient starting, listening on $PORT")

		ClientTickEvents.END_CLIENT_TICK.register {
			TaskManager.tick()
			drain()
			if (autoRespawn) {
				val mc = Minecraft.getInstance()
				if (mc.screen is DeathScreen) {
					mc.player?.respawn()
				}
			}
		}

		Thread(::runServer, "mcbotmod-net").apply {
			isDaemon = true
			start()
		}
	}

	private fun runServer() {
		try {
			ServerSocket(PORT, 1, InetAddress.getLoopbackAddress()).use { server ->
				LOGGER.info("Listening on $PORT")
				while (true) {
					val socket = server.accept()
					LOGGER.info("Client connected: ${socket.remoteSocketAddress}")
					Thread({ handleClient(socket) }, "mcbotmod-client").apply {
						isDaemon = true
						start()
					}
				}
			}
		} catch (e: Exception) {
			LOGGER.error("Server error", e)
		}
	}

	private fun handleClient(socket: Socket) {
		val reader: BufferedReader = socket.getInputStream().bufferedReader()
		val writer: BufferedWriter = socket.getOutputStream().bufferedWriter()

		try {
			reader.forEachLine { line ->
				if (line.isBlank()) return@forEachLine
				try {
					val json = JsonParser.parseString(line).asJsonObject
					val id = json.get("id")
					val method = json.get("method").asString
					val params = json.getAsJsonObject("params") ?: JsonObject()

					when (method) {
						"ping" -> {
							respond(writer, id, "pong")
							return@forEachLine
						}
						"protocol_version" -> {
							respond(writer, id, 1)
							return@forEachLine
						}
					}

					queue.offer(Request(id, method, params, writer))
				} catch (e: Exception) {
					LOGGER.warn("Bad request: $line", e)
				}
			}
		} finally {
			TaskManager.cancelAllFor(writer)
			chatSubscribers.remove(writer)
		}
	}

	private fun drain() {
		while (true) {
			val req = queue.poll() ?: break
			try {
				val result = handle(req.method, req.params, req.writer)
				respond(req.writer, req.id, result)
			} catch (e: MethodException) {
				respondError(req.writer, req.id, e.code, e.message ?: "")
			} catch (e: Exception) {
				LOGGER.error("Handle error: ${req.method}", e)
				respondError(req.writer, req.id, "INTERNAL", e.message ?: "")
			}
		}
	}

	private class MethodException(val code: String, message: String) : Exception(message)

	private fun handle(method: String, params: JsonObject, writer: BufferedWriter): Any {
		return when (method) {
			"set_move" -> {
				Movement.set(
					forward = params.get("forward")?.asBoolean,
					backward = params.get("backward")?.asBoolean,
					left = params.get("left")?.asBoolean,
					right = params.get("right")?.asBoolean,
					jump = params.get("jump")?.asBoolean,
					sneak = params.get("sneak")?.asBoolean,
				)
				JsonObject()
			}
			"stop_move" -> {
				Movement.stop()
				JsonObject()
			}
			"set_sprint" -> {
				val enabled = params.get("enabled")?.asBoolean ?: false
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")
				player.isSprinting = enabled
				JsonObject()
			}
			"look" -> {
				Rotation.set(
					yaw = params.get("yaw")?.asFloat,
					pitch = params.get("pitch")?.asFloat,
				)
				JsonObject()
			}
			"get_status" -> {
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")

				val obj = JsonObject()
				obj.addProperty("health", player.health)
				obj.addProperty("food", player.foodData.foodLevel)
				obj.addProperty("saturation", player.foodData.saturationLevel)
				obj
			}
			"get_position" -> {
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")

				val obj = JsonObject()
				obj.addProperty("x", player.x)
				obj.addProperty("y", player.y)
				obj.addProperty("z", player.z)
				obj.addProperty("yaw", player.yRot)
				obj.addProperty("pitch", player.xRot)
				obj
			}
			"get_nearby_blocks" -> {
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")
				val level = player.level()

				val radius = (params.get("radius")?.asInt ?: 4).coerceIn(0, 32)
				val center = player.blockPosition()
				val blocks = com.google.gson.JsonArray()

				for (pos in BlockPos.betweenClosed(
					center.offset(-radius, -radius, -radius),
					center.offset(radius, radius, radius),
				)) {
					val state = level.getBlockState(pos)
					if (state.isAir) continue

					val entry = com.google.gson.JsonArray()
					entry.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString())
					val coord = com.google.gson.JsonArray()
					coord.add(pos.x); coord.add(pos.y); coord.add(pos.z)
					entry.add(coord)
					val props = JsonObject()
					for (prop in state.properties) {
						props.addProperty(prop.name, state.getValue(prop).toString())
					}
					entry.add(props)
					blocks.add(entry)
				}

				val obj = JsonObject()
				obj.add("blocks", blocks)
				obj
			}
			"get_nearby_entities" -> {
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")
				val level = player.level()

				val radius = (params.get("radius")?.asDouble ?: 16.0).coerceIn(0.0, 64.0)
				val entities = com.google.gson.JsonArray()

				val box = player.boundingBox.inflate(radius)
				for (entity in level.getEntities(player, box)) {
					val entry = JsonObject()
					entry.addProperty("id", entity.id)
					entry.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString())
					entry.addProperty("x", entity.x)
					entry.addProperty("y", entity.y)
					entry.addProperty("z", entity.z)
					entry.addProperty("distance", player.distanceTo(entity))
					entities.add(entry)
				}

				val obj = JsonObject()
				obj.add("entities", entities)
				obj
			}
			"interact_block" -> {
				val mc = Minecraft.getInstance()
				val player = mc.player ?: throw MethodException("NOT_IN_GAME", "player is null")
				val gameMode = mc.gameMode ?: throw MethodException("NOT_IN_GAME", "gameMode is null")

				val pos = BlockPos(
					params.get("x").asInt,
					params.get("y").asInt,
					params.get("z").asInt,
				)
				val face = parseFace(params.get("face")?.asString, player, pos)
				val hand = parseHand(params.get("hand")?.asString)

				val center = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
				val hit = BlockHitResult(center, face, pos, false)
				val result = gameMode.useItemOn(player, hand, hit)

				val obj = JsonObject()
				obj.addProperty("result", result.toString())
				obj
			}
			"break_block" -> {
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")
				val pos = BlockPos(
					params.get("x").asInt,
					params.get("y").asInt,
					params.get("z").asInt,
				)
				val face = parseFace(params.get("face")?.asString, player, pos)
				val timeout = params.get("timeout")?.asLong

				val taskId = TaskManager.nextId()
				TaskManager.submit(BreakBlockTask(taskId, writer, pos, face, timeout))

				val obj = JsonObject()
				obj.addProperty("task", taskId)
				obj
			}
			"cancel_task" -> {
				val taskId = params.get("task").asString
				val cancelled = TaskManager.cancel(taskId)
				val obj = JsonObject()
				obj.addProperty("cancelled", cancelled)
				obj
			}
			"use_item" -> {
				val mc = Minecraft.getInstance()
				val player = mc.player ?: throw MethodException("NOT_IN_GAME", "player is null")
				val gameMode = mc.gameMode ?: throw MethodException("NOT_IN_GAME", "gameMode is null")

				val hand = parseHand(params.get("hand")?.asString)
				val result = gameMode.useItem(player, hand)

				val obj = JsonObject()
				obj.addProperty("result", result.toString())
				obj
			}
			"interact_entity" -> {
				val mc = Minecraft.getInstance()
				val player = mc.player ?: throw MethodException("NOT_IN_GAME", "player is null")
				val level = mc.level ?: throw MethodException("NOT_IN_GAME", "level is null")
				val gameMode = mc.gameMode ?: throw MethodException("NOT_IN_GAME", "gameMode is null")

				val entityId = params.get("entityId").asInt
				val entity = level.getEntity(entityId)
					?: throw MethodException("NOT_FOUND", "entity $entityId not found")
				val hand = parseHand(params.get("hand")?.asString)

				val hit = EntityHitResult(entity, entity.position())
				val result = gameMode.interact(player, entity, hit, hand)

				val obj = JsonObject()
				obj.addProperty("result", result.toString())
				obj
			}
			"attack_entity" -> {
				val mc = Minecraft.getInstance()
				val player = mc.player ?: throw MethodException("NOT_IN_GAME", "player is null")
				val level = mc.level ?: throw MethodException("NOT_IN_GAME", "level is null")
				val gameMode = mc.gameMode ?: throw MethodException("NOT_IN_GAME", "gameMode is null")

				val entityId = params.get("entityId").asInt
				val entity = level.getEntity(entityId)
					?: throw MethodException("NOT_FOUND", "entity $entityId not found")

				gameMode.attack(player, entity)

				JsonObject()
			}
			"list_singleplayer_worlds" -> {
				val mc = Minecraft.getInstance()
				val levelSource = mc.levelSource

				val candidates = levelSource.findLevelCandidates()
				val summaries = levelSource.loadLevelSummaries(candidates).get()

				val arr = com.google.gson.JsonArray()
				for (s in summaries) {
					val entry = JsonObject()
					entry.addProperty("id", s.levelId)
					entry.addProperty("name", s.levelName)
					entry.addProperty("lastPlayed", s.lastPlayed)
					arr.add(entry)
				}

				val obj = JsonObject()
				obj.add("worlds", arr)
				obj
			}
			"connect_server" -> {
				val host = params.get("host").asString
				val port = params.get("port")?.asInt ?: 25565

				val address = ServerAddress.parseString("$host:$port")
				val serverData = ServerData(host, "$host:$port", ServerData.Type.OTHER)

				val taskId = TaskManager.nextId()
				TaskManager.submit(ConnectServerTask(taskId, writer, address, serverData))

				val obj = JsonObject()
				obj.addProperty("task", taskId)
				obj
			}
			"open_singleplayer_world" -> {
				val levelId = params.get("id").asString
				val taskId = TaskManager.nextId()
				TaskManager.submit(OpenWorldTask(taskId, writer, levelId))

				val obj = JsonObject()
				obj.addProperty("task", taskId)
				obj
			}
			"disconnect" -> {
				Minecraft.getInstance().execute {
					Minecraft.getInstance().disconnectFromWorld(Component.literal("disconnected by bot"))
				}
				JsonObject()
			}
			"shutdown" -> {
				Minecraft.getInstance().execute {
					Minecraft.getInstance().stop()
				}
				JsonObject()
			}
			"get_server_info" -> {
				val host = params.get("host").asString
				val port = params.get("port")?.asInt ?: 25565

				val serverData = ServerData(host, "$host:$port", ServerData.Type.OTHER)
				val taskId = TaskManager.nextId()
				TaskManager.submit(ServerInfoTask(taskId, writer, serverData))

				val obj = JsonObject()
				obj.addProperty("task", taskId)
				obj
			}
			"send_chat" -> {
				val message = params.get("message").asString
				val connection = Minecraft.getInstance().player?.connection
					?: throw MethodException("NOT_IN_GAME", "player is null")

				if (message.startsWith("/")) {
					connection.sendCommand(message.substring(1))
				} else {
					connection.sendChat(message)
				}
				JsonObject()
			}
			"subscribe_chat" -> {
				val history = params.get("history")?.asBoolean ?: false
				chatSubscribers[writer] = true

				if (history) {
					val mc = Minecraft.getInstance()
					val chat = mc.gui.chat
					val accessor = chat as com.xuanfeng.mcbotmod.client.mixin.ChatAccessor
					val messages = accessor.allMessages
					for (msg in messages.reversed()) {
						pushChatTo(writer, msg.content().string, msg.source().name)
					}
				}

				JsonObject()
			}
			"key" -> {
				val keyParam = params.get("key")
				val key = if (keyParam.isJsonPrimitive && keyParam.asJsonPrimitive.isNumber) {
					keyParam.asInt
				} else {
					keyCodeFromName(keyParam.asString)
				}
				val action = params.get("action")?.asString ?: "click"
				val mc = Minecraft.getInstance()
				val handler = mc.keyboardHandler as com.xuanfeng.mcbotmod.client.mixin.KeyboardHandlerInvoker
				val window = mc.window.handle()

				val event = net.minecraft.client.input.KeyEvent(key, 0, 0)

				when (action.lowercase()) {
					"press" -> handler.invokeKeyPress(window, 1, event)
					"release" -> handler.invokeKeyPress(window, 0, event)
					else -> {
						handler.invokeKeyPress(window, 1, event)
						handler.invokeKeyPress(window, 0, event)
					}
				}
				JsonObject()
			}
			"respawn" -> {
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")
				player.respawn()
				JsonObject()
			}
			"auto_respawn" -> {
				val enabled = params.get("enabled")?.asBoolean ?: false
				autoRespawn = enabled
				JsonObject()
			}
			"esc" -> {
				val open = params.get("open")?.asBoolean ?: true
				val mc = Minecraft.getInstance()
				mc.execute {
					if (open) {
						mc.pauseGame(false)
					} else {
						mc.setScreen(null)
					}
				}
				JsonObject()
			}
			"get_inventory" -> {
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")
				val inv = player.inventory
				val level = player.level()

				val obj = JsonObject()
				obj.addProperty("selected", inv.selectedSlot)

				val main = com.google.gson.JsonArray()
				for (i in 0 until 36) {
					val stack = inv.getItem(i)
					if (stack.isEmpty) continue
					main.add(stackToJson(i, stack, level))
				}
				obj.add("main", main)

				val armor = com.google.gson.JsonArray()
				for (i in 36..39) {
					val stack = inv.getItem(i)
					if (stack.isEmpty) continue
					armor.add(stackToJson(i, stack, level))
				}
				obj.add("armor", armor)

				val offhand = inv.getItem(40)
				if (!offhand.isEmpty) {
					obj.add("offhand", stackToJson(40, offhand, level))
				}

				val mainHand = inv.getItem(inv.selectedSlot)
				if (!mainHand.isEmpty) {
					obj.add("mainHand", stackToJson(inv.selectedSlot, mainHand, level))
				}

				obj
			}
			"get_hotbar" -> {
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")
				val inv = player.inventory
				val level = player.level()

				val obj = JsonObject()
				obj.addProperty("selected", inv.selectedSlot)

				val slots = com.google.gson.JsonArray()
				for (i in 0 until 9) {
					val stack = inv.getItem(i)
					if (stack.isEmpty) continue
					slots.add(stackToJson(i, stack, level))
				}
				obj.add("slots", slots)

				val offhand = inv.getItem(40)
				if (!offhand.isEmpty) {
					obj.add("offhand", stackToJson(40, offhand, level))
				}

				val mainHand = inv.getItem(inv.selectedSlot)
				if (!mainHand.isEmpty) {
					obj.add("mainHand", stackToJson(inv.selectedSlot, mainHand, level))
				}

				obj
			}
			"select_slot" -> {
				val slot = params.get("slot").asInt
				if (slot < 0 || slot > 8) {
					throw MethodException("BAD_REQUEST", "slot must be 0-8")
				}
				val player = Minecraft.getInstance().player
					?: throw MethodException("NOT_IN_GAME", "player is null")
				player.inventory.setSelectedSlot(slot)
				JsonObject()
			}
			"move_to_offhand" -> {
				val slot = params.get("slot").asInt
				if (slot < 0 || slot > 35) {
					throw MethodException("BAD_REQUEST", "slot must be 0-35")
				}
				val mc = Minecraft.getInstance()
				val player = mc.player ?: throw MethodException("NOT_IN_GAME", "player is null")
				val gameMode = mc.gameMode ?: throw MethodException("NOT_IN_GAME", "gameMode is null")

				val menuSlot = if (slot < 9) 36 + slot else slot
				gameMode.handleContainerInput(0, menuSlot, 40, net.minecraft.world.inventory.ContainerInput.SWAP, player)
				JsonObject()
			}
			else -> throw MethodException("UNKNOWN_METHOD", method)
		}
	}

	fun pushChat(message: String, source: String) {
		for (writer in chatSubscribers.keys) {
			pushChatTo(writer, message, source)
		}
	}

	private fun pushChatTo(writer: BufferedWriter, message: String, source: String) {
		try {
			val data = JsonObject()
			data.addProperty("message", message)
			data.addProperty("source", source)
			val event = JsonObject()
			event.addProperty("event", "chat")
			event.add("data", data)
			synchronized(writer) {
				writer.write(GSON.toJson(event))
				writer.newLine()
				writer.flush()
			}
		} catch (e: Exception) {
		}
	}

	private fun parseFace(name: String?, player: LocalPlayer, pos: BlockPos): Direction {
		if (name != null) {
			return when (name.lowercase()) {
				"up" -> Direction.UP
				"down" -> Direction.DOWN
				"north" -> Direction.NORTH
				"south" -> Direction.SOUTH
				"east" -> Direction.EAST
				"west" -> Direction.WEST
				else -> throw MethodException("BAD_REQUEST", "invalid face: $name")
			}
		}
		val eye = player.eyePosition
		val dx = pos.x - eye.x.toInt()
		val dy = pos.y - eye.y.toInt()
		val dz = pos.z - eye.z.toInt()
		return Direction.getNearest(dx, dy, dz, Direction.UP) ?: Direction.UP
	}

	private fun parseHand(name: String?): InteractionHand {
		return when (name?.lowercase()) {
			null, "main" -> InteractionHand.MAIN_HAND
			"off" -> InteractionHand.OFF_HAND
			else -> throw MethodException("BAD_REQUEST", "invalid hand: $name")
		}
	}

	private fun keyCodeFromName(name: String): Int {
		val n = name.uppercase()
		if (n.length == 1 && n[0] in 'A'..'Z') return n[0].code
		if (n.length == 1 && n[0] in '0'..'9') return n[0].code
		return when (n) {
			"SPACE" -> 32
			"ESC", "ESCAPE" -> 256
			"ENTER", "RETURN" -> 257
			"TAB" -> 258
			"BACKSPACE" -> 259
			"INSERT" -> 260
			"DELETE" -> 261
			"RIGHT" -> 262
			"LEFT" -> 263
			"DOWN" -> 264
			"UP" -> 265
			"PAGE_UP" -> 266
			"PAGE_DOWN" -> 267
			"HOME" -> 268
			"END" -> 269
			"F1" -> 290; "F2" -> 291; "F3" -> 292; "F4" -> 293
			"F5" -> 294; "F6" -> 295; "F7" -> 296; "F8" -> 297
			"F9" -> 298; "F10" -> 299; "F11" -> 300; "F12" -> 301
			"SHIFT" -> 340
			"CTRL", "CONTROL" -> 341
			"ALT" -> 342
			else -> throw MethodException("BAD_REQUEST", "unknown key: $name")
		}
	}

	private fun stackToJson(slot: Int, stack: ItemStack, level: net.minecraft.world.level.Level): JsonObject {
		val obj = JsonObject()
		obj.addProperty("slot", slot)
		obj.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString())
		obj.addProperty("count", stack.count)

		try {
			val ops = net.minecraft.resources.RegistryOps.create(
				net.minecraft.nbt.NbtOps.INSTANCE,
				level.registryAccess()
			)
			val tag = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow()
			obj.addProperty("nbt", tag.toString())
		} catch (e: Exception) {
			obj.addProperty("nbt", "")
		}

        return obj
	}

	private class BreakBlockTask(
		override val id: String,
		override val writer: BufferedWriter,
		private val pos: BlockPos,
		private val face: Direction,
		private val timeoutMs: Long?,
	) : Task {
		private val startTime = System.currentTimeMillis()
		private var done = false

		override fun tick(): Boolean {
			if (done) return true

			val mc = Minecraft.getInstance()
			val player = mc.player ?: run { finish(false, "NOT_IN_GAME"); return true }
			val level = mc.level ?: run { finish(false, "NOT_IN_GAME"); return true }
			val gameMode = mc.gameMode ?: run { finish(false, "NOT_IN_GAME"); return true }

			if (level.getBlockState(pos).isAir) {
				finish(true, null)
				return true
			}

			val eye = player.eyePosition
			val center = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
			if (eye.distanceTo(center) > player.blockInteractionRange()) {
				gameMode.stopDestroyBlock()
				finish(false, "OUT_OF_RANGE")
				return true
			}

			if (timeoutMs != null && System.currentTimeMillis() - startTime > timeoutMs) {
				gameMode.stopDestroyBlock()
				finish(false, "TIMEOUT")
				return true
			}

			gameMode.startDestroyBlock(pos, face)
			return false
		}

		private fun finish(ok: Boolean, code: String?) {
			done = true
			TaskManager.pushTaskDone(writer, id, ok, code, null)
		}

		override fun cancel() {
			Minecraft.getInstance().gameMode?.stopDestroyBlock()
			done = true
		}
	}

	private class ConnectServerTask(
		override val id: String,
		override val writer: BufferedWriter,
		private val address: ServerAddress,
		private val serverData: ServerData,
	) : Task {
		private val startTime = System.currentTimeMillis()
		private var started = false

		override fun tick(): Boolean {
			val mc = Minecraft.getInstance()

			if (!started) {
				started = true
				val parent = mc.screen ?: object : net.minecraft.client.gui.screens.Screen(Component.empty()) {}
				mc.execute {
					net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
						parent, mc, address, serverData, false, null
					)
				}
				return false
			}

			if (mc.level != null && mc.player != null) {
				finish(true, null)
				return true
			}

			if (mc.screen is DisconnectedScreen) {
				finish(false, "CONNECT_FAILED")
				return true
			}

			if (System.currentTimeMillis() - startTime > 30000) {
				finish(false, "TIMEOUT")
				return true
			}

			return false
		}

		private fun finish(ok: Boolean, code: String?) {
			TaskManager.pushTaskDone(writer, id, ok, code, null)
		}

		override fun cancel() {
		}
	}

	private class OpenWorldTask(
		override val id: String,
		override val writer: BufferedWriter,
		private val levelId: String,
	) : Task {
		private val startTime = System.currentTimeMillis()
		private var started = false

		override fun tick(): Boolean {
			val mc = Minecraft.getInstance()

			if (!started) {
				started = true
				mc.execute {
					mc.createWorldOpenFlows().openWorld(levelId) {
					}
				}
				return false
			}

			if (mc.level != null && mc.player != null) {
				finish(true, null)
				return true
			}

			val screen = mc.screen
			if (screen is AlertScreen || screen is ConfirmScreen || screen is DatapackLoadFailureScreen || screen is BackupConfirmScreen) {
				finish(false, "REQUIRES_USER_ACTION")
				return true
			}

			if (System.currentTimeMillis() - startTime > 60000) {
				finish(false, "TIMEOUT")
				return true
			}

			return false
		}

		private fun finish(ok: Boolean, code: String?) {
			TaskManager.pushTaskDone(writer, id, ok, code, null)
		}

		override fun cancel() {
		}
	}

	private class ServerInfoTask(
		override val id: String,
		override val writer: BufferedWriter,
		private val serverData: ServerData,
	) : Task {
		private val startTime = System.currentTimeMillis()
		private var started = false
		private var pinger: ServerStatusPinger? = null

		override fun tick(): Boolean {
			if (!started) {
				started = true
				val mc = Minecraft.getInstance()
				val p = ServerStatusPinger()
				pinger = p
				try {
					p.pingServer(
						serverData,
						{},
						{},
						net.minecraft.server.network.EventLoopGroupHolder.remote(mc.options.useNativeTransport())
					)
				} catch (e: Exception) {
					finish(false, "PING_FAILED")
					return true
				}
				return false
			}

			pinger?.tick()

			if (serverData.ping > 0) {
				val obj = JsonObject()
				obj.addProperty("motd", serverData.motd?.getString() ?: "")
				obj.addProperty("online", serverData.players?.online() ?: 0)
				obj.addProperty("max", serverData.players?.max() ?: 0)
				obj.addProperty("ping", serverData.ping)
				obj.addProperty("version", serverData.version?.getString() ?: "")
				TaskManager.pushTaskDone(writer, id, true, null, obj)
				return true
			}

			if (System.currentTimeMillis() - startTime > 10000) {
				finish(false, "TIMEOUT")
				return true
			}

			return false
		}

		private fun finish(ok: Boolean, code: String?) {
			TaskManager.pushTaskDone(writer, id, ok, code, null)
		}

		override fun cancel() {
			pinger?.removeAll()
		}
	}

	private fun respond(writer: BufferedWriter, id: JsonElement?, result: Any) {
		val obj = JsonObject()
		if (id != null) obj.add("id", id)
		obj.addProperty("ok", true)
		obj.add("result", GSON.toJsonTree(result))
		writeLine(writer, obj)
	}

	private fun respondError(writer: BufferedWriter, id: JsonElement?, code: String, message: String) {
		val obj = JsonObject()
		if (id != null) obj.add("id", id)
		obj.addProperty("ok", false)
		val err = JsonObject()
		err.addProperty("code", code)
		err.addProperty("message", message)
		obj.add("error", err)
		writeLine(writer, obj)
	}

	private fun writeLine(writer: BufferedWriter, obj: JsonObject) {
		synchronized(writer) {
			writer.write(GSON.toJson(obj))
			writer.newLine()
			writer.flush()
		}
	}
}