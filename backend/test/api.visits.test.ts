import request from 'supertest'
import { randomUUID } from 'node:crypto'
import { beforeEach, describe, expect, it } from 'vitest'
import {
  comoUsuario,
  crearBanco,
  escenarioBase,
  jpegDePrueba,
  sembrarUsuario,
  sembrarVisita,
  type Banco,
} from './helpers'

let banco: Banco
let ctx: Awaited<ReturnType<typeof escenarioBase>>

beforeEach(async () => {
  banco = await crearBanco()
  ctx = await escenarioBase(banco)
})

const DENTRO = { lat: 4.70057, lng: -74.06366 }

const subirEvidencia = (visitId: string, uid: string) =>
  request(banco.app)
    .post(`/v1/visits/${visitId}/evidence`)
    .set('Authorization', comoUsuario(uid))
    .field('clienteEvidenciaId', randomUUID())
    .field('lat', String(DENTRO.lat))
    .field('lng', String(DENTRO.lng))
    .field('integrityToken', 'fake:confiable')
    .attach('foto', jpegDePrueba(randomUUID()), { filename: 'e.jpg', contentType: 'image/jpeg' })

describe('autenticación', () => {
  it('rechaza sin cabecera Authorization', async () => {
    const res = await request(banco.app).get('/v1/visits').expect(401)
    expect(res.body.error.codigo).toBe('NO_AUTORIZADO')
  })

  it('rechaza un esquema que no sea Bearer', async () => {
    await request(banco.app).get('/v1/visits').set('Authorization', 'Basic abc').expect(401)
  })

  it('devuelve 403 a un token válido sin usuario dado de alta', async () => {
    const res = await request(banco.app)
      .get('/v1/visits')
      .set('Authorization', comoUsuario('uid_fantasma'))
      .expect(403)
    expect(res.body.error.mensaje).toContain('no está dado de alta')
  })

  it('devuelve 403 a un usuario inactivo', async () => {
    await sembrarUsuario(banco, { id: 'u_off', rol: 'supervisor', activo: false })
    await request(banco.app).get('/v1/visits').set('Authorization', comoUsuario('u_off')).expect(403)
  })

  it('/v1/health responde sin token', async () => {
    const res = await request(banco.app).get('/v1/health').expect(200)
    expect(res.body.ok).toBe(true)
  })
})

describe('POST /v1/auth/login', () => {
  it('devuelve el perfil y vincula el dispositivo la primera vez', async () => {
    const res = await request(banco.app)
      .post('/v1/auth/login')
      .send({ idToken: `dev:${ctx.supervisor.id}`, deviceId: 'device-abc' })
      .expect(200)

    expect(res.body.usuario.rol).toBe('supervisor')
    expect(res.body).not.toHaveProperty('dispositivoVinculado')
    expect(res.body.usuario).not.toHaveProperty('deviceId')

    const usuario = await banco.db.usuarios.obtener(ctx.supervisor.id)
    expect(usuario?.deviceId).toBe('device-abc')
  })

  it('no revela si el dispositivo esta vinculado y tampoco lo reasigna', async () => {
    const primera = await request(banco.app)
      .post('/v1/auth/login')
      .send({ idToken: `dev:${ctx.supervisor.id}`, deviceId: 'device-abc' })
      .expect(200)

    const res = await request(banco.app)
      .post('/v1/auth/login')
      .send({ idToken: `dev:${ctx.supervisor.id}`, deviceId: 'device-de-otro' })
      .expect(200)

    // Este test antes exigía `false`, pero ese bit era un oráculo: confirmaba al
    // supervisor que sus evidencias activarían DISPOSITIVO_NO_VINCULADO.
    expect(res.body).not.toHaveProperty('dispositivoVinculado')
    expect(res.body.usuario).not.toHaveProperty('deviceId')
    expect(res.body).toEqual(primera.body)
    const usuario = await banco.db.usuarios.obtener(ctx.supervisor.id)
    expect(usuario?.deviceId).toBe('device-abc') // no se reasigna solo
  })

  it('rechaza a un usuario inactivo', async () => {
    await sembrarUsuario(banco, { id: 'u_off', rol: 'supervisor', activo: false })
    await request(banco.app).post('/v1/auth/login').send({ idToken: 'dev:u_off' }).expect(403)
  })
})

describe('GET /v1/visits', () => {
  it('devuelve sólo las visitas del supervisor autenticado', async () => {
    const otro = await sembrarUsuario(banco, { id: 'u_sup_2', rol: 'supervisor' })
    await sembrarVisita(banco, { id: 'visit_ajena', siteId: ctx.sitio.id, supervisorId: otro.id })

    const res = await request(banco.app)
      .get('/v1/visits')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)

    expect(res.body.items).toHaveLength(1)
    expect(res.body.items[0].id).toBe(ctx.visita.id)
  })

  it('devuelve 403 si un supervisor pide el buzón de otro', async () => {
    await request(banco.app)
      .get('/v1/visits?supervisorId=u_otro')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(403)
  })

  it('filtra por estado', async () => {
    await sembrarVisita(banco, {
      id: 'visit_completada',
      siteId: ctx.sitio.id,
      supervisorId: ctx.supervisor.id,
      estado: 'completada',
    })

    const res = await request(banco.app)
      .get('/v1/visits?estado=completada')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)

    expect(res.body.items).toHaveLength(1)
    expect(res.body.items[0].id).toBe('visit_completada')
  })

  it('pagina con cursor sin repetir ni saltarse elementos', async () => {
    for (let i = 0; i < 5; i++) {
      await sembrarVisita(banco, {
        id: `visit_p${i}`,
        siteId: ctx.sitio.id,
        supervisorId: ctx.supervisor.id,
        fechaAsignada: new Date(Date.UTC(2026, 7, 10 + i)),
      })
    }

    const vistos: string[] = []
    let cursor: string | null = null
    for (let pagina = 0; pagina < 10; pagina++) {
      const url: string = `/v1/visits?limite=2${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`
      const res = await request(banco.app)
        .get(url)
        .set('Authorization', comoUsuario(ctx.supervisor.id))
        .expect(200)
      vistos.push(...res.body.items.map((v: { id: string }) => v.id))
      cursor = res.body.siguienteCursor
      if (!cursor) break
    }

    expect(new Set(vistos).size).toBe(vistos.length) // sin duplicados
    expect(vistos).toHaveLength(6) // las 5 nuevas + la del escenario base
  })

  it('rechaza un límite fuera de rango', async () => {
    await request(banco.app)
      .get('/v1/visits?limite=9999')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(400)
  })
})

