package com.verificlean.app.ui.pantallas.visitas

import com.verificlean.app.domain.model.ChecklistItem
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.RespuestaChecklist
import com.verificlean.app.domain.model.Visita
import com.verificlean.app.domain.model.VisitaDetalle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Las dos condiciones para cerrar una visita —al menos una evidencia y checklist
 * completo— son las mismas que valida el servidor. Aquí sólo se comprueba que la UI las
 * refleje: si el botón se habilitara antes de tiempo, el supervisor recibiría un 409
 * después de dar por terminada la visita.
 */
class EstadoDetalleTest {

    private fun detalle(evidencias: Int) = VisitaDetalle(
        visita = Visita(
            id = "v1",
            siteId = "s1",
            siteClienteId = "c1",
            siteNombreCliente = "Cliente",
            siteNombreSede = "Sede",
            supervisorId = "u1",
            supervisorNombre = "Carlos",
            estado = EstadoVisita.EN_PROGRESO,
            fechaAsignada = Instant.now(),
            completadaEn = null,
            evidenciaCount = evidencias,
            tieneSospechosas = false,
        ),
        siteDireccion = "Cra 1",
        siteLat = 4.7,
        siteLng = -74.06,
        siteRadioPermitidoMetros = 150.0,
        iniciadaEn = null,
        observaciones = null,
        checklist = emptyList(),
        evidencias = emptyList(),
    )

    private fun checklist(vararg respuestas: RespuestaChecklist?) =
        respuestas.mapIndexed { indice, respuesta ->
            ChecklistItem("i$indice", "Pregunta $indice", respuesta, null)
        }

    @Test
    fun `no se puede completar sin evidencias aunque el checklist este lleno`() {
        val estado = EstadoDetalle(
            cargando = false,
            detalle = detalle(evidencias = 0),
            checklist = checklist(RespuestaChecklist.SI, RespuestaChecklist.NO),
        )
        assertFalse(estado.puedeCompletar)
    }

    @Test
    fun `no se puede completar con el checklist incompleto aunque haya evidencias`() {
        val estado = EstadoDetalle(
            cargando = false,
            detalle = detalle(evidencias = 3),
            checklist = checklist(RespuestaChecklist.SI, null),
        )
        assertFalse(estado.puedeCompletar)
        assertEquals(1, estado.itemsSinResponder.size)
    }

    @Test
    fun `se puede completar con evidencia y checklist completo`() {
        val estado = EstadoDetalle(
            cargando = false,
            detalle = detalle(evidencias = 1),
            checklist = checklist(RespuestaChecklist.SI, RespuestaChecklist.NA),
        )
        assertTrue(estado.puedeCompletar)
        assertTrue(estado.itemsSinResponder.isEmpty())
    }

    @Test
    fun `NA cuenta como respondido`() {
        val estado = EstadoDetalle(
            cargando = false,
            detalle = detalle(evidencias = 1),
            checklist = checklist(RespuestaChecklist.NA),
        )
        assertTrue(estado.puedeCompletar)
    }

    @Test
    fun `un checklist vacio no bloquea el cierre si hay evidencia`() {
        val estado = EstadoDetalle(cargando = false, detalle = detalle(evidencias = 1))
        assertTrue(estado.puedeCompletar)
    }

    @Test
    fun `no se puede completar mientras hay un envio en curso`() {
        val estado = EstadoDetalle(
            cargando = false,
            detalle = detalle(evidencias = 1),
            checklist = checklist(RespuestaChecklist.SI),
            completando = true,
        )
        assertFalse(estado.puedeCompletar)
    }

    @Test
    fun `una foto local en espera no habilita cierre antes de llegar al servidor`() {
        val estado = EstadoDetalle(
            cargando = false,
            detalle = detalle(evidencias = 0),
            checklist = checklist(RespuestaChecklist.SI),
            evidenciasPendientes = 1,
        )
        assertFalse(estado.puedeCompletar)
    }

    @Test
    fun `sin detalle cargado no se puede completar`() {
        assertFalse(EstadoDetalle(cargando = false, detalle = null).puedeCompletar)
    }
}
