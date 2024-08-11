package com.eugerman.endpoint

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureEndpoint() {
    routing {
        post("/parsing") {
            val multiPartDataInputStream = call.receiveStream()
            File("src/main/resources/tradesReceived.csv")
                .writeBytes(multiPartDataInputStream.readAllBytes())
            call.respond("File received")
        }
    }
}