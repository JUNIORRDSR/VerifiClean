package com.verificlean.app.ui.pantallas.gestion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validación del formulario de alta de sede.
 *
 * El campo que de verdad importa es el radio: es el umbral con el que se juzga a una
 * persona. Un radio de 10 m haría que casi toda evidencia legítima saliera marcada como
 * fuera de geocerca, y uno de 5 km haría que el sistema no probara nada.
 */
class FormularioSedeTest {

    private fun formulario(
        lat: String = "4.700543",
        lng: String = "-74.063644",
        radio: String = "150",
    ) = FormularioSede(
        clienteId = "cli_bavaria",
        nombreCliente = "Bavaria S.A.",
        nombreSede = "Torre Bavaria",
        direccion = "Cra 53 A #127-35",
        lat = lat,
        lng = lng,
        radio = radio,
    )

    // ------------------------------------------------------- separador decimal

    @Test
    fun `acepta coma como separador decimal`() {
        // El teclado numérico de un teléfono en español de Colombia ofrece coma. Sin esto,
        // escribir la coordenada como se escribe aquí daba "Latitud entre -90 y 90".
        val f = formulario(lat = "4,700543", lng = "-74,063644")

        assertEquals(4.700543, f.latValida!!, 0.000001)
        assertEquals(-74.063644, f.lngValida!!, 0.000001)
        assertTrue(f.puedeGuardar)
    }

    @Test
    fun `acepta punto como separador decimal`() {
        val f = formulario(lat = "4.700543", lng = "-74.063644")
        assertEquals(4.700543, f.latValida!!, 0.000001)
        assertTrue(f.puedeGuardar)
    }

    @Test
    fun `acepta coma en el radio`() {
        assertEquals(150.5, formulario(radio = "150,5").radioValido!!, 0.001)
    }

    @Test
    fun `ignora espacios alrededor`() {
        assertEquals(4.7, formulario(lat = "  4.7  ").latValida!!, 0.001)
    }

    @Test
    fun `prioriza las coordenadas de una url de Google Maps sobre otros numeros`() {
        val url = "https://www.google.com/maps/place/Calle+127/@4.700543,-74.063644,17z"
        assertEquals(
            "4.700543" to "-74.063644",
            FormularioSede.coordenadasDesdeTexto(url),
        )
    }

    @Test
    fun `el id de cliente nuevo es opaco y cabe en el contrato`() {
        val id = FormularioSede.nuevoClienteId()
        assertTrue(id.startsWith("cli_"))
        assertTrue(id.length <= 64)
        assertFalse(id.contains("bavaria"))
    }

    // --------------------------------------------------------------- rangos

    @Test
    fun `rechaza latitud fuera de rango`() {
        assertNull(formulario(lat = "91").latValida)
        assertNull(formulario(lat = "-91").latValida)
        assertNotNull(formulario(lat = "90").latValida)
    }

    @Test
    fun `rechaza longitud fuera de rango`() {
        assertNull(formulario(lng = "181").lngValida)
        assertNotNull(formulario(lng = "-180").lngValida)
    }

    @Test
    fun `el radio minimo son 50 metros, igual que en el servidor`() {
        // Por debajo, el error típico de PRIORITY_BALANCED_POWER_ACCURACY marcaría como
        // fraude a supervisores que sí estuvieron en la sede.
        assertNull(formulario(radio = "49").radioValido)
        assertNotNull(formulario(radio = "50").radioValido)
    }

    @Test
    fun `el radio maximo son 2000 metros, igual que en el servidor`() {
        assertNotNull(formulario(radio = "2000").radioValido)
        assertNull(formulario(radio = "2001").radioValido)
    }

    @Test
    fun `el error del radio explica por que, no sólo que está mal`() {
        val error = formulario(radio = "10").errorRadio
        assertNotNull(error)
        assertTrue(
            "El mensaje debe explicar la consecuencia: $error",
            error!!.contains("GPS") || error.contains("alertas"),
        )
    }

    @Test
    fun `un campo vacio no muestra error todavia`() {
        // Marcar en rojo un campo que el usuario aún no ha tocado es ruido.
        val f = formulario(lat = "", lng = "", radio = "")
        assertNull(f.errorLat)
        assertNull(f.errorLng)
        assertNull(f.errorRadio)
        assertFalse(f.puedeGuardar)
    }

    @Test
    fun `un valor no numerico si muestra error`() {
        assertNotNull(formulario(lat = "por ahí").errorLat)
    }

    // --------------------------------------------------------- puedeGuardar

    @Test
    fun `exige todos los campos de texto`() {
        assertFalse(formulario().copy(clienteId = "").puedeGuardar)
        assertFalse(formulario().copy(nombreCliente = "").puedeGuardar)
        assertFalse(formulario().copy(nombreSede = "").puedeGuardar)
        assertFalse(formulario().copy(direccion = "").puedeGuardar)
    }

    @Test
    fun `el formulario por defecto trae 150 metros`() {
        // Es el valor que funciona para una sede urbana típica; que venga puesto evita que
        // alguien escriba 10 sin pensarlo.
        assertEquals(150.0, FormularioSede().radioValido!!, 0.001)
    }
}
