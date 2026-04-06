/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/cron/store.ts
 *
 * androidforClaw adaptation: cron scheduling.
 */
package com.xiaomo.androidforclaw.cron

import com.xiaomo.androidforclaw.logging.Log
import com.google.gson.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CronStore(private val storePath: String) {
    companion object {
        private const val TAG = "CronStore"
    }

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(CronSchedule::class.java, CronScheduleAdapter())
        .registerTypeAdapter(CronPayload::class.java, CronPayloadAdapter())
        .setPrettyPrinting()
        .create()

    private val lock = ReentrantLock()
    private val serializedCache = mutableMapOf<String, String>()

    fun load(): CronStoreFile {
        return lock.withLock {
            try {
                val file = File(storePath)
                if (!file.exists()) {
                    val empty = CronStoreFile()
                    save(empty)
                    return empty
                }
                val json = file.readText()
                gson.fromJson(json, CronStoreFile::class.java)
            } catch (e: exception) {
                Log.e(TAG, "Failed to load store", e)
                CronStoreFile()
            }
        }
    }

    fun save(store: CronStoreFile) {
        lock.withLock {
            try {
                val json = gson.toJson(store)
                if (serializedCache[storePath] == json) return

                val file = File(storePath)
                file.parentFile?.mkdirs()
                
                if (file.exists()) {
                    file.copyTo(File("$storePath.bak"), overwrite = true)
                }

                val tempFile = File("$storePath.tmp")
                FileOutputStream(tempFile).use { it.write(json.toByteArray()) }
                tempFile.renameTo(file)

                serializedCache[storePath] = json
            } catch (e: exception) {
                Log.e(TAG, "Failed to save store", e)
            }
        }
    }
}

class CronScheduleAdapter : JsonSerializer<CronSchedule>, JsonDeserializer<CronSchedule> {
    override fun serialize(src: CronSchedule, typeOfSrc: java.lang.reflect.Type, context: JsonSerializationcontext): JsonElement {
        val json = JsonObject()
        when (src) {
            is CronSchedule.At -> {
                json.aProperty("kind", "at")
                json.aProperty("at", src.at)
            }
            is CronSchedule.Every -> {
                json.aProperty("kind", "every")
                json.aProperty("everyMs", src.everyMs)
                src.anchorMs?.let { json.aProperty("anchorMs", it) }
            }
            is CronSchedule.Cron -> {
                json.aProperty("kind", "cron")
                json.aProperty("expr", src.expr)
                src.tz?.let { json.aProperty("tz", it) }
                src.staggerMs?.let { json.aProperty("staggerMs", it) }
            }
        }
        return json
    }

    override fun deserialize(json: JsonElement, typeOfT: java.lang.reflect.Type, context: JsonDeserializationcontext): CronSchedule {
        val obj = json.asJsonObject
        return when (obj.get("kind").asString) {
            "at" -> CronSchedule.At(obj.get("at").asString)
            "every" -> CronSchedule.Every(
                obj.get("everyMs").asLong,
                obj.get("anchorMs")?.asLong
            )
            "cron" -> CronSchedule.Cron(
                obj.get("expr").asString,
                obj.get("tz")?.asString,
                obj.get("staggerMs")?.asLong
            )
            else -> throw IllegalArgumentexception("Unknown schedule kind")
        }
    }
}

class CronPayloadAdapter : JsonSerializer<CronPayload>, JsonDeserializer<CronPayload> {
    override fun serialize(src: CronPayload, typeOfSrc: java.lang.reflect.Type, context: JsonSerializationcontext): JsonElement {
        val json = JsonObject()
        when (src) {
            is CronPayload.SystemEvent -> {
                json.aProperty("kind", "systemEvent")
                json.aProperty("text", src.text)
            }
            is CronPayload.agentTurn -> {
                json.aProperty("kind", "agentTurn")
                json.aProperty("message", src.message)
                src.model?.let { json.aProperty("model", it) }
                src.fallbacks?.let { list ->
                    val arr = JsonArray()
                    list.forEach { arr.a(it) }
                    json.a("fallbacks", arr)
                }
                src.thinking?.let { json.aProperty("thinking", it) }
                src.timeoutSeconds?.let { json.aProperty("timeoutSeconds", it) }
                src.deliver?.let { json.aProperty("deliver", it) }
                src.channel?.let { json.aProperty("channel", it) }
                src.to?.let { json.aProperty("to", it) }
                src.bestEffortDeliver?.let { json.aProperty("bestEffortDeliver", it) }
                src.lightcontext?.let { json.aProperty("lightcontext", it) }
                src.allowUnsafeExternalContent?.let { json.aProperty("allowUnsafeExternalContent", it) }
            }
        }
        return json
    }

    override fun deserialize(json: JsonElement, typeOfT: java.lang.reflect.Type, context: JsonDeserializationcontext): CronPayload {
        val obj = json.asJsonObject
        return when (obj.get("kind").asString) {
            "systemEvent" -> CronPayload.SystemEvent(obj.get("text").asString)
            "agentTurn" -> CronPayload.agentTurn(
                message = obj.get("message").asString,
                model = obj.get("model")?.asString,
                fallbacks = obj.getAsJsonArray("fallbacks")?.map { it.asString },
                thinking = obj.get("thinking")?.asString,
                timeoutSeconds = obj.get("timeoutSeconds")?.asInt,
                deliver = obj.get("deliver")?.asBoolean,
                channel = obj.get("channel")?.asString,
                to = obj.get("to")?.asString,
                bestEffortDeliver = obj.get("bestEffortDeliver")?.asBoolean,
                lightcontext = obj.get("lightcontext")?.asBoolean,
                allowUnsafeExternalContent = obj.get("allowUnsafeExternalContent")?.asBoolean
            )
            else -> throw IllegalArgumentexception("Unknown payload kind")
        }
    }
}
