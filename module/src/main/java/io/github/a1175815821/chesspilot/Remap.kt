package io.github.a1175815821.chesspilot

import android.content.Context
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The class and method descriptors for the selected target APK. */
class Remap private constructor(map: JSONObject) {
    private val classes = map.getJSONObject("classes")
    private val methods = map.getJSONObject("methods")
    private val fields = map.getJSONObject("fields")
    private val promotion = map.optJSONObject("promotion")
    private val classCache = HashMap<String, Class<*>?>()

    fun resolveClass(loader: ClassLoader, key: String): Class<*>? = synchronized(classCache) {
        classCache.getOrPut(key) {
            val name = classes.optString(key).takeIf { it.isNotEmpty() } ?: return@getOrPut null
            Reflect.findClass(name, loader)
        }
    }

    fun methodName(key: String): String = descriptor(key).name

    fun method(loader: ClassLoader, key: String): Method = descriptor(key).resolve(loader)

    fun field(loader: ClassLoader, key: String): Field = DexField(fields.getString(key)).resolve(loader)

    private fun DexMethod.resolve(loader: ClassLoader): Method {
        val actual = getMethodInstance(loader)
        check(DexMethod(actual) == this) { "$this signature changed" }
        return actual.apply { isAccessible = true }
    }

    private fun DexField.resolve(loader: ClassLoader): Field {
        val actual = getFieldInstance(loader)
        check(DexField(actual) == this) { "$this type changed" }
        return actual.apply { isAccessible = true }
    }

    internal fun promotionPicker(loader: ClassLoader): PromotionPicker? {
        val profile = promotion ?: return null
        fun method(key: String) = DexMethod(profile.getString(key)).resolve(loader)
        return PromotionPicker(
            preview = method("preview"),
            choose = method("choose"),
            reset = method("reset"),
            parseMove = method("parseMove"),
            selectedMoves = DexField(profile.getString("selectedMoves")).resolve(loader),
            moveUci = method(loader, "moveUci"),
            liveState = method(loader, "liveState"),
            pieceType = Reflect.findClass(DexRemapper.PIECE_TYPE, loader),
        )
    }

    internal fun verify(loader: ClassLoader) {
        for (key in classes.keys()) resolveClass(loader, key)
        for (key in methods.keys()) method(loader, key)
        for (key in fields.keys()) field(loader, key)
    }

    private fun descriptor(key: String) = DexMethod(methods.getString(key))

    companion object {
        fun forApk(apkPath: String, moduleLibraryDir: String?): Remap {
            val known = JSONObject(RemapTable.JSON).optJSONObject(dexFingerprint(apkPath))
            if (known != null) return Remap(known)
            try {
                System.loadLibrary("dexkit")
            } catch (e: UnsatisfiedLinkError) {
                val dir = moduleLibraryDir ?: throw e
                System.load(File(dir, System.mapLibraryName("dexkit")).absolutePath)
            }
            return DexKitBridge.create(apkPath).use { dex ->
                dex.setThreadNum(2)
                Remap(DexRemapper(dex).discover())
            }
        }

        /** Use the DEX entries so split selection or resource packaging does not affect the match. */
        internal fun dexFingerprint(apkPath: String): String = ZipFile(apkPath).use { apk ->
            val files = apk.entries().asSequence().filter { DEX_FILE.matches(it.name) }.sortedBy { it.name }.toList()
            check(files.isNotEmpty()) { "no DEX in installed APK" }
            val index = files.joinToString("\n") { "${it.name}:${it.size}:${it.crc}" }
            MessageDigest.getInstance("SHA-256").digest(index.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private val DEX_FILE = Regex("classes\\d*\\.dex")
    }
}

/** Shared runtime accessors for the various hook components. */
object BridgeAccess {
    fun clear() {
        context = null
        boardController = null
        boardColor = null
        lastState = null
        riveView = null
        riveCandidates = emptyList()
    }
    @Volatile var context: Context? = null
    @Volatile var boardController: Any? = null
    /** Native board orientation for converting squares to positions in the view. */
    @Volatile var boardColor: String? = null

    /** Latest rendered ChessGameState instance (type `Object` — resolved dynamically). */
    @Volatile var lastState: Any? = null

    /** The RiveAnimationView inside the board controller (set by MainHook when found). */
    @Volatile var riveView: android.view.View? = null

    /** All View candidates discovered in the controller (see RiveViewFinder). */
    @Volatile var riveCandidates: List<Pair<String, android.view.View>> = emptyList()
}
