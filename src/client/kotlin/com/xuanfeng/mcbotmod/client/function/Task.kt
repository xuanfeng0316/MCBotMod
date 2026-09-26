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

package com.xuanfeng.mcbotmod.client.function

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedWriter

interface Task {
    val id: String
    val writer: BufferedWriter
    fun tick(): Boolean
    fun cancel()
}

object TaskManager {
    private val GSON = Gson()
    private val tasks = mutableMapOf<String, Task>()
    private var nextId = 1

    @JvmStatic
    fun nextId(): String = "t-${nextId++}"

    @JvmStatic
    fun submit(task: Task) {
        tasks[task.id] = task
    }

    @JvmStatic
    fun cancel(id: String): Boolean {
        val task = tasks.remove(id) ?: return false
        task.cancel()
        pushTaskDone(task.writer, id, false, "CANCELLED", null)
        return true
    }

    @JvmStatic
    fun cancelAllFor(writer: BufferedWriter) {
        val toRemove = tasks.values.filter { it.writer === writer }
        for (task in toRemove) {
            tasks.remove(task.id)
            task.cancel()
        }
    }

    @JvmStatic
    fun tick() {
        val it = tasks.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.tick()) {
                it.remove()
            }
        }
    }

    @JvmStatic
    fun pushTaskDone(writer: BufferedWriter, taskId: String, ok: Boolean, code: String?, result: JsonObject?) {
        try {
            val data = JsonObject()
            data.addProperty("task", taskId)
            data.addProperty("ok", ok)
            if (code != null) {
                val err = JsonObject()
                err.addProperty("code", code)
                data.add("error", err)
            }
            if (result != null) {
                data.add("result", result)
            }
            val event = JsonObject()
            event.addProperty("event", "task_done")
            event.add("data", data)
            synchronized(writer) {
                writer.write(GSON.toJson(event))
                writer.newLine()
                writer.flush()
            }
        } catch (e: Exception) {
        }
    }
}