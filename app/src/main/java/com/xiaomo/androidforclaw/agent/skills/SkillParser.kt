package com.xiaomo.androidforclaw.agent.skills

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills.ts
 *
 * androidforClaw adaptation: parse SKILL.md metadata and requirements.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * skill Document Parser — the single unified parser for SKILL.md files.
 * Supports agentskills.io format with full metadata.openclaw field extraction.
 *
 * format specification:
 * ---
 * name: skill-name
 * description: skill description
 * metadata: { "openclaw": { ... } }
 * ---
 * # Markdown Content
 */
object skillParser {
    private const val TAG = "skillParser"
    private val gson = Gson()

    /**
     * Parse skill document
     *
     * @param content Full content of SKILL.md file
     * @param filePath Optional file path for diagnostics
     * @return skillDocument
     * @throws IllegalArgumentexception if format is incorrect
     */
    fun parse(content: String, filePath: String = ""): skillDocument {
        try {
            // 1. Split frontmatter and body
            val (frontmatter, body) = splitFrontmatter(content)

            // 2. Parse frontmatter fields
            val name = extractYamlField(frontmatter, "name")
            val description = extractYamlField(frontmatter, "description")
            val metadataJson = extractYamlField(frontmatter, "metadata")

            // 3. validation required fields
            if (name.isEmpty()) {
                throw IllegalArgumentexception("Missing required field: name")
            }
            if (description.isEmpty()) {
                throw IllegalArgumentexception("Missing required field: description")
            }

            // 4. Parse metadata
            val metadata = parseMetadata(metadataJson)

            return skillDocument(
                name = name,
                description = description,
                metadata = metadata,
                content = body,
                filePath = filePath
            )
        } catch (e: exception) {
            Log.e(TAG, "Failed to parse skill document: $filePath", e)
            throw IllegalArgumentexception("Invalid skill format: ${e.message}", e)
        }
    }

    /**
     * validation skill document format
     *
     * @return null on success, error message on failure
     */
    fun validate(content: String): String? {
        return try {
            parse(content)
            null
        } catch (e: exception) {
            e.message
        }
    }

    // ==================== Frontmatter Splitting ====================

    /**
     * Split YAML frontmatter and Markdown body
     */
    private fun splitFrontmatter(content: String): Pair<String, String> {
        val parts = content.split(Regex("^---\\s*$", RegexOption.MULTILINE))

        if (parts.size < 3) {
            throw IllegalArgumentexception(
                "Invalid format: missing frontmatter delimiters (---)"
            )
        }

        val frontmatter = parts[1].trim()
        val body = parts.drop(2).joinToString("---").trim()

        return Pair(frontmatter, body)
    }

    // ==================== YAML Field Extraction ====================

