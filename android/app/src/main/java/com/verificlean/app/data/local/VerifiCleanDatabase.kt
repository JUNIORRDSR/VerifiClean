package com.verificlean.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base local.
 *
 * `exportSchema = true` (por defecto) y `room.schemaLocation` en el build: el JSON del
 * esquema se versiona en `app/schemas/`. Sin él, Room no puede generar ni validar
 * migraciones y cualquier cambio de entidad obliga a borrar los datos del dispositivo —
 * lo que en esta app significa borrar evidencias sin subir.
 */
@Database(
    entities = [VisitaEntity::class, EvidenciaPendienteEntity::class],
    version = 1,
)
abstract class VerifiCleanDatabase : RoomDatabase() {
    abstract fun visitaDao(): VisitaDao
    abstract fun evidenciaPendienteDao(): EvidenciaPendienteDao

    companion object {
        const val NOMBRE = "verificlean.db"
    }
}
