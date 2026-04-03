package ai.openclaw.app.avatar

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismDefaultParameterId
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.effect.CubismBreath
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.id.CubismId
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.ACubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion
import com.live2d.sdk.cubism.framework.motion.CubismMotion
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid

private const val TAG = "Live2dModel"

class Live2dModel(
    private val context: Context,
) : CubismUserModel() {

    private var modelDir: String = ""
    private var modelSetting: CubismModelSettingJson? = null

    // Motion/expression caches
    private val motions = mutableMapOf<String, ACubismMotion>()
    private val expressions = mutableMapOf<String, ACubismMotion>()

    // Parameter IDs for lip sync and eye blink
    private val eyeBlinkIds = mutableListOf<CubismId>()
    private val lipSyncIds = mutableListOf<CubismId>()

    // External lip sync value (0.0 - 1.0)
    var lipSyncValue: Float = 0f

    fun loadAssets(modelDirPath: String, modelJsonName: String) {
        modelDir = modelDirPath
        val jsonBytes = loadFile(modelDir + modelJsonName)
        modelSetting = CubismModelSettingJson(jsonBytes)
        setupModel()
    }

    private fun setupModel() {
        val setting = modelSetting ?: return

        // 1. Load MOC3
        val mocFile = setting.modelFileName
        if (mocFile.isNotBlank()) {
            loadModel(loadFile(modelDir + mocFile), true)
        }

        // 2. Load expressions
        for (i in 0 until setting.expressionCount) {
            val name = setting.getExpressionName(i)
            val file = setting.getExpressionFileName(i)
            val expr = loadExpression(loadFile(modelDir + file))
            expressions[name] = expr
        }

        // 3. Load physics
        val physicsFile = setting.physicsFileName
        if (!physicsFile.isNullOrBlank()) {
            loadPhysics(loadFile(modelDir + physicsFile))
        }

        // 4. Load pose
        val poseFile = setting.poseFileName
        if (!poseFile.isNullOrBlank()) {
            loadPose(loadFile(modelDir + poseFile))
        }

        // 5. Eye blink
        val eyeBlinkCount = setting.eyeBlinkParameterCount
        if (eyeBlinkCount > 0) {
            eyeBlink = CubismEyeBlink.create(setting)
            for (i in 0 until eyeBlinkCount) {
                eyeBlinkIds.add(setting.getEyeBlinkParameterId(i))
            }
        }

        // 6. Breath
        val idMgr = CubismFramework.getIdManager()
        val breathParams = mutableListOf<CubismBreath.BreathParameterData>()
        breathParams.add(
            CubismBreath.BreathParameterData(
                idMgr.getId(CubismDefaultParameterId.ParameterId.ANGLE_X.id), 0f, 15f, 6.5345f, 0.5f
            )
        )
        breathParams.add(
            CubismBreath.BreathParameterData(
                idMgr.getId(CubismDefaultParameterId.ParameterId.ANGLE_Y.id), 0f, 8f, 3.5345f, 0.5f
            )
        )
        breathParams.add(
            CubismBreath.BreathParameterData(
                idMgr.getId(CubismDefaultParameterId.ParameterId.ANGLE_Z.id), 0f, 10f, 5.5345f, 0.5f
            )
        )
        breathParams.add(
            CubismBreath.BreathParameterData(
                idMgr.getId(CubismDefaultParameterId.ParameterId.BODY_ANGLE_X.id), 0f, 4f, 15.5345f, 0.5f
            )
        )
        breathParams.add(
            CubismBreath.BreathParameterData(
                idMgr.getId(CubismDefaultParameterId.ParameterId.BREATH.id), 0.5f, 0.5f, 3.2345f, 0.5f
            )
        )
        breath = CubismBreath.create()
        breath.setParameters(breathParams)

        // 7. Lip sync IDs
        val lipCount = setting.lipSyncParameterCount
        for (i in 0 until lipCount) {
            lipSyncIds.add(setting.getLipSyncParameterId(i))
        }

        // 8. Layout
        val layout = mutableMapOf<String, Float>()
        if (setting.getLayoutMap(layout)) {
            modelMatrix.setupFromLayout(layout)
        }

        // 9. User data
        val udFile = setting.userDataFile
        if (!udFile.isNullOrBlank()) {
            loadUserData(loadFile(modelDir + udFile))
        }

        // 10. Pre-load motions
        for (g in 0 until setting.motionGroupCount) {
            val group = setting.getMotionGroupName(g)
            for (i in 0 until setting.getMotionCount(group)) {
                val file = setting.getMotionFileName(group, i)
                val key = "${group}_$i"
                try {
                    val motion = loadMotion(loadFile(modelDir + file)) as CubismMotion
                    motion.setFadeInTime(setting.getMotionFadeInTimeValue(group, i))
                    motion.setFadeOutTime(setting.getMotionFadeOutTimeValue(group, i))
                    motion.setEffectIds(eyeBlinkIds, lipSyncIds)
                    motions[key] = motion
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load motion $key: ${e.message}")
                }
            }
        }

        isInitialized = true
        isUpdated = false
    }

    fun setupRenderer(width: Int, height: Int) {
        val renderer = CubismRendererAndroid.create(width, height)
        setupRenderer(renderer)
        this.getRenderer<CubismRendererAndroid>().isPremultipliedAlpha(true)
        loadTextures()
    }

    private fun loadTextures() {
        val setting = modelSetting ?: return
        for (i in 0 until setting.textureCount) {
            val texturePath = modelDir + setting.getTextureFileName(i)
            val texId = createTexture(texturePath)
            this.getRenderer<CubismRendererAndroid>().bindTexture(i, texId)
        }
    }

    private fun createTexture(path: String): Int {
        val texId = IntArray(1)
        GLES20.glGenTextures(1, texId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        context.assets.open(path).use { input ->
            val bitmap = BitmapFactory.decodeStream(input, null, opts) ?: return 0
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            bitmap.recycle()
        }

        return texId[0]
    }

    fun update(deltaTime: Float) {
        val m = model ?: return

        m.loadParameters()

        // Only process manually triggered motions (no auto idle loop)
        if (!motionManager.isFinished) {
            motionManager.updateMotion(m, deltaTime)
        }

        m.saveParameters()

        // No auto eye blink, breath, or idle — body stays still by default.
        // AI controls everything via param overrides.

        // Lip sync (only when AI is speaking)
        if (lipSync && lipSyncValue > 0.01f) {
            for (id in lipSyncIds) {
                m.addParameterValue(id, lipSyncValue, 0.8f)
            }
        }

        // Apply AI parameter overrides
        val overrides = AvatarStateHolder.paramOverrides.value
        if (overrides.isNotEmpty()) {
            val idMgr = CubismFramework.getIdManager()
            for ((paramName, value) in overrides) {
                val id = idMgr.getId(paramName)
                m.setParameterValue(id, value, 1.0f)
            }
        }

        // Physics — hair/clothing still react to pose changes
        physics?.evaluate(m, deltaTime)

        // Pose (arm part switching)
        pose?.updateParameters(m, deltaTime)

        m.update()
    }

    fun draw(projection: CubismMatrix44) {
        val matrix = CubismMatrix44.create()
        matrix.setMatrix(projection.array)
        CubismMatrix44.multiply(modelMatrix.array, matrix.array, matrix.array)

        this.getRenderer<CubismRendererAndroid>().setMvpMatrix(matrix)
        this.getRenderer<CubismRendererAndroid>().drawModel()
    }

    fun startRandomMotion(group: String, priority: Int): Boolean {
        val setting = modelSetting ?: return false
        val count = setting.getMotionCount(group)
        if (count == 0) return false
        val idx = (0 until count).random()
        return startMotionByIndex(group, idx, priority)
    }

    fun startMotionByIndex(group: String, index: Int, priority: Int): Boolean {
        val key = "${group}_$index"
        val motion = motions[key] ?: return false

        if (!motionManager.reserveMotion(priority)) return false
        motionManager.startMotionPriority(motion, priority)
        return true
    }

    fun setExpression(name: String) {
        val expr = expressions[name] ?: return
        expressionManager.startMotionPriority(expr, PRIORITY_FORCE)
    }

    fun setRandomExpression() {
        if (expressions.isEmpty()) return
        val name = expressions.keys.random()
        setExpression(name)
    }

    fun snapshotParameters(): Map<String, Float> {
        val m = model ?: return emptyMap()
        val idMgr = CubismFramework.getIdManager()
        return SNAPSHOT_PARAMS.associate { name ->
            name to m.getParameterValue(idMgr.getId(name))
        }
    }

    fun release() {
        delete()
        motions.clear()
        expressions.clear()
    }

    private fun loadFile(path: String): ByteArray {
        return context.assets.open(path).use { it.readBytes() }
    }

    companion object {
        const val PRIORITY_NONE = 0
        const val PRIORITY_IDLE = 1
        const val PRIORITY_NORMAL = 2
        const val PRIORITY_FORCE = 3

        private val SNAPSHOT_PARAMS = listOf(
            "ParamAngleX", "ParamAngleY", "ParamAngleZ",
            "ParamEyeLOpen", "ParamEyeROpen", "ParamEyeLSmile", "ParamEyeRSmile",
            "ParamEyeBallX", "ParamEyeBallY",
            "ParamBrowLY", "ParamBrowRY", "ParamBrowLAngle", "ParamBrowRAngle",
            "ParamMouthForm", "ParamMouthOpenY",
            "ParamCheek",
            "ParamBodyAngleX", "ParamBodyAngleY", "ParamBodyAngleZ",
        )
    }
}
