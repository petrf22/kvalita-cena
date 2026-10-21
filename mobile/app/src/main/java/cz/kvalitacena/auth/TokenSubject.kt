package cz.kvalitacena.auth

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `sub` z access tokenu — server do něj dává `publicUid` uživatele (`JwtService`). Podpis se
 * tu neověřuje (to dělá server u každého requestu); appka z toho jen pozná, KDO je přihlášený,
 * aby čekající účtenka jednoho účtu neodešla pod tokenem jiného (`ReceiptUploadWorker`).
 */
fun accessTokenSubject(token: String): String? = runCatching {
  val payload = token.split('.')[1]
  val decoded = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
  Json.parseToJsonElement(decoded).jsonObject["sub"]?.jsonPrimitive?.content
}.getOrNull()