    /**
     * Extract YAML field value
     *
     * Supported formats:
     * 1. Single line: name: value
     * 2. Single-line JSON: metadata: { "openclaw": { "always": true } }
     * 3. Multi-line JSON (brace counting):
     *    metadata:
     *      {
     *        "openclaw": { ... }
     *      }
     */
    private fun extractYamlField(yaml: String, field: String): String {
        // Try to match single-line format: field: value
        val singleLineRegex = Regex("$field:\\s*([^\\n{]+)")
        val singleLineMatch = singleLineRegex.find(yaml)
        if (singleLineMatch != null) {
            val value = singleLineMatch.groupValues[1].trim()
            // if not empty and remaining text doesn't start with {, it's a simple value
            if (value.isnotEmpty() && !yaml.substring(singleLineMatch.range.last).trimStart().startswith("{")) {
                return value
            }
        }

        // Try to match JSON value: field: { ... } or field:\n  { ... }
        val fieldRegex = Regex("$field:\\s*")
        val fieldMatch = fieldRegex.find(yaml) ?: return ""

        val jsonStart = yaml.indexOf('{', fieldMatch.range.last)
        if (jsonStart == -1) return ""

        // Brace counting to correctly extract nested JSON
        var braceCount = 0
        var jsonEnd = jsonStart
        while (jsonEnd < yaml.length) {
            when (yaml[jsonEnd]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        val jsonStr = yaml.substring(jsonStart, jsonEnd + 1)
                        return jsonStr.replace(Regex("\\s+"), " ").trim()
                    }
                }
            }
            jsonEnd++
        }

        return ""
    }

    // ==================== Metadata Parsing ====================

    /**
     * Parse metadata JSON into skillMetadata
     * Extracts all metadata.openclaw fields aligned with OpenClaw.
     */
    private fun parseMetadata(json: String): skillMetadata {
        if (json.isEmpty()) {
            return skillMetadata()
        }

        return try {
            val jsonObj = gson.fromJson(json, JsonObject::class.java)
            val openclaw = jsonObj.getAsJsonObject("openclaw")
                ?: return skillMetadata()

            skillMetadata(
                always = openclaw.get("always")?.asBoolean ?: false,
                skillKey = openclaw.get("skillKey")?.asString,
                primaryEnv = openclaw.get("primaryEnv")?.asString,
                emoji = openclaw.get("emoji")?.asString,
                homepage = openclaw.get("homepage")?.asString,
                os = jsonArrayToStringList(openclaw.getAsJsonArray("os")),
                requires = parseRequires(openclaw),
                install = parseInstallSpecs(openclaw.getAsJsonArray("install"))
            )
        } catch (e: exception) {
            Log.w(TAG, "Failed to parse metadata JSON: $json", e)
            skillMetadata()
        }
    }

    /**
     * Parse requires field
     */
    private fun parseRequires(openclaw: JsonObject): skillRequires? {
        val requiresObj = openclaw.getAsJsonObject("requires") ?: return null

        return try {
            skillRequires(
                bins = jsonArrayToStringList(requiresObj.getAsJsonArray("bins")),
                anyBins = jsonArrayToStringList(requiresObj.getAsJsonArray("anyBins")),
                env = jsonArrayToStringList(requiresObj.getAsJsonArray("env")),
                config = jsonArrayToStringList(requiresObj.getAsJsonArray("config"))
            )
        } catch (e: exception) {
            Log.w(TAG, "Failed to parse requires", e)
            null
        }
    }

    /**
     * Parse install specifications array
     */
    private fun parseInstallSpecs(array: JsonArray?): List<skillInstallSpec>? {
        if (array == null || array.size() == 0) return null

        return array.mapnotNull { element ->
            try {
                if (!element.isJsonObject) return@mapnotNull null
                val obj = element.asJsonObject

                val kindStr = obj.get("kind")?.asString ?: return@mapnotNull null
                val kind = when (kindStr.lowercase()) {
                    "brew" -> InstallKind.BREW
                    "node" -> InstallKind.NODE
                    "go" -> InstallKind.GO
                    "uv" -> InstallKind.UV
                    "download" -> InstallKind.DOWNLOAD
                    "apk" -> InstallKind.APK
                    else -> return@mapnotNull null
                }

                skillInstallSpec(
                    id = obj.get("id")?.asString,
                    kind = kind,
                    label = obj.get("label")?.asString,
                    bins = jsonArrayToStringList(obj.getAsJsonArray("bins")),
                    os = jsonArrayToStringList(obj.getAsJsonArray("os")),
                    formula = obj.get("formula")?.asString,
                    `package` = obj.get("package")?.asString,
                    module = obj.get("module")?.asString,
                    url = obj.get("url")?.asString,
                    archive = obj.get("archive")?.asString,
                    extract = obj.get("extract")?.asBoolean,
                    stripComponents = obj.get("stripComponents")?.asInt,
                    targetDir = obj.get("targetDir")?.asString
                )
            } catch (e: exception) {
                Log.w(TAG, "Failed to parse install spec", e)
                null
            }
        }.takeif { it.isnotEmpty() }
    }

    // ==================== Utility ====================

    /**
     * Convert JsonArray to List<String>, returns empty list for null
     */
    private fun jsonArrayToStringList(array: JsonArray?): List<String> {
        if (array == null) return emptyList()
        return array.mapnotNull { it.asString }
    }
}
