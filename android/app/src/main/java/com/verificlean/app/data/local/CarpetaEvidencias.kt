package com.verificlean.app.data.local

import javax.inject.Qualifier

/**
 * Carpeta donde viven las evidencias cifradas a la espera de subirse.
 *
 * Se inyecta como `File` en vez de derivarla de `Context` dentro del almacén, para que los
 * tests puedan apuntarla a un directorio temporal y ejercitar de verdad la escritura
 * atómica y la limpieza de huérfanos sin necesidad de emulador.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CarpetaEvidencias
