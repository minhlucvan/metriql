package com.metriql

import com.metriql.service.auth.ProjectAuth
import com.metriql.util.MetriqlException
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwsHeader
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.SignatureException
import io.jsonwebtoken.SigningKeyResolver
import io.jsonwebtoken.UnsupportedJwtException
import io.netty.handler.codec.http.HttpHeaders.Names.AUTHORIZATION
import io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED
import org.rakam.server.http.HttpServerBuilder.IRequestParameterFactory
import org.rakam.server.http.IRequestParameter
import org.rakam.server.http.RakamHttpRequest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

data class UserContext(val user: String, val pass: String, val request: RakamHttpRequest)
typealias BasicAuthLoader = (UserContext) -> ProjectAuth

class MetriqlAuthRequestParameterFactory(private val oauthApiSecret: String?, private val basicAuthLoader: BasicAuthLoader?) : IRequestParameterFactory {
    override fun create(m: Method): IRequestParameter<ProjectAuth> {
        return IRequestParameter<ProjectAuth> { _, request ->
            // no authentication method is provided
            if (oauthApiSecret == null && basicAuthLoader == null) {
                ProjectAuth.singleProject(null)
            } else {
                val token = request.headers().get(AUTHORIZATION)?.split(" ".toRegex(), 2) ?: throw MetriqlException(UNAUTHORIZED)

                when (token[0].lowercase()) {
                    "bearer" -> {
                        val parser = Jwts.parser()
                        val secret = oauthApiSecret ?: throw getAuthException("Oauth")
                        val key = loadKeyFile(secret)
                        parser.setSigningKeyResolver(object : SigningKeyResolver {
                            override fun resolveSigningKey(header: JwsHeader<out JwsHeader<*>>?, claims: Claims?): Key? {
                                val algorithm = SignatureAlgorithm.forName(header!!.algorithm)
                                return key.getKey(algorithm)
                            }

                            override fun resolveSigningKey(header: JwsHeader<out JwsHeader<*>>?, plaintext: String?): Key? {
                                val algorithm = SignatureAlgorithm.forName(header!!.algorithm)
                                return key.getKey(algorithm)
                            }
                        })

                        val attributes = try {
                            parser.parse(token[1]).body as Map<String, Any?>
                        } catch (e: Exception) {
                            throw MetriqlException(UNAUTHORIZED)
                        }

                        ProjectAuth(-1, -1, true, true, null, null, attributes, null)
                    }
                    "basic" -> {
                        val loader = basicAuthLoader ?: throw getAuthException("Basic auth")

                        val userPass = String(Base64.getDecoder().decode(token[1]), StandardCharsets.UTF_8).split(":".toRegex(), 2)
                        loader.invoke(UserContext(userPass[0], userPass[1], request))
                    }
                    else -> throw MetriqlException(UNAUTHORIZED)
                }
            }
        }
    }

    private fun getAuthException(type: String): MetriqlException {
        return MetriqlException("$type is not supported in this environment", UNAUTHORIZED)
    }

    private fun loadKeyFile(value: String): LoadedKey {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(ByteArrayInputStream(value.toByteArray(StandardCharsets.UTF_8)))
            LoadedKey(cert.publicKey, null)
        } catch (var4: Exception) {
            try {
                val rawKey = Base64.getMimeDecoder().decode(value.toByteArray(StandardCharsets.US_ASCII))
                LoadedKey(null, rawKey)
            } catch (var3: IOException) {
                throw SignatureException("Unknown signing key id")
            }
        }
    }

    class LoadedKey(private val publicKey: Key?, private val hmacKey: ByteArray?) {
        fun getKey(algorithm: SignatureAlgorithm): Key? {
            return if (algorithm.isHmac) {
                if (hmacKey == null) {
                    throw UnsupportedJwtException(String.format("JWT is signed with %s, but no HMAC key is configured", algorithm))
                } else {
                    SecretKeySpec(hmacKey, algorithm.jcaName)
                }
            } else publicKey ?: throw UnsupportedJwtException(String.format("JWT is signed with %s, but no key is configured", algorithm))
        }
    }
}