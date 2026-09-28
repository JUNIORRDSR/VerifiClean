package com.verificlean.app.data.remote

import com.verificlean.app.data.remote.dto.ErrorEnvelopeDto
import com.verificlean.app.data.remote.dto.EvidenciaConfirmacionDto
import com.verificlean.app.data.remote.dto.LoginResponseDto
import com.verificlean.app.data.remote.dto.PaginaVisitasSupervisorDto
import com.verificlean.app.data.remote.dto.ReporteDto
import com.verificlean.app.data.remote.dto.VisitaDetalleAdminDto
import com.verificlean.app.data.remote.dto.VisitaDetalleSupervisorDto
import com.verificlean.app.data.remote.dto.VisitaResumenSupervisorDto
import com.verificlean.app.domain.model.MotivoAlerta
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato: JSON tal como lo emite el backend → DTO de Kotlin.
 *
 * Los tests de mapeadores construyen los DTO a mano, así que no detectan que un campo
 * cambie de nombre o de tipo en el servidor. Estos parten de **texto JSON literal**,
 * copiado de la forma que produce `backend/src/routes/mappers.ts`, y usan la misma
 * instancia de `Json` que configura Retrofit en `di/Modulos.kt`.
 *
 * Si el backend cambia una respuesta, esto falla aquí — en la suite, en segundos — en vez
 * de fallar en el teléfono de un supervisor a mitad de una ronda.
 */
class ContratoDtosTest {

