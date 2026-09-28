package com.verificlean.app.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Huella estable del dispositivo, para detectar que un supervisor le pasó sus
 * credenciales a otro.
 *
 * Se usa `ANDROID_ID`, que desde Android 8 es único por combinación de app + usuario +
 * dispositivo, sobrevive a actualizaciones de la app y cambia al restablecer de fábrica
 * o al reinstalar. Se le aplica SHA-256 antes de mandarlo: al servidor le basta con
 * comparar igualdad, y así el identificador crudo no viaja ni se almacena.
 *
 * Limitaciones asumidas: cambiar de teléfono cambia la huella (legítimo, por eso sólo
 * alerta y no bloquea), y en un dispositivo rooteado ANDROID_ID se puede falsificar —
 * pero ese caso ya lo cubre Play Integrity con DISPOSITIVO_NO_CONFIABLE.
 */
@Singleton
class IdDispositivo @Inject constructor(
    @ApplicationContext private val contexto: Context,
) {
    private val cacheado: String by lazy { calcular() }

    fun valor(): String = cacheado

    @SuppressLint("HardwareIds")
    private fun calcular(): String {
        val crudo = Settings.Secure.getString(contexto.contentResolver, Settings.Secure.ANDROID_ID)
            ?: return SIN_ID
        if (crudo.isBlank()) return SIN_ID

        val digest = MessageDigest.getInstance("SHA-256").digest(crudo.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private companion object {
        /** Sin ANDROID_ID el servidor no puede comparar nada, y no alertará por ello. */
        const val SIN_ID = "desconocido"
    }
}
