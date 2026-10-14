import { getQuery, getRequestHeader, setResponseHeader, setResponseStatus, type H3Event } from 'h3'
import { defineApiHandler, limitByAccount, limitByIp, readBytes, readJson, requireAccount } from './api'
import { communityWrite, jsonObject } from '../lib/community'
import { decodeB64uExact, parseUuid } from '../lib/encoding'
import { ApiError, badRequest, notFound } from '../lib/errors'
import { useRelay } from '../relay'

const uuid = (value: unknown) => {
  const id = parseUuid(value)
  if (!id) throw badRequest('expected UUID')
  return id
}
const done = (event: H3Event, status = 204) => { setResponseStatus(event, status); return null }
const method = (event: H3Event, allowed: string[]) => {
  if (!allowed.includes(event.method)) {
    setResponseHeader(event, 'allow', allowed.join(', '))
    throw new ApiError(405, 'bad_request', 'method not allowed')
  }
}

/** Separate namespace: never accepts a personal access key or touches 1:1 mail. */
export const communityHandler = defineApiHandler(async (event) => {
  const relay = useRelay(), store = relay.communities, rates = relay.config.rateLimits
  limitByIp(event, 'communities-ip', rates.communityIp)
  const actor = await requireAccount(event)
  const path = new URL(event.path, 'http://localhost').pathname.replace(/^\/v1\/communities\/?/, '').split('/').filter(Boolean)
  const isSend = path.length === 4 && path[1] === 'messages'
  limitByAccount(actor, isSend ? 'community-send' : 'community-api', isSend ? rates.communitySend : rates.communityApi)
  if (!path.length) {
    method(event, ['GET', 'POST'])
    if (event.method === 'GET') {
      const query = getQuery(event)
      if (query.query !== undefined && typeof query.query !== 'string') throw badRequest('query must be a string')
      return store.browse((query.query as string | undefined) ?? '', query.after === undefined ? undefined : uuid(query.after))
    }
    limitByAccount(actor, 'community-create', rates.communityCreate)
    const { state, signature } = communityWrite(await readJson(event, 12000))
    const result = await store.create(actor, state, signature)
    setResponseStatus(event, 201)
    return result
  }
  if (path.length === 1 && path[0] === 'mine') {
    method(event, ['GET']); return store.mine(actor)
  }
  if (path[0] === 'inbox') {
    if (path.length === 1) { method(event, ['GET']); return store.inbox(actor) }
    if (path.length === 2 && path[1] === 'ack') {
      method(event, ['POST'])
      const body = jsonObject(await readJson(event, 4000))
      if (!Array.isArray(body.ids) || body.ids.length > 100) throw badRequest('ids must be an array of at most 100 envelope IDs')
      const ids = body.ids.map(value => {
        const id = decodeB64uExact(value, 16)
        if (!id) throw badRequest('invalid envelope ID')
        return id
      })
      await store.ack(actor, ids); return done(event)
    }
    throw notFound('endpoint')
  }
  if (path.length === 2 && path[0] === 'invites' && path[1] === 'redeem') {
    method(event, ['POST'])
    limitByAccount(actor, 'community-join', rates.communityJoin)
    const body = jsonObject(await readJson(event, 1024)), id = uuid(body.communityId)
    const token = decodeB64uExact(body.token, 32)
    if (!token) throw badRequest('invalid invitation token')
    const result = await store.redeem(actor, id, token)
    setResponseStatus(event, 202); return result
  }
  const id = uuid(path[0])
  if (path.length === 1) {
    method(event, ['GET', 'DELETE'])
    if (event.method === 'GET') return store.get(actor, id)
    await store.remove(actor, id); return done(event)
  }
  if (path.length === 2 && path[1] === 'state') {
    method(event, ['PUT'])
    const { state, signature } = communityWrite(await readJson(event, 12000))
    return store.update(actor, id, state, signature)
  }
  if (path.length === 3 && path[1] === 'members' && path[2] === 'me') {
    method(event, ['DELETE']); await store.leave(actor, id); return done(event)
  }
  if (path[1] === 'requests') {
    if (path.length === 3) {
      method(event, ['DELETE'])
      if (path[2] === 'me') await store.withdraw(actor, id)
      else await store.reject(actor, id, uuid(path[2]))
      return done(event)
    }
    if (path.length !== 2) throw notFound('endpoint')
    method(event, ['GET', 'POST'])
    if (event.method === 'GET') return store.pending(actor, id)
    limitByAccount(actor, 'community-join', rates.communityJoin)
    await store.request(actor, id); return done(event, 202)
  }
  if (path.length === 2 && path[1] === 'invites') {
    method(event, ['POST', 'DELETE'])
    if (event.method === 'DELETE') { await store.revokeInvites(actor, id); return done(event) }
    const body = jsonObject(await readJson(event, 512))
    if (typeof body.expiresInSeconds !== 'number') throw badRequest('expiresInSeconds must be a number')
    const result = await store.invite(actor, id, body.expiresInSeconds)
    setResponseStatus(event, 201); return result
  }
  if (isSend) {
    method(event, ['PUT'])
    const header = getRequestHeader(event, 'x-community-revision')
    const revision = header && /^[1-9]\d{0,15}$/.test(header) ? Number(header) : NaN
    if (!Number.isSafeInteger(revision)) throw badRequest('invalid X-Community-Revision')
    const recipient = uuid(path[2]), messageId = uuid(path[3])
    const body = await readBytes(event, 65536)
    if (!body.length) throw badRequest('empty envelope')
    const result = await store.send(actor, id, recipient, messageId, revision, body)
    setResponseStatus(event, 202); return result
  }
  throw notFound('endpoint')
})
