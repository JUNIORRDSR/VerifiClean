import { crearApp } from './app'
import { cargarEnv } from './config/env'
import { crearContenedor } from './container'
import { logger } from './lib/logger'

async function main(): Promise<void> {
  const env = cargarEnv()
  const contenedor = await crearContenedor(env)
  const app = crearApp(contenedor)

  // En modo memoria la base arranca vacía y la app Android no tendría ni una visita
  // que abrir. Se siembra con los datos demo para poder desarrollar de inmediato.
  // Sólo aplica en memoria: contra Firestore, sembrar es un acto explícito (npm run seed).
  if (env.DB_MODE === 'memoria') {
    const { sembrar } = await import('./dev/seed')
    const resultado = await sembrar(contenedor.db, contenedor.auth)
    logger.info('base en memoria sembrada con datos demo', {
      usuarios: resultado.usuarios,
      sitios: resultado.sitios,
      visitas: resultado.visitas,
      // En AUTH_MODE=fake el token es `dev:<uid>`, así que los uid son lo que necesita
      // quien pruebe con curl o Postman.
      tokensDeEjemplo: ['dev:u_admin', 'dev:u_sup_1', 'dev:u_sup_2'],
    })
  }

  const servidor = app.listen(env.PORT, () => {
    logger.info('verificlean-api escuchando', {
      puerto: env.PORT,
      nodeEnv: env.NODE_ENV,
      db: env.DB_MODE,
      auth: env.AUTH_MODE,
      storage: env.STORAGE_MODE,
      integrity: env.INTEGRITY_MODE,
    })
  })

  // Cloud Run manda SIGTERM antes de retirar la instancia. Cerrar ordenadamente evita
  // cortar a mitad la subida de una evidencia que ya estaba en vuelo.
  const apagar = (senal: string) => {
    logger.info('apagando', { senal })
    servidor.close(() => process.exit(0))
    setTimeout(() => process.exit(1), 10_000).unref()
  }
  process.on('SIGTERM', () => apagar('SIGTERM'))
  process.on('SIGINT', () => apagar('SIGINT'))
}

main().catch((e) => {
  logger.error('Fallo al arrancar', { error: e instanceof Error ? e.message : String(e) })
  process.exit(1)
})