describe('GET /v1/visits/:id', () => {
  it('devuelve el detalle con checklist y datos de la sede', async () => {
    const res = await request(banco.app)
      .get(`/v1/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)

    expect(res.body.siteLat).toBeCloseTo(ctx.sitio.lat, 5)
    expect(res.body.checklist.length).toBeGreaterThan(0)
  })

  it('no le dice al supervisor cual es el radio de su geocerca', async () => {
    // Es el umbral con el que se le va a juzgar: saber que son 150 m le dice exactamente
    // cuanto puede alejarse antes de que la evidencia salga marcada. Es el mismo oraculo
    // que devolverle `motivosAlerta`, y ocultarlo solo en la pantalla no servia de nada
    // porque el valor viajaba en el JSON.
    const res = await request(banco.app)
      .get(`/v1/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)

    expect(res.body).not.toHaveProperty('siteRadioPermitidoMetros')
    // La direccion y las coordenadas si van: tiene que llegar hasta la sede.
    expect(res.body.siteDireccion).toBe(ctx.sitio.direccion)
  })

  it('el admin si ve el radio en su propio detalle', async () => {
    const res = await request(banco.app)
      .get(`/v1/admin/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    expect(res.body.siteRadioPermitidoMetros).toBe(150)
  })

  it('no expone hash ni URL de foto al supervisor', async () => {
    await subirEvidencia(ctx.visita.id, ctx.supervisor.id).expect(201)

    const res = await request(banco.app)
      .get(`/v1/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)

    expect(res.body.evidencias).toHaveLength(1)
    expect(res.body.evidencias[0]).not.toHaveProperty('hashSHA256')
    expect(res.body.evidencias[0]).not.toHaveProperty('fotoUrl')
    expect(res.body).not.toHaveProperty('evidenciasCompletas')
  })

  it('devuelve 404 —no 403— ante una visita ajena, para no filtrar su existencia', async () => {
    const otro = await sembrarUsuario(banco, { id: 'u_sup_2', rol: 'supervisor' })
    const res = await request(banco.app)
      .get(`/v1/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(otro.id))
      .expect(404)
    expect(res.body.error.codigo).toBe('NO_ENCONTRADO')
  })
})

describe('PATCH /v1/visits/:id/complete', () => {
  const checklistRespondido = (visita: { checklist: { id: string; pregunta: string }[] }) =>
    visita.checklist.map((i) => ({ ...i, respuesta: 'si', nota: null }))

  it('cierra la visita y sella completadaEn en el servidor', async () => {
    await subirEvidencia(ctx.visita.id, ctx.supervisor.id).expect(201)
    const visita = await banco.db.visitas.obtener(ctx.visita.id)

    const res = await request(banco.app)
      .patch(`/v1/visits/${ctx.visita.id}/complete`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ checklist: checklistRespondido(visita!), observaciones: 'Todo en orden' })
      .expect(200)

    expect(res.body.estado).toBe('completada')
    expect(res.body.completadaEn).not.toBeNull()
    expect(res.body.observaciones).toBe('Todo en orden')
  })

  it('rechaza cerrar sin evidencia fotográfica', async () => {
    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    const res = await request(banco.app)
      .patch(`/v1/visits/${ctx.visita.id}/complete`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ checklist: checklistRespondido(visita!) })
      .expect(409)

    expect(res.body.error.mensaje).toContain('sin al menos una evidencia')
  })

  it('rechaza cerrar con ítems del checklist sin responder', async () => {
    await subirEvidencia(ctx.visita.id, ctx.supervisor.id).expect(201)
    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    const checklist = checklistRespondido(visita!)
    checklist[0] = { ...checklist[0]!, respuesta: null as unknown as string }

    const res = await request(banco.app)
      .patch(`/v1/visits/${ctx.visita.id}/complete`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ checklist })
      .expect(409)

    expect(res.body.error.detalles.sinResponder).toContain(visita!.checklist[0]!.id)
  })

  it('es idempotente: un segundo cierre devuelve 200, no un error', async () => {
    await subirEvidencia(ctx.visita.id, ctx.supervisor.id).expect(201)
    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    const cuerpo = { checklist: checklistRespondido(visita!) }

    const primera = await request(banco.app)
      .patch(`/v1/visits/${ctx.visita.id}/complete`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send(cuerpo)
      .expect(200)

    const segunda = await request(banco.app)
      .patch(`/v1/visits/${ctx.visita.id}/complete`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send(cuerpo)
      .expect(200)

    expect(segunda.body.completadaEn).toBe(primera.body.completadaEn)
  })
})
