package com.verificlean.app.data.remote

import com.verificlean.app.data.remote.dto.AlertaDto
import com.verificlean.app.data.remote.dto.EvidenciaDto
import com.verificlean.app.data.remote.dto.IntegridadDto
import com.verificlean.app.data.remote.dto.VisitaResumenAdminDto
import com.verificlean.app.data.remote.dto.RevisionAlertaDto
import com.verificlean.app.domain.model.EstadoRevisionAlerta
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.MotivoAlerta
import com.verificlean.app.domain.model.VeredictoIntegridad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Lo que se protege aquí es la tolerancia del cliente a un backend más nuevo. Sin estos
 * casos, añadir un motivo de alerta en el servidor rompería todas las apps instaladas y
 * convertiría cada despliegue en una actualización obligatoria de Play.
 */
class MapeadoresTest {

    private fun visitaDto(estado: String = "pendiente", fecha: String = "2026-08-14T14:00:00Z") =
        VisitaResumenAdminDto(
            id = "v1",
            siteId = "s1",
            siteClienteId = "c1",
            siteNombreCliente = "Cliente",
            siteNombreSede = "Sede",
            supervisorId = "u1",
            supervisorNombre = "Carlos",
            estado = estado,
            fechaAsignada = fecha,
            evidenciaCount = 2,
            tieneSospechosas = true,
        )

    @Test
    fun `mapea los tres estados de visita`() {
        assertEquals(EstadoVisita.PENDIENTE, visitaDto("pendiente").aDominio().visita.estado)
        assertEquals(EstadoVisita.EN_PROGRESO, visitaDto("en_progreso").aDominio().visita.estado)
        assertEquals(EstadoVisita.COMPLETADA, visitaDto("completada").aDominio().visita.estado)
    }

    @Test
    fun `un estado desconocido cae a pendiente en vez de lanzar`() {
        assertEquals(EstadoVisita.PENDIENTE, visitaDto("estado_del_futuro").aDominio().visita.estado)
    }

    @Test
    fun `una fecha corrupta no tumba el mapeo`() {
        val visita = visitaDto(fecha = "no-es-una-fecha").aDominio()
        assertEquals(Instant.EPOCH, visita.visita.fechaAsignada)
    }

    @Test
    fun `mapea la memoria de revision sólo en el resumen admin`() {
        val dominio = visitaDto().copy(
            revisionAlerta = RevisionAlertaDto(
                estado = "escalada",
                nota = "Requiere seguimiento.",
                revisadaPorId = "admin-1",
                revisadaPorNombre = "Ana",
                revisadaEn = "2026-08-16T14:00:00Z",
            ),
        ).aDominio()

        assertEquals(EstadoRevisionAlerta.ESCALADA, dominio.revisionAlerta.estado)
        assertEquals("Ana", dominio.revisionAlerta.revisadaPorNombre)
        assertEquals(Instant.parse("2026-08-16T14:00:00Z"), dominio.revisionAlerta.revisadaEn)
    }

    @Test
    fun `el estado de dominio vuelve al formato del contrato`() {
        assertEquals("en_progreso", EstadoVisita.EN_PROGRESO.aWire())
        assertEquals("completada", EstadoVisita.COMPLETADA.aWire())
        assertEquals("pendiente", EstadoVisita.PENDIENTE.aWire())
    }

    @Test
    fun `un motivo de alerta que la app no conoce se mapea a DESCONOCIDO`() {
        val dto = evidenciaDto(motivos = listOf("FUERA_DE_GEOFENCE", "REGLA_NUEVA_DEL_SERVIDOR"))
        val evidencia = dto.aDominio()

        assertEquals(2, evidencia.motivosAlerta.size)
        assertTrue(evidencia.motivosAlerta.contains(MotivoAlerta.FUERA_DE_GEOFENCE))
        assertTrue(evidencia.motivosAlerta.contains(MotivoAlerta.DESCONOCIDO))
    }

    @Test
    fun `todo motivo tiene descripcion legible para el admin`() {
        MotivoAlerta.entries.forEach { motivo ->
            assertTrue("Falta descripción de $motivo", motivo.descripcion.length > 10)
        }
    }

    @Test
    fun `sin bloque de integridad el veredicto es NO_EVALUADO`() {
        assertEquals(
            VeredictoIntegridad.NO_EVALUADO,
            evidenciaDto(integridad = null).aDominio().veredictoIntegridad,
        )
    }

    @Test
    fun `mapea el veredicto de integridad cuando llega`() {
        val dto = evidenciaDto(integridad = IntegridadDto(veredicto = "NO_CONFIABLE"))
        assertEquals(VeredictoIntegridad.NO_CONFIABLE, dto.aDominio().veredictoIntegridad)
    }

    @Test
    fun `la alerta agregada conserva el conteo`() {
        val alerta = AlertaDto(motivo = "IMAGEN_DUPLICADA", conteo = 3).aDominio()
        assertEquals(MotivoAlerta.IMAGEN_DUPLICADA, alerta.motivo)
        assertEquals(3, alerta.conteo)
    }

    private fun evidenciaDto(
        motivos: List<String> = emptyList(),
        integridad: IntegridadDto? = IntegridadDto(veredicto = "CONFIABLE"),
    ) = EvidenciaDto(
        id = "e1",
        visitId = "v1",
        hashSHA256 = "a".repeat(64),
        lat = 4.7,
        lng = -74.06,
        distanciaAlSitioMetros = 12.0,
        timestampServidor = "2026-08-14T14:05:00Z",
        esSospechosa = motivos.isNotEmpty(),
        motivosAlerta = motivos,
        integrity = integridad,
    )
}
