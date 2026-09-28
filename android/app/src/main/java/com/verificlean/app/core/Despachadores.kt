package com.verificlean.app.core

import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Qualifier

/**
 * Los dispatchers se inyectan en vez de referenciar `Dispatchers.IO` directamente, para
 * que los tests puedan sustituirlos por uno de prueba y controlar el tiempo virtual.
 * Sin esto, cualquier test sobre un repositorio depende de temporizadores reales.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DispatcherIo
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DispatcherPorDefecto
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ScopeAplicacion

data class Despachadores(
    val io: CoroutineDispatcher,
    val porDefecto: CoroutineDispatcher,
)
