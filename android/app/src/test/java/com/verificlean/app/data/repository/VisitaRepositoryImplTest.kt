package com.verificlean.app.data.repository

import com.verificlean.app.core.Resultado
import com.verificlean.app.data.local.VisitaDao
import com.verificlean.app.data.local.VisitaEntity
import com.verificlean.app.data.remote.VerifiCleanApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VisitaRepositoryImplTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val api = mockk<VerifiCleanApi>(relaxed = true)
    private val dao = mockk<VisitaDao>(relaxed = true)
    private val repositorio = VisitaRepositoryImpl(api, dao, dispatcher)

    @Test
    fun `si falla la red devuelve el detalle que ya estaba en Room`() = runTest(dispatcher) {
        coEvery { api.obtenerVisita("v1") } throws IOException("sin señal")
        coEvery { dao.obtener("v1") } returns visitaCacheada()

        val resultado = repositorio.obtenerDetalle("v1")

        assertTrue(resultado is Resultado.Exito)
        val detalle = (resultado as Resultado.Exito).datos
        assertEquals("Torre Bavaria", detalle.visita.siteNombreSede)
        assertEquals("¿Baños limpios?", detalle.checklist.single().pregunta)
        assertNull(detalle.siteRadioPermitidoMetros)
    }

    @Test
    fun `si falla la red y no hay cache conserva el fallo`() = runTest(dispatcher) {
        coEvery { api.obtenerVisita("v1") } throws IOException("sin señal")
        coEvery { dao.obtener("v1") } returns null

        assertTrue(repositorio.obtenerDetalle("v1") is Resultado.Fallo)
    }

    private fun visitaCacheada() = VisitaEntity(
        id = "v1",
        siteId = "s1",
        siteClienteId = "c1",
        siteNombreCliente = "Bavaria",
        siteNombreSede = "Torre Bavaria",
        siteDireccion = "Cra. 53 # 127",
        siteLat = 4.7,
        siteLng = -74.06,
        siteRadioPermitidoMetros = 0.0,
        supervisorId = "u1",
        supervisorNombre = "Carlos",
        estado = "en_progreso",
        fechaAsignadaMillis = 1_700_000_000_000,
        completadaEnMillis = null,
        evidenciaCount = 1,
        tieneSospechosas = false,
        checklistJson = """[{"id":"banos","pregunta":"¿Baños limpios?","respuesta":"SI","nota":null}]""",
        observaciones = null,
        actualizadoEnMillis = 1_700_000_000_000,
    )
}
