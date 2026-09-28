import request from 'supertest'
import { randomUUID } from 'node:crypto'
import { beforeEach, describe, expect, it } from 'vitest'
import { sha256Hex } from '../src/lib/hash'
import { calcularNonceEsperado } from '../src/lib/nonce'
import type { Evidencia } from '../src/domain/types'
import {
  comoUsuario,
  crearBanco,
  escenarioBase,
  jpegDePrueba,
  sembrarVisita,
  type Banco,
} from './helpers'

/**
 * El endpoint que sostiene el producto.
 *
 * Dos invariantes se comprueban en casi todos los casos:
 *
 * 1. **Nunca rechaza por sospecha.** Una evidencia dudosa entra marcada, no rebotada.
 * 2. **Nunca revela el veredicto al supervisor.** La respuesta es confirmación de
 *    registro y nada más; los motivos sólo existen para el admin. Devolvérselos al
 *    supervisor convertiría la API en un oráculo donde ajusta y reintenta hasta pasar,
 *    que es justo lo que la regla 1 quiere evitar.
 *
 * Por eso el veredicto se verifica leyendo la evidencia guardada, no la respuesta HTTP.
 */

let banco: Banco
let ctx: Awaited<ReturnType<typeof escenarioBase>>

beforeEach(async () => {
  banco = await crearBanco()
  ctx = await escenarioBase(banco)
})

/** Coordenada dentro del radio de 150 m del sitio del escenario base. */
const DENTRO = { lat: 4.70057, lng: -74.06366 }
/** Mismo Bogotá, ~4 km al sur: fuera de cualquier margen razonable. */
const LEJOS = { lat: 4.667209, lng: -74.05372 }

/** Lee el veredicto real, el que el supervisor no ve. */
async function evidenciaGuardada(visitId = ctx.visita.id): Promise<Evidencia> {
  const todas = await banco.db.evidencias.porVisita(visitId)
  const ultima = todas.at(-1)
  if (!ultima) throw new Error(`No hay evidencias en la visita ${visitId}`)
  return ultima
}

function subir(
  visitId: string,
  opciones: {
    uid?: string
    lat?: number
    lng?: number
    contenidoFoto?: string
    clienteEvidenciaId?: string
    precisionMetros?: number
    mockLocation?: boolean
    integrityToken?: string
    hashClienteReportado?: string
    deviceId?: string
    foto?: Buffer
    checklist?: unknown
  } = {},
) {
  const req = request(banco.app)
    .post(`/v1/visits/${visitId}/evidence`)
    .set('Authorization', comoUsuario(opciones.uid ?? ctx.supervisor.id))

  if (opciones.deviceId) req.set('X-Device-Id', opciones.deviceId)

  req
    .field('clienteEvidenciaId', opciones.clienteEvidenciaId ?? randomUUID())
    .field('lat', String(opciones.lat ?? DENTRO.lat))
    .field('lng', String(opciones.lng ?? DENTRO.lng))
    .field('integrityToken', opciones.integrityToken ?? 'fake:confiable')

  if (opciones.precisionMetros !== undefined) {
    req.field('precisionMetros', String(opciones.precisionMetros))
  }
  if (opciones.mockLocation !== undefined) {
    req.field('mockLocationReportada', String(opciones.mockLocation))
  }
  if (opciones.hashClienteReportado) {
    req.field('hashClienteReportado', opciones.hashClienteReportado)
  }
  if (opciones.checklist !== undefined) {
    req.field('checklist', JSON.stringify(opciones.checklist))
  }

  return req.attach('foto', opciones.foto ?? jpegDePrueba(opciones.contenidoFoto ?? 'foto-ok'), {
    filename: 'evidencia.jpg',
    contentType: 'image/jpeg',
  })
}