    /** Misma configuración que `RedModule.json()`. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    // -------------------------------------------------- respuesta al supervisor

    @Test
    fun `el login no tiene campos para revelar el dispositivo vinculado`() {
        val cuerpo = """
            {"usuario":{"id":"u1","nombre":"Carlos","email":"carlos@example.com",
             "rol":"supervisor","activo":true,"deviceId":"huella-servidor"},
             "dispositivoVinculado":false}
        """.trimIndent()

        val dto = json.decodeFromString<LoginResponseDto>(cuerpo)
        val camposRespuesta = LoginResponseDto::class.java.declaredFields.map { it.name }.toSet()
        val camposUsuario = dto.usuario::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("El estado de vínculo no puede viajar al cliente", "dispositivoVinculado" !in camposRespuesta)
        assertTrue("La huella vinculada no puede viajar al cliente", "deviceId" !in camposUsuario)
        assertEquals("u1", dto.usuario.id)
    }

    @Test
    fun `la confirmacion de evidencia deserializa y no trae veredicto`() {
        // Forma exacta de `evidenciaConfirmacionDto`.
        val cuerpo = """
            {"id":"9f1c","visitId":"visit_demo_1","timestampServidor":"2026-08-15T22:31:05.017Z"}
        """.trimIndent()

        val dto = json.decodeFromString<EvidenciaConfirmacionDto>(cuerpo)

        assertEquals("9f1c", dto.id)
        assertEquals("visit_demo_1", dto.visitId)
        // El tipo no tiene ni siquiera un campo donde meter el veredicto: la garantía es
        // estructural, no una comprobación en tiempo de ejecución.
        assertTrue(
            EvidenciaConfirmacionDto::class.java.declaredFields
                .none { it.name in setOf("esSospechosa", "motivosAlerta", "hashSHA256") },
        )
    }

    @Test
    fun `el detalle del supervisor no trae veredicto ni radio de geocerca`() {
        // Cuerpo literal de `visitaDetalleSupervisorDto`. Faltan dos campos a propósito:
        // `tieneSospechosas`, que sería el veredicto, y `siteRadioPermitidoMetros`, que es
        // el umbral con el que se juzga al supervisor. Cualquiera de los dos le diría si
        // su evidencia va a salir marcada.
        val cuerpo = """
            {"id":"visit_demo_1","siteId":"site_1","siteClienteId":"cli_bavaria",
             "siteNombreCliente":"Bavaria S.A.","siteNombreSede":"Torre Bavaria - Piso 12",
             "siteDireccion":"Cra 53 A #127-35","siteLat":4.700543,"siteLng":-74.063644,
             "supervisorId":"u_sup_1","supervisorNombre":"Carlos",
             "estado":"en_progreso","fechaAsignada":"2026-08-15T02:35:05.017Z",
             "checklist":[{"id":"banos","pregunta":"¿Baños limpios?","respuesta":null,"nota":null}],
             "evidenciaCount":1,
             "evidencias":[{"id":"9f1c","visitId":"visit_demo_1","timestampServidor":"2026-08-15T22:31:05.017Z"}]}
        """.trimIndent()

        val dto = json.decodeFromString<VisitaDetalleSupervisorDto>(cuerpo)

        assertEquals(1, dto.evidenciaCount)
        assertEquals(1, dto.evidencias.size)
        assertTrue(
            VisitaDetalleSupervisorDto::class.java.declaredFields
                .none {
                    it.name in setOf(
                        "tieneSospechosas",
                        "siteRadioPermitidoMetros",
                        "revisionAlerta",
                    )
                },
        )
        assertEquals(1, dto.checklist.size)
        // El dominio deja el radio en null: ninguna pantalla del supervisor puede leerlo
        // aunque un backend futuro volviera a mandarlo.
        assertNull(dto.aDominio().siteRadioPermitidoMetros)
    }

    @Test
    fun `si el servidor volviera a mandar el radio, el dominio lo sigue ignorando`() {
        // Guarda contra la regresión más fácil de cometer: reañadir el campo en
        // `visitaDetalleSupervisorDto` sin darse cuenta de lo que significa.
        val cuerpo = """
            {"id":"v1","siteId":"s1","siteNombreCliente":"Bavaria","siteNombreSede":"Torre",
             "siteLat":4.7,"siteLng":-74.06,"siteRadioPermitidoMetros":150,
             "supervisorId":"u1","supervisorNombre":"Carlos","estado":"pendiente",
             "fechaAsignada":"2026-08-15T02:35:05.017Z","tieneSospechosas":true}
        """.trimIndent()

        val detalle = json.decodeFromString<VisitaDetalleSupervisorDto>(cuerpo).aDominio()

        assertNull(detalle.siteRadioPermitidoMetros)
        assertEquals(false, detalle.visita.tieneSospechosas)
    }

    @Test
    fun `una pagina de visitas del supervisor deserializa sin el flag de sospecha`() {
        val cuerpo = """
            {"items":[{"id":"v1","siteId":"s1","siteClienteId":"c1","siteNombreCliente":"Bavaria",
             "siteNombreSede":"Torre","supervisorId":"u1","supervisorNombre":"Carlos",
             "estado":"pendiente","fechaAsignada":"2026-08-15T02:35:05.017Z","evidenciaCount":0,
             "tieneSospechosas":true}],
             "siguienteCursor":null}
        """.trimIndent()

        val dto = json.decodeFromString<PaginaVisitasSupervisorDto>(cuerpo)

        assertEquals(1, dto.items.size)
        assertNull(dto.siguienteCursor)
        assertEquals(false, dto.items.single().aDominio().tieneSospechosas)
        assertTrue(
            VisitaResumenSupervisorDto::class.java.declaredFields
                .none { it.name in setOf("tieneSospechosas", "revisionAlerta") },
        )
    }

    // ------------------------------------------------------- respuesta al admin

    @Test
    fun `el detalle del admin trae veredicto, hash y alertas`() {
        val cuerpo = """
            {"id":"v1","siteId":"s1","siteClienteId":"c1","siteNombreCliente":"Bavaria",
             "siteNombreSede":"Torre","siteDireccion":"Cra 1","siteLat":4.7,"siteLng":-74.06,
             "siteRadioPermitidoMetros":150,"supervisorId":"u1","supervisorNombre":"Carlos",
             "estado":"en_progreso","fechaAsignada":"2026-08-15T02:35:05.017Z",
             "evidenciaCount":1,"tieneSospechosas":true,"checklist":[],
             "revisionAlerta":{"estado":"justificada","nota":"Acceso alterno autorizado.",
               "revisadaPorId":"admin-1","revisadaPorNombre":"Ana",
               "revisadaEn":"2026-08-16T14:00:00Z"},
             "evidencias":[{"id":"e1","timestampServidor":"2026-08-15T22:31:05.017Z",
                            "distanciaAlSitioMetros":12456.7,"esSospechosa":true}],
             "evidenciasCompletas":[{"id":"e1","visitId":"v1","siteId":"s1","supervisorId":"u1",
               "fotoUrl":"https://storage.example/firmada","hashSHA256":"${"a".repeat(64)}",
               "lat":4.61,"lng":-74.15,"precisionMetros":8.0,"distanciaAlSitioMetros":12456.7,
               "timestampServidor":"2026-08-15T22:31:05.017Z","mockLocationReportada":true,
               "esSospechosa":true,
               "motivosAlerta":["FUERA_DE_GEOFENCE","MOCK_LOCATION"],
               "integrity":{"veredicto":"CONFIABLE","deviceRecognition":["MEETS_DEVICE_INTEGRITY"],
                            "appRecognition":"PLAY_RECOGNIZED","motivo":null}}],
             "alertas":[{"motivo":"FUERA_DE_GEOFENCE","conteo":1,"descripcion":"..."},
                        {"motivo":"MOCK_LOCATION","conteo":1,"descripcion":"..."}]}
        """.trimIndent()

        val dto = json.decodeFromString<VisitaDetalleAdminDto>(cuerpo)
        val dominio = dto.aDominio()

        assertTrue(dominio.detalle.visita.tieneSospechosas)
        assertEquals("Ana", dominio.revisionAlerta.revisadaPorNombre)
        assertEquals(2, dominio.evidenciasCompletas.first().motivosAlerta.size)
        assertEquals(2, dominio.alertas.size)
        assertTrue(dominio.evidenciasCompletas.first().fotoUrl!!.startsWith("https://"))
    }

    @Test
    fun `un motivo de alerta que la app no conoce no rompe la deserializacion`() {
        // El backend puede estrenar reglas antifraude sin obligar a actualizar la app.
        val cuerpo = """
            {"id":"e1","visitId":"v1","hashSHA256":"${"a".repeat(64)}","lat":4.7,"lng":-74.06,
             "distanciaAlSitioMetros":10.0,"timestampServidor":"2026-08-15T22:31:05.017Z",
             "esSospechosa":true,"motivosAlerta":["FUERA_DE_GEOFENCE","REGLA_DEL_FUTURO"]}
        """.trimIndent()

        val dominio = json
            .decodeFromString<com.verificlean.app.data.remote.dto.EvidenciaDto>(cuerpo)
            .aDominio()

        assertEquals(2, dominio.motivosAlerta.size)
        assertTrue(dominio.motivosAlerta.contains(MotivoAlerta.DESCONOCIDO))
    }

    @Test
    fun `los tres motivos nuevos se mapean a su enum, no a DESCONOCIDO`() {
        val cuerpo = """
            {"id":"e1","visitId":"v1","hashSHA256":"${"a".repeat(64)}","lat":4.7,"lng":-74.06,
             "distanciaAlSitioMetros":10.0,"timestampServidor":"2026-08-15T22:31:05.017Z",
             "esSospechosa":true,
             "motivosAlerta":["TOKEN_INTEGRIDAD_REUTILIZADO","TOKEN_INTEGRIDAD_CADUCADO","CHECKLIST_ALTERADO"]}
        """.trimIndent()

        val motivos = json
            .decodeFromString<com.verificlean.app.data.remote.dto.EvidenciaDto>(cuerpo)
            .aDominio().motivosAlerta

        val esperados: List<MotivoAlerta> = listOf(
            MotivoAlerta.TOKEN_INTEGRIDAD_REUTILIZADO,
            MotivoAlerta.TOKEN_INTEGRIDAD_CADUCADO,
            MotivoAlerta.CHECKLIST_ALTERADO,
        )
        assertEquals(esperados, motivos)
    }

    @Test
    fun `la app conoce todos los motivos que puede emitir el backend`() {
        // Lista copiada de `MOTIVOS_ALERTA` en backend/src/domain/types.ts. Si el backend
        // añade uno y la app no, aquí se ve: sin este test el síntoma sería un panel
        // mostrando "DESCONOCIDO" sin explicar nada al admin.
        val delBackend = listOf(
            "FUERA_DE_GEOFENCE", "IMAGEN_DUPLICADA", "DISPOSITIVO_NO_CONFIABLE",
            "MOCK_LOCATION", "PRECISION_GPS_BAJA", "DISPOSITIVO_NO_VINCULADO",
            "HASH_NO_COINCIDE", "INTEGRIDAD_NO_EVALUADA", "TOKEN_INTEGRIDAD_REUTILIZADO",
            "TOKEN_INTEGRIDAD_CADUCADO", "CHECKLIST_ALTERADO",
        )
        val deLaApp = MotivoAlerta.entries.map { it.name }.toSet()

        val faltantes = delBackend.filterNot { it in deLaApp }
        assertTrue("La app no conoce estos motivos: $faltantes", faltantes.isEmpty())

        // Y cada uno tiene texto que el admin pueda leer.
        MotivoAlerta.entries.forEach {
            assertTrue("Falta descripción de $it", it.descripcion.length > 20)
        }
    }

    // ------------------------------------------------------------------ errores

    @Test
    fun `el envelope de error con detalles de texto deserializa`() {
        val cuerpo = """
            {"error":{"codigo":"SOLICITUD_INVALIDA","mensaje":"Hay campos inválidos.",
                      "detalles":{"lat":"fuera de rango"}}}
        """.trimIndent()

        val dto = json.decodeFromString<ErrorEnvelopeDto>(cuerpo)

        assertEquals("fuera de rango", dto.error.detallesComoTexto()["lat"])
    }

    @Test
    fun `el envelope con detalles que NO son texto tambien deserializa`() {
        // `sinResponder` llega como array y `maximo` como número. Con `Map<String,String>`
        // esta respuesta perfectamente válida no deserializaba, el envelope se descartaba
        // entero y el usuario acababa viendo un error genérico en vez del real.
        val cuerpo = """
            {"error":{"codigo":"CONFLICTO","mensaje":"El checklist tiene ítems sin responder.",
                      "detalles":{"sinResponder":["banos","pisos"],"maximo":5000}}}
        """.trimIndent()

        val dto = json.decodeFromString<ErrorEnvelopeDto>(cuerpo)
        val texto = dto.error.detallesComoTexto()

        assertEquals("El checklist tiene ítems sin responder.", dto.error.mensaje)
        assertTrue(texto["sinResponder"]!!.contains("banos"))
        assertEquals("5000", texto["maximo"])
    }

    @Test
    fun `el envelope sin detalles deserializa`() {
        val cuerpo = """{"error":{"codigo":"NO_AUTORIZADO","mensaje":"Token inválido"}}"""
        val dto = json.decodeFromString<ErrorEnvelopeDto>(cuerpo)

        assertTrue(dto.error.detallesComoTexto().isEmpty())
    }

    // ------------------------------------------------------------------ reporte

    @Test
    fun `el reporte del admin deserializa entero`() {
        val cuerpo = """
            {"clienteId":"cli_bavaria","nombreCliente":"Bavaria S.A.",
             "desde":"2026-01-01T00:00:00.000Z","hasta":"2026-12-31T00:00:00.000Z",
             "generadoEn":"2026-08-15T22:40:00.000Z",
             "resumen":{"visitasAsignadas":3,"visitasCompletadas":1,"visitasPendientes":2,
                        "cumplimientoPorcentaje":33.3,"evidenciasTotales":4,
                        "evidenciasSospechosas":1,
                        "motivosFrecuentes":[{"motivo":"FUERA_DE_GEOFENCE","conteo":1}]},
             "porSede":[{"siteId":"s1","nombreSede":"Torre","visitasAsignadas":2,
                         "visitasCompletadas":1,"evidenciasSospechosas":1}]}
        """.trimIndent()

        val dto = json.decodeFromString<ReporteDto>(cuerpo)

        assertEquals(33.3, dto.resumen.cumplimientoPorcentaje, 0.01)
        assertEquals(1, dto.resumen.motivosFrecuentes.size)
        assertEquals("Torre", dto.porSede.first().nombreSede)
    }

    @Test
    fun `un campo nuevo del backend no rompe nada`() {
        val cuerpo = """
            {"id":"9f1c","visitId":"v1","timestampServidor":"2026-08-15T22:31:05.017Z",
             "campoQueElBackendEstrenaraManana":{"anidado":true}}
        """.trimIndent()

        assertEquals("9f1c", json.decodeFromString<EvidenciaConfirmacionDto>(cuerpo).id)
    }
}
