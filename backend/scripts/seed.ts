/**
 * Siembra datos demo en el emulador de Firestore.
 *
 *   $ firebase emulators:start --only firestore
 *   $ FIRESTORE_EMULATOR_HOST=localhost:8080 npm run seed
 *
 * Se niega a correr sin `FIRESTORE_EMULATOR_HOST`. Sin esa comprobación, un
 * `npm run seed` distraído con las credenciales de producción cargadas escribiría
 * usuarios de prueba —con contraseñas conocidas— en la base real.
 */
import { cargarEnv } from '../src/config/env'
import { sembrar } from '../src/dev/seed'
import { DbFirestore, crearFirestore } from '../src/repos/firestore'
import { ServicioAuthFalso } from '../src/services/auth'

async function main(): Promise<void> {
  if (!process.env.FIRESTORE_EMULATOR_HOST) {
    console.error(
      'Abortado: falta FIRESTORE_EMULATOR_HOST.\n' +
        'Este script sólo siembra el emulador — nunca un proyecto real.\n' +
        'Arranca el emulador y reintenta:\n' +
        '  firebase emulators:start --only firestore\n' +
        '  FIRESTORE_EMULATOR_HOST=localhost:8080 npm run seed',
    )
    process.exit(1)
  }

  const env = cargarEnv({ ...process.env, DB_MODE: 'firestore', NODE_ENV: 'development' })
  const db = new DbFirestore(crearFirestore(env.GCP_PROJECT_ID ?? 'verificlean-local'))

  const resultado = await sembrar(db, new ServicioAuthFalso())

  console.log(
    `Sembrado en el emulador: ${resultado.usuarios} usuarios, ` +
      `${resultado.sitios} sedes, ${resultado.visitas} visitas.`,
  )
  console.table(resultado.credenciales)
}

main().catch((e) => {
  console.error('Fallo al sembrar:', e)
  process.exit(1)
})
