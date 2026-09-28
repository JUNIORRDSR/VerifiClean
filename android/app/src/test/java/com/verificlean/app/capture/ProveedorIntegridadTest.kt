package com.verificlean.app.capture

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProveedorIntegridadTest {

    @Test
    fun `una tarea que no responde termina en null al vencer el limite`() = runTest {
        val resultado = conTiempoLimiteIntegridad(tiempoMaximoMs = 100) {
            awaitCancellation()
        }

        assertNull(resultado)
    }

    @Test
    fun `un token disponible se devuelve sin modificar`() = runTest {
        val resultado = conTiempoLimiteIntegridad(tiempoMaximoMs = 100) {
            "token-opaco"
        }

        assertEquals("token-opaco", resultado)
    }
}
