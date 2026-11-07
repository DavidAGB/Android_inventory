package com.davidagb.inventario

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/** Apariencia elegida: "system" (sigue al teléfono), "light" o "dark". */
object Appearance {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString("theme", SYSTEM) ?: SYSTEM

    fun set(ctx: Context, mode: String) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
    }
}

/**
 * Todas las pantallas heredan de aquí: si se eligió claro u oscuro, se fuerza ese modo
 * sobre los recursos (values / values-night); con "Sistema" se deja el del teléfono.
 */
abstract class BaseActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val night = when (Appearance.get(newBase)) {
            Appearance.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Appearance.DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        applyOverrideConfiguration(Configuration().apply { uiMode = night })
    }
}

/**
 * Icono de la app. Cada opción es un <activity-alias> del manifiesto con su propio icono;
 * se activa el elegido y se desactivan los demás.
 */
object AppIcon {
    data class Option(val key: String, val name: String, val icon: Int)

    val options = listOf(
        Option("Clasico", "Clásico", R.mipmap.ic_launcher),
        Option("Noche", "Noche", R.mipmap.ic_launcher_noche),
        Option("Labial", "Labial", R.mipmap.ic_launcher_labial),
    )

    private fun component(ctx: Context, key: String) =
        android.content.ComponentName(ctx.packageName, "com.davidagb.inventario.Icon$key")

    fun current(ctx: Context): String {
        val pm = ctx.packageManager
        return options.firstOrNull {
            when (pm.getComponentEnabledSetting(component(ctx, it.key))) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> it.key == "Clasico"
                else -> false
            }
        }?.key ?: "Clasico"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** El icono que el usuario eligió, aunque todavía no se haya aplicado. */
    fun selected(ctx: Context): String = prefs(ctx).getString("pendingIcon", null) ?: current(ctx)

    /**
     * Guarda la elección. No se aplica en el momento porque Android cierra la app si se
     * desactiva el icono con el que se abrió: se aplica al salir de ella (ver [App]).
     */
    fun choose(ctx: Context, key: String) {
        if (key == current(ctx)) prefs(ctx).edit().remove("pendingIcon").apply()
        else prefs(ctx).edit().putString("pendingIcon", key).apply()
    }

    fun applyPending(ctx: Context) {
        val key = prefs(ctx).getString("pendingIcon", null) ?: return
        prefs(ctx).edit().remove("pendingIcon").apply()
        if (options.none { it.key == key }) return
        val pm = ctx.packageManager
        // Primero se activa el nuevo, para que siempre haya un icono en el lanzador.
        pm.setComponentEnabledSetting(
            component(ctx, key),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        for (o in options) if (o.key != key) {
            pm.setComponentEnabledSetting(
                component(ctx, o.key),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    }
}

/** Aplica el cambio de icono pendiente cuando la app pasa a segundo plano. */
class App : android.app.Application() {
    companion object {
        /** true mientras otra app trabaja para nosotros (cámara, galería, exportar, compartir). */
        var externalFlow = false
    }

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                externalFlow = false
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started == 0 && !activity.isChangingConfigurations) {
                    // Al volver a la pantalla de inicio el widget ya muestra los cambios.
                    Widget.update(this@App)
                    if (!externalFlow) AppIcon.applyPending(this@App)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
