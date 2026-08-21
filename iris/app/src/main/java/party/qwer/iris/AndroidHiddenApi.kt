package party.qwer.iris

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

@SuppressLint("PrivateApi")
class AndroidHiddenApi {
    companion object {
        val startService = getStartServiceMethod()
        val startActivity = getStartActivityMethod()
        val broadcastIntent = getBroadcastIntentMethod()

        private val callingPackageName: String by lazy {
            System.getenv("IRIS_RUNNER") ?: "com.android.shell"
        }

        // Root cause of the over-time send failure: the ActivityManager binder was bound
        // ONCE at class load and reused forever. When system_server restarts, that binder
        // dies and every startService() throws DeadObjectException until iris is restarted.
        // Fix: keep the interface but re-acquire it on demand whenever the bound binder is
        // dead, and auto-rebind+retry on a DeadObjectException.
        private val asInterfaceMethod by lazy {
            Class.forName("android.app.IActivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
        }

        @Volatile
        private var boundActivityBinder: IBinder? = null

        @Volatile
        private var cachedActivityManager: Any? = null

        // Live ActivityManager interface, rebinding from ServiceManager if the previously
        // bound binder has died (or on forceRefresh after a DeadObjectException).
        @Synchronized
        private fun activityManager(forceRefresh: Boolean = false): Any {
            val cached = cachedActivityManager
            val bound = boundActivityBinder
            if (!forceRefresh && cached != null && bound != null && bound.isBinderAlive) {
                return cached
            }
            val binder = getService("activity")
            boundActivityBinder = binder
            val am = asInterfaceMethod.invoke(null, binder)!!
            cachedActivityManager = am
            return am
        }

        // Run an AMS reflection call; on DeadObjectException rebind the binder once and
        // retry so a system_server restart self-heals without restarting iris.
        // Unwraps InvocationTargetException so callers/diag see the real cause.
        private fun invokeAms(block: (Any) -> Unit) {
            try {
                block(activityManager())
                return
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.targetException
                if (cause !is android.os.DeadObjectException) throw cause
                System.err.println("[AMS] DeadObjectException — rebinding ActivityManager and retrying")
            }
            try {
                block(activityManager(forceRefresh = true))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        fun binderStatus(): String {
            val bound = boundActivityBinder
            val boundInfo = if (bound == null) "bound=null" else
                "boundAlive=${bound.isBinderAlive} " +
                    "boundPing=${runCatching { bound.pingBinder() }.getOrDefault(false)} " +
                    "boundHash=${System.identityHashCode(bound)}"
            val freshInfo = runCatching {
                val f = getService("activity")
                "freshHash=${System.identityHashCode(f)} freshAlive=${f.isBinderAlive}"
            }.getOrElse { "fresh-failed:$it" }
            return "callingPkg=$callingPackageName $boundInfo $freshInfo"
        }

        private fun getStartServiceMethod(): (Intent) -> Unit {
            val IActivityManager = Class.forName("android.app.IActivityManager")
            val IApplicationThread = Class.forName("android.app.IApplicationThread")

            try {
                // IApplicationThread caller, Intent service, String resolvedType,
                // boolean requireForeground, String callingPackage, String callingFeatureId, int userId
                val method = IActivityManager.getMethod(
                    "startService",
                    IApplicationThread,
                    Intent::class.java,
                    java.lang.String::class.java,
                    java.lang.Boolean.TYPE,
                    java.lang.String::class.java,
                    java.lang.String::class.java,
                    java.lang.Integer.TYPE,
                )

                return { intent ->
                    invokeAms { am ->
                        method.invoke(
                            am, null, intent, null, false, callingPackageName, null, -3
                        )
                    }
                }
            } catch (_: Exception) {
            }

            try {
                // IApplicationThread caller, Intent service, String resolvedType,
                // boolean requireForeground, in String callingPackage, int userId);
                val method = IActivityManager.getMethod(
                    "startService",
                    IApplicationThread,
                    Intent::class.java,
                    java.lang.String::class.java,
                    java.lang.Boolean.TYPE,
                    java.lang.String::class.java,
                    java.lang.Integer.TYPE,
                )

                return { intent ->
                    invokeAms { am ->
                        method.invoke(
                            am, null, intent, null, false, callingPackageName, -3
                        )
                    }
                }
            } catch (_: Exception) {
            }


            val sdk = android.os.Build.VERSION.SDK_INT
            val methods = IActivityManager.methods.map {
                it.toString().trim()
            }.filter {
                it.contains("startService")
            }.joinToString("\n")

            val errorMsg = """
                failed to get startService Method. Please report
                SDK: $sdk
                METHODS: $methods
            """.trimIndent()

            println(errorMsg)
            throw Exception(errorMsg)
        }

        private fun getStartActivityMethod(): (Intent) -> Unit {
            val IActivityManager = Class.forName("android.app.IActivityManager")
            val IApplicationThread = Class.forName("android.app.IApplicationThread")

            try {
                // IApplicationThread caller, String callingPackage, String callingFeatureId,
                // Intent intent, String resolvedType, IBinder resultTo, String resultWho,
                // int requestCode, int flags, ProfilerInfo profilerInfo, Bundle options, int userId
                val ProfilerInfo = Class.forName("android.app.ProfilerInfo")
                val method = IActivityManager.getMethod(
                    "startActivity",
                    IApplicationThread,
                    String::class.java,
                    String::class.java,
                    Intent::class.java,
                    String::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Integer.TYPE,
                    Integer.TYPE,
                    ProfilerInfo,
                    Bundle::class.java,
                    Integer.TYPE
                )

                return { intent ->
                    invokeAms { am ->
                        method.invoke(
                            am,
                            null,
                            callingPackageName,
                            null,
                            intent,
                            intent.type,
                            null,
                            null,
                            0,
                            0,
                            null,
                            null,
                            -3
                        )
                    }
                }
            } catch (_: Exception) {
            }

            try {
                // IApplicationThread, java.lang.String, android.content.Intent,
                // java.lang.String, android.os.IBinder, java.lang.String, int, int, android.app.ProfilerInfo, android.os.Bundle, int
                val ProfilerInfo = Class.forName("android.app.ProfilerInfo")
                val method = IActivityManager.getMethod(
                    "startActivityAsUser",
                    IApplicationThread,
                    String::class.java,
                    Intent::class.java,
                    String::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Integer.TYPE,
                    Integer.TYPE,
                    ProfilerInfo,
                    Bundle::class.java,
                    Integer.TYPE
                )

                return { intent ->
                    invokeAms { am ->
                        method.invoke(
                            am,
                            null,
                            callingPackageName,
                            intent,
                            intent.type,
                            null,
                            null,
                            0,
                            0,
                            null,
                            null,
                            -3
                        )
                    }
                }
            } catch (_: Exception) {
            }

            val sdk = android.os.Build.VERSION.SDK_INT
            val methods = IActivityManager.methods.map {
                it.toString().trim()
            }.filter {
                it.contains("startActivity")
            }.joinToString("\n")

            val errorMsg = """
                failed to get startActivity Method. Please report
                SDK: $sdk
                METHODS: $methods
            """.trimIndent()

            println(errorMsg)
            throw Exception(errorMsg)
        }

        private fun getBroadcastIntentMethod(): (Intent) -> Unit {
            val IActivityManager = Class.forName("android.app.IActivityManager")
            val IApplicationThread = Class.forName("android.app.IApplicationThread")

            try {
                // IApplicationThread caller, Intent intent, String resolvedType,
                // IIntentReceiver resultTo, int resultCode, String resultData,
                // Bundle map, String[] requiredPermissions, int appOp, Bundle options,
                // boolean serialized, boolean sticky, int userId
                val IIntentReceiver = Class.forName("android.content.IIntentReceiver")
                val method = IActivityManager.getMethod(
                    "broadcastIntent",
                    IApplicationThread,
                    Intent::class.java,
                    String::class.java,
                    IIntentReceiver,
                    Integer.TYPE,
                    String::class.java,
                    Bundle::class.java,
                    Array<String>::class.java,
                    Integer.TYPE,
                    Bundle::class.java,
                    Boolean::class.java,
                    Boolean::class.java,
                    Int::class.java
                )

                return { intent ->
                    invokeAms { am ->
                        method.invoke(
                            am,
                            null,
                            intent,
                            null,
                            null,
                            0,
                            null,
                            null,
                            null,
                            -1,
                            null,
                            false,
                            false,
                            -3
                        )
                    }
                }
            } catch (_: Exception) {
            }


            val sdk = android.os.Build.VERSION.SDK_INT
            val methods = IActivityManager.methods.map {
                it.toString().trim()
            }.filter {
                it.contains("broadcastIntent")
            }.joinToString("\n")

            val errorMsg = """
                failed to get broadcastIntent Method. Please report
                SDK: $sdk
                METHODS: $methods
            """.trimIndent()

            println(errorMsg)
            throw Exception(errorMsg)
        }

        private fun getService(name: String): IBinder {
            val method = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)

            return method.invoke(null, name) as IBinder
        }
    }
}