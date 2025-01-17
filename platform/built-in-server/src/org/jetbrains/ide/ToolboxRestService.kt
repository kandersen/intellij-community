// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.ide

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.Disposer
import com.intellij.util.castSafelyTo
import com.intellij.util.concurrency.AppExecutorUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import org.jetbrains.io.addCommonHeaders
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

val toolboxHandlerEP: ExtensionPointName<ToolboxServiceHandler<*>> = ExtensionPointName.create("com.intellij.toolboxServiceHandler")

interface ToolboxServiceHandler<T> {
  /**
   * Specifies a request, it is actually the last part of the path,
   * e.g. `http://localhost:port/api/toolbox/update-notification
   */
  val requestName : String

  /**
   * This method is executed synchronously for the handler to parser
   * request parameters, it may throw an exception on malformed inputs,
   */
  fun parseRequest(request: JsonElement) : T

  /**
   * This function is executes on a background thread to handle a Toolbox
   * request. The implementation allows to send a response after a long
   * while back to Toolbox.
   *
   * The [lifetime] should be used to bind all necessary
   * resources to the underlying Toolbox connection. It can
   * be disposed during the execution of this method.
   *
   * Use the [onResult] to pass a result back to Toolbox and
   * to close the connection. The [lifetime] is disposed after
   * the connection is closed too, it must not be used after [onResult]
   * callback is executed
   */
  fun handleToolboxRequest(
    lifetime: Disposable,
    request: T,
    onResult: (JsonElement) -> Unit,
  )
}

private fun findToolboxHandlerByUri(requestUri: String): ToolboxServiceHandler<*>? = toolboxHandlerEP.findFirstSafe {
  requestUri.endsWith("/" + it.requestName.trim('/'))
}

private fun interface ToolboxInnerHandler {
  fun handleToolboxRequest(
    lifetime: Disposable, onResult: (JsonElement) -> Unit,
  )
}

private fun <T> wrapHandler(handler: ToolboxServiceHandler<T>, request: JsonElement): ToolboxInnerHandler {
  val param = handler.parseRequest(request)
  return object : ToolboxInnerHandler {
    override fun handleToolboxRequest(lifetime: Disposable, onResult: (JsonElement) -> Unit) {
      handler.handleToolboxRequest(lifetime, param, onResult)
    }

    override fun toString(): String = "ToolboxInnerHandler{$handler, $param}"
  }
}

internal class ToolboxRestService : RestService() {
  internal companion object {
    @Suppress("SSBasedInspection")
    private val LOG = logger<ToolboxRestService>()
  }

  override fun getServiceName() = "toolbox"

  override fun isSupported(request: FullHttpRequest): Boolean {
    val token = System.getProperty("toolbox.notification.token") ?: return false
    if (request.headers()["Authorization"] != "toolbox $token") return false
    val requestUri = request.uri().substringBefore('?')
    if (findToolboxHandlerByUri(requestUri) == null) return false
    return super.isSupported(request)
  }

  override fun isMethodSupported(method: HttpMethod) = method == HttpMethod.POST

  override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
    val requestJson = createJsonReader(request).use { JsonParser.parseReader(it) }
    val channel = context.channel()

    val toolboxRequest : ToolboxInnerHandler = try {
      val handler = findToolboxHandlerByUri(urlDecoder.path())
      if (handler == null) {
        sendStatus(HttpResponseStatus.NOT_FOUND, false, channel)
        return null
      }

      wrapHandler(handler, requestJson)
    }
    catch (t: Throwable) {
      LOG.warn("Failed to process parameters of $request. ${t.message}", t)
      sendStatus(HttpResponseStatus.BAD_REQUEST, false, channel)
      return null
    }

    val lifetime = Disposer.newDisposable("toolbox-service-request")
    val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
    response.addCommonHeaders()
    response.headers().remove(HttpHeaderNames.ACCEPT_RANGES)
    response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, must-revalidate") //NON-NLS
    response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
    response.headers().set(HttpHeaderNames.LAST_MODIFIED, Date(Calendar.getInstance().timeInMillis))
    channel.writeAndFlush(response)

    val heartbeatDelay = requestJson.castSafelyTo<JsonObject>()?.get("heartbeatMillis")?.asLong
                         ?: System.getProperty("toolbox.heartbeat.millis", "5000").toLong()

    runCatching { channel.config().setOption(ChannelOption.TCP_NODELAY, true) }
    runCatching { channel.config().setOption(ChannelOption.SO_KEEPALIVE, true) }
    runCatching { channel.config().setOption(ChannelOption.SO_TIMEOUT, heartbeatDelay.toInt() * 2) }

    val heartbeat = context.executor().scheduleWithFixedDelay(
      object: Runnable {
        private fun handleError(t: Throwable) {
          LOG.debug("Failed to write next heartbeat. ${t.message}", t)
          Disposer.dispose(lifetime)
        }

        private val futureListener = GenericFutureListener<Future<in Void>> { f ->
          f.cause()?.let { t -> handleError(t) }
        }

        override fun run() {
          try {
            channel
              .writeAndFlush(Unpooled.copiedBuffer(" ", Charsets.UTF_8))
              .addListener(futureListener)
          }
          catch (t: Throwable) {
            handleError(t)
          }
        }
      }, heartbeatDelay, heartbeatDelay, TimeUnit.MILLISECONDS)

    Disposer.register(lifetime) { heartbeat.cancel(false) }

    val callback = CompletableFuture<JsonElement?>()
    AppExecutorUtil.getAppExecutorService().submit {
      toolboxRequest.handleToolboxRequest(lifetime) { r -> callback.complete(r) }
    }

    channel.closeFuture().addListener {
      Disposer.dispose(lifetime)
    }

    callback
      .exceptionally { e ->
        LOG.warn("The future completed with exception. ${e.message}", e)
        JsonObject().apply { addProperty("status", "error") }
      }
      .thenAcceptAsync(
        { json ->
          //kill the heartbeat, it may close the lifetime too
          runCatching {
            heartbeat.cancel(false)
            heartbeat.await()
          }

          //no need to do anything if it's already disposed
          if (Disposer.isDisposed(lifetime)) {
            //closing the channel just in case
            runCatching { channel.close() }
            return@thenAcceptAsync
          }

          runCatching { channel.write(Unpooled.copiedBuffer(gson.toJson(json), Charsets.UTF_8)) }
          runCatching { channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT) }

          Disposer.dispose(lifetime)
        },
        AppExecutorUtil.getAppExecutorService()
      )

    return null
  }
}
