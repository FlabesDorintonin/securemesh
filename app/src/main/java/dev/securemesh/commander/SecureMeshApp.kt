package dev.securemesh.commander

import android.app.Application

class SecureMeshApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
