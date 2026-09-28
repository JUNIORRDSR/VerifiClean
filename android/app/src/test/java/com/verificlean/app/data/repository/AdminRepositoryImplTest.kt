package com.verificlean.app.data.repository

import com.verificlean.app.core.Resultado
import com.verificlean.app.data.remote.VerifiCleanApi
import com.verificlean.app.data.remote.dto.RevisionAlertaDto
import com.verificlean.app.data.remote.dto.RevisionAlertaRequestDto
import com.verificlean.app.data.remote.dto.VisitaDetalleAdminDto
import com.verificlean.app.domain.model.EstadoRevisionAlerta
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class AdminRepositoryImplTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val api = mockk<VerifiCleanApi>()
    private val repositorio = AdminRepositoryImpl(api, dispatcher)

    @Test
    fun `envia sólo estado y nota y devuelve la memoria fijada por servidor`() = runTest(dispatcher) {
        val cuerpo = slot<RevisionAlertaRequestDto>()
        coEvery { api.adminActualizarRevisionAlerta("v1", capture(cuerpo)) } returns
            Response.success(detalleAdmin())

        val resultado = repositorio.actualizarRevisionAlerta(
            visitId = "v1",
            estado = EstadoRevisionAlerta.ESCALADA,
            nota = "Remitido a operaciones.",
        )

        assertEquals("escalada", cuerpo.captured.estado)
        assertEquals("Remitido a operaciones.", cuerpo.captured.nota)
        assertTrue(resultado is Resultado.Exito)
        val revision = (resultado as Resultado.Exito).datos.revisionAlerta
        assertEquals(EstadoRevisionAlerta.ESCALADA, revision.estado)
        assertEquals("Ana", revision.revisadaPorNombre)
    }

    private fun detalleAdmin() = VisitaDetalleAdminDto(
        id = "v1",
        siteId = "s1",
        siteNombreCliente = "Cliente",
        siteNombreSede = "Sede",
        siteDireccion = "Calle 1",
        siteLat = 4.7,
        siteLng = -74.0,
        siteRadioPermitidoMetros = 150.0,
        supervisorId = "u1",
        supervisorNombre = "Carlos",
        estado = "en_progreso",
        fechaAsignada = "2026-08-16T12:00:00Z",
        tieneSospechosas = true,
        revisionAlerta = RevisionAlertaDto(
            estado = "escalada",
            nota = "Remitido a operaciones.",
            revisadaPorId = "admin-1",
            revisadaPorNombre = "Ana",
            revisadaEn = "2026-08-16T14:00:00Z",
        ),
    )
}
