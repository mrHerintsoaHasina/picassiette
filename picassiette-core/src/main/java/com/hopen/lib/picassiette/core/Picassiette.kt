package com.hopen.lib.picassiette.core

class Picassiette private constructor() {

    class Builder {

        var cacheEnabled = false
            get() = throw IllegalAccessException("Call to getters is not allowed")

    }

}