describe('POST /v1/visits/:id/evidence — la respuesta no filtra el veredicto', () => {
  it('devuelve sólo confirmación de registro, sin nada que sirva de oráculo', async () => {
    const res = await subir(ctx.visita.id, { ...LEJOS, mockLocation: true }).expect(201)

    expect(res.body).toEqual({
      id: expect.any(String),
      visitId: ctx.visita.id,
      timestampServidor: expect.any(String),
    })

    // Explícito para que una regresión futura salte aquí y no en producción.
    for (const campo of [
      'esSospechosa',
      'motivosAlerta',
      'distanciaAlSitioMetros',
      'hashSHA256',
      'integrity',
      'lat',
      'lng',
      'fotoUrl',
    ]) {
      expect(res.body, `la respuesta al supervisor no debe incluir ${campo}`).not.toHaveProperty(campo)
    }

    // Y sin embargo, el servidor sí la marcó.
    const guardada = await evidenciaGuardada()
    expect(guardada.esSospechosa).toBe(true)
    expect(guardada.motivosAlerta).toContain('FUERA_DE_GEOFENCE')
  })

  it('el detalle de visita del supervisor tampoco expone el veredicto', async () => {
    await subir(ctx.visita.id, { ...LEJOS }).expect(201)

    const res = await request(banco.app)
      .get(`/v1/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)

    expect(res.body).not.toHaveProperty('tieneSospechosas')
    expect(res.body.evidencias[0]).toEqual({
      id: expect.any(String),
      visitId: ctx.visita.id,
      timestampServidor: expect.any(String),
    })
  })

  it('la lista de visitas del supervisor no expone tieneSospechosas', async () => {
    await subir(ctx.visita.id, { ...LEJOS }).expect(201)

    const res = await request(banco.app)
      .get('/v1/visits')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)

    expect(res.body.items[0]).not.toHaveProperty('tieneSospechosas')
  })

  it('el admin sí ve todo', async () => {
    await subir(ctx.visita.id, { ...LEJOS }).expect(201)

    const res = await request(banco.app)
      .get(`/v1/admin/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    expect(res.body.tieneSospechosas).toBe(true)
    expect(res.body.evidenciasCompletas[0].motivosAlerta).toContain('FUERA_DE_GEOFENCE')
    expect(res.body.evidenciasCompletas[0].hashSHA256).toMatch(/^[a-f0-9]{64}$/)
  })
})

describe('POST /v1/visits/:id/evidence — camino limpio', () => {
  it('registra la evidencia sin alertas y arranca la visita', async () => {
    await subir(ctx.visita.id).expect(201)

    const guardada = await evidenciaGuardada()
    expect(guardada.esSospechosa).toBe(false)
    expect(guardada.motivosAlerta).toEqual([])
    expect(guardada.hashSHA256).toMatch(/^[a-f0-9]{64}$/)
    expect(guardada.distanciaAlSitioMetros).toBeLessThan(150)

    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    expect(visita?.estado).toBe('en_progreso')
    expect(visita?.evidenciaCount).toBe(1)
    expect(visita?.tieneSospechosas).toBe(false)
    expect(visita?.iniciadaEn).not.toBeNull()
  })

  it('sella el timestamp en el servidor e ignora el del dispositivo', async () => {
    const antes = Date.now()
    await request(banco.app)
      .post(`/v1/visits/${ctx.visita.id}/evidence`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .field('clienteEvidenciaId', randomUUID())
      .field('lat', String(DENTRO.lat))
      .field('lng', String(DENTRO.lng))
      // Reloj del dispositivo atrasado tres años.
      .field('capturadoEn', '2023-01-01T00:00:00.000Z')
      .attach('foto', jpegDePrueba(), { filename: 'e.jpg', contentType: 'image/jpeg' })
      .expect(201)

    const guardada = await evidenciaGuardada()
    expect(guardada.timestampServidor.getTime()).toBeGreaterThanOrEqual(antes)
    expect(guardada.timestampCliente?.toISOString()).toBe('2023-01-01T00:00:00.000Z')
    expect(guardada.timestampServidor.getTime()).toBeGreaterThan(
      guardada.timestampCliente!.getTime(),
    )
  })

  it('guarda los bytes exactos que llegaron, sin recomprimir', async () => {
    const foto = jpegDePrueba('bytes-exactos')
    await subir(ctx.visita.id, { foto }).expect(201)

    const guardado = [...banco.almacenamiento.archivos.values()][0]
    expect(guardado?.bytes.equals(foto)).toBe(true)
    expect((await evidenciaGuardada()).hashSHA256).toBe(sha256Hex(foto))
  })
})

describe('POST /v1/visits/:id/evidence — reglas antifraude', () => {
  it('marca fuera de geocerca sin rechazar la subida', async () => {
    await subir(ctx.visita.id, { ...LEJOS }).expect(201)

    const guardada = await evidenciaGuardada()
    expect(guardada.motivosAlerta).toContain('FUERA_DE_GEOFENCE')
    expect(guardada.distanciaAlSitioMetros).toBeGreaterThan(3_000)

    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    expect(visita?.tieneSospechosas).toBe(true)
  })

  it('marca imagen duplicada cuando el mismo hash aparece en otra visita', async () => {
    const otra = await sembrarVisita(banco, {
      id: 'visit_2',
      siteId: ctx.sitio.id,
      supervisorId: ctx.supervisor.id,
    })

    await subir(ctx.visita.id, { contenidoFoto: 'la-misma-foto' }).expect(201)
    await subir(otra.id, { contenidoFoto: 'la-misma-foto' }).expect(201)

    expect((await evidenciaGuardada(otra.id)).motivosAlerta).toContain('IMAGEN_DUPLICADA')
  })

  it('no marca duplicado por repetir foto dentro de la misma visita', async () => {
    await subir(ctx.visita.id, { contenidoFoto: 'repetida' }).expect(201)
    await subir(ctx.visita.id, { contenidoFoto: 'repetida' }).expect(201)

    expect((await evidenciaGuardada()).motivosAlerta).not.toContain('IMAGEN_DUPLICADA')
  })

  it('marca dispositivo comprometido', async () => {
    await subir(ctx.visita.id, { integrityToken: 'fake:no_confiable' }).expect(201)

    const guardada = await evidenciaGuardada()
    expect(guardada.motivosAlerta).toContain('DISPOSITIVO_NO_CONFIABLE')
    expect(guardada.integrity.veredicto).toBe('NO_CONFIABLE')
  })

  it('marca ausencia de token de Play Integrity', async () => {
    await request(banco.app)
      .post(`/v1/visits/${ctx.visita.id}/evidence`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .field('clienteEvidenciaId', randomUUID())
      .field('lat', String(DENTRO.lat))
      .field('lng', String(DENTRO.lng))
      .attach('foto', jpegDePrueba(), { filename: 'e.jpg', contentType: 'image/jpeg' })
      .expect(201)

    const guardada = await evidenciaGuardada()
    expect(guardada.motivosAlerta).toContain('INTEGRIDAD_NO_EVALUADA')
    expect(guardada.integrity.veredicto).toBe('NO_EVALUADO')
  })

  it('marca un token de integridad que no fue emitido para esta evidencia', async () => {
    // El nonce liga el token a visita + id de evidencia + hash de la foto. Un token
    // legítimo obtenido para otra captura y reutilizado aquí no cuadra.
    await subir(ctx.visita.id, { integrityToken: 'fake:nonce_malo' }).expect(201)

    const guardada = await evidenciaGuardada()
    expect(guardada.motivosAlerta).toContain('TOKEN_INTEGRIDAD_REUTILIZADO')
    // El dispositivo sigue siendo genuino: son dos hechos distintos y se reportan aparte.
    expect(guardada.integrity.veredicto).toBe('CONFIABLE')
  })

  it('marca un token de integridad emitido fuera de la ventana de validez', async () => {
    await subir(ctx.visita.id, { integrityToken: 'fake:caducado' }).expect(201)
    expect((await evidenciaGuardada()).motivosAlerta).toContain('TOKEN_INTEGRIDAD_CADUCADO')
  })

  it('no reporta problemas de token cuando no hubo token que evaluar', async () => {
    await request(banco.app)
      .post(`/v1/visits/${ctx.visita.id}/evidence`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .field('clienteEvidenciaId', randomUUID())
      .field('lat', String(DENTRO.lat))
      .field('lng', String(DENTRO.lng))
      .attach('foto', jpegDePrueba(), { filename: 'e.jpg', contentType: 'image/jpeg' })
      .expect(201)

    const motivos = (await evidenciaGuardada()).motivosAlerta
    expect(motivos).not.toContain('TOKEN_INTEGRIDAD_REUTILIZADO')
    expect(motivos).not.toContain('TOKEN_INTEGRIDAD_CADUCADO')
  })

  it('sin ubicación marca SIN_UBICACION y no inventa distancia', async () => {
    // El dispositivo puede no conseguir GPS. Lo que no puede pasar es que el panel del
    // admin muestre "8.241 km" como si fuera una medición: sobre un número así alguien
    // podría abrir un expediente disciplinario.
    await request(banco.app)
      .post(`/v1/visits/${ctx.visita.id}/evidence`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .field('clienteEvidenciaId', randomUUID())
      .field('integrityToken', 'fake:confiable')
      .attach('foto', jpegDePrueba(), { filename: 'e.jpg', contentType: 'image/jpeg' })
      .expect(201)

    const guardada = await evidenciaGuardada()
    expect(guardada.lat).toBeNull()
    expect(guardada.lng).toBeNull()
    expect(guardada.distanciaAlSitioMetros).toBeNull()
    expect(guardada.motivosAlerta).toContain('SIN_UBICACION')
    expect(guardada.motivosAlerta).not.toContain('FUERA_DE_GEOFENCE')
  })

  it('sigue rechazando una coordenada presente pero imposible', async () => {
    const res = await subir(ctx.visita.id, { lat: 200 }).expect(400)
    expect(res.body.error.codigo).toBe('SOLICITUD_INVALIDA')
  })

  it('marca ubicación simulada', async () => {
    await subir(ctx.visita.id, { mockLocation: true }).expect(201)
    expect((await evidenciaGuardada()).motivosAlerta).toContain('MOCK_LOCATION')
  })

  it('marca hash declarado que no corresponde a los bytes recibidos', async () => {
    await subir(ctx.visita.id, {
      contenidoFoto: 'real',
      hashClienteReportado: sha256Hex(Buffer.from('otra cosa')),
    }).expect(201)

    expect((await evidenciaGuardada()).motivosAlerta).toContain('HASH_NO_COINCIDE')
  })

  it('marca dispositivo distinto al vinculado', async () => {
    await banco.db.usuarios.actualizar(ctx.supervisor.id, { deviceId: 'device-registrado' })
    await subir(ctx.visita.id, { deviceId: 'device-de-otro' }).expect(201)

    expect((await evidenciaGuardada()).motivosAlerta).toContain('DISPOSITIVO_NO_VINCULADO')
  })

  it('no alerta por dispositivo cuando el supervisor todavía no tiene uno registrado', async () => {
    await subir(ctx.visita.id, { deviceId: 'primer-device' }).expect(201)
    expect((await evidenciaGuardada()).motivosAlerta).not.toContain('DISPOSITIVO_NO_VINCULADO')
  })

  it('marca un checklist que no es el asignado a la visita', async () => {
    await subir(ctx.visita.id, {
      checklist: [{ id: 'inventado', pregunta: '¿Todo bien?', respuesta: 'si', nota: null }],
    }).expect(201)

    expect((await evidenciaGuardada()).motivosAlerta).toContain('CHECKLIST_ALTERADO')
  })

  it('no marca el checklist cuando coincide con el asignado', async () => {
    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    await subir(ctx.visita.id, {
      checklist: visita!.checklist.map((i) => ({ ...i, respuesta: 'si' })),
    }).expect(201)

    expect((await evidenciaGuardada()).motivosAlerta).not.toContain('CHECKLIST_ALTERADO')
  })
})

describe('nonce de Play Integrity', () => {
  it('cambia si cambia la visita, la evidencia o la foto', () => {
    const base = { visitId: 'v1', clienteEvidenciaId: 'e1', hashSHA256: 'h1' }
    const nonce = calcularNonceEsperado(base)

    expect(calcularNonceEsperado({ ...base, visitId: 'v2' })).not.toBe(nonce)
    expect(calcularNonceEsperado({ ...base, clienteEvidenciaId: 'e2' })).not.toBe(nonce)
    expect(calcularNonceEsperado({ ...base, hashSHA256: 'h2' })).not.toBe(nonce)
  })

  it('es determinista para los mismos datos', () => {
    const datos = { visitId: 'v1', clienteEvidenciaId: 'e1', hashSHA256: 'h1' }
    expect(calcularNonceEsperado(datos)).toBe(calcularNonceEsperado(datos))
  })

  it('coincide byte a byte con el que calcula la app Android', () => {
    // El mismo valor está fijado en `EvidenciaRepositoryImplTest` del lado Android. Si una
    // de las dos implementaciones cambia sin la otra, este test lo dice — de lo contrario
    // el síntoma sería que TODAS las evidencias salen marcadas como token reutilizado, sin
    // ninguna pista de por qué.
    expect(
      calcularNonceEsperado({ visitId: 'v1', clienteEvidenciaId: 'e1', hashSHA256: 'h1' }),
    ).toBe('YrxEOmy2NqR3gCbiWxGqA_P0FuyDNXYoKh2I5n5wseE')
  })
})

describe('POST /v1/visits/:id/evidence — permisos', () => {
  it('un admin no puede subir evidencia: rompería la atribución al supervisor', async () => {
    const res = await subir(ctx.visita.id, { uid: ctx.admin.id }).expect(403)
    expect(res.body.error.codigo).toBe('PROHIBIDO')
  })

  it('devuelve 404 si otro supervisor intenta subir a una visita ajena', async () => {
    const otro = 'u_sup_2'
    banco.auth.registrar(otro, 'otro@verificlean.test')
    await banco.db.usuarios.crear({
      id: otro,
      nombre: 'Otro',
      email: 'otro@verificlean.test',
      rol: 'supervisor',
      deviceId: null,
      activo: true,
      creadoEn: new Date(),
      actualizadoEn: new Date(),
    })

    await subir(ctx.visita.id, { uid: otro }).expect(404)
  })
})

describe('POST /v1/visits/:id/evidence — idempotencia y errores', () => {
  it('devuelve 200 y la misma evidencia ante un reenvío con el mismo id de cliente', async () => {
    const clienteEvidenciaId = randomUUID()
    const primera = await subir(ctx.visita.id, { clienteEvidenciaId }).expect(201)
    const segunda = await subir(ctx.visita.id, { clienteEvidenciaId }).expect(200)

    expect(segunda.body.id).toBe(primera.body.id)
    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    expect(visita?.evidenciaCount).toBe(1)
  })

  it('el id de idempotencia de otro supervisor no devuelve su evidencia', async () => {
    // Con una búsqueda global, conocer el UUID ajeno bastaba para recibir la evidencia
    // entera del compañero —hash incluido—, que es justo lo que hace falta para fabricar
    // un duplicado que pase el filtro.
    const otro = await sembrarVisita(banco, {
      id: 'visit_del_otro',
      siteId: ctx.sitio.id,
      supervisorId: ctx.supervisor.id,
    })

    const compartido = randomUUID()
    const ajena = await subir(otro.id, { clienteEvidenciaId: compartido, contenidoFoto: 'ajena' })
      .expect(201)

    const propia = await subir(ctx.visita.id, {
      clienteEvidenciaId: compartido,
      contenidoFoto: 'propia',
    }).expect(201)

    expect(propia.body.id).not.toBe(ajena.body.id)
    expect(propia.body.visitId).toBe(ctx.visita.id)
  })

  it('rechaza un archivo que no es JPEG', async () => {
    const res = await subir(ctx.visita.id, { foto: Buffer.from('esto es un txt') }).expect(400)
    expect(res.body.error.codigo).toBe('SOLICITUD_INVALIDA')
  })

  it('rechaza coordenadas fuera de rango', async () => {
    const res = await subir(ctx.visita.id, { lat: 200 }).expect(400)
    expect(res.body.error.codigo).toBe('SOLICITUD_INVALIDA')
  })

  it('rechaza un id de cliente que no es UUID', async () => {
    const res = await subir(ctx.visita.id, { clienteEvidenciaId: 'no-es-uuid' }).expect(400)
    expect(res.body.error.detalles).toHaveProperty('clienteEvidenciaId')
  })

  it('devuelve 409 sobre una visita ya completada', async () => {
    await subir(ctx.visita.id).expect(201)
    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    await request(banco.app)
      .patch(`/v1/visits/${ctx.visita.id}/complete`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ checklist: visita!.checklist.map((i) => ({ ...i, respuesta: 'si' })) })
      .expect(200)

    const res = await subir(ctx.visita.id).expect(409)
    expect(res.body.error.codigo).toBe('CONFLICTO')
  })
})
