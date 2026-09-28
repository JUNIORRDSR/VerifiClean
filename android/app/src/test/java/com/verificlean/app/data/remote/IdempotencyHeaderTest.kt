package com.verificlean.app.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.verificlean.app.data.remote.dto.SitioNuevoDto
import com.verificlean.app.data.remote.dto.VisitaNuevaDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.util.UUID

class IdempotencyHeaderTest {

    private lateinit var servidor: MockWebServer
    private lateinit var api: VerifiCleanApi

    @Before
    fun before() {
        servidor = MockWebServer()
        servidor.start()
        api = Retrofit.Builder()
            .baseUrl(servidor.url("/"))
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(VerifiCleanApi::class.java)
    }

    @After
    fun after() = servidor.shutdown()

    @Test
    fun `crear sede envia la clave en la cabecera`() = runTest {
        servidor.enqueue(MockResponse().setResponseCode(500))
        val clave = UUID.randomUUID().toString()

        api.crearSitio(
            claveIdempotencia = clave,
            cuerpo = SitioNuevoDto(
                clienteId = "cli_1",
                nombreCliente = "Cliente Uno",
                nombreSede = "Sede Norte",
                direccion = "Cra 7 #100-20",
                lat = 4.686,
                lng = -74.045,
                radioPermitidoMetros = 150.0,
            ),
        )

        assertEquals(clave, servidor.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `asignar visita envia la clave en la cabecera`() = runTest {
        servidor.enqueue(MockResponse().setResponseCode(500))
        val clave = UUID.randomUUID().toString()

        api.asignarVisita(
            claveIdempotencia = clave,
            cuerpo = VisitaNuevaDto(
                siteId = "site_1",
                supervisorId = "u_1",
                fechaAsignada = "2026-08-17T13:00:00Z",
            ),
        )

        assertEquals(clave, servidor.takeRequest().getHeader("Idempotency-Key"))
    }
}
