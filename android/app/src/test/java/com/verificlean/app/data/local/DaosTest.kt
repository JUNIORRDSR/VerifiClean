package com.verificlean.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.verificlean.app.domain.model.EstadoSincronizacion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Los DAO contra SQLite real, dentro de la JVM gracias a Robolectric.
 *
 * Las consultas de la cola de subida son lógica de verdad, no boilerplate: de ellas
 * depende que una evidencia fallida no se reintente en bucle y que la limpieza de
 * huérfanos no borre archivos que aún hacen falta. Un typo en un `IN (...)` no lo detecta
 * el compilador ni un mock.
 */
@RunWith(RobolectricTestRunner::class)
class DaosTest {

    private lateinit var bd: VerifiCleanDatabase
    private lateinit var visitas: VisitaDao
    private lateinit var pendientes: EvidenciaPendienteDao

    @Before
    fun preparar() {
        bd = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VerifiCleanDatabase::class.java,
        ).allowMainThreadQueries().build()
        visitas = bd.visitaDao()
        pendientes = bd.evidenciaPendienteDao()
    }

    @After
    fun cerrar() = bd.close()

    // ------------------------------------------------------------------ visitas

    private fun visita(id: String, estado: String = "pendiente", fechaMillis: Long = 1_000L) =
        VisitaEntity(
            id = id,
            siteId = "s1",
            siteClienteId = "c1",
            siteNombreCliente = "Cliente",
            siteNombreSede = "Sede $id",
            siteDireccion = "Cra 1",
            siteLat = 4.7,
            siteLng = -74.06,
            siteRadioPermitidoMetros = 150.0,
            supervisorId = "u1",
            supervisorNombre = "Carlos",
            estado = estado,
            fechaAsignadaMillis = fechaMillis,
            completadaEnMillis = null,
            evidenciaCount = 0,
            tieneSospechosas = false,
            checklistJson = "[]",
            observaciones = null,
            actualizadoEnMillis = 0L,
        )

    @Test
    fun `las visitas salen ordenadas por fecha descendente`() = runTest {
        visitas.guardarTodas(
            listOf(
                visita("vieja", fechaMillis = 1_000),
                visita("nueva", fechaMillis = 3_000),
                visita("media", fechaMillis = 2_000),
            ),
        )

        val orden = visitas.observarTodas().first().map { it.id }
        assertEquals(listOf("nueva", "media", "vieja"), orden)
    }

    @Test
    fun `filtrar por estado devuelve solo ese estado`() = runTest {
        visitas.guardarTodas(
            listOf(
                visita("a", estado = "pendiente"),
                visita("b", estado = "completada"),
                visita("c", estado = "completada"),
            ),
        )

        val completadas = visitas.observarPorEstado("completada").first().map { it.id }
        assertEquals(setOf("b", "c"), completadas.toSet())
    }

    @Test
    fun `reemplazar borra lo que ya no viene del servidor`() = runTest {
        visitas.guardarTodas(listOf(visita("a"), visita("b"), visita("c")))

        visitas.reemplazar(listOf(visita("a"), visita("nueva")))

        val ids = visitas.observarTodas().first().map { it.id }.toSet()
        assertEquals(setOf("a", "nueva"), ids)
    }

    @Test
    fun `reemplazar con lista vacia deja la cache vacia`() = runTest {
        visitas.guardarTodas(listOf(visita("a")))
        visitas.reemplazar(emptyList())
        assertTrue(visitas.observarTodas().first().isEmpty())
    }

    @Test
    fun `guardar la misma visita dos veces la actualiza en vez de duplicarla`() = runTest {
        visitas.guardar(visita("a", estado = "pendiente"))
        visitas.guardar(visita("a", estado = "completada"))

        val todas = visitas.observarTodas().first()
        assertEquals(1, todas.size)
        assertEquals("completada", todas.first().estado)
    }

    @Test
    fun `incrementar evidencias suma una al contador`() = runTest {
        visitas.guardar(visita("a"))
        visitas.incrementarEvidencias("a")
        visitas.incrementarEvidencias("a")
        assertEquals(2, visitas.obtener("a")!!.evidenciaCount)
    }

    // ------------------------------------------------------- cola de evidencias

    private val duenoPorDefecto = "u_sup_1"

    private fun pendiente(
        id: String,
        estado: EstadoSincronizacion = EstadoSincronizacion.PENDIENTE,
        capturadoEnMillis: Long = 1_000L,
        intentos: Int = 0,
        ownerUid: String = duenoPorDefecto,
    ) = EvidenciaPendienteEntity(
        clienteEvidenciaId = id,
        ownerUid = ownerUid,
        visitId = "v1",
        visitaNombreSede = "Sede",
        rutaArchivoCifrado = "/data/$id.enc",
        hashSHA256 = "a".repeat(64),
        lat = 4.7,
        lng = -74.06,
        precisionMetros = 12.0,
        mockLocationReportada = false,
        capturadoEnMillis = capturadoEnMillis,
        integrityToken = "fake:confiable",
        checklistJson = "[]",
        estado = estado.name,
        intentos = intentos,
        ultimoError = null,
    )

    @Test
    fun `la cola respeta el orden de captura`() = runTest {
        pendientes.insertar(pendiente("tercera", capturadoEnMillis = 3_000))
        pendientes.insertar(pendiente("primera", capturadoEnMillis = 1_000))
        pendientes.insertar(pendiente("segunda", capturadoEnMillis = 2_000))

        val orden = pendientes.siguientesParaSubir(duenoPorDefecto, 10).map { it.clienteEvidenciaId }
        assertEquals(listOf("primera", "segunda", "tercera"), orden)
    }

    @Test
    fun `la cola excluye las fallidas para no reintentarlas en bucle`() = runTest {
        pendientes.insertar(pendiente("ok"))
        pendientes.insertar(pendiente("rota", estado = EstadoSincronizacion.FALLIDA))

        val cola = pendientes.siguientesParaSubir(duenoPorDefecto, 10).map { it.clienteEvidenciaId }
        assertEquals(listOf("ok"), cola)
    }

    @Test
    fun `la cola incluye las que quedaron en ENVIANDO tras morir el proceso`() = runTest {
        // Si el proceso muere a mitad de una subida, la fila queda en ENVIANDO. Excluirla
        // la dejaría atascada para siempre.
        pendientes.insertar(pendiente("a-medias", estado = EstadoSincronizacion.ENVIANDO))
        assertEquals(1, pendientes.siguientesParaSubir(duenoPorDefecto, 10).size)
    }

    @Test
    fun `el limite del lote se respeta`() = runTest {
        repeat(8) { pendientes.insertar(pendiente("ev$it", capturadoEnMillis = it.toLong())) }
        assertEquals(5, pendientes.siguientesParaSubir(duenoPorDefecto, 5).size)
    }

    @Test
    fun `el contador de pendientes no cuenta las fallidas`() = runTest {
        pendientes.insertar(pendiente("a"))
        pendientes.insertar(pendiente("b"))
        pendientes.insertar(pendiente("c", estado = EstadoSincronizacion.FALLIDA))

        assertEquals(2, pendientes.contarPendientes().first())
    }

    @Test
    fun `todasLasRutas incluye las fallidas`() = runTest {
        // Es la diferencia que evita que la limpieza de huérfanos borre el archivo de una
        // evidencia fallida que el supervisor todavía puede reintentar.
        pendientes.insertar(pendiente("ok"))
        pendientes.insertar(pendiente("rota", estado = EstadoSincronizacion.FALLIDA))

        val rutas = pendientes.todasLasRutas().toSet()
        assertEquals(setOf("/data/ok.enc", "/data/rota.enc"), rutas)
    }

    @Test
    fun `actualizar estado guarda intentos y ultimo error`() = runTest {
        pendientes.insertar(pendiente("a"))
        pendientes.actualizarEstado("a", EstadoSincronizacion.FALLIDA.name, 3, "409 conflicto")

        val fila = pendientes.obtener("a")!!
        assertEquals(EstadoSincronizacion.FALLIDA.name, fila.estado)
        assertEquals(3, fila.intentos)
        assertEquals("409 conflicto", fila.ultimoError)
    }

    @Test
    fun `borrar quita la fila de la cola`() = runTest {
        pendientes.insertar(pendiente("a"))
        assertNotNull(pendientes.obtener("a"))

        pendientes.borrar("a")
        assertNull(pendientes.obtener("a"))
    }

    @Test
    fun `la cola de un supervisor no incluye evidencias de otro`() = runTest {
        // La cola sobrevive al cierre de sesión a propósito: es trabajo real sin subir. Sin
        // este aislamiento, el supervisor que entrara después subiría la evidencia ajena
        // con su token, el servidor devolvería 403 y quedaría FALLIDA para siempre.
        pendientes.insertar(pendiente("mia", ownerUid = "u_sup_1"))
        pendientes.insertar(pendiente("del-otro", ownerUid = "u_sup_2"))

        val cola = pendientes.siguientesParaSubir("u_sup_1", 10).map { it.clienteEvidenciaId }
        assertEquals(listOf("mia"), cola)
    }

    @Test
    fun `la evidencia del otro supervisor sigue ahi esperando a que vuelva`() = runTest {
        pendientes.insertar(pendiente("del-otro", ownerUid = "u_sup_2"))

        assertEquals(0, pendientes.siguientesParaSubir("u_sup_1", 10).size)
        assertEquals(1, pendientes.siguientesParaSubir("u_sup_2", 10).size)
        assertNotNull(pendientes.obtener("del-otro"))
    }

    @Test
    fun `la cola no incluye las que aun estan en PREPARANDO`() = runTest {
        // Les falta la ubicación: subirlas ya las condenaría a salir marcadas sin
        // necesidad, cuando el enriquecimiento puede estar a punto de terminar.
        pendientes.insertar(pendiente("a-medio-preparar", estado = EstadoSincronizacion.PREPARANDO))
        assertEquals(0, pendientes.siguientesParaSubir(duenoPorDefecto, 10).size)
    }

    @Test
    fun `una PREPARANDO vieja se detecta como abandonada`() = runTest {
        val ahora = System.currentTimeMillis()
        pendientes.insertar(
            pendiente("reciente", estado = EstadoSincronizacion.PREPARANDO, capturadoEnMillis = ahora),
        )
        pendientes.insertar(
            pendiente(
                "abandonada",
                estado = EstadoSincronizacion.PREPARANDO,
                capturadoEnMillis = ahora - 10 * 60_000,
            ),
        )

        val abandonadas = pendientes.preparandoAbandonadas(ahora - 2 * 60_000)
            .map { it.clienteEvidenciaId }

        assertEquals(listOf("abandonada"), abandonadas)
    }

    @Test
    fun `el contador de pendientes incluye las PREPARANDO`() = runTest {
        // La foto ya está en disco: para el supervisor cuenta como trabajo hecho a la
        // espera de enviarse, aunque técnicamente aún no esté en la cola de subida.
        pendientes.insertar(pendiente("a", estado = EstadoSincronizacion.PREPARANDO))
        pendientes.insertar(pendiente("b"))

        assertEquals(2, pendientes.contarPendientes().first())
    }

    @Test
    fun `enriquecer completa la fila y la pone en cola`() = runTest {
        pendientes.insertar(pendiente("a", estado = EstadoSincronizacion.PREPARANDO))

        pendientes.enriquecer(
            id = "a",
            lat = 4.70057,
            lng = -74.06366,
            precision = 12.0,
            mock = false,
            token = "fake:confiable",
            checklist = """[{"id":"banos","pregunta":"x","respuesta":"si"}]""",
            estado = EstadoSincronizacion.PENDIENTE.name,
        )

        val fila = pendientes.obtener("a")!!
        assertEquals(EstadoSincronizacion.PENDIENTE.name, fila.estado)
        assertEquals(4.70057, fila.lat!!, 0.00001)
        assertEquals("fake:confiable", fila.integrityToken)
        assertEquals(1, pendientes.siguientesParaSubir(duenoPorDefecto, 10).size)
    }

    @Test
    fun `insertar dos veces el mismo id de cliente falla en vez de duplicar`() = runTest {
        pendientes.insertar(pendiente("mismo-uuid"))

        val duplicado = runCatching { pendientes.insertar(pendiente("mismo-uuid")) }

        assertTrue("El insert duplicado debería fallar", duplicado.isFailure)
        assertEquals(1, pendientes.observarTodas().first().size)
    }
}
