package com.eugerman

import com.eugerman.endpoint.configureEndpoint
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    embeddedServer(Netty, port = 8085, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureEndpoint()
}