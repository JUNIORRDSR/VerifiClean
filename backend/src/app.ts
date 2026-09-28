import express, { type Express } from 'express'
import type { Contenedor } from './container'
import { exigirAdmin, exigirAuth } from './middleware/auth'
import { manejadorErrores, noEncontradoHandler } from './middleware/error'
import { requestId } from './middleware/requestId'
import { rutasAdmin } from './routes/admin'
import { rutasAuth } from './routes/auth'
import { rutasCatalogo } from './routes/catalog'
import { rutasVisitas } from './routes/visits'

/**
 * Construye la app sin escuchar en ningún puerto, para que los tests la monten con
 * repos en memoria y `supertest` la ataque directamente.
 *
 * Sin CORS ni helmet a propósito: el único consumidor es la app Android, y ni la
 * política de origen cruzado ni las cabeceras de endurecimiento de navegador aplican
 * a un cliente nativo. El día que exista un dashboard web, entra `cors` con una lista
 * de orígenes explícita — no antes, y no con `*`.
 */
export function crearApp(c: Contenedor): Express {
  const app = express()

  // Cloud Run va detrás de un proxy de Google: sin esto, `req.ip` sería siempre la IP
  // interna del balanceador.
  app.set('trust proxy', true)
  app.disable('x-powered-by')

  app.use(requestId)
  app.use(express.json({ limit: '1mb' }))

  app.get('/v1/health', (_req, res) => {
    res.json({ ok: true, version: '1.0.0' })
  })

  // Rutas públicas primero: al resolverse aquí, nunca alcanzan el middleware de auth.
  app.use('/v1/auth', rutasAuth(c))

  // A partir de este punto, todo `/v1/*` exige token. Va como middleware suelto y no
  // repetido en cada router para que sólo se verifique una vez por petición.
  app.use('/v1', exigirAuth(c.auth, c.db))

  // `exigirAdmin` sólo puede colgarse de un prefijo que no comparta espacio con rutas
  // de supervisor. `/v1/admin` lo cumple; el catálogo, montado en `/v1`, no — ahí el
  // rol se exige ruta por ruta dentro del propio router.
  app.use('/v1/admin', exigirAdmin, rutasAdmin(c))

  // El catálogo va antes que `/v1/visits` para que `POST /v1/visits/assign` no compita
  // con el enrutado por parámetro del router de visitas.
  app.use('/v1', rutasCatalogo(c))
  app.use('/v1/visits', rutasVisitas(c))

  app.use(noEncontradoHandler)
  app.use(manejadorErrores)

  return app
}
